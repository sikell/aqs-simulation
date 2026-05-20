#!/usr/bin/env python3
"""Mass-run CSV analysis: auto-detects varying dimensions and generates focused plots.

Every parameter dimension from the mass-run dialog is supported.  Only dimensions
that actually vary in the loaded data produce plots, keeping the output lean.
All plots are line- or bar-based (no heatmaps).

Example (defaults shown):
  python mass-run-analysis/analyze_mass_run.py \\
    --input-csv  mass-run-results/mass-run-results.csv \\
    --output-dir mass-run-results/analysis
"""

from __future__ import annotations

import argparse
import html
import json
import re
import textwrap
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable

import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np
import pandas as pd
import seaborn as sns
from mpl_toolkits.mplot3d import Axes3D  # noqa: F401 – registers 3d projection
from scipy import stats

try:
    import statsmodels.formula.api as smf
    import statsmodels.api as sm
    from statsmodels.stats.multicomp import pairwise_tukeyhsd
except Exception:
    smf = sm = pairwise_tukeyhsd = None

# ---------------------------------------------------------------------------
# Schema
# ---------------------------------------------------------------------------

_NUMERIC_COLS = [
    "kHops", "p2pRqsRadius", "taxiCount", "clientCount", "taxiSeatCount",
    "p2pOverlayMinNeighbors", "p2pOverlayMaxNeighbors", "p2pOverlayShortcuts",
    "runIndex", "worldSeed", "min", "max", "avg", "sum", "count", "spread",
]
_OPTIONAL_NUMERIC = {
    "p2pRqsRadius": -1, "taxiCount": -1, "clientCount": -1, "taxiSeatCount": -1,
    "p2pOverlayMinNeighbors": -1, "p2pOverlayMaxNeighbors": -1, "p2pOverlayShortcuts": -1,
}
_OPTIONAL_STRING = {"spawnScenario": "BASELINE", "idleRoamingMode": "n/a"}

NUMERIC_DIMS = [
    "kHops", "p2pRqsRadius", "taxiCount", "clientCount",
    "taxiSeatCount", "p2pOverlayMinNeighbors", "p2pOverlayMaxNeighbors", "p2pOverlayShortcuts",
]
CATEGORICAL_DIMS = ["algorithm", "p2pStrategy", "spawnScenario", "idleRoamingMode"]

DIM_LABELS: dict[str, str] = {
    "kHops": "k-Hops",
    "p2pRqsRadius": "RQS-Radius",
    "taxiCount": "Taxi-Anzahl",
    "clientCount": "Client-Anzahl",
    "taxiSeatCount": "Taxi-Sitze",
    "p2pOverlayMinNeighbors": "Overlay Min-Nachbarn",
    "p2pOverlayMaxNeighbors": "Overlay Max-Nachbarn",
    "p2pOverlayShortcuts": "Overlay Shortcuts",
    "spawnScenario": "Spawn-Szenario",
    "algorithm": "Algorithmus",
    "p2pStrategy": "P2P-Strategie",
    "idleRoamingMode": "Idle-Roaming-Modus",
}

_ZERO_THR = 1e-9


# ---------------------------------------------------------------------------
# Config & CLI
# ---------------------------------------------------------------------------

@dataclass
class AnalysisConfig:
    input_csv: Path
    output_dir: Path
    metrics: list[str] = field(default_factory=list)


def parse_args() -> AnalysisConfig:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input-csv", default="mass-run-results/mass-run-results.csv", type=Path)
    p.add_argument("--output-dir", default="mass-run-results/analysis", type=Path)
    p.add_argument("--metrics", default="", help="Comma-separated metric filter (default: all)")
    a = p.parse_args()
    return AnalysisConfig(
        input_csv=a.input_csv,
        output_dir=a.output_dir,
        metrics=[m.strip() for m in a.metrics.split(",") if m.strip()],
    )


# ---------------------------------------------------------------------------
# I/O helpers
# ---------------------------------------------------------------------------

def ensure_dirs(base: Path) -> dict[str, Path]:
    dirs = {"base": base, "plots": base / "plots", "tables": base / "tables", "stats": base / "stats"}
    for d in dirs.values():
        d.mkdir(parents=True, exist_ok=True)
    for old in [*dirs["plots"].glob("*.png"), *dirs["tables"].glob("*.csv"), *dirs["stats"].glob("*.csv")]:
        old.unlink(missing_ok=True)
    return dirs


def _safe(s: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_\-]+", "_", str(s)).strip("_")


def _wrap(text: str, w: int = 70) -> str:
    return "\n".join(textwrap.wrap(text, w))


def _save(fig: plt.Figure, path: Path) -> None:
    fig.tight_layout()
    fig.savefig(path, dpi=150, bbox_inches="tight")
    plt.close(fig)


# ---------------------------------------------------------------------------
# Data loading
# ---------------------------------------------------------------------------

def load_data(csv_path: Path) -> pd.DataFrame:
    if not csv_path.exists():
        raise FileNotFoundError(f"CSV not found: {csv_path}")
    df = pd.read_csv(csv_path)

    if "idleRoamingMode" not in df.columns:
        if "idleRoamingEnabled" in df.columns:
            enabled = (
                df["idleRoamingEnabled"]
                .astype(str)
                .str.strip()
                .str.lower()
                .map({"true": True, "1": True, "yes": True, "false": False, "0": False, "no": False})
            )
            strategy = (
                df["idleRoamingStrategy"].astype(str).str.strip().str.lower()
                if "idleRoamingStrategy" in df.columns
                else pd.Series(["random"] * len(df), index=df.index)
            )
            df["idleRoamingMode"] = np.where(enabled.fillna(False), strategy, "none")
        else:
            df["idleRoamingMode"] = "n/a"

    for col, default in _OPTIONAL_NUMERIC.items():
        if col not in df.columns:
            df[col] = default
    for col, default in _OPTIONAL_STRING.items():
        if col not in df.columns:
            df[col] = default

    missing = [c for c in ["avg", "algorithm", "metric", "kHops", "runIndex", "p2pStrategy"] if c not in df.columns]
    if missing:
        raise ValueError(f"CSV is missing required columns: {missing}")

    for col in _NUMERIC_COLS:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    for col in ["algorithm", "p2pStrategy", "metric", "spawnScenario", "idleRoamingMode"]:
        if col in df.columns:
            df[col] = df[col].astype(str).str.strip()

    df = df.dropna(subset=["avg", "algorithm", "metric", "kHops", "runIndex"])
    df = _enrich(df)
    return df


def _is_collector(name: str) -> bool:
    return "p2pcollector" in str(name).lower()


def _fmt_khops(v: object) -> str:
    try:
        i = int(v)
        return "∞" if i >= 2_147_483_647 else str(i)
    except Exception:
        return str(v)


def _fmt_rqs(v: object) -> str:
    """Format RQS radius value, handling -1 and None as 'default'."""
    try:
        i = int(v)
        return str(i) if i >= 0 else "default"
    except Exception:
        return str(v)


def _enrich(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()
    df["algorithm_base"] = df["algorithm"].str.strip()
    df["strategy_eff"] = df.apply(
        lambda r: str(r["p2pStrategy"]).strip() if _is_collector(r["algorithm_base"]) else "n/a",
        axis=1,
    )
    df["variant"] = df.apply(
        lambda r: f"{r['algorithm_base']}+{r['strategy_eff']}" if r["strategy_eff"] != "n/a" else r["algorithm_base"],
        axis=1,
    )
    _short = {
        "TaxiAlgorithmFillAllSeats": "FillAllSeats",
        "TaxiAlgorithmSinglePassenger": "SinglePassenger",
        "TaxiAlgorithmP2PCollector": "P2PCollector",
        "TaxiAlgorithmGroupByProximity": "GroupByProximity",
        "TaxiAlgorithmGroupByTarget": "GroupByTarget",
        "TaxiAlgorithmVehicleRouting": "VehicleRouting",
    }
    df["algo_short"] = df["algorithm_base"].map(_short).fillna(df["algorithm_base"])
    df["variant_short"] = df.apply(
        lambda r: f"{r['algo_short']}+{r['strategy_eff']}" if r["strategy_eff"] != "n/a" else r["algo_short"],
        axis=1,
    )
    df["kHopsLabel"] = df["kHops"].apply(_fmt_khops)
    df["rqsLabel"] = df["p2pRqsRadius"].apply(_fmt_rqs)
    df["variant_k"] = df.apply(
        lambda r: f"{r['variant_short']} (k={r['kHopsLabel']})" if _is_collector(r["algorithm_base"]) else r[
            "variant_short"],
        axis=1,
    )
    df["variant_r"] = df.apply(
        lambda r: f"{r['variant_short']} (rqs={r['rqsLabel']})" if _is_collector(r["algorithm_base"]) else r[
            "variant_short"],
        axis=1,
    )
    df["variant_kr"] = df.apply(
        lambda r: f"{r['variant_short']} (k={r['kHopsLabel']}, rqs={r['rqsLabel']})" if _is_collector(r["algorithm_base"]) else r[
            "variant_short"],
        axis=1,
    )
    return df


# ---------------------------------------------------------------------------
# Dimension analysis
# ---------------------------------------------------------------------------

def varying_dims(df: pd.DataFrame) -> dict[str, list]:
    """Return {dim: sorted_unique_values} for dimensions that actually vary (>1 value)."""
    result = {}
    for dim in NUMERIC_DIMS + CATEGORICAL_DIMS:
        if dim not in df.columns:
            continue
        vals = df[dim].dropna().unique()
        if dim in NUMERIC_DIMS:
            vals = vals[vals >= 0]
        if len(vals) > 1:
            result[dim] = sorted(vals.tolist())
    return result


def active_metrics(df: pd.DataFrame, requested: list[str] | None = None) -> list[str]:
    metrics = [str(m) for m, g in df.groupby("metric", dropna=False) if g["avg"].abs().max() > _ZERO_THR]
    metrics = sorted(metrics)
    if requested:
        metrics = [m for m in metrics if m in requested]
    return metrics


# ---------------------------------------------------------------------------
# Plot helpers
# ---------------------------------------------------------------------------

def _palette(n: int) -> list:
    return sns.color_palette("tab10", n_colors=max(n, 1))


def _ci95(series: pd.Series) -> float:
    s = pd.to_numeric(series.dropna(), errors="coerce")
    n = int(s.count())
    if n <= 1:
        return 0.0
    return float(stats.t.ppf(0.975, df=n - 1) * s.std(ddof=1) / np.sqrt(n))


def _vk_sort(label: str) -> tuple:
    m = re.search(r"\(k=(\d+|∞)\)$", str(label).strip())
    if m:
        ks = m.group(1)
        return (label[: m.start()].strip(), float("inf") if ks == "∞" else int(ks))
    return (str(label), -1)


def _sorted_variants(vals: Iterable[str]) -> list[str]:
    return sorted(set(vals), key=_vk_sort)


def _extract_rqs_from_label(label: str) -> str | None:
    m = re.search(r"rqs=([^,)]+)", str(label))
    return m.group(1).strip() if m else None


def _rqs_sort_key(v: str) -> tuple[int, str]:
    try:
        return (0, f"{int(v):012d}")
    except Exception:
        return (1, str(v))


def _group_color_map(groups: list[str]) -> dict[str, tuple]:
    """Return colors for groups.

    If labels contain rqs=..., colors are assigned per RQS value so all lines
    with the same RQS share a color across the plot.
    """
    rqs_vals = sorted({r for r in (_extract_rqs_from_label(g) for g in groups) if r is not None}, key=_rqs_sort_key)
    if rqs_vals:
        rqs_palette = _palette(len(rqs_vals))
        rqs_cmap = {r: rqs_palette[i] for i, r in enumerate(rqs_vals)}
        fallback = sns.color_palette("tab10", n_colors=1)[0]
        return {g: rqs_cmap.get(_extract_rqs_from_label(g), fallback) for g in groups}

    palette = _palette(len(groups))
    return {g: palette[i] for i, g in enumerate(groups)}


def _rqs_legend_handles(groups: list[str], cmap: dict[str, tuple]) -> list:
    """Build compact generic legend entries: one item per RQS value."""
    rqs_vals = sorted({r for r in (_extract_rqs_from_label(g) for g in groups) if r is not None}, key=_rqs_sort_key)
    if not rqs_vals:
        return []

    handles = []
    for r in rqs_vals:
        color = next((cmap[g] for g in groups if _extract_rqs_from_label(g) == r), sns.color_palette("tab10", n_colors=1)[0])
        handles.append(plt.Line2D([0], [0], color=color, marker="o", linewidth=2, label=f"RQS={r}"))
    return handles


def _base_without_rqs(label: str) -> str:
    s = str(label).strip()
    # Remove trailing "(rqs=...)" and ", rqs=..." inside combined labels.
    s = re.sub(r",\s*rqs=[^,)]+(?=\))", "", s)
    s = re.sub(r"\s*\(rqs=[^,)]+\)$", "", s)
    return s.strip()


def _group_style_maps(groups: list[str]) -> tuple[dict[str, tuple[str, str]], dict[str, tuple[str, str]]]:
    """Return (group->(linestyle, marker), base->(linestyle, marker))."""
    bases = sorted({_base_without_rqs(g) for g in groups}, key=_vk_sort)
    linestyles = ["-", "--", "-.", ":"]
    markers = ["o", "s", "^", "D", "v", "P", "X", "*", "h", "<", ">", "x", "+"]

    base_style: dict[str, tuple[str, str]] = {}
    for i, b in enumerate(bases):
        ls = linestyles[i % len(linestyles)]
        mk = markers[(i // len(linestyles)) % len(markers)]
        base_style[b] = (ls, mk)

    group_style = {g: base_style[_base_without_rqs(g)] for g in groups}
    return group_style, base_style


def _algo_k_legend_handles(base_style: dict[str, tuple[str, str]]) -> list:
    """Compact legend entries for algorithm/k style encoding."""
    handles = []
    for b in sorted(base_style.keys(), key=_vk_sort):
        ls, mk = base_style[b]
        handles.append(
            plt.Line2D([0], [0], color="#444444", linestyle=ls, marker=mk, linewidth=2, label=b)
        )
    return handles


def _annotate_bars(ax: plt.Axes, bars, values: pd.Series) -> None:
    ymax = ax.get_ylim()[1]
    for bar, val in zip(bars, values):
        if pd.isna(val):
            continue
        ax.text(
            bar.get_x() + bar.get_width() / 2,
            bar.get_height() + ymax * 0.01,
            f"{val:.2f}", ha="center", va="bottom", fontsize=7.5,
        )


def _collector_focus(df: pd.DataFrame) -> pd.DataFrame:
    """Return collector-only rows when available; otherwise return input unchanged."""
    if "algorithm_base" not in df.columns:
        return df
    mask = df["algorithm_base"].astype(str).str.contains("p2pcollector", case=False, na=False)
    sub = df[mask]
    return sub if not sub.empty else df


# ---------------------------------------------------------------------------
# 1. Algorithm overview bar chart (per metric)
# ---------------------------------------------------------------------------

def plot_algorithm_overview(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Bar chart comparing algorithm variants – one separate plot per scenario."""
    sns.set_theme(style="whitegrid")
    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])

    for metric in metrics:
        mdf = df[df["metric"] == metric]
        variants = _sorted_variants(mdf["variant_k"].dropna().unique())
        n_v = len(variants)
        palette = _palette(n_v)
        cmap = {v: palette[i] for i, v in enumerate(variants)}

        for scenario in scenarios:
            sub = mdf[mdf["spawnScenario"] == scenario] if "spawnScenario" in mdf.columns else mdf
            agg = (sub.groupby("variant_k", dropna=False)["avg"]
                   .agg(["mean", "count"]).reset_index())
            agg["ci95"] = [_ci95(sub[sub["variant_k"] == v]["avg"]) for v in agg["variant_k"]]
            agg = agg.set_index("variant_k").reindex(variants).reset_index()

            fig, ax = plt.subplots(figsize=(max(7, 2.5 * n_v), 5))
            x = np.arange(n_v)
            bars = ax.bar(x, agg["mean"].fillna(0), yerr=agg["ci95"].fillna(0),
                          color=[cmap[v] for v in variants], capsize=5,
                          error_kw={"elinewidth": 1.5, "ecolor": "#333"}, width=0.65)
            _annotate_bars(ax, bars, agg["mean"])
            ax.set_xticks(x)
            ax.set_xticklabels(variants, rotation=35, ha="right", fontsize=9)
            ax.set_xlabel("")
            ax.set_ylabel(metric)
            ax.set_title(_wrap(f"Algorithmen-Vergleich: {metric}  [Spawn: {scenario}]"), fontsize=12)
            _save(fig, plots_dir / f"algo_overview_{_safe(scenario)}_{_safe(metric)}.png")


# ---------------------------------------------------------------------------
# 2. Per-dimension line plots (numeric dims)
# ---------------------------------------------------------------------------

def plot_dim_effect(df: pd.DataFrame, dim: str, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Line plot: effect of one numeric parameter – one plot per scenario.

    For kHops we use variant_short (without k embedded) as the grouping key,
    because variant_k already folds kHops into the label and would yield
    single-point "lines" that look empty.

    Same logic applies to p2pRqsRadius: if RQS varies and is not the current dim,
    we use variant_r to show RQS as separate lines.

    If both kHops and p2pRqsRadius vary, we use variant_kr to show both in the label.
    """
    sns.set_theme(style="whitegrid")
    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])
    dim_label = DIM_LABELS.get(dim, dim)

    # Determine grouping key: for kHops/RQS use variant_short so multiple values
    # appear as x-axis points within the same line.
    # If both vary, use variant_kr; if only kHops varies use variant_k;
    # if only RQS varies use variant_r; otherwise use variant_k
    khops_varies = "kHops" in vdims and len(vdims.get("kHops", [])) > 1
    rqs_varies = "p2pRqsRadius" in vdims and len(vdims.get("p2pRqsRadius", [])) > 1

    if dim == "kHops":
        group_col = "variant_short"
    elif dim == "p2pRqsRadius":
        group_col = "variant_short"
    elif khops_varies and rqs_varies:
        group_col = "variant_kr"
    elif khops_varies:
        group_col = "variant_k"
    elif rqs_varies:
        group_col = "variant_r"
    else:
        group_col = "variant_k"

    for metric in metrics:
        mdf = df[df["metric"] == metric].copy()
        if mdf[dim].dropna().nunique() < 2:
            continue
        groups = _sorted_variants(mdf[group_col].dropna().unique())
        cmap = _group_color_map(groups)
        rqs_handles = _rqs_legend_handles(groups, cmap)
        gstyle, base_style = _group_style_maps(groups)
        algo_handles = _algo_k_legend_handles(base_style)

        for scenario in scenarios:
            sub = mdf[mdf["spawnScenario"] == scenario] if "spawnScenario" in mdf.columns else mdf

            agg = (sub.groupby([group_col, dim], dropna=False)["avg"]
                   .agg(["mean", "count"]).reset_index().sort_values(dim))
            agg["ci95"] = [
                _ci95(sub[(sub[group_col] == r[group_col]) & (sub[dim] == r[dim])]["avg"])
                for _, r in agg.iterrows()
            ]

            fig, ax = plt.subplots(figsize=(8, 5))
            for var in groups:
                vdata = agg[agg[group_col] == var].sort_values(dim)
                if vdata.empty or vdata[dim].dropna().nunique() < 2:
                    continue
                ls, mk = gstyle[var]
                ax.plot(vdata[dim], vdata["mean"], linewidth=2,
                        label=var, color=cmap[var], linestyle=ls, marker=mk)
                ax.fill_between(vdata[dim],
                                vdata["mean"] - vdata["ci95"],
                                vdata["mean"] + vdata["ci95"],
                                alpha=0.13, color=cmap[var])
                last = vdata.iloc[-1]
                ax.annotate(f"{last['mean']:.1f}",
                            xy=(last[dim], last["mean"]),
                            xytext=(4, 2), textcoords="offset points",
                            fontsize=7, color=cmap[var])

            ax.set_xlabel(dim_label, fontsize=11)
            ax.set_ylabel(metric)
            ax.xaxis.set_major_locator(mticker.MaxNLocator(integer=True))
            if rqs_handles:
                lg_rqs = ax.legend(handles=rqs_handles, title="RQS-Radius", fontsize=8,
                                   loc="center left", bbox_to_anchor=(1.02, 0.72), borderaxespad=0.0)
                ax.add_artist(lg_rqs)
                ax.legend(handles=algo_handles, title="Algorithmus/k", fontsize=7,
                          loc="center left", bbox_to_anchor=(1.02, 0.25), borderaxespad=0.0)
            else:
                ax.legend(title="Variante", fontsize=8,
                          loc="center left", bbox_to_anchor=(1.02, 0.5), borderaxespad=0.0)
            ax.set_title(_wrap(f"{dim_label} -> {metric}  [Spawn: {scenario}]"), fontsize=12)
            _save(fig, plots_dir / f"dim_{_safe(dim)}_{_safe(scenario)}_{_safe(metric)}.png")


# ---------------------------------------------------------------------------
# 3. Categorical dimension bar chart (spawnScenario, p2pStrategy, taxiSeatCount)
# ---------------------------------------------------------------------------

def plot_categorical_dim(df: pd.DataFrame, dim: str, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Grouped bar chart: effect of a categorical/discrete dimension on each metric."""
    sns.set_theme(style="whitegrid")
    categories = vdims.get(dim, [])
    if len(categories) < 2:
        return
    dim_label = DIM_LABELS.get(dim, dim)

    for metric in metrics:
        mdf = df[df["metric"] == metric]
        variants = _sorted_variants(mdf["variant_k"].dropna().unique())
        n_v = len(variants)
        n_c = len(categories)
        palette = _palette(n_v)
        cmap = {v: palette[i] for i, v in enumerate(variants)}

        bar_w = 0.8 / max(n_v, 1)
        x_base = np.arange(n_c)

        fig, ax = plt.subplots(figsize=(max(8, 2.8 * n_c * n_v), 6))

        for vi, var in enumerate(variants):
            vdata = mdf[mdf["variant_k"] == var]
            means, cis = [], []
            for cat in categories:
                sub = vdata[vdata[dim] == cat]["avg"]
                means.append(float(sub.mean()) if not sub.empty else 0.0)
                cis.append(_ci95(sub))
            offset = (vi - n_v / 2 + 0.5) * bar_w
            ax.bar(x_base + offset, means, yerr=cis, width=bar_w * 0.9,
                   label=var, color=cmap[var], capsize=4,
                   error_kw={"elinewidth": 1.2, "ecolor": "#333"})

        ax.set_xticks(x_base)
        ax.set_xticklabels([str(c) for c in categories], rotation=20, ha="right", fontsize=10)
        ax.set_xlabel(dim_label, fontsize=11)
        ax.set_ylabel(metric)
        ax.legend(title="Variante", fontsize=8, loc="upper right")
        fig.suptitle(_wrap(f"Effekt von {dim_label} auf {metric}"), y=1.01, fontsize=13)
        _save(fig, plots_dir / f"cat_{_safe(dim)}_{_safe(metric)}.png")


# ---------------------------------------------------------------------------
# 4. Multi-dimension facet plot: x=dim_x, columns=dim_facet, lines=variant_k
# ---------------------------------------------------------------------------

def plot_two_dim_facet(df: pd.DataFrame, dim_x: str, dim_facet: str,
                       vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Faceted line plot: effect of dim_x faceted by dim_facet.

    If dim_x is kHops or p2pRqsRadius, we use variant_short for grouping
    so multiple values appear on the x-axis within the same line.
    """
    sns.set_theme(style="whitegrid")
    facet_vals = vdims.get(dim_facet, [])
    if len(facet_vals) < 2 or df[dim_x].dropna().nunique() < 2:
        return

    dim_x_label = DIM_LABELS.get(dim_x, dim_x)
    dim_f_label = DIM_LABELS.get(dim_facet, dim_facet)

    khops_varies = "kHops" in vdims and len(vdims.get("kHops", [])) > 1
    rqs_varies = "p2pRqsRadius" in vdims and len(vdims.get("p2pRqsRadius", [])) > 1

    for metric in metrics:
        mdf = df[df["metric"] == metric]

        # Choose grouping column based on whether kHops/RQS vary
        if dim_x in ["kHops", "p2pRqsRadius"] or dim_facet in ["kHops", "p2pRqsRadius"]:
            if khops_varies and rqs_varies:
                gcol = "variant_kr"
            elif khops_varies:
                gcol = "variant_k"
            elif rqs_varies:
                gcol = "variant_r"
            else:
                gcol = "variant_k"
        else:
            gcol = "variant_k"

        variants = _sorted_variants(mdf[gcol].dropna().unique())
        cmap = _group_color_map(variants)
        rqs_handles = _rqs_legend_handles(variants, cmap)
        gstyle, base_style = _group_style_maps(variants)
        algo_handles = _algo_k_legend_handles(base_style)

        n_f = len(facet_vals)
        fig, axes = plt.subplots(1, n_f, figsize=(7 * n_f, 5), sharey=True, squeeze=False)

        for fi, fval in enumerate(facet_vals):
            ax = axes[0][fi]
            sub = mdf[mdf[dim_facet] == fval]
            agg = (sub.groupby([gcol, dim_x], dropna=False)["avg"]
                   .agg(["mean"]).reset_index().sort_values(dim_x))
            agg["ci95"] = [
                _ci95(sub[(sub[gcol] == r[gcol]) & (sub[dim_x] == r[dim_x])]["avg"])
                for _, r in agg.iterrows()
            ]

            for var in variants:
                vd = agg[agg[gcol] == var].sort_values(dim_x)
                if vd.empty:
                    continue
                ls, mk = gstyle[var]
                ax.plot(vd[dim_x], vd["mean"], linewidth=2,
                        label=var, color=cmap[var], linestyle=ls, marker=mk)
                ax.fill_between(vd[dim_x], vd["mean"] - vd["ci95"], vd["mean"] + vd["ci95"],
                                alpha=0.13, color=cmap[var])

            ax.set_title(f"{dim_f_label} = {fval}", fontsize=10)
            ax.set_xlabel(dim_x_label, fontsize=10)
            if fi == 0:
                ax.set_ylabel(metric)
            ax.xaxis.set_major_locator(mticker.MaxNLocator(integer=True))

        if rqs_handles:
            fig.legend(handles=rqs_handles, title="RQS-Radius", fontsize=8,
                       loc="center left", bbox_to_anchor=(1.01, 0.72), borderaxespad=0.0)
            fig.legend(handles=algo_handles, title="Algorithmus/k", fontsize=7,
                       loc="center left", bbox_to_anchor=(1.01, 0.28), borderaxespad=0.0)
        else:
            handles = [plt.Line2D([0], [0], color=cmap[v], marker="o", linewidth=2, label=v) for v in variants]
            fig.legend(handles=handles, title="Variante", fontsize=8,
                       loc="center left", bbox_to_anchor=(1.01, 0.5), borderaxespad=0.0)
        fig.suptitle(_wrap(f"{dim_x_label} × {dim_f_label}: {metric}"), y=1.01, fontsize=13)
        _save(fig, plots_dir / f"multi_{_safe(dim_x)}_x_{_safe(dim_facet)}_{_safe(metric)}.png")


# ---------------------------------------------------------------------------
# 5. Scaling overview: all varying numeric dims in a grid (one figure per metric)
# ---------------------------------------------------------------------------

def plot_scaling_overview(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Grid of line plots (rows = numeric dims) – one figure per scenario × metric.

    Compact thesis-focused grouping:
    - Collector: one aggregated line per RQS value.
    - Non-collector: one aggregated line per algorithm.
    """
    # Exclude RQS here because this overview already uses RQS as grouping
    # for collector lines; including it as x-dimension causes duplicate
    # grouping/index columns in pandas reset_index().
    numeric_varying = [d for d in NUMERIC_DIMS if d in vdims and d != "p2pRqsRadius"]
    if not numeric_varying:
        return
    sns.set_theme(style="whitegrid")

    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])
    has_scenarios = "spawnScenario" in vdims
    n_rows = len(numeric_varying)

    for metric in metrics:
        mdf = df[df["metric"] == metric]
        for scenario in scenarios:
            sub = mdf[mdf["spawnScenario"] == scenario] if has_scenarios else mdf
            if sub.empty:
                continue

            collector_mask = sub["algorithm_base"].astype(str).str.contains("p2pcollector", case=False, na=False)
            sub_collector = sub[collector_mask]
            sub_non_collector = sub[~collector_mask]

            rqs_vals = sorted(sub_collector["p2pRqsRadius"].dropna().unique().tolist(), key=_rqs_sort_key) if not sub_collector.empty else []
            rqs_palette = _palette(len(rqs_vals))
            rqs_cmap = {r: rqs_palette[i] for i, r in enumerate(rqs_vals)}

            non_algos = sorted(sub_non_collector["algorithm_base"].dropna().unique().tolist()) if not sub_non_collector.empty else []
            algo_palette = sns.color_palette("Set2", n_colors=max(len(non_algos), 1))
            algo_cmap = {a: algo_palette[i] for i, a in enumerate(non_algos)}

            fig, axes = plt.subplots(n_rows, 1, figsize=(8, 4 * n_rows), squeeze=False)
            fig_rqs_handles = []
            fig_algo_handles = []

            for ri, dim in enumerate(numeric_varying):
                ax = axes[ri][0]
                # Collector: one aggregated line per RQS value.
                if not sub_collector.empty and rqs_vals:
                    agg_c = (sub_collector.groupby(["p2pRqsRadius", dim], dropna=False)["avg"]
                             .agg(["mean"]).reset_index().sort_values(dim))
                    agg_c["ci95"] = [
                        _ci95(sub_collector[(sub_collector["p2pRqsRadius"] == r["p2pRqsRadius"]) & (sub_collector[dim] == r[dim])]["avg"])
                        for _, r in agg_c.iterrows()
                    ]
                    for rv in rqs_vals:
                        vd = agg_c[agg_c["p2pRqsRadius"] == rv].sort_values(dim)
                        if vd.empty or vd[dim].dropna().nunique() < 2:
                            continue
                        color = rqs_cmap[rv]
                        ax.plot(vd[dim], vd["mean"], linewidth=2.0, color=color)
                        ax.fill_between(vd[dim], vd["mean"] - vd["ci95"], vd["mean"] + vd["ci95"],
                                        alpha=0.12, color=color)
                    if not fig_rqs_handles:
                        fig_rqs_handles = [
                            plt.Line2D([0], [0], color=rqs_cmap[rv], linewidth=2, marker="o", label=f"RQS={rv}")
                            for rv in rqs_vals
                        ]

                # Non-collector: one aggregated line per algorithm.
                if not sub_non_collector.empty and non_algos:
                    agg_n = (sub_non_collector.groupby(["algorithm_base", dim], dropna=False)["avg"]
                             .agg(["mean"]).reset_index().sort_values(dim))
                    agg_n["ci95"] = [
                        _ci95(sub_non_collector[(sub_non_collector["algorithm_base"] == r["algorithm_base"]) & (sub_non_collector[dim] == r[dim])]["avg"])
                        for _, r in agg_n.iterrows()
                    ]
                    for algo in non_algos:
                        vd = agg_n[agg_n["algorithm_base"] == algo].sort_values(dim)
                        if vd.empty or vd[dim].dropna().nunique() < 2:
                            continue
                        color = algo_cmap[algo]
                        ax.plot(vd[dim], vd["mean"], linewidth=2.0, linestyle="--", color=color)
                        ax.fill_between(vd[dim], vd["mean"] - vd["ci95"], vd["mean"] + vd["ci95"],
                                        alpha=0.08, color=color)
                    if not fig_algo_handles:
                        fig_algo_handles = [
                            plt.Line2D([0], [0], color=algo_cmap[a], linewidth=2, linestyle="--", label=a)
                            for a in non_algos
                        ]

                ax.set_xlabel(DIM_LABELS.get(dim, dim), fontsize=9)
                ax.set_ylabel(metric, fontsize=9)
                ax.xaxis.set_major_locator(mticker.MaxNLocator(integer=True))
                ax.set_title(DIM_LABELS.get(dim, dim), fontsize=10)

            if fig_rqs_handles:
                fig.legend(handles=fig_rqs_handles, title="RQS-Radius", fontsize=8,
                           loc="center left", bbox_to_anchor=(1.01, 0.72), borderaxespad=0.0)
            if fig_algo_handles:
                fig.legend(handles=fig_algo_handles, title="Algorithmus (ohne Collector)", fontsize=7,
                           loc="center left", bbox_to_anchor=(1.01, 0.28), borderaxespad=0.0)

            fig.suptitle(_wrap(f"Skalierung: {metric}  [Spawn: {scenario}]"), y=1.01, fontsize=13)
            _save(fig, plots_dir / f"scaling_{_safe(scenario)}_{_safe(metric)}.png")


# ---------------------------------------------------------------------------
# 5b. Scaling grouped per RQS range
# ---------------------------------------------------------------------------

def plot_scaling_per_rqs(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Scaling grouped by RQS: one plot per RQS (collector) + one plot per non-collector algorithm."""
    numeric_varying = [d for d in NUMERIC_DIMS if d in vdims and d != "p2pRqsRadius"]
    if not numeric_varying:
        return
    sns.set_theme(style="whitegrid")

    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])
    has_scenarios = "spawnScenario" in vdims

    for metric in metrics:
        mdf = df[df["metric"] == metric]
        if mdf.empty:
            continue

        # Stable colors for all variant_k lines across all generated group plots
        all_variants = _sorted_variants(mdf["variant_k"].dropna().unique())
        palette = _palette(len(all_variants))
        variant_cmap = {v: palette[i] for i, v in enumerate(all_variants)}

        for scenario in scenarios:
            sub_all = mdf[mdf["spawnScenario"] == scenario] if has_scenarios else mdf
            if sub_all.empty:
                continue

            collector_mask = sub_all["algorithm_base"].astype(str).str.contains("p2pcollector", case=False, na=False)
            sub_collector = sub_all[collector_mask]
            sub_non_collector = sub_all[~collector_mask]

            # 1) One scaling plot per RQS value (collector-only)
            if not sub_collector.empty and "p2pRqsRadius" in sub_collector.columns:
                rqs_vals = sorted(sub_collector["p2pRqsRadius"].dropna().unique().tolist(), key=_rqs_sort_key)
                for rqs_val in rqs_vals:
                    sub_group = sub_collector[sub_collector["p2pRqsRadius"] == rqs_val]
                    if sub_group.empty:
                        continue

                    n_rows = len(numeric_varying)
                    fig, axes = plt.subplots(n_rows, 1, figsize=(8, 4 * n_rows), squeeze=False)

                    for ri, dim in enumerate(numeric_varying):
                        ax = axes[ri][0]
                        gcol = "variant_short" if dim == "kHops" else "variant_k"
                        grp_variants = _sorted_variants(sub_group[gcol].dropna().unique())
                        agg = (sub_group.groupby([gcol, dim], dropna=False)["avg"]
                               .agg(["mean"]).reset_index().sort_values(dim))
                        agg["ci95"] = [
                            _ci95(sub_group[(sub_group[gcol] == r[gcol]) & (sub_group[dim] == r[dim])]["avg"])
                            for _, r in agg.iterrows()
                        ]

                        for var in grp_variants:
                            vd = agg[agg[gcol] == var].sort_values(dim)
                            if vd.empty or vd[dim].dropna().nunique() < 2:
                                continue
                            color_key = var if gcol == "variant_k" else (
                                sub_group[sub_group["variant_short"] == var]["variant_k"].dropna().iloc[0]
                                if not sub_group[sub_group["variant_short"] == var]["variant_k"].dropna().empty else var
                            )
                            color = variant_cmap.get(color_key, "#333333")
                            ax.plot(vd[dim], vd["mean"], linewidth=1.8, label=var, color=color)
                            ax.fill_between(vd[dim], vd["mean"] - vd["ci95"], vd["mean"] + vd["ci95"],
                                            alpha=0.12, color=color)
                        ax.set_xlabel(DIM_LABELS.get(dim, dim), fontsize=9)
                        ax.set_ylabel(metric, fontsize=9)
                        ax.xaxis.set_major_locator(mticker.MaxNLocator(integer=True))
                        ax.set_title(DIM_LABELS.get(dim, dim), fontsize=10)
                        if ax.lines:
                            ax.legend(title="Variante", fontsize=7, loc="best")

                    fig.suptitle(_wrap(f"Skalierung @ RQS={rqs_val}: {metric}  [Spawn: {scenario}]"),
                                 y=1.01, fontsize=13)
                    _save(fig, plots_dir / f"scaling_per_rqs_{_safe(scenario)}_{_safe(metric)}_rqs{_safe(str(rqs_val))}.png")

            # 2) One scaling plot per non-collector algorithm (RQS irrelevant here)
            if not sub_non_collector.empty:
                non_algos = sorted(sub_non_collector["algorithm_base"].dropna().unique().tolist())
                for algo_name in non_algos:
                    sub_group = sub_non_collector[sub_non_collector["algorithm_base"] == algo_name]
                    if sub_group.empty:
                        continue

                    n_rows = len(numeric_varying)
                    fig, axes = plt.subplots(n_rows, 1, figsize=(8, 4 * n_rows), squeeze=False)

                    for ri, dim in enumerate(numeric_varying):
                        ax = axes[ri][0]
                        gcol = "variant_short" if dim == "kHops" else "variant_k"
                        grp_variants = _sorted_variants(sub_group[gcol].dropna().unique())
                        agg = (sub_group.groupby([gcol, dim], dropna=False)["avg"]
                               .agg(["mean"]).reset_index().sort_values(dim))
                        agg["ci95"] = [
                            _ci95(sub_group[(sub_group[gcol] == r[gcol]) & (sub_group[dim] == r[dim])]["avg"])
                            for _, r in agg.iterrows()
                        ]

                        for var in grp_variants:
                            vd = agg[agg[gcol] == var].sort_values(dim)
                            if vd.empty or vd[dim].dropna().nunique() < 2:
                                continue
                            color_key = var if gcol == "variant_k" else (
                                sub_group[sub_group["variant_short"] == var]["variant_k"].dropna().iloc[0]
                                if not sub_group[sub_group["variant_short"] == var]["variant_k"].dropna().empty else var
                            )
                            color = variant_cmap.get(color_key, "#333333")
                            ax.plot(vd[dim], vd["mean"], linewidth=1.8, label=var, color=color)
                            ax.fill_between(vd[dim], vd["mean"] - vd["ci95"], vd["mean"] + vd["ci95"],
                                            alpha=0.12, color=color)
                        ax.set_xlabel(DIM_LABELS.get(dim, dim), fontsize=9)
                        ax.set_ylabel(metric, fontsize=9)
                        ax.xaxis.set_major_locator(mticker.MaxNLocator(integer=True))
                        ax.set_title(DIM_LABELS.get(dim, dim), fontsize=10)
                        if ax.lines:
                            ax.legend(title="Variante", fontsize=7, loc="best")

                    fig.suptitle(_wrap(f"Skalierung @ {algo_name} (ohne RQS): {metric}  [Spawn: {scenario}]"),
                                 y=1.01, fontsize=13)
                    _save(fig, plots_dir / f"scaling_per_noncollector_{_safe(scenario)}_{_safe(metric)}_{_safe(algo_name)}.png")


# ---------------------------------------------------------------------------
# 5c. Scaling grouped per algorithm+k-hop
# ---------------------------------------------------------------------------

def plot_scaling_per_algo_k(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Separate scaling grids per algo+k: one figure per metric × scenario × algo_k_variant."""
    numeric_varying = [d for d in NUMERIC_DIMS if d in vdims and d not in ["kHops", "p2pRqsRadius"]]
    if not numeric_varying:
        return

    rqs_varies = "p2pRqsRadius" in vdims and len(vdims.get("p2pRqsRadius", [])) > 1
    if not rqs_varies:
        return

    sns.set_theme(style="whitegrid")
    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])
    has_scenarios = "spawnScenario" in vdims
    rqs_vals = sorted(vdims["p2pRqsRadius"], key=_rqs_sort_key)
    rqs_palette = _palette(len(rqs_vals))
    rqs_cmap = {r: rqs_palette[i] for i, r in enumerate(rqs_vals)}

    for metric in metrics:
        mdf = _collector_focus(df[df["metric"] == metric])
        all_algos = _sorted_variants(mdf["variant_k"].dropna().unique())

        for scenario in scenarios:
            for algo_k in all_algos:
                sub = mdf[mdf["spawnScenario"] == scenario] if has_scenarios else mdf
                sub_algo = sub[sub["variant_k"] == algo_k]
                if sub_algo.empty:
                    continue

                n_rows = len(numeric_varying)
                fig, axes = plt.subplots(n_rows, 1, figsize=(8, 4 * n_rows), squeeze=False)

                for ri, dim in enumerate(numeric_varying):
                    ax = axes[ri][0]
                    line_count = 0
                    agg = (sub_algo.groupby(["p2pRqsRadius", dim], dropna=False)["avg"]
                           .agg(["mean"]).reset_index().sort_values(dim))
                    agg["ci95"] = [
                        _ci95(sub_algo[(sub_algo["p2pRqsRadius"] == r["p2pRqsRadius"]) & (sub_algo[dim] == r[dim])]["avg"])
                        for _, r in agg.iterrows()
                    ]

                    for rqs_val in rqs_vals:
                        vd = agg[agg["p2pRqsRadius"] == rqs_val].sort_values(dim)
                        if vd.empty or vd[dim].dropna().nunique() < 2:
                            continue
                        line_count += 1
                        ax.plot(vd[dim], vd["mean"], linewidth=1.8, label=f"RQS={rqs_val}",
                                color=rqs_cmap[rqs_val])
                        ax.fill_between(vd[dim], vd["mean"] - vd["ci95"], vd["mean"] + vd["ci95"],
                                        alpha=0.12, color=rqs_cmap[rqs_val])
                    ax.set_xlabel(DIM_LABELS.get(dim, dim), fontsize=9)
                    ax.set_ylabel(metric, fontsize=9)
                    ax.xaxis.set_major_locator(mticker.MaxNLocator(integer=True))
                    ax.set_title(DIM_LABELS.get(dim, dim), fontsize=10)
                    if line_count > 0:
                        ax.legend(title="RQS-Radius", fontsize=8, loc="best")

                fig.suptitle(_wrap(f"Skalierung @ {algo_k}: {metric}  [Spawn: {scenario}]"), y=1.01, fontsize=13)
                _save(fig, plots_dir / f"scaling_per_algo_{_safe(scenario)}_{_safe(metric)}_{_safe(algo_k)}.png")


# ---------------------------------------------------------------------------
# 6. Distribution violin + strip (multi-run only)
# ---------------------------------------------------------------------------

def plot_distributions(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Violin + strip plots – one plot per scenario."""
    n_per_v = df.groupby("variant_k")["avg"].count()
    if not (n_per_v > 1).any():
        return
    sns.set_theme(style="whitegrid")

    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])

    for metric in metrics:
        mdf = df[df["metric"] == metric].copy()
        if mdf.empty:
            continue
        variant_order = _sorted_variants(mdf["variant_k"].dropna().unique())

        for scenario in scenarios:
            sub = mdf[mdf["spawnScenario"] == scenario] if "spawnScenario" in mdf.columns else mdf
            if sub.empty:
                continue

            fig, ax = plt.subplots(figsize=(max(8, 2.5 * len(variant_order)), 5.5))
            try:
                sns.violinplot(data=sub, x="variant_k", y="avg", order=variant_order,
                               inner="quartile", density_norm="width", cut=0, ax=ax)
            except TypeError:
                sns.violinplot(data=sub, x="variant_k", y="avg", order=variant_order,
                               inner="quartile", scale="width", cut=0, ax=ax)
            sns.stripplot(data=sub, x="variant_k", y="avg", order=variant_order,
                          color="black", size=3, alpha=0.4, jitter=True, ax=ax)
            ax.set_xlabel("")
            ax.set_ylabel(metric)
            ax.tick_params(axis="x", rotation=30)
            for lbl in ax.get_xticklabels():
                lbl.set_ha("right")
            ax.set_title(_wrap(f"Verteilung: {metric}  [Spawn: {scenario}]"), fontsize=12)
            _save(fig, plots_dir / f"dist_{_safe(scenario)}_{_safe(metric)}.png")


# ---------------------------------------------------------------------------
# 7. Stability scatter (multi-run only)
# ---------------------------------------------------------------------------

def plot_stability(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Mean vs std scatter: identify unstable configurations."""
    n_per_v = df.groupby("variant_k")["avg"].count()
    if not (n_per_v > 1).any():
        return
    sns.set_theme(style="whitegrid")

    group_cols = (["metric", "variant_k", "spawnScenario"]
                  if "spawnScenario" in df.columns else ["metric", "variant_k"])
    agg = (df[df["metric"].isin(metrics)]
           .groupby(group_cols, dropna=False)["avg"]
           .agg(["mean", "std", "count"]).reset_index())
    agg = agg[agg["std"].fillna(0) > _ZERO_THR]
    if agg.empty:
        return

    hue_order = _sorted_variants(agg["variant_k"].dropna().unique())
    fig, ax = plt.subplots(figsize=(11, 6))
    sns.scatterplot(data=agg, x="mean", y="std", hue="variant_k", hue_order=hue_order,
                    style="metric", size="count", sizes=(40, 250), alpha=0.82, ax=ax)
    ax.set_xlabel("Mittelwert (avg)")
    ax.set_ylabel("Standardabweichung der Runs")
    ax.set_title("Stabilitätskarte: Mittelwert vs. Streuung")
    _save(fig, plots_dir / "stability.png")


# ---------------------------------------------------------------------------
# 8. 3-D plot: taxiCount × clientCount surface/bar per variant × metric
# ---------------------------------------------------------------------------

def _build_3d_figure(sub: pd.DataFrame, variants: list[str], bar_col: str,
                     bar_cmap: dict, taxi_vals: list, client_vals: list,
                     taxi_step: float, client_step: float,
                     metric: str, title: str, plots_dir: Path, fname: str) -> None:
    """Core 3D bar builder used by all 3D plot variants."""
    n_v = len(variants)
    if n_v == 0:
        return
    group_width_x = taxi_step * 0.7
    group_width_y = client_step * 0.3
    bar_dx = group_width_x / max(n_v, 1)
    bar_dy = group_width_y
    x_offsets = [(i - n_v / 2 + 0.5) * bar_dx for i in range(n_v)]

    fig = plt.figure(figsize=(max(10, 3 * len(taxi_vals)), max(7, 2.5 * len(client_vals))))
    ax = fig.add_subplot(1, 1, 1, projection="3d")

    legend_handles, bar_x, bar_y, bar_h, bar_colors, annotations = [], [], [], [], [], []

    for vi, var in enumerate(variants):
        vdata = sub[sub[bar_col] == var]
        agg = (vdata.groupby(["taxiCount", "clientCount"], dropna=False)["avg"]
               .mean().reset_index())
        if agg.empty:
            continue
        color = bar_cmap[var]
        for x, y, z in zip(agg["taxiCount"], agg["clientCount"], agg["avg"]):
            if np.isnan(z):
                continue
            bar_x.append(float(x) + x_offsets[vi] - bar_dx / 2)
            bar_y.append(float(y) - bar_dy / 2)
            bar_h.append(float(z))
            bar_colors.append((*color, 0.78))
            if n_v <= 5:
                annotations.append((float(x) + x_offsets[vi], float(y), float(z), color))
        legend_handles.append(plt.Rectangle((0, 0), 1, 1, fc=(*color, 0.78),
                                            ec="white", linewidth=0.5, label=var))

    if bar_x:
        n_bars = len(bar_x)
        ax.bar3d(np.asarray(bar_x), np.asarray(bar_y), np.zeros(n_bars),
                 np.full(n_bars, bar_dx * 0.72), np.full(n_bars, bar_dy * 0.72),
                 np.asarray(bar_h), color=bar_colors, edgecolor="white",
                 linewidth=0.3, shade=True, zsort="average")
        for x, y, z, color in annotations:
            ax.text(x, y, z * 1.015, f"{z:.1f}", ha="center", va="bottom",
                    fontsize=6.5, color=[c * 0.6 for c in color])

    ax.set_xlabel(DIM_LABELS.get("taxiCount", "taxiCount"), fontsize=10, labelpad=8)
    ax.set_ylabel(DIM_LABELS.get("clientCount", "clientCount"), fontsize=10, labelpad=8)
    ax.set_zlabel(metric, fontsize=10, labelpad=6)
    ax.set_xticks(taxi_vals)
    ax.set_yticks(client_vals)
    ax.view_init(elev=28, azim=-50)
    if legend_handles:
        ax.legend(handles=legend_handles, title="Variante", fontsize=8,
                  loc="upper left", bbox_to_anchor=(0.0, 1.0))
    fig.suptitle(_wrap(title), y=1.01, fontsize=13)
    _save(fig, plots_dir / fname)


def plot_3d_taxi_client(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """3-D grouped bar chart: all algorithm variants – one plot per metric × scenario."""
    if "taxiCount" not in vdims or "clientCount" not in vdims:
        return
    sns.set_theme(style="whitegrid")
    taxi_vals = sorted(vdims["taxiCount"])
    client_vals = sorted(vdims["clientCount"])
    taxi_step = (max(taxi_vals) - min(taxi_vals)) / max(len(taxi_vals) - 1, 1) if len(taxi_vals) > 1 else 1.0
    client_step = (max(client_vals) - min(client_vals)) / max(len(client_vals) - 1, 1) if len(client_vals) > 1 else 1.0
    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])

    for metric in metrics:
        mdf = df[df["metric"] == metric]
        variants = _sorted_variants(mdf["variant_k"].dropna().unique())
        palette = _palette(len(variants))
        cmap = {v: palette[i] for i, v in enumerate(variants)}
        for scenario in scenarios:
            sub = mdf[mdf["spawnScenario"] == scenario] if "spawnScenario" in mdf.columns else mdf
            if sub.empty:
                continue
            _build_3d_figure(sub, variants, "variant_k", cmap, taxi_vals, client_vals,
                             taxi_step, client_step, metric,
                             f"3D: Taxi × Client → {metric}  [Spawn: {scenario}]",
                             plots_dir, f"3d_taxi_client_{_safe(scenario)}_{_safe(metric)}.png")


def plot_3d_per_rqs(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """3-D plots filtered per RQS value: shows all algo/k at a fixed RQS."""
    if "taxiCount" not in vdims or "clientCount" not in vdims:
        return
    if "p2pRqsRadius" not in vdims or len(vdims["p2pRqsRadius"]) < 2:
        return
    sns.set_theme(style="whitegrid")
    taxi_vals = sorted(vdims["taxiCount"])
    client_vals = sorted(vdims["clientCount"])
    taxi_step = (max(taxi_vals) - min(taxi_vals)) / max(len(taxi_vals) - 1, 1) if len(taxi_vals) > 1 else 1.0
    client_step = (max(client_vals) - min(client_vals)) / max(len(client_vals) - 1, 1) if len(client_vals) > 1 else 1.0
    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])
    rqs_vals = sorted(vdims["p2pRqsRadius"], key=_rqs_sort_key)

    for metric in metrics:
        mdf = _collector_focus(df[df["metric"] == metric])
        # Consistent algo color map across all RQS plots
        all_variants = _sorted_variants(mdf["variant_k"].dropna().unique())
        palette = _palette(len(all_variants))
        cmap = {v: palette[i] for i, v in enumerate(all_variants)}

        for scenario in scenarios:
            base_sub = mdf[mdf["spawnScenario"] == scenario] if "spawnScenario" in mdf.columns else mdf
            for rqs_val in rqs_vals:
                sub = base_sub[base_sub["p2pRqsRadius"] == rqs_val]
                if sub.empty:
                    continue
                variants = _sorted_variants(sub["variant_k"].dropna().unique())
                _build_3d_figure(sub, variants, "variant_k", cmap, taxi_vals, client_vals,
                                 taxi_step, client_step, metric,
                                 f"3D @ RQS={rqs_val}: Taxi × Client → {metric}  [Spawn: {scenario}]",
                                 plots_dir,
                                 f"3d_per_rqs_{_safe(scenario)}_{_safe(metric)}_rqs{_safe(str(rqs_val))}.png")


def plot_3d_per_algo_k(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """3-D plots per algo+k: bars are RQS values (shows RQS impact on scaling)."""
    if "taxiCount" not in vdims or "clientCount" not in vdims:
        return
    if "p2pRqsRadius" not in vdims or len(vdims["p2pRqsRadius"]) < 2:
        return
    sns.set_theme(style="whitegrid")
    taxi_vals = sorted(vdims["taxiCount"])
    client_vals = sorted(vdims["clientCount"])
    taxi_step = (max(taxi_vals) - min(taxi_vals)) / max(len(taxi_vals) - 1, 1) if len(taxi_vals) > 1 else 1.0
    client_step = (max(client_vals) - min(client_vals)) / max(len(client_vals) - 1, 1) if len(client_vals) > 1 else 1.0
    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])
    rqs_vals = sorted(vdims["p2pRqsRadius"], key=_rqs_sort_key)
    rqs_palette = _palette(len(rqs_vals))
    rqs_cmap = {r: rqs_palette[i] for i, r in enumerate(rqs_vals)}
    rqs_label_map = {r: f"RQS={r}" for r in rqs_vals}

    for metric in metrics:
        mdf = _collector_focus(df[df["metric"] == metric])
        all_algos = _sorted_variants(mdf["variant_k"].dropna().unique())

        for scenario in scenarios:
            base_sub = mdf[mdf["spawnScenario"] == scenario] if "spawnScenario" in mdf.columns else mdf
            for algo_k in all_algos:
                sub_algo = base_sub[base_sub["variant_k"] == algo_k].copy()
                if sub_algo.empty:
                    continue
                # Treat each RQS value as a "variant" for bar grouping
                sub_algo["_rqs_label"] = sub_algo["p2pRqsRadius"].apply(lambda r: f"RQS={r}")
                rqs_variants = [f"RQS={r}" for r in rqs_vals if not sub_algo[sub_algo["p2pRqsRadius"] == r].empty]
                rqs_bar_cmap = {f"RQS={r}": rqs_cmap[r] for r in rqs_vals}
                _build_3d_figure(sub_algo, rqs_variants, "_rqs_label", rqs_bar_cmap,
                                 taxi_vals, client_vals, taxi_step, client_step, metric,
                                 f"3D @ {algo_k}: RQS-Impact → {metric}  [Spawn: {scenario}]",
                                 plots_dir,
                                 f"3d_per_algo_{_safe(scenario)}_{_safe(metric)}_{_safe(algo_k)}.png")


# ---------------------------------------------------------------------------
# 8b. Impact comparison bar charts (key thesis plots)
# ---------------------------------------------------------------------------

def plot_impact_by_rqs(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Grouped bar: x=algo_k, groups=RQS values. Shows RQS impact per algorithm."""
    if "p2pRqsRadius" not in vdims or len(vdims["p2pRqsRadius"]) < 2:
        return
    sns.set_theme(style="whitegrid")
    rqs_vals = sorted(vdims["p2pRqsRadius"], key=_rqs_sort_key)
    rqs_palette = _palette(len(rqs_vals))
    rqs_cmap = {r: rqs_palette[i] for i, r in enumerate(rqs_vals)}
    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])

    for metric in metrics:
        mdf = _collector_focus(df[df["metric"] == metric])
        algos = _sorted_variants(mdf["variant_k"].dropna().unique())
        n_a, n_r = len(algos), len(rqs_vals)
        bar_w = 0.8 / max(n_r, 1)
        x_base = np.arange(n_a)

        for scenario in scenarios:
            sub = mdf[mdf["spawnScenario"] == scenario] if "spawnScenario" in mdf.columns else mdf
            fig, ax = plt.subplots(figsize=(max(9, 2.0 * n_a), 5))
            for ri, rqs_val in enumerate(rqs_vals):
                vals, cis = [], []
                for algo in algos:
                    s = sub[(sub["variant_k"] == algo) & (sub["p2pRqsRadius"] == rqs_val)]["avg"]
                    vals.append(float(s.mean()) if not s.empty else 0.0)
                    cis.append(_ci95(s))
                offset = (ri - n_r / 2 + 0.5) * bar_w
                ax.bar(x_base + offset, vals, yerr=cis, width=bar_w * 0.88,
                       label=f"RQS={rqs_val}", color=rqs_cmap[rqs_val], capsize=3,
                       error_kw={"elinewidth": 1.0, "ecolor": "#333"})
            ax.set_xticks(x_base)
            ax.set_xticklabels(algos, rotation=35, ha="right", fontsize=8)
            ax.set_ylabel(metric)
            ax.legend(title="RQS-Radius", fontsize=8,
                      loc="center left", bbox_to_anchor=(1.01, 0.5))
            ax.set_title(_wrap(f"RQS-Einfluss: {metric}  [Spawn: {scenario}]"), fontsize=12)
            _save(fig, plots_dir / f"impact_rqs_{_safe(scenario)}_{_safe(metric)}.png")


def plot_impact_by_khop(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Grouped bar: x=algo_k, groups=kHops values. Shows k-hop impact per algorithm."""
    if "kHops" not in vdims or len(vdims["kHops"]) < 2:
        return
    sns.set_theme(style="whitegrid")
    khop_vals = sorted(vdims["kHops"])
    khop_palette = _palette(len(khop_vals))
    khop_cmap = {k: khop_palette[i] for i, k in enumerate(khop_vals)}
    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])

    for metric in metrics:
        mdf = df[df["metric"] == metric]
        algos = _sorted_variants(mdf["variant_short"].dropna().unique())
        n_a, n_k = len(algos), len(khop_vals)
        bar_w = 0.8 / max(n_k, 1)
        x_base = np.arange(n_a)

        for scenario in scenarios:
            sub = mdf[mdf["spawnScenario"] == scenario] if "spawnScenario" in mdf.columns else mdf
            fig, ax = plt.subplots(figsize=(max(9, 2.0 * n_a), 5))
            for ki, kval in enumerate(khop_vals):
                label = f"k={_fmt_khops(kval)}"
                color = khop_cmap[kval]
                vals, cis = [], []
                for algo in algos:
                    s = sub[(sub["variant_short"] == algo) & (sub["kHops"] == kval)]["avg"]
                    vals.append(float(s.mean()) if not s.empty else 0.0)
                    cis.append(_ci95(s))
                offset = (ki - n_k / 2 + 0.5) * bar_w
                ax.bar(x_base + offset, vals, yerr=cis, width=bar_w * 0.88,
                       label=label, color=color, capsize=3,
                       error_kw={"elinewidth": 1.0, "ecolor": "#333"})
            ax.set_xticks(x_base)
            ax.set_xticklabels(algos, rotation=35, ha="right", fontsize=8)
            ax.set_ylabel(metric)
            ax.legend(title="k-Hop", fontsize=8,
                      loc="center left", bbox_to_anchor=(1.01, 0.5))
            ax.set_title(_wrap(f"k-Hop-Einfluss: {metric}  [Spawn: {scenario}]"), fontsize=12)
            _save(fig, plots_dir / f"impact_khop_{_safe(scenario)}_{_safe(metric)}.png")


def plot_impact_by_strategy(df: pd.DataFrame, vdims: dict, plots_dir: Path, metrics: list[str]) -> None:
    """Grouped bar: x=algo, groups=p2pStrategy. Shows strategy (nearest/greedy) impact."""
    if "p2pStrategy" not in vdims or len(vdims["p2pStrategy"]) < 2:
        return
    sns.set_theme(style="whitegrid")
    strats = sorted(vdims["p2pStrategy"])
    strat_palette = _palette(len(strats))
    strat_cmap = {s: strat_palette[i] for i, s in enumerate(strats)}
    scenarios = vdims.get("spawnScenario",
                          [df["spawnScenario"].iloc[0]] if "spawnScenario" in df.columns else ["BASELINE"])

    for metric in metrics:
        mdf = df[df["metric"] == metric]
        algos = _sorted_variants(mdf["algo_short"].dropna().unique())
        n_a, n_s = len(algos), len(strats)
        bar_w = 0.8 / max(n_s, 1)
        x_base = np.arange(n_a)

        for scenario in scenarios:
            sub = mdf[mdf["spawnScenario"] == scenario] if "spawnScenario" in mdf.columns else mdf
            fig, ax = plt.subplots(figsize=(max(9, 2.0 * n_a), 5))
            for si, strat in enumerate(strats):
                vals, cis = [], []
                for algo in algos:
                    s = sub[(sub["algo_short"] == algo) & (sub["p2pStrategy"] == strat)]["avg"]
                    vals.append(float(s.mean()) if not s.empty else 0.0)
                    cis.append(_ci95(s))
                offset = (si - n_s / 2 + 0.5) * bar_w
                ax.bar(x_base + offset, vals, yerr=cis, width=bar_w * 0.88,
                       label=strat, color=strat_cmap[strat], capsize=3,
                       error_kw={"elinewidth": 1.0, "ecolor": "#333"})
            ax.set_xticks(x_base)
            ax.set_xticklabels(algos, rotation=35, ha="right", fontsize=8)
            ax.set_ylabel(metric)
            ax.legend(title="P2P-Strategie", fontsize=8,
                      loc="center left", bbox_to_anchor=(1.01, 0.5))
            ax.set_title(_wrap(f"Strategie-Einfluss: {metric}  [Spawn: {scenario}]"), fontsize=12)
            _save(fig, plots_dir / f"impact_strategy_{_safe(scenario)}_{_safe(metric)}.png")


def write_stats(df: pd.DataFrame, dirs: dict, metrics: list[str]) -> pd.DataFrame:
    records = []
    for metric in metrics:
        mdf = df[df["metric"] == metric]
        groups = [g["avg"].values for _, g in mdf.groupby("variant_k") if len(g) >= 2]
        if len(groups) >= 2 and any(pd.Series(g).std(ddof=0) > 0 for g in groups):
            try:
                f, p = stats.f_oneway(*groups)
                records.append({"metric": metric, "test": "ANOVA(avg ~ variant_k)",
                                "F": float(f), "p_value": float(p)})
            except Exception:
                pass

    tests_df = pd.DataFrame(records)
    tests_df.to_csv(dirs["stats"] / "anova_results.csv", index=False)

    group_cols = (["metric", "variant_k", "spawnScenario"]
                  if "spawnScenario" in df.columns else ["metric", "variant_k"])
    summary = (
        df[df["metric"].isin(metrics)]
        .groupby(group_cols, dropna=False)["avg"]
        .agg(["count", "mean", "median", "std", "min", "max"])
        .reset_index()
    )
    summary.to_csv(dirs["tables"] / "summary.csv", index=False)

    pivot_cols = [c for c in ["variant_k", "kHops", "spawnScenario", "taxiCount", "taxiSeatCount",
                              "p2pRqsRadius", "p2pOverlayMinNeighbors", "p2pOverlayMaxNeighbors",
                              "p2pOverlayShortcuts", "idleRoamingMode"] if c in df.columns]
    pivot = (
        df[df["metric"].isin(metrics)]
        .pivot_table(index=pivot_cols, columns="metric", values="avg", aggfunc="mean")
        .reset_index()
    )
    pivot.to_csv(dirs["tables"] / "pivot.csv", index=False)
    return tests_df


# ---------------------------------------------------------------------------
# Overview JSON
# ---------------------------------------------------------------------------

def write_overview(df: pd.DataFrame, vdims: dict, out: Path) -> dict:
    def _ul(col):
        return sorted(df[col].dropna().unique().tolist()) if col in df.columns else []

    data = {
        "rows": int(len(df)),
        "algorithms": _ul("algorithm_base"),
        "variants": _ul("variant"),
        "metrics": _ul("metric"),
        "spawnScenarios": _ul("spawnScenario"),
        "varying_dims": {k: [str(x) for x in v] for k, v in vdims.items()},
        "runIndex_range": [int(df["runIndex"].min()), int(df["runIndex"].max())] if "runIndex" in df.columns else [],
    }
    out.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    return data


# ---------------------------------------------------------------------------
# HTML report
# ---------------------------------------------------------------------------

_GROUP_ORDER = [
    "Algorithmen-Vergleich",
    "RQS-Einfluss", "k-Hop-Einfluss", "Strategie-Einfluss",
    "Skalierung (Übersicht)", "Skalierung (RQS-gemittelt)", "Skalierung (pro Algo/k)",
    "3D Taxi×Client", "3D pro RQS", "3D pro Algo/k",
    "Dimension-Effekte", "Kategorie-Effekte", "Mehr-Dim Facet",
    "Verteilungen", "Diagnostik",
]


def _plot_group(name: str) -> str:
    if name.startswith("algo_overview"):      return "Algorithmen-Vergleich"
    if name.startswith("impact_rqs"):         return "RQS-Einfluss"
    if name.startswith("impact_khop"):        return "k-Hop-Einfluss"
    if name.startswith("impact_strategy"):    return "Strategie-Einfluss"
    if name.startswith("scaling_avg_rqs"):    return "Skalierung (RQS-gemittelt)"
    if name.startswith("scaling_per_algo"):   return "Skalierung (pro Algo/k)"
    if name.startswith("scaling"):            return "Skalierung (Übersicht)"
    if name.startswith("3d_per_rqs"):         return "3D pro RQS"
    if name.startswith("3d_per_algo"):        return "3D pro Algo/k"
    if name.startswith("3d_taxi_client"):     return "3D Taxi×Client"
    if name.startswith("dim_"):               return "Dimension-Effekte"
    if name.startswith("cat_"):               return "Kategorie-Effekte"
    if name.startswith("multi_"):             return "Mehr-Dim Facet"
    if name.startswith("dist"):               return "Verteilungen"
    return "Diagnostik"


def build_report(dirs: dict, overview: dict, tests_df: pd.DataFrame) -> None:
    plots = sorted(p.name for p in dirs["plots"].glob("*.png"))
    tables = sorted(p.name for p in dirs["tables"].glob("*.csv"))
    stats_files = sorted(p.name for p in dirs["stats"].glob("*.csv"))

    # Group plots and collect all image paths for lightbox navigation
    grouped: dict[str, list[str]] = {}
    for pname in plots:
        grouped.setdefault(_plot_group(pname), []).append(pname)

    # Build flat ordered list of all images for prev/next navigation
    all_images: list[str] = []
    for g in _GROUP_ORDER:
        all_images.extend(grouped.get(g, []))

    def _figures(names: list[str]) -> str:
        items = []
        for n in names:
            idx = all_images.index(n)
            caption = n.replace(".png", "").replace("_", " ")
            items.append(
                f"<figure>"
                f"<figcaption>{html.escape(caption)}</figcaption>"
                f"<img src='plots/{html.escape(n)}' alt='{html.escape(n)}' "
                f"     class='zoomable' data-idx='{idx}' title='Klicken zum Vergrößern'/>"
                f"</figure>"
            )
        return "".join(items)

    # Section nav links (only groups that have plots)
    active_groups = [g for g in _GROUP_ORDER if g in grouped]
    section_nav = "".join(
        f"<a href='#{_safe(g)}'>{html.escape(g)}</a>"
        for g in active_groups
    )

    plot_sections = "".join(
        f"<section id='{_safe(g)}'>"
        f"<h3>{html.escape(g)}"
        f"  <span class='badge'>{len(grouped[g])}</span>"
        f"  <a class='top-link' href='#plots'>↑ oben</a>"
        f"</h3>"
        f"<div class='grid'>{_figures(grouped[g])}</div>"
        f"</section>"
        for g in active_groups
    )

    # JSON array for JS lightbox
    import json as _json
    images_json = _json.dumps(
        [{"src": f"plots/{n}", "caption": n.replace(".png", "").replace("_", " ")} for n in all_images]
    )

    sig = 0
    findings = "<p>Keine signifikanten ANOVA-Ergebnisse.</p>"
    if tests_df is not None and not tests_df.empty and "p_value" in tests_df.columns:
        sig_df = tests_df[pd.to_numeric(tests_df["p_value"], errors="coerce") < 0.05]
        sig = len(sig_df)
        if sig:
            findings = "<ul>" + "".join(
                f"<li>{html.escape(str(r['metric']))}: {html.escape(str(r['test']))} (p={float(r['p_value']):.4g})</li>"
                for _, r in sig_df.iterrows()
            ) + "</ul>"

    cards = "".join(
        f"<div class='card'><div class='ct'>{html.escape(t)}</div><div class='cv'>{html.escape(v)}</div></div>"
        for t, v in [
            ("Zeilen", str(overview.get("rows", "–"))),
            ("Metriken", str(len(overview.get("metrics", [])))),
            ("Varianten", str(len(overview.get("variants", [])))),
            ("Spawn-Szenarien", str(len(overview.get("spawnScenarios", [])))),
            ("Var. Dimensionen", str(len(overview.get("varying_dims", {})))),
            ("Signif. ANOVA", str(sig)),
        ]
    )
    vdims_list = "".join(
        f"<li><strong>{html.escape(k)}</strong>: {html.escape(', '.join(str(x) for x in v))}</li>"
        for k, v in overview.get("varying_dims", {}).items()
    )

    html_out = f"""<!doctype html>
<html lang="de">
<head>
  <meta charset="utf-8"/>
  <title>Mass-Run Analyse</title>
  <style>
    /* ---- Base ---- */
    :root{{
      --bg:#f4f6fa;--card:#fff;--line:#dde1eb;--text:#1c2230;
      --muted:#5f6b7c;--acc:#2563eb;--acc2:#1d4ed8;
    }}
    *{{box-sizing:border-box}}
    body{{font-family:"Segoe UI",Arial,sans-serif;margin:0;color:var(--text);background:var(--bg);font-size:14px}}

    /* ---- Layout ---- */
    .layout{{display:flex;min-height:100vh}}
    aside{{
      width:220px;min-width:220px;background:#1e2535;color:#c9d0de;
      position:sticky;top:0;height:100vh;overflow-y:auto;flex-shrink:0;
      display:flex;flex-direction:column;padding:0 0 24px
    }}
    aside .brand{{padding:20px 18px 12px;font-size:15px;font-weight:700;color:#fff;border-bottom:1px solid #2e3a4e}}
    aside nav{{display:flex;flex-direction:column;padding:10px 0}}
    aside nav a{{
      color:#c9d0de;text-decoration:none;padding:7px 18px;font-size:13px;
      border-left:3px solid transparent;transition:background .15s,color .15s
    }}
    aside nav a:hover,aside nav a.active{{background:#2e3a4e;color:#fff;border-left-color:var(--acc)}}
    aside nav .nav-group{{padding:14px 18px 4px;font-size:10px;text-transform:uppercase;
      letter-spacing:.8px;color:#6b7a95;font-weight:600}}
    main{{flex:1;padding:28px 32px 60px;max-width:1320px;overflow-x:hidden}}

    /* ---- Typography ---- */
    h1{{font-size:22px;margin:0 0 4px}}
    h2{{font-size:17px;margin:32px 0 12px;padding-bottom:6px;border-bottom:1px solid var(--line)}}
    h3{{font-size:14px;margin:0 0 10px;display:flex;align-items:center;gap:8px}}
    p,li{{line-height:1.55}}

    /* ---- Cards ---- */
    .cards{{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;margin:12px 0 20px}}
    .card{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}}
    .ct{{color:var(--muted);font-size:10px;text-transform:uppercase;letter-spacing:.6px}}
    .cv{{font-size:24px;font-weight:700;margin-top:4px;color:var(--text)}}

    /* ---- Plot sections ---- */
    .plot-section{{margin-bottom:32px}}
    .badge{{background:var(--acc);color:#fff;border-radius:99px;font-size:10px;
            font-weight:700;padding:2px 7px;vertical-align:middle}}
    .top-link{{font-size:11px;color:var(--muted);text-decoration:none;margin-left:auto;font-weight:400}}
    .top-link:hover{{color:var(--acc)}}
    .grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(480px,1fr));gap:14px;margin-top:8px}}
    figure{{
      background:var(--card);border:1px solid var(--line);border-radius:10px;
      padding:12px;margin:0;display:flex;flex-direction:column
    }}
    figcaption{{font-size:11px;color:var(--muted);margin-bottom:8px;
                white-space:nowrap;overflow:hidden;text-overflow:ellipsis}}
    img.zoomable{{
      width:100%;height:auto;border-radius:6px;cursor:zoom-in;
      border:1px solid #e4e8f0;transition:opacity .15s
    }}
    img.zoomable:hover{{opacity:.88}}

    /* ---- Tables / details ---- */
    details{{background:var(--card);border:1px solid var(--line);border-radius:8px;
             padding:8px 14px;margin:6px 0}}
    summary{{cursor:pointer;font-weight:600;font-size:13px}}
    ul{{margin:6px 0 6px 18px}}
    code{{background:#eef1f8;padding:2px 5px;border-radius:4px;font-size:12px}}

    /* ---- Lightbox ---- */
    #lb{{
      display:none;position:fixed;inset:0;background:rgba(0,0,0,.88);
      z-index:9999;align-items:center;justify-content:center;flex-direction:column
    }}
    #lb.open{{display:flex}}
    #lb img{{
      max-width:92vw;max-height:84vh;border-radius:8px;
      box-shadow:0 8px 40px rgba(0,0,0,.6);cursor:default
    }}
    #lb-caption{{color:#d0d8e8;font-size:13px;margin-top:10px;max-width:92vw;text-align:center}}
    #lb-counter{{color:#8090a8;font-size:11px;margin-top:4px}}
    .lb-btn{{
      position:fixed;top:50%;transform:translateY(-50%);background:rgba(255,255,255,.12);
      color:#fff;border:none;border-radius:50%;width:46px;height:46px;font-size:22px;
      cursor:pointer;display:flex;align-items:center;justify-content:center;
      transition:background .15s;z-index:10000
    }}
    .lb-btn:hover{{background:rgba(255,255,255,.28)}}
    #lb-prev{{left:16px}}
    #lb-next{{right:16px}}
    #lb-close{{
      position:fixed;top:14px;right:18px;background:rgba(255,255,255,.12);
      color:#fff;border:none;border-radius:50%;width:36px;height:36px;
      font-size:18px;cursor:pointer;display:flex;align-items:center;
      justify-content:center;transition:background .15s;z-index:10000
    }}
    #lb-close:hover{{background:rgba(255,255,255,.28)}}
  </style>
</head>
<body>
<div class="layout">

  <!-- Sidebar -->
  <aside>
    <div class="brand">Mass-Run Analyse</div>
    <nav>
      <span class="nav-group">Bericht</span>
      <a href="#overview">Übersicht</a>
      <a href="#dims">Dimensionen</a>
      <a href="#findings">Befunde</a>
      <span class="nav-group">Plots</span>
      <a href="#plots">Alle Plots</a>
      {section_nav}
      <span class="nav-group">Daten</span>
      <a href="#files">Dateien</a>
    </nav>
  </aside>

  <!-- Main content -->
  <main>
    <section id="overview">
      <h2>Übersicht</h2>
      <div class="cards">{cards}</div>
    </section>

    <section id="dims">
      <h2>Variierende Dimensionen</h2>
      <ul>{vdims_list}</ul>
    </section>

    <section id="findings">
      <h2>Statistische Befunde (ANOVA)</h2>
      {findings}
    </section>

    <section id="plots">
      <h2>Plots</h2>
      {plot_sections}
    </section>

    <section id="files">
      <h2>Dateien</h2>
      <details open><summary>Tabellen ({len(tables)})</summary>
        <ul>{''.join(f"<li><code>tables/{html.escape(n)}</code></li>" for n in tables)}</ul>
      </details>
      <details><summary>Statistiken ({len(stats_files)})</summary>
        <ul>{''.join(f"<li><code>stats/{html.escape(n)}</code></li>" for n in stats_files)}</ul>
      </details>
    </section>
  </main>
</div>

<!-- Lightbox overlay -->
<div id="lb" role="dialog" aria-modal="true">
  <button id="lb-close" title="Schließen (Esc)">✕</button>
  <button class="lb-btn" id="lb-prev" title="Vorheriges (←)">&#8249;</button>
  <img id="lb-img" src="" alt=""/>
  <button class="lb-btn" id="lb-next" title="Nächstes (→)">&#8250;</button>
  <div id="lb-caption"></div>
  <div id="lb-counter"></div>
</div>

<script>
(function(){{
  var IMAGES = {images_json};
  var cur = 0;
  var lb   = document.getElementById('lb');
  var img  = document.getElementById('lb-img');
  var cap  = document.getElementById('lb-caption');
  var ctr  = document.getElementById('lb-counter');

  function open(idx) {{
    cur = (idx + IMAGES.length) % IMAGES.length;
    img.src = IMAGES[cur].src;
    cap.textContent = IMAGES[cur].caption;
    ctr.textContent = (cur+1) + ' / ' + IMAGES.length;
    lb.classList.add('open');
    document.body.style.overflow = 'hidden';
  }}
  function close() {{
    lb.classList.remove('open');
    document.body.style.overflow = '';
  }}
  function prev() {{ open(cur - 1); }}
  function next() {{ open(cur + 1); }}

  // Attach click to all thumbnails
  document.querySelectorAll('img.zoomable').forEach(function(el) {{
    el.addEventListener('click', function() {{ open(parseInt(el.dataset.idx)); }});
  }});

  document.getElementById('lb-close').addEventListener('click', close);
  document.getElementById('lb-prev').addEventListener('click', prev);
  document.getElementById('lb-next').addEventListener('click', next);

  // Click backdrop to close
  lb.addEventListener('click', function(e) {{
    if (e.target === lb) close();
  }});

  // Keyboard navigation
  document.addEventListener('keydown', function(e) {{
    if (!lb.classList.contains('open')) return;
    if (e.key === 'Escape')      close();
    if (e.key === 'ArrowLeft')   prev();
    if (e.key === 'ArrowRight')  next();
  }});

  // Highlight active sidebar link on scroll
  var sections = document.querySelectorAll('main section[id]');
  var links = document.querySelectorAll('aside nav a');
  window.addEventListener('scroll', function() {{
    var scrollY = window.scrollY + 80;
    var active = '';
    sections.forEach(function(s) {{
      if (s.offsetTop <= scrollY) active = '#' + s.id;
    }});
    links.forEach(function(a) {{
      a.classList.toggle('active', a.getAttribute('href') === active);
    }});
  }}, {{passive: true}});
}})();
</script>
</body>
</html>"""
    (dirs["base"] / "report.html").write_text(html_out, encoding="utf-8")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    cfg = parse_args()
    dirs = ensure_dirs(cfg.output_dir)
    df = load_data(cfg.input_csv)

    if cfg.metrics:
        df = df[df["metric"].isin(cfg.metrics)].copy()
        if df.empty:
            raise ValueError("No rows after --metrics filter.")

    metrics = active_metrics(df, cfg.metrics or None)
    vdims = varying_dims(df)

    print(f"[analyze] {len(df)} rows | {len(metrics)} metrics | varying dims: {list(vdims)}")

    overview = write_overview(df, vdims, dirs["base"] / "overview.json")
    tests_df = write_stats(df, dirs, metrics)

    # 1. Algorithm comparison bar chart (always generated)
    plot_algorithm_overview(df, vdims, dirs["plots"], metrics)

    # 2. Impact comparison: key thesis plots (RQS / k-hop / strategy vs. each algo)
    plot_impact_by_rqs(df, vdims, dirs["plots"], metrics)
    plot_impact_by_khop(df, vdims, dirs["plots"], metrics)
    plot_impact_by_strategy(df, vdims, dirs["plots"], metrics)

    # 3. Scaling overview grid (all varying numeric dims in one figure per metric)
    plot_scaling_overview(df, vdims, dirs["plots"], metrics)

    # 3b. Scaling averaged over RQS (k-hop/algo as lines)
    plot_scaling_per_rqs(df, vdims, dirs["plots"], metrics)

    # 3c. Scaling per algo+k-hop (RQS as lines)
    plot_scaling_per_algo_k(df, vdims, dirs["plots"], metrics)

    # 4. Individual line plots per varying numeric dimension
    for dim in NUMERIC_DIMS:
        if dim in vdims:
            plot_dim_effect(df, dim, vdims, dirs["plots"], metrics)

    # 5. Categorical dimension bar charts
    for dim in ["spawnScenario", "p2pStrategy", "idleRoamingMode", "taxiSeatCount"]:
        if dim in vdims:
            plot_categorical_dim(df, dim, vdims, dirs["plots"], metrics)

    # 6. Multi-dim facet plots for the most informative 2-dim combinations
    _facet_pairs = [
        ("taxiCount", "spawnScenario"),
        ("kHops", "spawnScenario"),
        ("p2pRqsRadius", "spawnScenario"),
        ("taxiCount", "taxiSeatCount"),
        ("kHops", "taxiSeatCount"),
        ("p2pRqsRadius", "taxiSeatCount"),
        ("taxiCount", "p2pStrategy"),
        ("taxiCount", "idleRoamingMode"),
    ]
    for dim_x, dim_facet in _facet_pairs:
        if dim_x in vdims and dim_facet in vdims:
            plot_two_dim_facet(df, dim_x, dim_facet, vdims, dirs["plots"], metrics)

    # 7. Distribution violin + strip (multi-run data only)
    plot_distributions(df, vdims, dirs["plots"], metrics)

    # 8. Stability scatter (multi-run data only)
    plot_stability(df, vdims, dirs["plots"], metrics)

    # 9. 3-D taxi × client: full, per-RQS, per-algo+k
    plot_3d_taxi_client(df, vdims, dirs["plots"], metrics)
    plot_3d_per_rqs(df, vdims, dirs["plots"], metrics)
    plot_3d_per_algo_k(df, vdims, dirs["plots"], metrics)

    build_report(dirs, overview, tests_df)
    print(f"[analyze] Done -> {dirs['base'] / 'report.html'}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Stratified mass-run analysis.

This script avoids city/scenario/config blending:
- ``tables/summary.csv`` aggregates only exact experiment configs across runs.
- ``tables/p2p_vs_single.csv`` compares each P2P config with the matching
  SinglePassenger result for the same metric, city size, seat count, and scenario.
- ``stats/*`` screens effects inside metric/city/scenario strata only.
"""

from __future__ import annotations

import argparse
import html
import json
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd

try:
    import matplotlib.pyplot as plt
except Exception:  # pragma: no cover - tables still work without plots
    plt = None

try:
    from scipy import stats
except Exception:  # pragma: no cover - factor screen becomes unavailable
    stats = None


NUMERIC_COLS = [
    "kHops",
    "p2pRequestRepublishTicks",
    "p2pRqsRadius",
    "taxiCount",
    "clientCount",
    "taxiSeatCount",
    "p2pOverlayMinNeighbors",
    "p2pOverlayMaxNeighbors",
    "p2pOverlayShortcuts",
    "p2pOverlayMaxDistanceFactor",
    "p2pTopologyScanTicks",
    "runIndex",
    "worldSeed",
    "min",
    "max",
    "avg",
    "sum",
    "count",
    "spread",
]

P2P_COLS = [
    "kHops",
    "p2pRequestRepublishTicks",
    "p2pRqsRadius",
    "p2pStrategy",
    "p2pOverlayMinNeighbors",
    "p2pOverlayMaxNeighbors",
    "p2pOverlayShortcuts",
    "p2pOverlayMaxDistanceFactor",
    "p2pTopologyScanTicks",
    "idleRoamingEnabled",
    "idleRoamingStrategy",
    "idleRoamingMode",
]

BASE_COLS = ["metric", "algorithm", "taxiCount", "clientCount", "taxiSeatCount", "spawnScenario"]
EXACT_CONFIG_COLS = BASE_COLS + P2P_COLS
MATCH_COLS = ["metric", "taxiCount", "clientCount", "taxiSeatCount", "spawnScenario"]
CORE_METRIC_PATTERNS = ["Taxi Travel Distance", "Client Waiting Time", "Client Travel Time"]
MAX_STRATIFIED_PLOTS = 36


@dataclass
class Config:
    input_csv: Path
    output_dir: Path
    metrics: list[str]


def parse_args() -> Config:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-csv", default="mass-run-results/mass-run-results.csv", type=Path)
    parser.add_argument("--output-dir", default="mass-run-results/analysis", type=Path)
    parser.add_argument("--metrics", default="", help="Comma-separated metric filter.")
    args = parser.parse_args()
    return Config(
        input_csv=args.input_csv,
        output_dir=args.output_dir,
        metrics=[m.strip() for m in args.metrics.split(",") if m.strip()],
    )


def ensure_dirs(base: Path) -> dict[str, Path]:
    dirs = {"base": base, "tables": base / "tables", "stats": base / "stats", "plots": base / "plots"}
    for directory in dirs.values():
        directory.mkdir(parents=True, exist_ok=True)
    for directory in [dirs["tables"], dirs["stats"], dirs["plots"]]:
        for old in directory.iterdir():
            if old.is_file():
                old.unlink()
    return dirs


def load_data(path: Path, metrics: list[str]) -> pd.DataFrame:
    if not path.exists():
        raise FileNotFoundError(f"CSV not found: {path}")

    df = pd.read_csv(path)
    for col in NUMERIC_COLS:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    defaults = {
        "p2pStrategy": "n/a",
        "idleRoamingStrategy": "n/a",
        "idleRoamingMode": "n/a",
        "spawnScenario": "BASELINE",
        "idleRoamingEnabled": False,
    }
    for col, default in defaults.items():
        if col not in df.columns:
            df[col] = default
        df[col] = df[col].fillna(default)

    for col in ["algorithm", "metric", "p2pStrategy", "idleRoamingStrategy", "idleRoamingMode", "spawnScenario"]:
        df[col] = df[col].astype(str).str.strip().replace({"": "n/a", "nan": "n/a"})

    for col in P2P_COLS:
        if col not in df.columns:
            df[col] = "n/a"

    required = ["metric", "algorithm", "avg", "runIndex", "taxiCount", "clientCount", "spawnScenario"]
    missing = [col for col in required if col not in df.columns]
    if missing:
        raise ValueError(f"CSV missing required columns: {missing}")

    df = df.dropna(subset=["avg", "runIndex", "taxiCount", "clientCount"])
    if metrics:
        df = df[df["metric"].isin(metrics)].copy()
        if df.empty:
            raise ValueError("No rows left after --metrics filter.")

    df["is_p2p"] = df["algorithm"].str.contains("P2PCollector", case=False, na=False)
    df.loc[~df["is_p2p"], P2P_COLS] = df.loc[~df["is_p2p"], P2P_COLS].where(
        df.loc[~df["is_p2p"], P2P_COLS].notna(), "n/a"
    )
    return df


def write_overview(df: pd.DataFrame, path: Path) -> dict:
    overview = {
        "rows": int(len(df)),
        "iterations": int(len(df) // max(1, df["metric"].nunique())),
        "metrics": sorted(df["metric"].unique().tolist()),
        "algorithms": df["algorithm"].value_counts().to_dict(),
        "taxiClientPairs": df[["taxiCount", "clientCount"]].drop_duplicates().to_dict("records"),
        "spawnScenarios": sorted(df["spawnScenario"].unique().tolist()),
    }
    if "timestamp" in df.columns:
        ts = pd.to_datetime(df["timestamp"], errors="coerce")
        overview["timestampMin"] = str(ts.min())
        overview["timestampMax"] = str(ts.max())
        overview["days"] = sorted(ts.dt.date.dropna().astype(str).unique().tolist())
    path.write_text(json.dumps(overview, indent=2), encoding="utf-8")
    return overview


def aggregate_exact_configs(df: pd.DataFrame, out: Path) -> pd.DataFrame:
    summary = (
        df.groupby(EXACT_CONFIG_COLS, dropna=False)
        .agg(
            runs=("runIndex", "nunique"),
            avgMean=("avg", "mean"),
            avgStd=("avg", "std"),
            avgMin=("avg", "min"),
            avgMax=("avg", "max"),
            countMean=("count", "mean"),
            countMin=("count", "min"),
            countMax=("count", "max"),
            rawRows=("avg", "size"),
        )
        .reset_index()
    )
    summary["servedRatioMean"] = np.where(
        summary["clientCount"].gt(0), summary["countMean"] / summary["clientCount"], np.nan
    )
    summary.to_csv(out / "summary.csv", index=False)
    return summary


def compare_p2p_to_single(summary: pd.DataFrame, out: Path) -> pd.DataFrame:
    single = summary[~summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
    single = single[MATCH_COLS + ["avgMean", "countMean"]].rename(
        columns={"avgMean": "singleAvg", "countMean": "singleCount"}
    )

    p2p = summary[summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
    cols = MATCH_COLS + P2P_COLS + ["avgMean", "avgStd", "countMean", "servedRatioMean", "runs"]
    comp = p2p[cols].merge(single, on=MATCH_COLS, how="left")
    comp["delta"] = comp["avgMean"] - comp["singleAvg"]
    comp["deltaPct"] = np.where(comp["singleAvg"].ne(0), comp["delta"] / comp["singleAvg"] * 100.0, np.nan)
    comp.to_csv(out / "p2p_vs_single.csv", index=False)

    best = (
        comp.sort_values(["metric", "taxiCount", "clientCount", "spawnScenario", "avgMean"])
        .groupby(MATCH_COLS, dropna=False)
        .head(1)
        .reset_index(drop=True)
    )
    best.to_csv(out / "best_p2p_vs_single.csv", index=False)
    return comp


def write_single_passenger(summary: pd.DataFrame, out: Path) -> None:
    single = summary[~summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
    single[BASE_COLS + ["runs", "avgMean", "avgStd", "avgMin", "avgMax", "servedRatioMean"]].to_csv(
        out / "single_passenger.csv", index=False
    )


def pair_effect(df: pd.DataFrame, factor: str, a: object, b: object) -> pd.DataFrame:
    p2p = df[df["is_p2p"]].copy()
    correlated = {factor, "algorithm"}
    if factor == "idleRoamingMode":
        correlated.add("idleRoamingStrategy")
    index_cols = [col for col in EXACT_CONFIG_COLS if col not in correlated] + ["runIndex", "worldSeed"]
    wide = p2p.pivot_table(index=index_cols, columns=factor, values="avg", aggfunc="first")
    if a not in wide.columns or b not in wide.columns:
        return pd.DataFrame()
    diff = (wide[a] - wide[b]).abs().dropna()
    if diff.empty:
        return pd.DataFrame()
    frame = diff.reset_index(name="absDiff")
    return (
        frame.groupby(["metric", "taxiCount", "clientCount", "spawnScenario"], dropna=False)
        .agg(pairs=("absDiff", "size"), changed=("absDiff", lambda s: int((s > 1e-9).sum())), maxAbsDiff=("absDiff", "max"))
        .reset_index()
        .assign(factor=factor, valueA=str(a), valueB=str(b))
    )


def write_effect_screens(df: pd.DataFrame, out: Path) -> None:
    strategy = pair_effect(df, "p2pStrategy", "nearest", "greedy")
    strategy.to_csv(out / "strategy_effect.csv", index=False)

    roaming = pd.concat(
        [
            pair_effect(df, "idleRoamingMode", "random", "past-avg-revisit"),
            pair_effect(df, "idleRoamingMode", "past-avg", "past-avg-total"),
        ],
        ignore_index=True,
    )
    roaming.to_csv(out / "roaming_effect.csv", index=False)

    if stats is None:
        pd.DataFrame([{"note": "scipy not available"}]).to_csv(out / "factor_screen.csv", index=False)
        return

    factors = ["kHops", "p2pRqsRadius", "p2pStrategy", "p2pOverlayShortcuts", "idleRoamingMode"]
    records = []
    p2p = df[df["is_p2p"]].copy()
    for keys, sub in p2p.groupby(["metric", "taxiCount", "clientCount", "spawnScenario"], dropna=False):
        for factor in factors:
            groups = [g["avg"].dropna().to_numpy() for _, g in sub.groupby(factor, dropna=False)]
            groups = [g for g in groups if len(g) >= 2]
            if len(groups) < 2:
                continue
            constant_groups = all(np.allclose(group, group[0]) for group in groups)
            if constant_groups:
                values = [float(group[0]) for group in groups]
                same_value = np.allclose(values, values[0])
                records.append(
                    {
                        "metric": keys[0],
                        "taxiCount": keys[1],
                        "clientCount": keys[2],
                        "spawnScenario": keys[3],
                        "factor": factor,
                        "F": np.nan if same_value else np.inf,
                        "pValue": np.nan if same_value else 0.0,
                        "note": "constant input; ANOVA skipped",
                    }
                )
                continue
            f_value, p_value = stats.f_oneway(*groups)
            records.append(
                {
                    "metric": keys[0],
                    "taxiCount": keys[1],
                    "clientCount": keys[2],
                    "spawnScenario": keys[3],
                    "factor": factor,
                    "F": f_value,
                    "pValue": p_value,
                    "note": "one-factor screen within metric/city/scenario; inspect matched deltas before claiming causality",
                }
            )
    pd.DataFrame(records).to_csv(out / "factor_screen.csv", index=False)


def safe_name(text: object) -> str:
    return "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in str(text)).strip("_")


def core_metrics(df: pd.DataFrame) -> list[str]:
    metrics = sorted(df["metric"].dropna().unique().tolist())
    selected = [metric for metric in metrics if any(pattern in metric for pattern in CORE_METRIC_PATTERNS)]
    return selected or metrics


def city_label(taxi_count: object, client_count: object) -> str:
    try:
        taxis = int(taxi_count)
        clients = int(client_count)
    except Exception:
        return f"{taxi_count}/{client_count}"
    return "Konstanz" if taxis < 500 else "Berlin" if taxis > 500 else f"{taxis}/{clients}"


def row_value(row: object, name: str) -> object:
    if isinstance(row, pd.Series):
        return row[name]
    return getattr(row, name)


def config_label(row: object) -> str:
    return (
        f"k={int(row_value(row, 'kHops'))}, r={int(row_value(row, 'p2pRqsRadius'))}, "
        f"{row_value(row, 'p2pStrategy')}, shortcuts={int(row_value(row, 'p2pOverlayShortcuts'))}, "
        f"roam={row_value(row, 'idleRoamingMode')}"
    )


def fmt_number(value: object, suffix: str = "") -> str:
    try:
        number = float(value)
    except Exception:
        return "n/a"
    if not np.isfinite(number):
        return "n/a"
    return f"{number:.2f}{suffix}"


def plot_entry(file: str, section: str, note: str = "") -> dict[str, str]:
    return {"file": file, "section": section, "note": note}


def finite_series(*values: object) -> pd.Series:
    series = [pd.to_numeric(pd.Series(value), errors="coerce") for value in values]
    if not series:
        return pd.Series(dtype=float)
    data = pd.concat(series, ignore_index=True).dropna()
    return data[np.isfinite(data)]


def set_sensible_y_span(ax, *values: object) -> None:
    data = finite_series(*values)
    if data.empty:
        return
    low = float(data.min())
    high = float(data.max())
    span = high - low
    baseline = max(abs(float(data.mean())), 1.0)
    min_span = baseline * 0.05
    if span < min_span:
        mid = (low + high) / 2.0
        ax.set_ylim(mid - min_span / 2.0, mid + min_span / 2.0)


def add_line(ax, data: pd.DataFrame, x_col: str, y_col: str, label_col: str) -> int:
    plotted = 0
    for label, group in data.groupby(label_col, dropna=False):
        group = group.sort_values(x_col)
        group = group[pd.to_numeric(group[y_col], errors="coerce").notna()]
        if group.empty:
            continue
        ax.plot(group[x_col], group[y_col], marker="o", linewidth=2, label=str(label))
        plotted += 1
    return plotted


def compact_fixed_config(values: dict[str, object]) -> str:
    labels = [
        ("kHops", "k"),
        ("p2pRqsRadius", "r"),
        ("p2pStrategy", "strategy"),
        ("p2pOverlayShortcuts", "shortcuts"),
        ("idleRoamingMode", "roam"),
    ]
    return ", ".join(f"{label}={values[col]}" for col, label in labels if col in values)


def plot_information_trends(summary: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None:
        return []
    p2p = summary[summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
    files = []
    for metric in core_metrics(p2p):
        metric_df = p2p[p2p["metric"].eq(metric)]
        for (taxi_count, client_count, scenario), sub in metric_df.groupby(
            ["taxiCount", "clientCount", "spawnScenario"], dropna=False
        ):
            agg = (
                sub.groupby(["kHops", "p2pRqsRadius"], dropna=False)["avgMean"]
                .mean()
                .reset_index()
            )
            if agg["kHops"].nunique() > 1:
                fig, ax = plt.subplots(figsize=(7.5, 4.5))
                add_line(ax, agg, "kHops", "avgMean", "p2pRqsRadius")
                ax.set_title(f"H1 k-hop trend | {metric} | {city_label(taxi_count, client_count)} | {scenario}")
                ax.set_xlabel("k-Hops")
                ax.set_ylabel(metric)
                set_sensible_y_span(ax, agg["avgMean"])
                ax.legend(title="RQS radius", fontsize=8)
                fig.tight_layout()
                name = f"h1_k_trend_{safe_name(metric)}_{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
                fig.savefig(out / name, dpi=140)
                plt.close(fig)
                files.append(plot_entry(name, "Hypothesis plots", "Overview: averages all P2P configs with the same metric, scale, scenario, k, and RQS radius. Strategy, shortcuts, and roaming vary."))
            if agg["p2pRqsRadius"].nunique() > 1:
                fig, ax = plt.subplots(figsize=(7.5, 4.5))
                add_line(ax, agg, "p2pRqsRadius", "avgMean", "kHops")
                ax.set_title(f"H1 RQS trend | {metric} | {city_label(taxi_count, client_count)} | {scenario}")
                ax.set_xlabel("RQS radius [m]")
                ax.set_ylabel(metric)
                set_sensible_y_span(ax, agg["avgMean"])
                ax.legend(title="k-Hops", fontsize=8)
                fig.tight_layout()
                name = f"h1_rqs_trend_{safe_name(metric)}_{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
                fig.savefig(out / name, dpi=140)
                plt.close(fig)
                files.append(plot_entry(name, "Hypothesis plots", "Overview: averages all P2P configs with the same metric, scale, scenario, k, and RQS radius. Strategy, shortcuts, and roaming vary."))
    return files


def plot_scale_trends(comp: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None or comp.empty:
        return []
    best = (
        comp.sort_values(["metric", "taxiCount", "clientCount", "spawnScenario", "avgMean"])
        .groupby(MATCH_COLS, dropna=False)
        .head(1)
        .reset_index(drop=True)
    )
    files = []
    for metric in core_metrics(best):
        metric_df = best[best["metric"].eq(metric)]
        for scenario, sub in metric_df.groupby("spawnScenario", dropna=False):
            sub = sub.sort_values("clientCount")
            fig, ax = plt.subplots(figsize=(7, 4.5))
            ax.plot(sub["clientCount"], sub["singleAvg"], marker="o", linewidth=2, label="SinglePassenger")
            ax.plot(sub["clientCount"], sub["avgMean"], marker="o", linewidth=2, label="Best P2P")
            ax.set_xscale("log")
            ax.set_title(f"H2 scale trend | {metric} | {scenario}")
            ax.set_xlabel("Client count (log scale)")
            ax.set_ylabel(metric)
            set_sensible_y_span(ax, sub["singleAvg"], sub["avgMean"])
            ax.legend()
            fig.tight_layout()
            name = f"h2_scale_trend_{safe_name(metric)}_{safe_name(scenario)}.png"
            fig.savefig(out / name, dpi=140)
            plt.close(fig)
            files.append(plot_entry(name, "Hypothesis plots"))
    return files


def plot_roaming_trends(summary: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None:
        return []
    p2p = summary[summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
    files = []
    for metric in core_metrics(p2p):
        metric_df = p2p[p2p["metric"].eq(metric)]
        for (taxi_count, client_count), sub in metric_df.groupby(["taxiCount", "clientCount"], dropna=False):
            agg = (
                sub.groupby(["spawnScenario", "idleRoamingMode"], dropna=False)["avgMean"]
                .mean()
                .reset_index()
            )
            if agg["idleRoamingMode"].nunique() < 2:
                continue
            scenarios = sorted(agg["spawnScenario"].unique().tolist())
            x = np.arange(len(scenarios))
            fig, ax = plt.subplots(figsize=(8, 4.8))
            series_by_label: list[tuple[str, pd.Series]] = []
            for mode, group in agg.groupby("idleRoamingMode", dropna=False):
                y = group.set_index("spawnScenario").reindex(scenarios)["avgMean"]
                if y.notna().sum() < 2:
                    continue
                for idx, (_, existing) in enumerate(series_by_label):
                    if np.allclose(y.fillna(np.nan), existing.fillna(np.nan), equal_nan=True):
                        series_by_label[idx] = (f"{series_by_label[idx][0]} / {mode}", existing)
                        break
                else:
                    series_by_label.append((str(mode), y))
            if not series_by_label:
                plt.close(fig)
                continue
            for label, y in series_by_label:
                ax.plot(x, y, marker="o", linewidth=2, label=label)
            ax.set_title(f"H3 roaming trend | {metric} | {city_label(taxi_count, client_count)}")
            ax.set_xlabel("Scenario")
            ax.set_ylabel(metric)
            set_sensible_y_span(ax, agg["avgMean"])
            ax.set_xticks(x)
            ax.set_xticklabels(scenarios, rotation=25, ha="right")
            ax.legend(title="Roaming", fontsize=8)
            fig.tight_layout()
            name = f"h3_roaming_trend_{safe_name(metric)}_{safe_name(city_label(taxi_count, client_count))}.png"
            fig.savefig(out / name, dpi=140)
            plt.close(fig)
            files.append(plot_entry(name, "Hypothesis plots", "Overview: averages all P2P configs with the same metric, scale, scenario, and roaming mode. k, radius, strategy, and shortcuts vary. Empty roaming modes are skipped from the legend."))
    return files


def plot_topology_trends(summary: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None:
        return []
    p2p = summary[summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
    files = []
    for metric in core_metrics(p2p):
        metric_df = p2p[p2p["metric"].eq(metric)]
        for (taxi_count, client_count, scenario), sub in metric_df.groupby(
            ["taxiCount", "clientCount", "spawnScenario"], dropna=False
        ):
            agg = (
                sub.groupby(["kHops", "p2pOverlayShortcuts"], dropna=False)["avgMean"]
                .mean()
                .reset_index()
            )
            if agg["p2pOverlayShortcuts"].nunique() < 2:
                continue
            fig, ax = plt.subplots(figsize=(7.5, 4.5))
            add_line(ax, agg, "kHops", "avgMean", "p2pOverlayShortcuts")
            ax.set_title(f"H4 topology trend | {metric} | {city_label(taxi_count, client_count)} | {scenario}")
            ax.set_xlabel("k-Hops")
            ax.set_ylabel(metric)
            set_sensible_y_span(ax, agg["avgMean"])
            ax.legend(title="Shortcuts", fontsize=8)
            fig.tight_layout()
            name = f"h4_shortcut_trend_{safe_name(metric)}_{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
            fig.savefig(out / name, dpi=140)
            plt.close(fig)
            files.append(plot_entry(name, "Hypothesis plots", "Overview: averages all P2P configs with the same metric, scale, scenario, k, and shortcut count. Radius, strategy, and roaming vary."))
    return files


def plot_fixed_parameter_trends(
    summary: pd.DataFrame,
    out: Path,
    varied_cols: list[str],
    x_col: str,
    label_col: str,
    title_prefix: str,
    filename_prefix: str,
    max_files: int,
) -> list[dict[str, str]]:
    if plt is None:
        return []
    p2p = summary[summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
    fixed_cols = [col for col in EXACT_CONFIG_COLS if col not in varied_cols]
    files = []
    core = set(core_metrics(p2p))
    for keys, sub in p2p.groupby(fixed_cols, dropna=False):
        fixed = dict(zip(fixed_cols, keys))
        if fixed["metric"] not in core:
            continue
        if sub[x_col].nunique() < 2 or sub[label_col].nunique() < 2:
            continue
        agg = sub.groupby([x_col, label_col], dropna=False)["avgMean"].mean().reset_index()
        fig, ax = plt.subplots(figsize=(7.8, 4.8))
        plotted = add_line(ax, agg, x_col, "avgMean", label_col)
        if plotted == 0:
            plt.close(fig)
            continue
        city = city_label(fixed["taxiCount"], fixed["clientCount"])
        ax.set_title(f"{title_prefix} | {fixed['metric']} | {city} | {fixed['spawnScenario']}")
        ax.set_xlabel(x_col)
        ax.set_ylabel(fixed["metric"])
        set_sensible_y_span(ax, agg["avgMean"])
        ax.legend(title=label_col, fontsize=8)
        fig.tight_layout()
        name = (
            f"{filename_prefix}_{safe_name(fixed['metric'])}_{safe_name(city)}_"
            f"{safe_name(fixed['spawnScenario'])}_{len(files) + 1}.png"
        )
        fig.savefig(out / name, dpi=140)
        plt.close(fig)
        note = "Fixed all non-plotted config columns; averages only repeated runs. " + compact_fixed_config(fixed)
        files.append(plot_entry(name, "Interesting trends", note))
        if len(files) >= max_files:
            break
    return files


def plot_best_delta_trends(comp: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None or comp.empty:
        return []
    best = (
        comp.sort_values(["metric", "taxiCount", "clientCount", "spawnScenario", "avgMean"])
        .groupby(MATCH_COLS, dropna=False)
        .head(1)
        .reset_index(drop=True)
    )
    files = []
    for metric in core_metrics(best):
        metric_df = best[best["metric"].eq(metric)]
        for scenario, sub in metric_df.groupby("spawnScenario", dropna=False):
            if sub["clientCount"].nunique() < 2:
                continue
            sub = sub.sort_values("clientCount")
            fig, ax = plt.subplots(figsize=(7, 4.5))
            ax.plot(sub["clientCount"], sub["deltaPct"], marker="o", linewidth=2)
            ax.axhline(0, color="#6b7280", linewidth=1)
            ax.set_xscale("log")
            ax.set_title(f"Best P2P delta trend | {metric} | {scenario}")
            ax.set_xlabel("Client count (log scale)")
            ax.set_ylabel("Delta vs SinglePassenger [%]")
            fig.tight_layout()
            name = f"best_p2p_delta_trend_{safe_name(metric)}_{safe_name(scenario)}.png"
            fig.savefig(out / name, dpi=140)
            plt.close(fig)
            files.append(plot_entry(name, "Interesting trends"))
    return files


def plot_interesting_trends(summary: pd.DataFrame, comp: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    return (
        plot_best_delta_trends(comp, out)
        + plot_fixed_parameter_trends(
            summary,
            out,
            ["kHops", "p2pRqsRadius"],
            "kHops",
            "p2pRqsRadius",
            "Fixed-config k/RQS trend",
            "fixed_k_rqs_trend",
            MAX_STRATIFIED_PLOTS // 2,
        )
        + plot_fixed_parameter_trends(
            summary,
            out,
            ["kHops", "p2pOverlayShortcuts"],
            "kHops",
            "p2pOverlayShortcuts",
            "Fixed-config shortcut trend",
            "fixed_shortcut_trend",
            MAX_STRATIFIED_PLOTS // 2,
        )
    )


def plot_thesis_focus(summary: pd.DataFrame, comp: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    return (
        plot_best_vs_single(comp, out)
        + plot_scale_trends(comp, out)
        + plot_information_trends(summary, out)
        + plot_roaming_trends(summary, out)
        + plot_topology_trends(summary, out)
        + plot_interesting_trends(summary, comp, out)
    )


def plot_best_vs_single(comp: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None or comp.empty:
        return []
    best = (
        comp.sort_values(["metric", "taxiCount", "clientCount", "spawnScenario", "avgMean"])
        .groupby(MATCH_COLS, dropna=False)
        .head(1)
        .reset_index(drop=True)
    )
    files = []
    for metric, sub in best.groupby("metric", dropna=False):
        labels = [
            f"{int(r.taxiCount)}/{int(r.clientCount)}\n{r.spawnScenario}" for r in sub.itertuples(index=False)
        ]
        x = np.arange(len(sub))
        width = 0.38
        fig, ax = plt.subplots(figsize=(max(8, len(sub) * 1.25), 5))
        ax.bar(x - width / 2, sub["singleAvg"], width, label="SinglePassenger")
        ax.bar(x + width / 2, sub["avgMean"], width, label="Best P2P config")
        ax.set_ylabel(metric)
        set_sensible_y_span(ax, sub["singleAvg"], sub["avgMean"])
        ax.set_xticks(x)
        ax.set_xticklabels(labels, rotation=35, ha="right")
        ax.legend()
        fig.tight_layout()
        name = f"best_p2p_vs_single_{safe_name(metric)}.png"
        fig.savefig(out / name, dpi=140)
        plt.close(fig)
        files.append(plot_entry(name, "Best P2P"))

        configs = [config_label(row) for row in sub.itertuples(index=False)]
        palette = plt.get_cmap("tab20")
        colors = {cfg: palette(i % 20) for i, cfg in enumerate(dict.fromkeys(configs))}
        fig, ax = plt.subplots(figsize=(max(9, len(sub) * 1.35), 5.4))
        seen = set()
        for i, (row, cfg) in enumerate(zip(sub.itertuples(index=False), configs)):
            label = cfg if cfg not in seen else None
            if i == 0:
                ax.bar(i - width / 2, row.singleAvg, width, color="#6b7280", label="SinglePassenger")
            else:
                ax.bar(i - width / 2, row.singleAvg, width, color="#6b7280")
            ax.bar(i + width / 2, row.avgMean, width, color=colors[cfg], label=label)
            seen.add(cfg)
        ax.set_ylabel(metric)
        set_sensible_y_span(ax, sub["singleAvg"], sub["avgMean"])
        ax.set_xticks(x)
        ax.set_xticklabels(labels, rotation=35, ha="right")
        ax.legend(title="Best P2P config", fontsize=8, ncol=1 if len(seen) < 6 else 2)
        fig.tight_layout()
        name = f"best_p2p_config_colored_{safe_name(metric)}.png"
        fig.savefig(out / name, dpi=140)
        plt.close(fig)
        files.append(plot_entry(name, "Best P2P"))
    return files


def write_report(base: Path, overview: dict, plot_files: list[dict[str, str]]) -> None:
    tables = sorted(p.name for p in (base / "tables").glob("*.csv"))
    stats_files = sorted(p.name for p in (base / "stats").glob("*.csv"))
    best_path = base / "tables" / "best_p2p_vs_single.csv"
    best_rows = pd.read_csv(best_path) if best_path.exists() else pd.DataFrame()
    if not best_rows.empty:
        best_rows = best_rows.copy()
        best_rows = best_rows[best_rows["metric"].isin(core_metrics(best_rows))]
        if not best_rows.empty:
            best_rows["city"] = best_rows.apply(lambda r: city_label(r["taxiCount"], r["clientCount"]), axis=1)
            best_rows["config"] = best_rows.apply(config_label, axis=1)
            best_table = "".join(
                "<tr>"
                f"<td>{html.escape(str(row.metric))}</td>"
                f"<td>{html.escape(str(row.city))}</td>"
                f"<td>{html.escape(str(row.spawnScenario))}</td>"
                f"<td>{html.escape(str(row.config))}</td>"
                f"<td>{fmt_number(row.avgMean)}</td>"
                f"<td>{fmt_number(row.singleAvg)}</td>"
                f"<td>{fmt_number(row.deltaPct, '%')}</td>"
                "</tr>"
                for row in best_rows.itertuples(index=False)
            )
        else:
            best_table = '<tr><td colspan="7">No core best-config rows found.</td></tr>'
    else:
        best_table = '<tr><td colspan="7">No best-config table found.</td></tr>'
    table_links = "".join(
        f'<a class="file" href="tables/{html.escape(name)}"><span>{html.escape(name)}</span><small>table</small></a>'
        for name in tables
    )
    stat_links = "".join(
        f'<a class="file" href="stats/{html.escape(name)}"><span>{html.escape(name)}</span><small>stats</small></a>'
        for name in stats_files
    )
    plot_records = [
        item if isinstance(item, dict) else {"file": item, "section": "Plots", "note": ""}
        for item in plot_files
    ]
    plot_sections = []
    for section in ["Best P2P", "Hypothesis plots", "Interesting trends", "Plots"]:
        records = [record for record in plot_records if record["section"] == section]
        if not records:
            continue
        cards = "\n".join(
            f"""
            <a class="plot" href="plots/{html.escape(record['file'])}">
              <img src="plots/{html.escape(record['file'])}" alt="{html.escape(record['file'])}">
              <span><strong>{html.escape(record['file'])}</strong>{('<small>' + html.escape(record['note']) + '</small>') if record.get('note') else ''}</span>
            </a>"""
            for record in records
        )
        plot_sections.append(f'<section class="plot-group"><h3>{html.escape(section)}</h3><div class="plots">{cards}</div></section>')
    plots = "\n".join(plot_sections)
    days = overview.get("days") or []
    day_text = f"{days[0]} to {days[-1]}" if days else "n/a"
    pairs = ", ".join(f"{int(p['taxiCount'])}/{int(p['clientCount'])}" for p in overview["taxiClientPairs"])
    metric_list = "".join(f"<li>{html.escape(metric)}</li>" for metric in overview["metrics"])
    scenario_list = "".join(f"<li>{html.escape(scenario)}</li>" for scenario in overview["spawnScenarios"])
    body = f"""<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Mass-run analysis</title>
  <style>
    :root {{
      --bg: #f6f7f9;
      --panel: #ffffff;
      --ink: #20242a;
      --muted: #657083;
      --line: #dbe1ea;
      --accent: #0f766e;
      --accent-soft: #d9f3ef;
      --shadow: 0 10px 30px rgba(22, 34, 51, .08);
    }}
    * {{ box-sizing: border-box; }}
    html {{ scroll-behavior: smooth; }}
    body {{
      margin: 0;
      background: var(--bg);
      color: var(--ink);
      font: 15px/1.5 system-ui, -apple-system, Segoe UI, sans-serif;
    }}
    a {{ color: inherit; text-decoration: none; }}
    .layout {{ display: grid; grid-template-columns: 260px 1fr; min-height: 100vh; }}
    aside {{
      position: sticky;
      top: 0;
      height: 100vh;
      padding: 24px 18px;
      background: #17202b;
      color: #eef3f8;
    }}
    .brand {{ font-size: 19px; font-weight: 750; margin-bottom: 22px; }}
    nav {{ display: grid; gap: 6px; }}
    nav a {{
      padding: 9px 10px;
      border-radius: 7px;
      color: #cbd5e1;
    }}
    nav a:hover {{ background: rgba(255,255,255,.08); color: #fff; }}
    main {{ padding: 30px; max-width: 1320px; width: 100%; }}
    section {{ margin-bottom: 28px; scroll-margin-top: 24px; }}
    .hero {{
      display: flex;
      justify-content: space-between;
      gap: 18px;
      align-items: flex-end;
      padding-bottom: 18px;
      border-bottom: 1px solid var(--line);
      margin-bottom: 24px;
    }}
    h1 {{ font-size: 30px; line-height: 1.15; margin: 0 0 8px; }}
    h2 {{ font-size: 18px; margin: 0 0 12px; }}
    p {{ color: var(--muted); margin: 0; }}
    .cards {{
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 12px;
    }}
    .card, .panel {{
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 8px;
      box-shadow: var(--shadow);
    }}
    .card {{ padding: 16px; }}
    .card small {{ display: block; color: var(--muted); margin-bottom: 6px; }}
    .card strong {{ display: block; font-size: 22px; }}
    .panel {{ padding: 18px; }}
    .split {{ display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }}
    ul {{ margin: 0; padding-left: 20px; }}
    .files {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 10px; }}
    .file {{
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 10px 12px;
      background: #fff;
      border: 1px solid var(--line);
      border-radius: 7px;
    }}
    .file:hover {{ border-color: var(--accent); background: var(--accent-soft); }}
    .file span {{ overflow-wrap: anywhere; }}
    .file small {{ color: var(--muted); text-transform: uppercase; letter-spacing: .04em; }}
    .plots {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 14px; }}
    .plot {{
      overflow: hidden;
      background: #fff;
      border: 1px solid var(--line);
      border-radius: 8px;
      box-shadow: var(--shadow);
    }}
    .plot:hover {{ border-color: var(--accent); }}
    .plot img {{ display: block; width: 100%; background: #fff; }}
    .plot span {{
      display: block;
      padding: 10px 12px;
      border-top: 1px solid var(--line);
      color: var(--muted);
      overflow-wrap: anywhere;
      font-size: 13px;
    }}
    .plot span strong {{ display: block; color: var(--ink); font-weight: 650; margin-bottom: 4px; }}
    .plot span small {{ display: block; color: var(--muted); line-height: 1.35; }}
    .plot-group {{ margin-bottom: 18px; }}
    h3 {{ font-size: 15px; margin: 0 0 10px; color: var(--muted); }}
    code {{
      background: #eef1f5;
      padding: 2px 5px;
      border-radius: 4px;
      font-size: 13px;
    }}
    .table-wrap {{ overflow-x: auto; }}
    table {{ width: 100%; border-collapse: collapse; min-width: 920px; }}
    th, td {{ padding: 9px 10px; border-bottom: 1px solid var(--line); text-align: left; vertical-align: top; }}
    th {{ color: var(--muted); font-size: 12px; text-transform: uppercase; letter-spacing: .04em; }}
    td:nth-child(4) {{ font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: 13px; }}
    @media (max-width: 860px) {{
      .layout {{ display: block; }}
      aside {{ position: static; height: auto; }}
      nav {{ grid-template-columns: repeat(2, minmax(0, 1fr)); }}
      main {{ padding: 18px; }}
      .hero {{ display: block; }}
      .cards, .split {{ grid-template-columns: 1fr; }}
      .plots {{ grid-template-columns: 1fr; }}
    }}
  </style>
</head>
<body>
<div class="layout">
  <aside>
    <div class="brand">Mass-run analysis</div>
    <nav>
      <a href="#overview">Overview</a>
      <a href="#dimensions">Dimensions</a>
      <a href="#best">Best P2P</a>
      <a href="#averaging">Averaging</a>
      <a href="#tables">Tables</a>
      <a href="#stats">Stats</a>
      <a href="#plots">Plots</a>
    </nav>
  </aside>
  <main>
    <section class="hero" id="overview">
      <div>
        <h1>Stratified mass-run report</h1>
        <p>Exact-config summaries. Matched P2P vs SinglePassenger deltas. No blended city averages.</p>
      </div>
      <p><code>{day_text}</code></p>
    </section>

    <section>
      <div class="cards">
        <div class="card"><small>Rows</small><strong>{overview["rows"]}</strong></div>
        <div class="card"><small>Iterations</small><strong>{overview["iterations"]}</strong></div>
        <div class="card"><small>Metrics</small><strong>{len(overview["metrics"])}</strong></div>
        <div class="card"><small>Taxi/client pairs</small><strong>{html.escape(pairs)}</strong></div>
      </div>
    </section>

    <section id="dimensions" class="split">
      <div class="panel">
        <h2>Metrics</h2>
        <ul>{metric_list}</ul>
      </div>
      <div class="panel">
        <h2>Scenarios</h2>
        <ul>{scenario_list}</ul>
      </div>
    </section>

    <section id="best" class="panel">
      <h2>Best P2P configs</h2>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Metric</th>
              <th>Scale</th>
              <th>Scenario</th>
              <th>Config</th>
              <th>P2P avg</th>
              <th>Single avg</th>
              <th>Delta</th>
            </tr>
          </thead>
          <tbody>{best_table}</tbody>
        </table>
      </div>
    </section>

    <section id="averaging" class="panel">
      <h2>Averaging rules</h2>
      <ul>
        <li><code>summary.csv</code>: one row per exact config; averages repeated runs only.</li>
        <li><code>best_p2p_vs_single.csv</code>: chooses the lowest P2P average per metric, scale, seat count, and scenario.</li>
        <li>Overview hypothesis plots average over non-plotted P2P parameters; each caption states what varies.</li>
        <li>Interesting trend plots fix all non-plotted config columns; only repeated runs are averaged.</li>
      </ul>
    </section>

    <section id="tables" class="panel">
      <h2>Tables</h2>
      <div class="files">{table_links}</div>
    </section>

    <section id="stats" class="panel">
      <h2>Stats</h2>
      <div class="files">{stat_links}</div>
    </section>

    <section id="plots">
      <h2>Plots</h2>
      {plots or '<p>No plots generated.</p>'}
    </section>
  </main>
</div>
</body>
</html>
"""
    (base / "report.html").write_text(body, encoding="utf-8")


def main() -> None:
    config = parse_args()
    dirs = ensure_dirs(config.output_dir)
    df = load_data(config.input_csv, config.metrics)
    overview = write_overview(df, dirs["base"] / "overview.json")
    summary = aggregate_exact_configs(df, dirs["tables"])
    comp = compare_p2p_to_single(summary, dirs["tables"])
    write_single_passenger(summary, dirs["tables"])
    write_effect_screens(df, dirs["stats"])
    plots = plot_thesis_focus(summary, comp, dirs["plots"])
    write_report(dirs["base"], overview, plots)
    print(f"[analyze] {len(df)} rows -> {dirs['base'] / 'report.html'}")


if __name__ == "__main__":
    main()

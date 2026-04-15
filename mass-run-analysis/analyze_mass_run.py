#!/usr/bin/env python3
"""Mass-run CSV analysis with statistics, diagnostics, plots and HTML report.

Example:
  python mass-run-analysis/analyze_mass_run.py \
    --input-csv mass-run-results/mass-run-results.csv \
    --output-dir mass-run-results/analysis
"""

from __future__ import annotations

import argparse
import html
import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import seaborn as sns
from scipy import stats


NUMERIC_COLUMNS = [
    "kHops",
    "taxiCount",
    "taxiSeatCount",
    "runIndex",
    "worldSeed",
    "min",
    "max",
    "avg",
    "sum",
    "count",
    "spread",
]
CATEGORY_COLUMNS = ["algorithm", "p2pStrategy", "metric"]
OPTIONAL_NUMERIC_COLUMNS = {"taxiCount": -1, "taxiSeatCount": -1}


@dataclass
class AnalysisConfig:
    input_csv: Path
    output_dir: Path
    metrics: list[str]


def parse_args() -> AnalysisConfig:
    parser = argparse.ArgumentParser(description="Analyze mass-run CSV results and generate statistics + plots.")
    parser.add_argument("--input-csv", required=True, type=Path, help="Path to mass-run-results.csv")
    parser.add_argument("--output-dir", required=True, type=Path, help="Directory for analysis outputs")
    parser.add_argument(
        "--metrics",
        type=str,
        default="",
        help="Comma-separated metric filter (default: all metrics found in CSV)",
    )
    args = parser.parse_args()

    metrics = [m.strip() for m in args.metrics.split(",") if m.strip()]
    return AnalysisConfig(input_csv=args.input_csv, output_dir=args.output_dir, metrics=metrics)


def ensure_dirs(base: Path) -> dict[str, Path]:
    paths = {
        "base": base,
        "tables": base / "tables",
        "plots": base / "plots",
        "stats": base / "stats",
    }
    for p in paths.values():
        p.mkdir(parents=True, exist_ok=True)

    # Remove stale artifacts from previous runs so report/plots stay consistent.
    for old_plot in paths["plots"].glob("*.png"):
        old_plot.unlink(missing_ok=True)
    for old_table in paths["tables"].glob("*.csv"):
        old_table.unlink(missing_ok=True)
    for old_stats in paths["stats"].glob("*.csv"):
        old_stats.unlink(missing_ok=True)
    (paths["base"] / "report.html").unlink(missing_ok=True)

    return paths


def is_collector_algorithm(algorithm_name: str) -> bool:
    return "p2pcollector" in (algorithm_name or "").lower()


def with_semantic_grouping(df: pd.DataFrame) -> pd.DataFrame:
    out = df.copy()
    out["algorithm_base"] = out["algorithm"].astype(str).str.strip()
    out["strategy_effective"] = out.apply(
        lambda r: str(r["p2pStrategy"]).strip() if is_collector_algorithm(str(r["algorithm_base"])) else "n/a",
        axis=1,
    )
    out["algorithm_variant"] = out.apply(
        lambda r: f"{r['algorithm_base']}+{r['strategy_effective']}"
        if r["strategy_effective"] != "n/a"
        else str(r["algorithm_base"]),
        axis=1,
    )
    return out


def load_data(csv_path: Path) -> pd.DataFrame:
    if not csv_path.exists():
        raise FileNotFoundError(f"CSV not found: {csv_path}")

    df = pd.read_csv(csv_path)
    for column, default_value in OPTIONAL_NUMERIC_COLUMNS.items():
        if column not in df.columns:
            df[column] = default_value

    missing = [c for c in [*NUMERIC_COLUMNS, *CATEGORY_COLUMNS] if c not in df.columns]
    if missing:
        raise ValueError(f"CSV is missing required columns: {missing}")

    for col in NUMERIC_COLUMNS:
        df[col] = pd.to_numeric(df[col], errors="coerce")

    for col in CATEGORY_COLUMNS:
        df[col] = df[col].astype(str).str.strip()

    df["timestamp"] = pd.to_datetime(df.get("timestamp"), errors="coerce", utc=True)
    df = df.dropna(subset=["avg", "algorithm", "metric", "kHops", "runIndex", "p2pStrategy"])
    df = with_semantic_grouping(df)
    return df


def write_overview(df: pd.DataFrame, out_file: Path) -> None:
    overview = {
        "rows": int(len(df)),
        "algorithm_base": sorted(df["algorithm_base"].unique().tolist()),
        "algorithm_variant": sorted(df["algorithm_variant"].unique().tolist()),
        "raw_strategies": sorted(df["p2pStrategy"].unique().tolist()),
        "effective_strategies": sorted(df["strategy_effective"].unique().tolist()),
        "metrics": sorted(df["metric"].unique().tolist()),
        "kHops": sorted(df["kHops"].dropna().astype(int).unique().tolist()),
        "taxiCount": sorted(df["taxiCount"].dropna().astype(int).unique().tolist()),
        "taxiSeatCount": sorted(df["taxiSeatCount"].dropna().astype(int).unique().tolist()),
        "runIndex_range": [int(df["runIndex"].min()), int(df["runIndex"].max())],
    }
    out_file.write_text(json.dumps(overview, indent=2), encoding="utf-8")


def descriptive_tables(df: pd.DataFrame, tables_dir: Path) -> None:
    by_group = (
        df.groupby(
            [
                "metric",
                "algorithm_variant",
                "algorithm_base",
                "strategy_effective",
                "kHops",
                "taxiCount",
                "taxiSeatCount",
            ],
            dropna=False,
        )["avg"]
        .agg(["count", "mean", "median", "std", "min", "max"])
        .reset_index()
        .sort_values(["metric", "algorithm_variant", "kHops", "taxiCount", "taxiSeatCount"])
    )
    by_group.to_csv(tables_dir / "avg_group_summary.csv", index=False)

    spread_group = (
        df.groupby(
            [
                "metric",
                "algorithm_variant",
                "algorithm_base",
                "strategy_effective",
                "kHops",
                "taxiCount",
                "taxiSeatCount",
            ],
            dropna=False,
        )["spread"]
        .agg(["count", "mean", "median", "std", "min", "max"])
        .reset_index()
        .sort_values(["metric", "algorithm_variant", "kHops", "taxiCount", "taxiSeatCount"])
    )
    spread_group.to_csv(tables_dir / "spread_group_summary.csv", index=False)

    pivot = (
        df.pivot_table(
            index=["algorithm_variant", "kHops", "taxiCount", "taxiSeatCount"],
            columns="metric",
            values="avg",
            aggfunc="mean",
        )
        .reset_index()
        .sort_values(["algorithm_variant", "kHops", "taxiCount", "taxiSeatCount"])
    )
    pivot.to_csv(tables_dir / "avg_metric_pivot.csv", index=False)


def statistical_tests(df: pd.DataFrame, stats_dir: Path) -> pd.DataFrame:
    results: list[dict[str, object]] = []

    for metric_name, metric_df in df.groupby("metric", dropna=False):
        groups = [series.values for _, series in metric_df.groupby("algorithm_variant")["avg"] if len(series) >= 2]
        has_variance = any(float(pd.Series(g).std(ddof=0)) > 0 for g in groups)
        if len(groups) >= 2 and has_variance:
            f_stat, f_p = stats.f_oneway(*groups)
            results.append(
                {
                    "metric": metric_name,
                    "test": "ANOVA(avg ~ algorithm_variant)",
                    "statistic": float(f_stat),
                    "p_value": float(f_p),
                }
            )

        hop_groups = [series.values for _, series in metric_df.groupby("kHops")["avg"] if len(series) >= 2]
        hop_has_variance = any(float(pd.Series(g).std(ddof=0)) > 0 for g in hop_groups)
        if len(hop_groups) >= 2 and hop_has_variance:
            h_stat, h_p = stats.kruskal(*hop_groups)
            results.append(
                {
                    "metric": metric_name,
                    "test": "Kruskal(avg ~ kHops)",
                    "statistic": float(h_stat),
                    "p_value": float(h_p),
                }
            )

        seat_groups = [series.values for _, series in metric_df.groupby("taxiSeatCount")["avg"] if len(series) >= 2]
        seat_has_variance = any(float(pd.Series(g).std(ddof=0)) > 0 for g in seat_groups)
        if len(seat_groups) >= 2 and seat_has_variance:
            s_stat, s_p = stats.kruskal(*seat_groups)
            results.append(
                {
                    "metric": metric_name,
                    "test": "Kruskal(avg ~ taxiSeatCount)",
                    "statistic": float(s_stat),
                    "p_value": float(s_p),
                }
            )

    out = pd.DataFrame(results)
    out.to_csv(stats_dir / "statistical_tests.csv", index=False)
    return out


def duplicate_diagnostics(df: pd.DataFrame, stats_dir: Path) -> pd.DataFrame:
    records: list[dict[str, object]] = []
    base_keys = ["metric", "algorithm_variant", "kHops", "taxiCount", "taxiSeatCount", "runIndex", "worldSeed"]
    compare_cols = ["avg", "min", "max", "spread", "sum", "count"]

    grouped = (
        df[base_keys + compare_cols]
        .sort_values(base_keys)
        .groupby(["metric", "algorithm_variant", "kHops", "taxiCount", "taxiSeatCount"], dropna=False)
    )

    fingerprints: dict[tuple[str, str, int, int, int], tuple] = {}
    for key, g in grouped:
        payload = tuple(tuple(round(float(x), 8) for x in row) for row in g[compare_cols].to_numpy())
        fingerprints[key] = payload

    keys = list(fingerprints.keys())
    for i in range(len(keys)):
        for j in range(i + 1, len(keys)):
            left = keys[i]
            right = keys[j]
            if left[0] != right[0]:
                continue
            if fingerprints[left] == fingerprints[right]:
                records.append(
                    {
                        "metric": left[0],
                        "left_variant": left[1],
                        "left_kHops": left[2],
                        "left_taxiCount": left[3],
                        "left_taxiSeatCount": left[4],
                        "right_variant": right[1],
                        "right_kHops": right[2],
                        "right_taxiCount": right[3],
                        "right_taxiSeatCount": right[4],
                        "status": "identical_series",
                    }
                )

    out = pd.DataFrame(records)
    out.to_csv(stats_dir / "duplicate_diagnostics.csv", index=False)
    return out


def _save_plot(fig: plt.Figure, target: Path) -> None:
    fig.tight_layout()
    fig.savefig(target, dpi=150)
    plt.close(fig)


def _safe_name(value: str) -> str:
    return re.sub(r"[^a-zA-Z0-9_\-]+", "_", str(value)).strip("_")


def plot_distributions(df: pd.DataFrame, plots_dir: Path) -> None:
    sns.set_theme(style="whitegrid")

    fig, ax = plt.subplots(figsize=(13, 7))
    sns.boxplot(data=df, x="metric", y="avg", hue="algorithm_variant", ax=ax)
    ax.set_title("Average metric values by algorithm variant")
    ax.set_xlabel("Metric")
    ax.set_ylabel("Avg")
    ax.tick_params(axis="x", rotation=20)
    _save_plot(fig, plots_dir / "box_avg_by_metric_algorithm_variant.png")

    fig, ax = plt.subplots(figsize=(13, 7))
    sns.boxplot(data=df, x="metric", y="spread", hue="algorithm_variant", ax=ax)
    ax.set_title("Spread by metric and algorithm variant")
    ax.set_xlabel("Metric")
    ax.set_ylabel("Spread")
    ax.tick_params(axis="x", rotation=20)
    _save_plot(fig, plots_dir / "box_spread_by_metric_algorithm_variant.png")


def plot_heatmaps(df: pd.DataFrame, plots_dir: Path) -> None:
    mean_df = (
        df.groupby(["algorithm_variant", "kHops", "taxiCount", "taxiSeatCount", "metric"], dropna=False)["avg"]
        .mean()
        .reset_index()
    )
    mean_df["row"] = (
        mean_df["algorithm_variant"]
        + " | k="
        + mean_df["kHops"].astype(int).astype(str)
        + " | taxis="
        + mean_df["taxiCount"].astype(int).astype(str)
        + " | seats="
        + mean_df["taxiSeatCount"].astype(int).astype(str)
    )
    pivot = mean_df.pivot(index="row", columns="metric", values="avg")

    fig, ax = plt.subplots(figsize=(12, max(5, 0.35 * len(pivot.index))))
    sns.heatmap(pivot, annot=True, fmt=".2f", cmap="viridis", ax=ax)
    ax.set_title("Mean avg by algorithm variant and kHops")
    _save_plot(fig, plots_dir / "heatmap_mean_avg_variant.png")


def plot_trends(df: pd.DataFrame, plots_dir: Path, metrics: Iterable[str]) -> None:
    for metric_name in metrics:
        metric_df = df[df["metric"] == metric_name].copy()
        if metric_df.empty:
            continue

        fig, ax = plt.subplots(figsize=(12, 7))
        sns.lineplot(
            data=metric_df,
            x="runIndex",
            y="avg",
            hue="algorithm_variant",
            style="taxiCount",
            markers=True,
            dashes=False,
            estimator="mean",
            errorbar=("ci", 95),
            ax=ax,
        )
        ax.set_title(f"Run trend: {metric_name}")
        ax.set_xlabel("Run index")
        ax.set_ylabel("Avg")
        safe_name = metric_name.replace(" ", "_").replace("/", "_")
        _save_plot(fig, plots_dir / f"trend_{safe_name}.png")


def plot_pairwise_strip(df: pd.DataFrame, plots_dir: Path) -> None:
    fig, ax = plt.subplots(figsize=(12, 7))
    sns.stripplot(
        data=df,
        x="algorithm_variant",
        y="avg",
        hue="metric",
        dodge=True,
        alpha=0.6,
        ax=ax,
    )
    ax.set_title("Run-level avg distribution by algorithm variant")
    ax.set_xlabel("Algorithm variant")
    ax.set_ylabel("Avg")
    ax.tick_params(axis="x", rotation=20)
    _save_plot(fig, plots_dir / "strip_avg_algorithm_variant.png")


def plot_multidimensional(df: pd.DataFrame, plots_dir: Path) -> None:
    multidim_df = df[df["taxiCount"] >= 0].copy()
    if multidim_df.empty:
        return

    unique_k = multidim_df["kHops"].nunique(dropna=True)
    unique_t = multidim_df["taxiCount"].nunique(dropna=True)
    unique_seats = multidim_df["taxiSeatCount"].nunique(dropna=True)
    metrics = sorted(multidim_df["metric"].dropna().unique().tolist())

    if unique_k > 1 and unique_t > 1:
        for metric_name in metrics:
            metric_df = multidim_df[multidim_df["metric"] == metric_name]
            if metric_df.empty:
                continue

            mean_df = (
                metric_df.groupby(["algorithm_variant", "kHops", "taxiCount"], dropna=False)["avg"]
                .mean()
                .reset_index()
            )

            fig, ax = plt.subplots(figsize=(12, 7))
            sns.lineplot(
                data=mean_df,
                x="kHops",
                y="avg",
                hue="taxiCount",
                style="algorithm_variant",
                markers=True,
                dashes=False,
                ax=ax,
            )
            ax.set_title(f"kHops x taxiCount interaction: {metric_name}")
            ax.set_xlabel("kHops")
            ax.set_ylabel("Mean avg")
            _save_plot(fig, plots_dir / f"multidim_khop_curves_{_safe_name(metric_name)}.png")

            fig, ax = plt.subplots(figsize=(12, 7))
            sns.lineplot(
                data=mean_df,
                x="taxiCount",
                y="avg",
                hue="kHops",
                style="algorithm_variant",
                markers=True,
                dashes=False,
                ax=ax,
            )
            ax.set_title(f"taxiCount x kHops interaction: {metric_name}")
            ax.set_xlabel("taxiCount")
            ax.set_ylabel("Mean avg")
            _save_plot(fig, plots_dir / f"multidim_taxi_curves_{_safe_name(metric_name)}.png")

            bubble_df = (
                metric_df.groupby(["algorithm_variant", "kHops", "taxiCount"], dropna=False)["avg"]
                .mean()
                .reset_index()
            )
            fig, ax = plt.subplots(figsize=(12, 7))
            sns.scatterplot(
                data=bubble_df,
                x="kHops",
                y="taxiCount",
                size="avg",
                hue="algorithm_variant",
                sizes=(40, 450),
                alpha=0.8,
                ax=ax,
            )
            ax.set_title(f"Bubble interaction map: {metric_name}")
            ax.set_xlabel("kHops")
            ax.set_ylabel("taxiCount")
            _save_plot(fig, plots_dir / f"multidim_bubble_{_safe_name(metric_name)}.png")

            for variant_name, variant_df in metric_df.groupby("algorithm_variant", dropna=False):
                pivot = (
                    variant_df.pivot_table(
                        index="taxiCount",
                        columns="kHops",
                        values="avg",
                        aggfunc="mean",
                    )
                    .sort_index(axis=0)
                    .sort_index(axis=1)
                )
                if pivot.empty:
                    continue
                fig, ax = plt.subplots(figsize=(9, 6))
                sns.heatmap(pivot, annot=True, fmt=".2f", cmap="mako", ax=ax)
                ax.set_title(f"Heatmap {variant_name} - {metric_name}")
                ax.set_xlabel("kHops")
                ax.set_ylabel("taxiCount")
                _save_plot(
                    fig,
                    plots_dir
                    / f"multidim_heatmap_{_safe_name(metric_name)}_{_safe_name(str(variant_name))}.png",
                )

    if unique_seats > 1:
        for metric_name in metrics:
            metric_df = multidim_df[multidim_df["metric"] == metric_name]
            if metric_df.empty:
                continue
            fig, ax = plt.subplots(figsize=(12, 7))
            sns.boxplot(
                data=metric_df,
                x="taxiSeatCount",
                y="avg",
                hue="algorithm_variant",
                ax=ax,
            )
            ax.set_title(f"Seat count effect: {metric_name}")
            ax.set_xlabel("taxiSeatCount")
            ax.set_ylabel("Avg")
            _save_plot(fig, plots_dir / f"multidim_seat_effect_{_safe_name(metric_name)}.png")


def plot_all_dimensions(df: pd.DataFrame, plots_dir: Path) -> None:
    all_df = df[(df["taxiCount"] >= 0) & (df["taxiSeatCount"] >= 0)].copy()
    if all_df.empty:
        return

    metrics = sorted(all_df["metric"].dropna().unique().tolist())
    for metric_name in metrics:
        metric_df = all_df[all_df["metric"] == metric_name].copy()
        if metric_df.empty:
            continue

        grouped = (
            metric_df.groupby(["algorithm_variant", "kHops", "taxiCount", "taxiSeatCount"], dropna=False)[
                ["avg", "spread"]
            ]
            .mean()
            .reset_index()
        )
        if grouped.empty:
            continue

        # 1) Parallel Coordinates: one line per full configuration (all dimensions encoded).
        pc = grouped.copy()
        for col in ["kHops", "taxiCount", "taxiSeatCount", "avg", "spread"]:
            min_v = float(pc[col].min())
            max_v = float(pc[col].max())
            pc[col] = 0.5 if np.isclose(max_v, min_v) else (pc[col] - min_v) / (max_v - min_v)

        fig, ax = plt.subplots(figsize=(13, 7))
        x_cols = ["kHops", "taxiCount", "taxiSeatCount", "avg", "spread"]
        x = np.arange(len(x_cols))
        variants = sorted(pc["algorithm_variant"].unique().tolist())
        palette = sns.color_palette("tab10", n_colors=max(1, len(variants)))
        color_by_variant = {v: palette[i % len(palette)] for i, v in enumerate(variants)}

        for _, row in pc.iterrows():
            y = [row[c] for c in x_cols]
            ax.plot(x, y, color=color_by_variant[row["algorithm_variant"]], alpha=0.55, linewidth=1.4)

        handles = [
            plt.Line2D([0], [0], color=color_by_variant[v], lw=2, label=v)
            for v in variants
        ]
        ax.legend(handles=handles, title="algorithm_variant", loc="best")
        ax.set_xticks(x)
        ax.set_xticklabels(x_cols)
        ax.set_ylim(-0.05, 1.05)
        ax.set_title(f"All-dim Parallel Coordinates: {metric_name}")
        ax.set_ylabel("normalized value")
        _save_plot(fig, plots_dir / f"all_dims_parallel_{_safe_name(metric_name)}.png")

        # 2) Facet Heatmaps: full view over algorithm_variant x taxiSeatCount with taxiCount/kHops grid.
        variants = sorted(grouped["algorithm_variant"].unique().tolist())
        seats = sorted(grouped["taxiSeatCount"].unique().tolist())
        if variants and seats:
            n_rows = len(variants)
            n_cols = len(seats)
            fig, axes = plt.subplots(
                n_rows,
                n_cols,
                figsize=(4.8 * n_cols, 3.6 * n_rows),
                squeeze=False,
            )
            for r, variant in enumerate(variants):
                for c, seat in enumerate(seats):
                    ax = axes[r][c]
                    sub = grouped[(grouped["algorithm_variant"] == variant) & (grouped["taxiSeatCount"] == seat)]
                    if sub.empty:
                        ax.axis("off")
                        continue
                    pivot = (
                        sub.pivot_table(index="taxiCount", columns="kHops", values="avg", aggfunc="mean")
                        .sort_index(axis=0)
                        .sort_index(axis=1)
                    )
                    sns.heatmap(pivot, annot=True, fmt=".2f", cmap="viridis", ax=ax, cbar=False)
                    ax.set_title(f"{variant} | seats={int(seat)}")
                    ax.set_xlabel("kHops")
                    ax.set_ylabel("taxiCount")
            fig.suptitle(f"All-dim Facet Heatmaps: {metric_name}", y=1.01)
            _save_plot(fig, plots_dir / f"all_dims_facet_heatmap_{_safe_name(metric_name)}.png")

        # 3) All-dim Scatter: kHops/taxiCount plane + seat/style + variant/color + avg/size.
        fig, ax = plt.subplots(figsize=(12, 7))
        sns.scatterplot(
            data=grouped,
            x="kHops",
            y="taxiCount",
            size="avg",
            style="taxiSeatCount",
            hue="algorithm_variant",
            sizes=(50, 450),
            alpha=0.8,
            ax=ax,
        )
        ax.set_title(f"All-dim Scatter (size=avg, style=seats): {metric_name}")
        ax.set_xlabel("kHops")
        ax.set_ylabel("taxiCount")
        _save_plot(fig, plots_dir / f"all_dims_scatter_{_safe_name(metric_name)}.png")


def _html_table_from_df(df: pd.DataFrame, max_rows: int = 20) -> str:
    if df is None or df.empty:
        return "<p><em>Keine Daten</em></p>"
    head = df.head(max_rows).copy()
    cols = head.columns.tolist()
    th = "".join(f"<th>{html.escape(str(c))}</th>" for c in cols)
    rows = []
    for _, row in head.iterrows():
        td = "".join(f"<td>{html.escape(str(row[c]))}</td>" for c in cols)
        rows.append(f"<tr>{td}</tr>")
    return f"<table><thead><tr>{th}</tr></thead><tbody>{''.join(rows)}</tbody></table>"


def build_html_report(paths: dict[str, Path], overview: dict, tests_df: pd.DataFrame, duplicate_df: pd.DataFrame) -> None:
    plots = sorted(p.name for p in paths["plots"].glob("*.png"))
    tables = sorted(p.name for p in paths["tables"].glob("*.csv"))

    plot_sections = "".join(
        f"<h3>{html.escape(name)}</h3><img src='plots/{html.escape(name)}' alt='{html.escape(name)}'/>"
        for name in plots
    )

    html_text = f"""
<!doctype html>
<html lang="de">
<head>
  <meta charset="utf-8" />
  <title>Mass-Run Gesamtbericht</title>
  <style>
    body {{ font-family: Arial, sans-serif; margin: 24px; }}
    h1, h2 {{ margin-top: 28px; }}
    table {{ border-collapse: collapse; width: 100%; margin: 10px 0 20px 0; }}
    th, td {{ border: 1px solid #ddd; padding: 6px 8px; font-size: 13px; }}
    th {{ background: #f4f4f4; }}
    img {{ max-width: 100%; border: 1px solid #ddd; margin-bottom: 18px; }}
    code {{ background: #f5f5f5; padding: 2px 4px; }}
  </style>
</head>
<body>
  <h1>Mass-Run Gesamtbericht</h1>
  <p>
    Vergleich basiert auf <code>algorithm_variant</code>:
    Bei Collector-Algorithmen wird die Strategie Teil der Variante (z. B. <code>TaxiAlgorithmP2PCollector+nearest</code>),
    bei Nicht-Collector wird Strategie auf <code>n/a</code> normalisiert.
  </p>

  <h2>Overview</h2>
  <pre>{html.escape(json.dumps(overview, indent=2, ensure_ascii=False))}</pre>

  <h2>Statistische Tests (Auszug)</h2>
  {_html_table_from_df(tests_df, max_rows=50)}

  <h2>Diagnostik: identische Ergebnisserien</h2>
  {_html_table_from_df(duplicate_df, max_rows=200)}

  <h2>Tabellen-Dateien</h2>
  <ul>
    {''.join(f"<li><code>tables/{html.escape(name)}</code></li>" for name in tables)}
  </ul>

  <h2>Plots</h2>
  {plot_sections}
</body>
</html>
"""
    (paths["base"] / "report.html").write_text(html_text, encoding="utf-8")


def main() -> None:
    cfg = parse_args()
    paths = ensure_dirs(cfg.output_dir)
    df = load_data(cfg.input_csv)

    if cfg.metrics:
        df = df[df["metric"].isin(cfg.metrics)].copy()
        if df.empty:
            raise ValueError("No rows left after applying --metrics filter.")

    overview = {
        "rows": int(len(df)),
        "algorithm_base": sorted(df["algorithm_base"].unique().tolist()),
        "algorithm_variant": sorted(df["algorithm_variant"].unique().tolist()),
        "raw_strategies": sorted(df["p2pStrategy"].unique().tolist()),
        "effective_strategies": sorted(df["strategy_effective"].unique().tolist()),
        "metrics": sorted(df["metric"].unique().tolist()),
        "kHops": sorted(df["kHops"].dropna().astype(int).unique().tolist()),
        "taxiCount": sorted(df["taxiCount"].dropna().astype(int).unique().tolist()),
        "taxiSeatCount": sorted(df["taxiSeatCount"].dropna().astype(int).unique().tolist()),
        "runIndex_range": [int(df["runIndex"].min()), int(df["runIndex"].max())],
    }
    write_overview(df, paths["base"] / "overview.json")
    descriptive_tables(df, paths["tables"])
    tests_df = statistical_tests(df, paths["stats"])
    duplicate_df = duplicate_diagnostics(df, paths["stats"])
    plot_distributions(df, paths["plots"])
    plot_heatmaps(df, paths["plots"])
    plot_trends(df, paths["plots"], sorted(df["metric"].unique()))
    plot_pairwise_strip(df, paths["plots"])
    plot_multidimensional(df, paths["plots"])
    plot_all_dimensions(df, paths["plots"])
    build_html_report(paths, overview, tests_df, duplicate_df)


if __name__ == "__main__":
    main()


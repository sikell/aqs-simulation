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
    import matplotlib

    matplotlib.use("Agg")
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

TIME_NUMERIC_COLS = [
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
    "tick",
    "calculationTimeNanos",
    "calculationTimeMillis",
    "activeClientCount",
    "servedRequestCount",
    "waitingTimeSum",
    "waitingTimeCount",
    "waitingTimeAvg",
    "finishedRequestCount",
]

REQUEST_NUMERIC_COLS = [
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
    "originX",
    "originY",
    "zoneX",
    "zoneY",
    "requestCount",
    "finishedCount",
    "waitingTimeSum",
    "waitingTimeAvg",
    "waitingTimeMax",
    "travelTimeSum",
    "travelTimeAvg",
]

REQUEST_HEATMAP_REQUIRED_COLS = [
    "algorithm",
    "taxiCount",
    "clientCount",
    "spawnScenario",
    "originX",
    "originY",
    "requestCount",
    "waitingTimeAvg",
    "waitingTimeMax",
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
P2P_ALGORITHM = "P2PCollector"
CALCULATION_METRIC = "Calculation Time [millis]"
COMMUNICATION_METRIC = "Custom Time [micros]"
WAITING_METRIC = "Client Waiting Time [min]"
TRAVEL_METRIC = "Client Travel Time [min]"
DISTANCE_METRIC = "Taxi Travel Distance [km]"
EXPECTED_METRICS = frozenset(
    {
        WAITING_METRIC,
        TRAVEL_METRIC,
        DISTANCE_METRIC,
        CALCULATION_METRIC,
        COMMUNICATION_METRIC,
        "Simulation Time [millis]",
    }
)
COMMUNICATION_COL = "communicationTimeMillisMean"
OKABE_ITO_COLORS = (
    "#0072B2",
    "#E69F00",
    "#009E73",
    "#CC79A7",
    "#56B4E9",
    "#D55E00",
    "#F0E442",
    "#000000",
)
CALCULATION_COLOR = OKABE_ITO_COLORS[0]
if plt is not None:
    plt.rcParams["axes.prop_cycle"] = plt.cycler(color=OKABE_ITO_COLORS)
LEGACY_SECOND_METRICS = {"Client Waiting Time [min]", "Client Travel Time [min]"}
LEGACY_METER_METRICS = {"Taxi Travel Distance [km]"}
MAX_TIME_SERIES_PLOTS = 24
MAX_REQUEST_PLOTS = 24
DEFAULT_TICK_BLOCK_SIZE = 1000
AUX_CHUNK_SIZE = 100_000
RESULT_CORE_BLOCKS = (10, 85)

TIME_SERIES_GROUP_COLS = [
    "series",
    "selectedConfig",
    "algorithm",
    "taxiCount",
    "clientCount",
    "taxiSeatCount",
    "spawnScenario",
    "runIndex",
    "worldSeed",
    "tickBlock",
]
REQUEST_GROUP_COLS = [
    "algorithm",
    "taxiCount",
    "clientCount",
    "taxiSeatCount",
    "spawnScenario",
    "kHops",
    "p2pRqsRadius",
    "p2pStrategy",
    "p2pOverlayShortcuts",
    "idleRoamingMode",
    "zoneX",
    "zoneY",
    "originX",
    "originY",
]
SCENARIO_COLS = ["taxiCount", "clientCount", "taxiSeatCount", "spawnScenario"]
CONFIG_COLS = [
    "kHops",
    "p2pRequestRepublishTicks",
    "p2pRqsRadius",
    "p2pStrategy",
    "p2pOverlayMinNeighbors",
    "p2pOverlayMaxNeighbors",
    "p2pOverlayShortcuts",
    "p2pOverlayMaxDistanceFactor",
    "p2pTopologyScanTicks",
    "idleRoamingMode",
]
RESULT_VALUE_COLS = [
    "waitingAvgMean",
    "travelAvgMean",
    "distanceAvgMean",
    "servedRatio",
    "calculationAvgMean",
    "communicationAvgMean",
    "kmPerServedClient",
    "calculationMsPerServedClient",
]
RESULT_FACTOR_CONTRASTS = {
    "p2pRqsRadius": [(1000, 2000), (2000, 3000)],
    "kHops": [(0, 2)],
    "p2pStrategy": [("nearest", "greedy")],
    "p2pOverlayMinNeighbors": [(0, 1)],
    "p2pOverlayShortcuts": [(0, 1)],
    "idleRoamingMode": [
        ("past-avg", "past-avg-total"),
        ("random", "past-avg-total"),
    ],
}
MODERATION_OUTCOMES = {
    "waiting": ("waitingAvgMean", "relative percent"),
    "pickup": ("servedRatio", "percentage points"),
    "distance": ("distanceAvgMean", "relative percent"),
}
DEFAULT_COLUMNS = {
    "p2pStrategy": "n/a",
    "idleRoamingStrategy": "n/a",
    "idleRoamingMode": "n/a",
    "spawnScenario": "BASELINE",
    "idleRoamingEnabled": False,
}
TEXT_COLUMNS = [
    "algorithm",
    "p2pStrategy",
    "idleRoamingStrategy",
    "idleRoamingMode",
    "spawnScenario",
]
RUN_CONFIG_COLS = [col for col in EXACT_CONFIG_COLS if col != "metric"]
RUN_ID_COLS = RUN_CONFIG_COLS + ["runIndex", "worldSeed"]


# Configuration and input


@dataclass
class Config:
    input_csv: Path
    output_dir: Path
    metrics: list[str]
    time_series_csv: Path
    requests_csv: Path
    tick_block_size: int


def parse_args() -> Config:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-csv", default="mass-run-results/mass-run-results.csv", type=Path)
    parser.add_argument("--output-dir", default="mass-run-results/analysis", type=Path)
    parser.add_argument("--metrics", default="", help="Comma-separated metric filter.")
    parser.add_argument("--time-series-csv", default=None, type=Path)
    parser.add_argument("--requests-csv", default=None, type=Path, help="Request heatmap CSV.")
    parser.add_argument("--tick-block-size", default=DEFAULT_TICK_BLOCK_SIZE, type=int)
    args = parser.parse_args()
    time_series_csv = args.time_series_csv or args.input_csv.with_name("mass-run-time-series.csv")
    requests_csv = args.requests_csv or args.input_csv.with_name("mass-run-request-heatmap.csv")
    return Config(
        input_csv=args.input_csv,
        output_dir=args.output_dir,
        metrics=[m.strip() for m in args.metrics.split(",") if m.strip()],
        time_series_csv=time_series_csv,
        requests_csv=requests_csv,
        tick_block_size=max(1, args.tick_block_size),
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


def is_p2p(rows: pd.DataFrame) -> pd.Series:
    return rows["algorithm"].str.contains(P2P_ALGORITHM, case=False, na=False)


def expected_runs(path: Path) -> int | None:
    config_path = path.with_name("mass-run-config.properties")
    if not config_path.exists():
        return None
    for line in config_path.read_text(encoding="utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == "runs":
            return int(value.strip())
    raise ValueError(f"Config file has no runs property: {config_path}")


def validate_runs(df: pd.DataFrame, path: Path) -> None:
    numeric = [col for col in NUMERIC_COLS if col in df.columns]
    invalid = df[numeric].isna().any(axis=1)
    if invalid.any():
        raise ValueError(
            f"CSV contains {int(invalid.sum())} rows with invalid required numeric values; "
            f"first row indices: {df.index[invalid].tolist()[:10]}"
        )

    duplicate = df.duplicated(RUN_ID_COLS + ["metric"], keep=False)
    if duplicate.any():
        raise ValueError(
            f"CSV contains {int(duplicate.sum())} duplicate metric rows per run; "
            f"first row indices: {df.index[duplicate].tolist()[:10]}"
        )

    metric_sets = df.groupby(RUN_ID_COLS, dropna=False)["metric"].agg(frozenset)
    incomplete = ~metric_sets.map(lambda values: values == EXPECTED_METRICS)
    if incomplete.any():
        run = metric_sets.index[incomplete][0]
        found = metric_sets.loc[run]
        raise ValueError(
            "Run has an incomplete metric set: "
            f"run={run}, missing={sorted(EXPECTED_METRICS - found)}, "
            f"unexpected={sorted(found - EXPECTED_METRICS)}"
        )

    runs = df[RUN_ID_COLS].drop_duplicates()
    for identifier in ["runIndex", "worldSeed"]:
        duplicate_identifier = runs.duplicated(RUN_CONFIG_COLS + [identifier], keep=False)
        if duplicate_identifier.any():
            raise ValueError(f"Configuration contains duplicate {identifier} values.")
    run_counts = runs.groupby(RUN_CONFIG_COLS, dropna=False).size()
    if run_counts.nunique() != 1:
        raise ValueError(
            "Configurations contain different run counts: "
            f"min={int(run_counts.min())}, max={int(run_counts.max())}"
        )

    configured_runs = expected_runs(path)
    if configured_runs is not None and not run_counts.eq(configured_runs).all():
        raise ValueError(
            f"Expected {configured_runs} runs per configuration from "
            f"{path.with_name('mass-run-config.properties')}, found "
            f"{int(run_counts.min())} to {int(run_counts.max())}."
        )

    seed_sets = runs.groupby(RUN_CONFIG_COLS, dropna=False)["worldSeed"].agg(frozenset)
    if seed_sets.nunique() != 1:
        raise ValueError("Configurations contain different world-seed sets.")


def load_data(path: Path, metrics: list[str]) -> pd.DataFrame:
    if not path.exists():
        raise FileNotFoundError(f"CSV not found: {path}")

    df = pd.read_csv(path)
    required = [
        "metric", "algorithm", "min", "max", "avg", "sum", "count", "spread",
        "runIndex", "worldSeed", "taxiCount", "clientCount", "taxiSeatCount", "spawnScenario",
    ]
    missing = [col for col in required if col not in df.columns]
    if missing:
        raise ValueError(f"CSV missing required columns: {missing}")
    invalid_text = df[["metric", "algorithm", "spawnScenario"]].isna() | df[
        ["metric", "algorithm", "spawnScenario"]
    ].astype(str).apply(lambda column: column.str.strip().eq(""))
    if invalid_text.any(axis=1).any():
        rows = df.index[invalid_text.any(axis=1)].tolist()[:10]
        raise ValueError(f"CSV contains missing required text values; first row indices: {rows}")

    for col in NUMERIC_COLS:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    # The simulator advances in seconds; the legacy metric names already say "[min]".
    time_rows = df["metric"].isin(LEGACY_SECOND_METRICS)
    df.loc[time_rows, ["min", "max", "avg", "sum", "spread"]] /= 60.0
    distance_rows = df["metric"].isin(LEGACY_METER_METRICS)
    df.loc[distance_rows, ["min", "max", "avg", "sum", "spread"]] /= 1000.0

    for col, default in DEFAULT_COLUMNS.items():
        if col not in df.columns:
            df[col] = default
        df[col] = df[col].fillna(default)

    for col in ["metric", *TEXT_COLUMNS]:
        df[col] = df[col].astype(str).str.strip().replace({"": "n/a", "nan": "n/a"})

    for col in P2P_COLS:
        if col not in df.columns:
            df[col] = "n/a"

    validate_runs(df, path)
    run_config_cols = RUN_ID_COLS
    communication = (
        df[df["metric"].eq(COMMUNICATION_METRIC)]
        .groupby(run_config_cols, dropna=False)["avg"]
        .mean()
        .div(1000.0)
        .rename(COMMUNICATION_COL)
        .reset_index()
    )
    df = df.merge(communication, on=run_config_cols, how="left")
    calculation_rows = df["metric"].eq(CALCULATION_METRIC) & is_p2p(df)
    invalid_communication = calculation_rows & (
        df[COMMUNICATION_COL].isna()
        | df[COMMUNICATION_COL].lt(0)
        | df[COMMUNICATION_COL].gt(df["avg"] + 1e-9)
    )
    if invalid_communication.any():
        columns = RUN_ID_COLS + ["avg", COMMUNICATION_COL]
        sample = df.loc[invalid_communication, columns].head(5).to_dict("records")
        raise ValueError(
            "P2P communication time is missing, negative, or greater than calculation time: "
            f"{sample}"
        )
    df.loc[~calculation_rows, COMMUNICATION_COL] = np.nan
    if metrics:
        df = df[df["metric"].isin(metrics)].copy()
        if df.empty:
            raise ValueError("No rows left after --metrics filter.")

    df["is_p2p"] = is_p2p(df)
    df.loc[~df["is_p2p"], P2P_COLS] = df.loc[~df["is_p2p"], P2P_COLS].where(
        df.loc[~df["is_p2p"], P2P_COLS].notna(), "n/a"
    )
    return df


def normalize_aux(df: pd.DataFrame, numeric_cols: list[str]) -> pd.DataFrame:
    for col in numeric_cols:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")
    for col, default in DEFAULT_COLUMNS.items():
        if col not in df.columns:
            df[col] = default
        df[col] = df[col].fillna(default)
    for col in TEXT_COLUMNS:
        if col in df.columns:
            df[col] = df[col].astype(str).str.strip().replace({"": "n/a", "nan": "n/a"})
    return df


# Result tables


def load_time_series_summary(
        path: Path, tick_block_size: int, selected_configs: pd.DataFrame
) -> pd.DataFrame:
    if not path.exists():
        return pd.DataFrame()
    required = list(
        dict.fromkeys(
            TIME_NUMERIC_COLS
            + ["algorithm", "spawnScenario", "p2pStrategy", "idleRoamingMode"]
        )
    )
    selection_cols = SCENARIO_COLS + CONFIG_COLS
    parts = []
    for chunk in pd.read_csv(path, usecols=required, chunksize=AUX_CHUNK_SIZE, low_memory=False):
        chunk = normalize_aux(chunk, TIME_NUMERIC_COLS)
        chunk[["waitingTimeSum", "waitingTimeAvg"]] /= 60.0
        chunk["tickBlock"] = (chunk["tick"] // tick_block_size).astype("int64")
        central = chunk[~is_p2p(chunk)].copy()
        central["series"] = "Central"
        central["selectedConfig"] = "n/a"
        selected = chunk[is_p2p(chunk)].merge(
            selected_configs[selection_cols + ["selectedConfig"]],
            on=selection_cols,
            how="inner",
        )
        selected["series"] = "Selected P2P"
        chunk = pd.concat([central, selected], ignore_index=True)
        if chunk.empty:
            continue
        parts.append(
            chunk.groupby(TIME_SERIES_GROUP_COLS, dropna=False)
            .agg(
                activeClientsSum=("activeClientCount", "sum"),
                servedRequestsSum=("servedRequestCount", "sum"),
                finishedRequestsSum=("finishedRequestCount", "sum"),
                waitingTimeSum=("waitingTimeSum", "sum"),
                waitingTimeCount=("waitingTimeCount", "sum"),
                calculationTimeMillisSum=("calculationTimeMillis", "sum"),
                rows=("tick", "size"),
            )
            .reset_index()
        )
    if not parts:
        return pd.DataFrame()
    summary = (
        pd.concat(parts, ignore_index=True)
        .groupby(TIME_SERIES_GROUP_COLS, dropna=False)
        .sum(numeric_only=True)
        .reset_index()
    )
    summary["activeClientsMean"] = summary["activeClientsSum"] / summary["rows"]
    summary["servedRequestsMean"] = summary["servedRequestsSum"] / summary["rows"]
    summary["finishedRequestsMean"] = summary["finishedRequestsSum"] / summary["rows"]
    summary["calcTimeMillisMean"] = summary["calculationTimeMillisSum"] / summary["rows"]
    summary["waitingTimeAvg"] = np.where(
        summary["waitingTimeCount"].gt(0),
        summary["waitingTimeSum"] / summary["waitingTimeCount"],
        np.nan,
    )
    return summary.drop(columns=["activeClientsSum", "calculationTimeMillisSum"])


def load_request_summary(path: Path) -> pd.DataFrame:
    if not path.exists():
        return pd.DataFrame()
    required = REQUEST_NUMERIC_COLS + ["algorithm", "spawnScenario", "idleRoamingMode", "p2pStrategy"]
    parts = []
    for chunk in pd.read_csv(path, usecols=required, chunksize=AUX_CHUNK_SIZE, low_memory=False):
        chunk = normalize_aux(chunk, REQUEST_NUMERIC_COLS)
        missing = [col for col in REQUEST_HEATMAP_REQUIRED_COLS if col not in chunk.columns]
        if missing:
            raise ValueError(f"Request heatmap CSV missing required columns: {missing}")
        chunk["waitingTimeSum"] = pd.to_numeric(chunk["waitingTimeSum"], errors="coerce").fillna(
            chunk["waitingTimeAvg"] * chunk["requestCount"]
        )
        chunk[["waitingTimeSum", "waitingTimeAvg", "waitingTimeMax", "travelTimeSum", "travelTimeAvg"]] /= 60.0
        parts.append(
            chunk.groupby(REQUEST_GROUP_COLS, dropna=False)
            .agg(
                requestCount=("requestCount", "sum"),
                finishedCount=("finishedCount", "sum"),
                waitingTimeSum=("waitingTimeSum", "sum"),
                waitingTimeMax=("waitingTimeMax", "max"),
                travelTimeSum=("travelTimeSum", "sum"),
            )
            .reset_index()
        )
    if not parts:
        return pd.DataFrame()
    return (
        pd.concat(parts, ignore_index=True)
        .groupby(REQUEST_GROUP_COLS, dropna=False)
        .agg(
            requestCount=("requestCount", "sum"),
            finishedCount=("finishedCount", "sum"),
            waitingTimeSum=("waitingTimeSum", "sum"),
            waitingTimeMax=("waitingTimeMax", "max"),
            travelTimeSum=("travelTimeSum", "sum"),
        )
        .reset_index()
    )


def write_overview(df: pd.DataFrame, path: Path) -> dict:
    overview = {
        "rows": int(len(df)),
        "iterations": int(df[RUN_ID_COLS].drop_duplicates().shape[0]),
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
            sumMean=("sum", "mean"),
            sumStd=("sum", "std"),
            countMean=("count", "mean"),
            countMin=("count", "min"),
            countMax=("count", "max"),
            spreadMean=("spread", "mean"),
            spreadStd=("spread", "std"),
            communicationTimeMillisMean=(COMMUNICATION_COL, "mean"),
            rawRows=("avg", "size"),
        )
        .reset_index()
    )
    waiting_rows = summary["metric"].eq("Client Waiting Time [min]")
    summary["servedRatioMean"] = np.where(
        waiting_rows & summary["clientCount"].gt(0),
        summary["countMean"] / summary["clientCount"],
        np.nan,
    )
    summary.to_csv(out / "summary.csv", index=False)
    return summary


def best_p2p_configs(rows: pd.DataFrame) -> pd.DataFrame:
    return (
        rows.sort_values(["metric", "taxiCount", "clientCount", "spawnScenario", "avgMean"])
        .groupby(MATCH_COLS, dropna=False)
        .head(1)
        .reset_index(drop=True)
    )


def compare_p2p_to_single(summary: pd.DataFrame, out: Path) -> pd.DataFrame:
    single = summary[~is_p2p(summary)].copy()
    single = single[MATCH_COLS + ["avgMean", "avgStd", "countMean"]].rename(
        columns={
            "avgMean": "singleAvg",
            "avgStd": "singleStd",
            "countMean": "singleCount",
        }
    )

    p2p = summary[is_p2p(summary)].copy()
    cols = MATCH_COLS + P2P_COLS + ["avgMean", "avgStd", "countMean", "servedRatioMean", "runs", COMMUNICATION_COL]
    comp = p2p[cols].merge(single, on=MATCH_COLS, how="left")
    comp["delta"] = comp["avgMean"] - comp["singleAvg"]
    comp["deltaPct"] = np.where(comp["singleAvg"].ne(0), comp["delta"] / comp["singleAvg"] * 100.0, np.nan)
    comp.to_csv(out / "p2p_vs_single.csv", index=False)

    best = best_p2p_configs(comp)
    best.to_csv(out / "best_p2p_vs_single.csv", index=False)
    return comp


def write_single_passenger(summary: pd.DataFrame, out: Path) -> None:
    single = summary[~is_p2p(summary)].copy()
    single[BASE_COLS + ["runs", "avgMean", "avgStd", "avgMin", "avgMax", "servedRatioMean"]].to_csv(
        out / "single_passenger.csv", index=False
    )


def metric_matrix(rows: pd.DataFrame, extra_index: list[str] | None = None) -> pd.DataFrame:
    index = [col for col in EXACT_CONFIG_COLS if col != "metric"] + (extra_index or [])
    metrics = {
        WAITING_METRIC: "waiting",
        TRAVEL_METRIC: "travel",
        DISTANCE_METRIC: "distance",
        CALCULATION_METRIC: "calculation",
        COMMUNICATION_METRIC: "communication",
        "Simulation Time [millis]": "simulation",
    }
    result = rows[index].drop_duplicates()
    for metric, prefix in metrics.items():
        values = [col for col in ["avgMean", "avgStd", "countMean", "sumMean"] if col in rows.columns]
        part = rows[rows["metric"].eq(metric)][index + values].rename(
            columns={col: f"{prefix}{col[0].upper()}{col[1:]}" for col in values}
        )
        result = result.merge(part, on=index, how="left")
    return result


def add_operational_metrics(matrix: pd.DataFrame) -> pd.DataFrame:
    result = matrix.copy()
    result["servedClients"] = result["waitingCountMean"]
    invalid = result["servedClients"].isna() | result["servedClients"].le(0)
    if invalid.any():
        columns = [col for col in RUN_CONFIG_COLS if col in result.columns]
        sample = result.loc[invalid, columns + ["servedClients"]].head(5).to_dict("records")
        raise ValueError(f"Operational metrics require at least one served client: {sample}")
    result["servedRatio"] = result["servedClients"] / result["clientCount"]
    result["totalTaxiDistanceKm"] = result["distanceAvgMean"] * result["taxiCount"]
    result["kmPerServedClient"] = result["totalTaxiDistanceKm"] / result["servedClients"]
    result["calculationMsPerServedClient"] = result["calculationSumMean"] / result["servedClients"]
    result["communicationTotalMs"] = result["communicationSumMean"] / 1000.0
    result["communicationMsPerServedClient"] = result["communicationTotalMs"] / result["servedClients"]
    return result


def summarize_result_values(rows: pd.DataFrame, group_cols: list[str]) -> pd.DataFrame:
    result = rows.groupby(group_cols, dropna=False)[RESULT_VALUE_COLS].mean().reset_index()
    result["pickupPct"] = result["servedRatio"] * 100.0
    result["communicationSharePct"] = (
            result["communicationAvgMean"] / 1000.0 / result["calculationAvgMean"] * 100.0
    )
    return result


def match_central(matrix: pd.DataFrame, extra_match: list[str] | None = None) -> pd.DataFrame:
    match = SCENARIO_COLS + (extra_match or [])
    value_cols = [
        "waitingAvgMean",
        "waitingAvgStd",
        "travelAvgMean",
        "distanceAvgMean",
        "calculationAvgMean",
        "calculationSumMean",
        "servedClients",
        "servedRatio",
        "kmPerServedClient",
        "calculationMsPerServedClient",
    ]
    central = matrix[~is_p2p(matrix)][match + value_cols].rename(
        columns={col: f"central{col[0].upper()}{col[1:]}" for col in value_cols}
    )
    p2p = matrix[is_p2p(matrix)].copy()
    result = p2p.merge(central, on=match, how="left")
    for name in ["waiting", "travel", "distance", "kmPerServedClient", "calculationMsPerServedClient"]:
        value = f"{name}AvgMean" if name in {"waiting", "travel", "distance"} else name
        central_value = f"central{value[0].upper()}{value[1:]}"
        result[f"{name}DeltaPct"] = (result[value] / result[central_value] - 1.0) * 100.0
    result["pickupGapPoints"] = (result["centralServedRatio"] - result["servedRatio"]) * 100.0
    return result


def add_crossover_flags(rows: pd.DataFrame, prefix: str = "") -> pd.DataFrame:
    result = rows.copy()
    current_name = f"{prefix}CurrentPass" if prefix else "currentPass"
    extended_name = f"{prefix}ExtendedPass" if prefix else "extendedPass"
    for threshold in [0, 5, 10, 15]:
        suffix = str(threshold)
        limit = float(threshold)
        current = (
                result["waitingDeltaPct"].le(limit)
                & result["distanceDeltaPct"].le(limit)
                & result["pickupGapPoints"].le(1.0)
        )
        result[f"{current_name}{suffix}"] = current
        result[f"{extended_name}{suffix}"] = current & result["travelDeltaPct"].le(limit)
    return result


def seed_result_matrix(df: pd.DataFrame) -> pd.DataFrame:
    seed_rows = (
        df.groupby(EXACT_CONFIG_COLS + ["worldSeed"], dropna=False)
        .agg(
            avgMean=("avg", "mean"),
            avgStd=("avg", "std"),
            countMean=("count", "mean"),
            sumMean=("sum", "mean"),
        )
        .reset_index()
    )
    return add_operational_metrics(metric_matrix(seed_rows, ["worldSeed"]))


def paired_seed_tables(seed_matrix: pd.DataFrame, out: Path) -> tuple[pd.DataFrame, pd.DataFrame]:
    paired = add_crossover_flags(match_central(seed_matrix, ["worldSeed"]), "seed")

    deltas = []
    group_cols = SCENARIO_COLS + CONFIG_COLS
    for metric in ["waiting", "travel", "distance"]:
        column = f"{metric}DeltaPct"
        part = (
            paired.groupby(group_cols, dropna=False)[column]
            .agg(["count", "mean", "std", "min", "max"])
            .reset_index()
            .rename(
                columns={
                    "count": "seeds",
                    "mean": "deltaPctMean",
                    "std": "deltaPctStd",
                    "min": "deltaPctMin",
                    "max": "deltaPctMax",
                }
            )
        )
        part["metric"] = metric
        deltas.append(part)
    paired_deltas = pd.concat(deltas, ignore_index=True)
    paired_deltas.to_csv(out / "paired_seed_deltas.csv", index=False)

    pass_columns = [
        col for col in paired.columns if col.startswith("seed") and ("Pass" in col)
    ]
    seed_pass = paired.groupby(group_cols, dropna=False)[pass_columns].mean().reset_index()
    seed_pass["seeds"] = paired.groupby(group_cols, dropna=False)["worldSeed"].nunique().to_numpy()
    seed_pass.to_csv(out / "crossover_seed_pass_rates.csv", index=False)
    return paired_deltas, seed_pass


def moderation_seed_contrast(
        rows: pd.DataFrame,
        name: str,
        factor: str,
        factor_values: tuple[object, object],
        moderator: str,
        moderator_values: tuple[object, object],
        strata: list[str],
) -> pd.DataFrame:
    factor_from, factor_to = factor_values
    moderator_from, moderator_to = moderator_values
    grouped = (
        rows[rows[factor].isin(factor_values) & rows[moderator].isin(moderator_values)]
        .groupby(strata + ["worldSeed", factor, moderator], dropna=False)[
            [column for column, _ in MODERATION_OUTCOMES.values()]
        ]
        .mean()
        .reset_index()
    )
    index = strata + ["worldSeed"]
    results = []
    required = [
        (factor_from, moderator_from),
        (factor_to, moderator_from),
        (factor_from, moderator_to),
        (factor_to, moderator_to),
    ]
    for outcome, (column, scale) in MODERATION_OUTCOMES.items():
        cells = grouped.pivot(index=index, columns=[factor, moderator], values=column)
        if not all(cell in cells.columns for cell in required):
            continue
        if scale == "relative percent":
            effect_from = (cells[(factor_to, moderator_from)] / cells[(factor_from, moderator_from)] - 1.0) * 100.0
            effect_to = (cells[(factor_to, moderator_to)] / cells[(factor_from, moderator_to)] - 1.0) * 100.0
        else:
            effect_from = (cells[(factor_to, moderator_from)] - cells[(factor_from, moderator_from)]) * 100.0
            effect_to = (cells[(factor_to, moderator_to)] - cells[(factor_from, moderator_to)]) * 100.0
        result = pd.DataFrame(
            {
                "effectAtModeratorFrom": effect_from,
                "effectAtModeratorTo": effect_to,
                "interaction": effect_to - effect_from,
            }
        ).reset_index()
        result["moderation"] = name
        result["factor"] = factor
        result["factorFrom"] = factor_from
        result["factorTo"] = factor_to
        result["moderator"] = moderator
        result["moderatorFrom"] = moderator_from
        result["moderatorTo"] = moderator_to
        result["outcome"] = outcome
        result["effectScale"] = scale
        results.append(result)
    return pd.concat(results, ignore_index=True)


def summarize_moderation(rows: pd.DataFrame) -> pd.DataFrame:
    values = {"effectAtModeratorFrom", "effectAtModeratorTo", "interaction", "worldSeed"}
    group_cols = [column for column in rows.columns if column not in values]
    summaries = []
    for keys, part in rows.groupby(group_cols, dropna=False):
        interaction = part["interaction"].dropna()
        mean = interaction.mean()
        std = interaction.std()
        margin = stats.t.ppf(0.975, len(interaction) - 1) * std / np.sqrt(len(interaction)) if stats is not None and len(interaction) > 1 else np.nan
        p_value = stats.ttest_1samp(interaction, 0.0).pvalue if stats is not None and len(interaction) > 1 else np.nan
        summaries.append(
            {
                **dict(zip(group_cols, keys)),
                "seeds": len(interaction),
                "effectAtModeratorFromMean": part["effectAtModeratorFrom"].mean(),
                "effectAtModeratorToMean": part["effectAtModeratorTo"].mean(),
                "interactionMean": mean,
                "interactionStd": std,
                "ci95Low": mean - margin,
                "ci95High": mean + margin,
                "sameDirectionSeeds": int((interaction >= 0).sum() if mean >= 0 else (interaction <= 0).sum()),
                "pValue": p_value,
            }
        )
    summary = pd.DataFrame(summaries)
    summary["pValueHolm"] = np.nan
    for indices in summary.groupby("scope", dropna=False).groups.values():
        p_values = summary.loc[indices, "pValue"].dropna().sort_values()
        adjusted = np.maximum.accumulate(p_values.to_numpy() * np.arange(len(p_values), 0, -1)).clip(max=1.0)
        summary.loc[p_values.index, "pValueHolm"] = adjusted
    return summary


def write_moderation_analysis(seed_matrix: pd.DataFrame, out: Path) -> pd.DataFrame:
    p2p = seed_matrix[is_p2p(seed_matrix)].copy()
    city_cols = ["taxiCount", "clientCount", "taxiSeatCount"]
    radius_steps = [(1000, 2000), (2000, 3000)]
    hop_steps = [(0, 1), (1, 2)]
    detailed = [
        *[
            moderation_seed_contrast(
                p2p, "roaming_by_radius", "idleRoamingMode", ("none", "past-avg-total"),
                "p2pRqsRadius", radius_step, SCENARIO_COLS,
            )
            for radius_step in radius_steps
        ],
        *[
            moderation_seed_contrast(
                p2p, "hops_by_radius", "kHops", hop_step,
                "p2pRqsRadius", radius_step, SCENARIO_COLS,
            )
            for hop_step in hop_steps
            for radius_step in radius_steps
        ],
        *[
            moderation_seed_contrast(
                p2p, "shortcuts_by_hops", "p2pOverlayShortcuts", (0, 1),
                "kHops", hop_step, SCENARIO_COLS,
            )
            for hop_step in hop_steps
        ],
    ]
    for scenario in ["SPATIAL_IMBALANCE", "SPATIAL_ISLANDS"]:
        detailed.append(
            moderation_seed_contrast(
                p2p, "roaming_by_scenario", "idleRoamingMode", ("random", "past-avg-total"),
                "spawnScenario", ("BASELINE", scenario), city_cols,
            )
        )
    detailed = pd.concat(detailed, ignore_index=True)
    detailed["scope"] = "city-scenario"

    overall_cols = [
        "moderation", "factor", "factorFrom", "factorTo", "moderator", "moderatorFrom",
        "moderatorTo", "outcome", "effectScale", "worldSeed",
    ]
    overall = (
        detailed.groupby(overall_cols, dropna=False)[
            ["effectAtModeratorFrom", "effectAtModeratorTo", "interaction"]
        ]
        .mean()
        .reset_index()
    )
    overall["scope"] = "equal-weight overall"
    rows = pd.concat([detailed, overall], ignore_index=True)
    summary = summarize_moderation(rows)
    expected_seeds = p2p["worldSeed"].nunique()
    if summary.empty or not summary["seeds"].eq(expected_seeds).all():
        raise ValueError("Moderation contrasts are incomplete across world seeds.")
    rows.to_csv(out / "moderation_seed_contrasts.csv", index=False)
    summary.to_csv(out / "moderation_summary.csv", index=False)
    return summary


def write_parameter_effects(p2p: pd.DataFrame, out: Path) -> None:
    factor_levels = []
    overall_factor_levels = []
    factor_effects = []
    effect_metrics = {
        "waitingAvgMean": "waitingMin",
        "travelAvgMean": "travelMin",
        "distanceAvgMean": "distanceKm",
        "calculationAvgMean": "calculationMs",
        "communicationAvgMean": "communicationMicros",
        "kmPerServedClient": "kmPerServedClient",
        "calculationMsPerServedClient": "calculationMsPerServedClient",
        "communicationSharePct": "communicationSharePct",
    }
    for factor, contrasts in RESULT_FACTOR_CONTRASTS.items():
        levels = summarize_result_values(p2p, SCENARIO_COLS + [factor])
        levels["normalizedWaitingIndex"] = levels["waitingAvgMean"] / levels.groupby(
            SCENARIO_COLS, dropna=False
        )["waitingAvgMean"].transform("mean")
        factor_levels.append(levels.rename(columns={factor: "level"}).assign(factor=factor))
        overall_factor_levels.append(
            summarize_result_values(p2p, [factor])
            .rename(columns={factor: "level"})
            .assign(factor=factor, basis="equal-weight configuration means")
        )
        normalized = levels.groupby(factor, dropna=False)["normalizedWaitingIndex"].mean()
        for before, after in contrasts:
            left = levels[levels[factor].eq(before)].drop(columns=[factor])
            right = levels[levels[factor].eq(after)].drop(columns=[factor])
            effect = left.merge(right, on=SCENARIO_COLS, suffixes=("From", "To"))
            effect.insert(0, "factor", factor)
            effect.insert(1, "fromValue", before)
            effect.insert(2, "toValue", after)
            effect.insert(3, "basis", "scenario means")
            for source, label in effect_metrics.items():
                effect[f"{label}From"] = effect[f"{source}From"]
                effect[f"{label}To"] = effect[f"{source}To"]
                effect[f"{label}DeltaPct"] = (
                                                     effect[f"{source}To"] / effect[f"{source}From"] - 1.0
                                             ) * 100.0
            effect["pickupDeltaPoints"] = effect["pickupPctTo"] - effect["pickupPctFrom"]
            effect["communicationShareDeltaPoints"] = (
                    effect["communicationSharePctTo"] - effect["communicationSharePctFrom"]
            )
            factor_effects.append(effect)
            if before in normalized.index and after in normalized.index:
                factor_effects.append(
                    pd.DataFrame(
                        [
                            {
                                "factor": factor,
                                "fromValue": before,
                                "toValue": after,
                                "basis": "equal-weight normalized index",
                                "spawnScenario": "ALL_NORMALIZED",
                                "waitingMinFrom": normalized.loc[before],
                                "waitingMinTo": normalized.loc[after],
                                "waitingMinDeltaPct": (
                                                              normalized.loc[after] / normalized.loc[before] - 1.0
                                                      )
                                                      * 100.0,
                            }
                        ]
                    )
                )
    pd.concat(factor_levels, ignore_index=True).to_csv(
        out / "parameter_level_summary.csv", index=False
    )
    pd.concat(overall_factor_levels, ignore_index=True).to_csv(
        out / "parameter_level_overall_summary.csv", index=False
    )
    pd.concat(factor_effects, ignore_index=True).to_csv(
        out / "parameter_effect_summary.csv", index=False
    )


def write_extended_analysis(
        df: pd.DataFrame, summary: pd.DataFrame, out: Path
) -> dict[str, pd.DataFrame]:
    seed_matrix = seed_result_matrix(df)
    matrix = add_operational_metrics(metric_matrix(summary))
    ratio_cols = [
        "kmPerServedClient",
        "calculationMsPerServedClient",
        "communicationMsPerServedClient",
    ]
    seed_ratios = (
        seed_matrix.groupby(RUN_CONFIG_COLS, dropna=False)[ratio_cols]
        .mean()
        .reset_index()
    )
    matrix = matrix.drop(columns=ratio_cols).merge(seed_ratios, on=RUN_CONFIG_COLS, how="left")
    matched = add_crossover_flags(match_central(matrix))
    matched["pickupPct"] = matched["servedRatio"] * 100.0

    metric_cols = [
        "waitingAvgMean",
        "travelAvgMean",
        "distanceAvgMean",
        "servedRatio",
        "kmPerServedClient",
        "calculationAvgMean",
        "calculationSumMean",
        "calculationMsPerServedClient",
        "communicationTotalMs",
        "communicationMsPerServedClient",
    ]
    p2p = matrix[is_p2p(matrix)].copy()
    central = matrix[~is_p2p(matrix)].copy()

    system = matrix.copy()
    system["architecture"] = np.where(
        is_p2p(system),
        "P2P mean",
        "Central",
    )
    system = summarize_result_values(system, SCENARIO_COLS + ["architecture"])
    system.to_csv(out / "system_comparison_summary.csv", index=False)

    write_parameter_effects(p2p, out)

    interaction = summarize_result_values(
        p2p, SCENARIO_COLS + ["kHops", "p2pRqsRadius", "idleRoamingMode"]
    )
    interaction.to_csv(out / "interaction_k_radius_roaming.csv", index=False)
    topology_interaction = summarize_result_values(
        p2p, SCENARIO_COLS + ["kHops", "p2pOverlayShortcuts"]
    )
    topology_interaction.to_csv(out / "interaction_k_shortcuts.csv", index=False)

    roaming = (
        p2p.groupby(SCENARIO_COLS + ["idleRoamingMode"], dropna=False)[metric_cols]
        .mean()
        .reset_index()
    )
    roaming["variant"] = "P2P " + roaming["idleRoamingMode"].astype(str)
    central_roaming = central[SCENARIO_COLS + metric_cols].copy()
    central_roaming["idleRoamingMode"] = "central"
    central_roaming["variant"] = "Central"
    roaming = pd.concat([central_roaming, roaming], ignore_index=True)
    roaming.to_csv(out / "architecture_roaming_control.csv", index=False)

    central_wait = central[SCENARIO_COLS + ["waitingAvgMean"]].rename(
        columns={"waitingAvgMean": "centralWaitingAvgMean"}
    )
    interaction_radius_roaming = (
        p2p.groupby(SCENARIO_COLS + ["p2pRqsRadius", "idleRoamingMode"], dropna=False)
        .agg(waitingAvgMean=("waitingAvgMean", "mean"), servedRatio=("servedRatio", "mean"))
        .reset_index()
        .merge(central_wait, on=SCENARIO_COLS, how="left")
    )
    interaction_radius_roaming["waitingDeltaPct"] = (
                                                            interaction_radius_roaming["waitingAvgMean"]
                                                            / interaction_radius_roaming["centralWaitingAvgMean"]
                                                            - 1.0
                                                    ) * 100.0
    interaction_radius_roaming.to_csv(out / "interaction_radius_roaming.csv", index=False)

    interaction_radius_k = (
        p2p.groupby(SCENARIO_COLS + ["p2pRqsRadius", "kHops"], dropna=False)
        .agg(waitingAvgMean=("waitingAvgMean", "mean"), servedRatio=("servedRatio", "mean"))
        .reset_index()
        .merge(central_wait, on=SCENARIO_COLS, how="left")
    )
    interaction_radius_k["waitingDeltaPct"] = (
                                                      interaction_radius_k["waitingAvgMean"] / interaction_radius_k[
                                                  "centralWaitingAvgMean"]
                                                      - 1.0
                                              ) * 100.0
    interaction_radius_k.to_csv(out / "interaction_radius_k.csv", index=False)

    matched.to_csv(out / "same_config_metrics.csv", index=False)
    crossover_counts = []
    for threshold in [0, 5, 10, 15]:
        grouped = matched.groupby(SCENARIO_COLS, dropna=False).agg(
            configurations=(f"currentPass{threshold}", "size"),
            currentPass=(f"currentPass{threshold}", "sum"),
            withTravelTime=(f"extendedPass{threshold}", "sum"),
        )
        grouped["thresholdPct"] = threshold
        crossover_counts.append(grouped.reset_index())
    crossover_counts = pd.concat(crossover_counts, ignore_index=True)
    crossover_counts.to_csv(out / "crossover_travel_validation.csv", index=False)

    matched["waitingRegret"] = matched["waitingDeltaPct"] / 5.0
    matched["travelRegret"] = matched["travelDeltaPct"] / 5.0
    matched["distanceRegret"] = matched["distanceDeltaPct"] / 5.0
    matched["pickupRegret"] = matched["pickupGapPoints"] / 1.0
    matched["referenceWorstNormalizedRegret"] = matched[
        ["waitingRegret", "travelRegret", "distanceRegret", "pickupRegret"]
    ].max(axis=1)
    robust = (
        matched.groupby(CONFIG_COLS, dropna=False)
        .agg(
            groups=("spawnScenario", "size"),
            groupsWithin5=("currentPass5", "sum"),
            groupsWithin5WithTravel=("extendedPass5", "sum"),
            meanWaitingDeltaPct=("waitingDeltaPct", "mean"),
            worstWaitingDeltaPct=("waitingDeltaPct", "max"),
            meanDistanceDeltaPct=("distanceDeltaPct", "mean"),
            worstDistanceDeltaPct=("distanceDeltaPct", "max"),
            meanTravelDeltaPct=("travelDeltaPct", "mean"),
            worstTravelDeltaPct=("travelDeltaPct", "max"),
            worstPickupGapPoints=("pickupGapPoints", "max"),
            worstReferenceNormalizedRegret=("referenceWorstNormalizedRegret", "max"),
        )
        .reset_index()
        .sort_values(
            ["worstReferenceNormalizedRegret", "groupsWithin5WithTravel", "groupsWithin5"],
            ascending=[True, False, False],
        )
        .reset_index(drop=True)
    )
    robust.insert(0, "rank", np.arange(1, len(robust) + 1))
    robust.to_csv(out / "robust_config_ranking.csv", index=False)
    top = robust.iloc[0]
    robust_details = matched.copy()
    for col in CONFIG_COLS:
        robust_details = robust_details[robust_details[col].eq(top[col])]
    robust_details.to_csv(out / "robust_config_scenario_details.csv", index=False)

    best_waiting = (
        matched.sort_values(SCENARIO_COLS + ["waitingAvgMean"])
        .groupby(SCENARIO_COLS, dropna=False)
        .head(1)
        .reset_index(drop=True)
    )
    best_waiting.to_csv(out / "best_waiting_operational_cost.csv", index=False)

    paired_deltas, seed_pass = paired_seed_tables(seed_matrix, out)
    best_waiting_seed_uncertainty = best_waiting[SCENARIO_COLS + CONFIG_COLS].merge(
        paired_deltas[paired_deltas["metric"].eq("waiting")],
        on=SCENARIO_COLS + CONFIG_COLS,
        how="left",
    )
    best_waiting_seed_uncertainty.to_csv(
        out / "best_waiting_seed_uncertainty.csv", index=False
    )
    moderation = write_moderation_analysis(seed_matrix, out)
    seed_counts = []
    for threshold in [0, 5, 10, 15]:
        grouped = seed_pass.groupby(SCENARIO_COLS, dropna=False).agg(
            configurations=(f"seedCurrentPass{threshold}", "size"),
            allSeeds=(f"seedCurrentPass{threshold}", lambda s: int((s >= 1.0).sum())),
            withTravelAllSeeds=(
                f"seedExtendedPass{threshold}",
                lambda s: int((s >= 1.0).sum()),
            ),
        )
        grouped["thresholdPct"] = threshold
        seed_counts.append(grouped.reset_index())
    seed_counts = pd.concat(seed_counts, ignore_index=True)
    seed_counts.to_csv(out / "crossover_seed_robustness.csv", index=False)
    crossover_summary = crossover_counts.merge(
        seed_counts,
        on=SCENARIO_COLS + ["thresholdPct", "configurations"],
        how="left",
    )
    crossover_summary.to_csv(out / "crossover_summary.csv", index=False)

    return {
        "roaming": roaming,
        "radiusRoaming": interaction_radius_roaming,
        "radiusK": interaction_radius_k,
        "matched": matched,
        "crossover": crossover_counts,
        "robust": robust,
        "robustDetails": robust_details,
        "bestWaiting": best_waiting,
        "pairedDeltas": paired_deltas,
        "seedCounts": seed_counts,
        "moderation": moderation,
    }


def scenario_minimax_configs(matched: pd.DataFrame) -> pd.DataFrame:
    selected = (
        matched.sort_values(
            SCENARIO_COLS
            + ["referenceWorstNormalizedRegret", "waitingAvgMean", "distanceAvgMean"]
        )
        .groupby(SCENARIO_COLS, dropna=False)
        .head(1)
        .reset_index(drop=True)
    )
    selected["selectedConfig"] = selected.apply(
        lambda row: f"{config_label(row)}, minN={int(row.p2pOverlayMinNeighbors)}", axis=1
    )
    assert not selected.duplicated(SCENARIO_COLS).any()
    return selected


def write_time_window_summary(time_df: pd.DataFrame, out: Path, tick_block_size: int) -> None:
    if time_df.empty:
        return
    time_df.to_csv(out / "time_window_summary.csv", index=False)
    (
        time_df.loc[
            time_df["series"].eq("Selected P2P"), SCENARIO_COLS + ["selectedConfig"]
        ]
        .drop_duplicates()
        .to_csv(out / "time_window_selected_configs.csv", index=False)
    )
    windows = [("all", time_df)]
    start, end = RESULT_CORE_BLOCKS
    windows.append((f"blocks_{start}_{end}", time_df[time_df["tickBlock"].between(start, end)]))
    summaries = []
    for window, rows in windows:
        if rows.empty:
            continue
        grouped = (
            rows.groupby(
                ["series", "selectedConfig", "algorithm", *SCENARIO_COLS], dropna=False
            )
            .agg(
                firstBlock=("tickBlock", "min"),
                lastBlock=("tickBlock", "max"),
                blocks=("tickBlock", "nunique"),
                activeClientsMean=("activeClientsMean", "mean"),
                servedRequestsMean=("servedRequestsMean", "mean"),
                finishedRequestsMean=("finishedRequestsMean", "mean"),
                meanBlockWaitingTime=("waitingTimeAvg", "mean"),
                waitingTimeMax=("waitingTimeAvg", "max"),
                waitingTimeSum=("waitingTimeSum", "sum"),
                waitingTimeCount=("waitingTimeCount", "sum"),
                calculationTimeMillisMean=("calcTimeMillisMean", "mean"),
            )
            .reset_index()
        )
        grouped["pooledClientWaitingTime"] = np.where(
            grouped["waitingTimeCount"].gt(0),
            grouped["waitingTimeSum"] / grouped["waitingTimeCount"],
            np.nan,
        )
        grouped.insert(0, "window", window)
        grouped.insert(1, "tickBlockSize", tick_block_size)
        summaries.append(grouped)
    pd.concat(summaries, ignore_index=True).to_csv(
        out / "time_window_result_summary.csv", index=False
    )


def write_request_tail_summary(request_df: pd.DataFrame, out: Path) -> None:
    if request_df.empty:
        return
    group_cols = [
        "algorithm",
        "taxiCount",
        "clientCount",
        "taxiSeatCount",
        "spawnScenario",
        "kHops",
        "p2pRqsRadius",
        "p2pStrategy",
        "p2pOverlayShortcuts",
        "idleRoamingMode",
    ]
    grouped = request_df.groupby(group_cols, dropna=False).agg(
        requests=("requestCount", "sum"),
        finished=("finishedCount", "sum"),
        waitingSum=("waitingTimeSum", "sum"),
        waitMax=("waitingTimeMax", "max"),
        travelSum=("travelTimeSum", "sum"),
    )
    grouped["completionRatio"] = grouped["finished"] / grouped["requests"].where(grouped["requests"] != 0)
    grouped["waitMean"] = grouped["waitingSum"] / grouped["requests"].where(grouped["requests"] != 0)
    grouped["travelMean"] = grouped["travelSum"] / grouped["finished"].where(grouped["finished"] != 0)
    grouped.drop(columns=["waitingSum", "travelSum"]).reset_index().to_csv(
        out / "request_tail_summary.csv", index=False
    )


def write_spatial_result_summary(
        request_df: pd.DataFrame, summary: pd.DataFrame, out: Path
) -> None:
    if request_df.empty:
        return
    panel_cols = [
        "algorithm",
        "taxiCount",
        "clientCount",
        "taxiSeatCount",
        "spawnScenario",
        "idleRoamingMode",
    ]
    zones = (
        request_df.groupby(panel_cols + ["zoneX", "zoneY"], dropna=False)
        .agg(requests=("requestCount", "sum"), waitingSum=("waitingTimeSum", "sum"))
        .reset_index()
    )
    zones["zoneWaitingMean"] = zones["waitingSum"] / zones["requests"].where(
        zones["requests"].ne(0)
    )
    spatial = (
        zones.groupby(panel_cols, dropna=False)
        .agg(
            spatialRequests=("requests", "sum"),
            waitingSum=("waitingSum", "sum"),
            zoneWaitingMin=("zoneWaitingMean", "min"),
            zoneWaitingMax=("zoneWaitingMean", "max"),
        )
        .reset_index()
    )
    spatial["waitingMean"] = spatial["waitingSum"] / spatial["spatialRequests"].where(
        spatial["spatialRequests"].ne(0)
    )
    pickup = (
        summary[summary["metric"].eq(WAITING_METRIC)]
        .groupby(panel_cols, dropna=False)["countMean"]
        .mean()
        .reset_index(name="pickedUpPerRun")
    )
    spatial = spatial.merge(pickup, on=panel_cols, how="left")
    spatial["notPickedUpPerRun"] = (spatial["clientCount"] - spatial["pickedUpPerRun"]).clip(
        lower=0
    )
    spatial["notPickedUpPct"] = (
            spatial["notPickedUpPerRun"] / spatial["clientCount"] * 100.0
    )
    spatial.drop(columns="waitingSum").to_csv(out / "spatial_result_summary.csv", index=False)


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
        .agg(
            pairs=("absDiff", "size"),
            changed=("absDiff", lambda s: int((s > 1e-9).sum())),
            maxAbsDiff=("absDiff", "max"),
        )
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

    factors = ["kHops", "p2pRqsRadius", "p2pStrategy", "p2pOverlayShortcuts", "idleRoamingMode"]
    records = []
    p2p = df[df["is_p2p"]].copy()
    for keys, sub in p2p.groupby(["metric", "taxiCount", "clientCount", "spawnScenario"], dropna=False):
        for factor in factors:
            levels = (
                sub.groupby(factor, dropna=False)["avg"]
                .agg(["count", "mean", "std", "min", "max"])
                .reset_index()
            )
            if len(levels) < 2:
                continue
            for level in levels.itertuples(index=False):
                records.append(
                    {
                        "metric": keys[0],
                        "taxiCount": keys[1],
                        "clientCount": keys[2],
                        "spawnScenario": keys[3],
                        "factor": factor,
                        "level": getattr(level, factor),
                        "count": level.count,
                        "mean": level.mean,
                        "std": level.std,
                        "min": level.min,
                        "max": level.max,
                        "note": "descriptive marginal screen; crossed factors and repeated seeds not modeled",
                    }
                )
    pd.DataFrame(records).to_csv(out / "factor_screen.csv", index=False)


# Plot helpers


def safe_name(text: object) -> str:
    return "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in str(text)).strip("_")


def analysis_metrics(df: pd.DataFrame) -> list[str]:
    return sorted(df["metric"].dropna().unique().tolist())


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


def add_calculation_bars(
        ax,
        x: object,
        total: object,
        communication: object,
        width: float,
        yerr: object,
        edgecolor: object = "#374151",
        labels: tuple[str | None, str | None] = (None, None),
) -> None:
    remainder = np.asarray(total) - np.asarray(communication)
    style = {"edgecolor": edgecolor, "linewidth": 0.8}
    ax.bar(x, remainder, width, color=CALCULATION_COLOR, label=labels[0], **style)
    ax.bar(
        x,
        communication,
        width,
        bottom=remainder,
        yerr=yerr,
        capsize=3,
        color=CALCULATION_COLOR,
        hatch="///",
        label=labels[1],
        **style,
    )


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
    p2p = summary[is_p2p(summary)].copy()
    files = []
    for metric in analysis_metrics(p2p):
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
                name = (
                    f"h1_k_trend_{safe_name(metric)}_"
                    f"{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
                )
                fig.savefig(out / name, dpi=140)
                plt.close(fig)
                files.append(
                    plot_entry(
                        name,
                        "Hypothesis plots",
                        "Overview: averages all P2P configs with the same metric, scale, "
                        "scenario, k, and RQS radius. Strategy, shortcuts, and roaming vary.",
                    )
                )
            if agg["p2pRqsRadius"].nunique() > 1:
                fig, ax = plt.subplots(figsize=(7.5, 4.5))
                add_line(ax, agg, "p2pRqsRadius", "avgMean", "kHops")
                ax.set_title(f"H1 RQS trend | {metric} | {city_label(taxi_count, client_count)} | {scenario}")
                ax.set_xlabel("RQS radius [m]")
                ax.set_ylabel(metric)
                set_sensible_y_span(ax, agg["avgMean"])
                ax.legend(title="k-Hops", fontsize=8)
                fig.tight_layout()
                name = (
                    f"h1_rqs_trend_{safe_name(metric)}_"
                    f"{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
                )
                fig.savefig(out / name, dpi=140)
                plt.close(fig)
                files.append(
                    plot_entry(
                        name,
                        "Hypothesis plots",
                        "Overview: averages all P2P configs with the same metric, scale, "
                        "scenario, k, and RQS radius. Strategy, shortcuts, and roaming vary.",
                    )
                )
    return files


def plot_scale_trends(comp: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None or comp.empty:
        return []
    best = best_p2p_configs(comp)
    files = []
    for metric in analysis_metrics(best):
        metric_df = best[best["metric"].eq(metric)]
        for scenario, sub in metric_df.groupby("spawnScenario", dropna=False):
            sub = sub.copy()
            sub["clientCount"] = pd.to_numeric(sub["clientCount"], errors="coerce")
            sub = sub[sub["clientCount"] > 0]
            if sub["clientCount"].nunique() < 2:
                continue
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
    p2p = summary[is_p2p(summary)].copy()
    files = []
    for metric in analysis_metrics(p2p):
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
            files.append(
                plot_entry(
                    name,
                    "Hypothesis plots",
                    "Overview: averages all P2P configs with the same metric, scale, "
                    "scenario, and roaming mode. k, radius, strategy, and shortcuts vary. "
                    "Empty roaming modes are skipped from the legend.",
                )
            )
    return files


def plot_topology_trends(summary: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None:
        return []
    p2p = summary[is_p2p(summary)].copy()
    files = []
    for metric in analysis_metrics(p2p):
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
            name = (
                f"h4_shortcut_trend_{safe_name(metric)}_"
                f"{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
            )
            fig.savefig(out / name, dpi=140)
            plt.close(fig)
            files.append(
                plot_entry(
                    name,
                    "Hypothesis plots",
                    "Overview: averages all P2P configs with the same metric, scale, "
                    "scenario, k, and shortcut count. Radius, strategy, and roaming vary.",
                )
            )
    return files


def plot_fixed_parameter_trends(
        summary: pd.DataFrame,
        comp: pd.DataFrame,
        out: Path,
        varied_cols: list[str],
        x_col: str,
        label_col: str,
        title_prefix: str,
        filename_prefix: str,
) -> list[dict[str, str]]:
    if plt is None:
        return []
    p2p = summary[is_p2p(summary)].copy()
    fixed_cols = [col for col in EXACT_CONFIG_COLS if col not in varied_cols]
    best = best_p2p_configs(comp)
    reference_cols = [col for col in fixed_cols if col in best.columns]
    p2p = p2p.merge(best[reference_cols].drop_duplicates(), on=reference_cols, how="inner")
    files = []
    for keys, sub in p2p.groupby(fixed_cols, dropna=False):
        fixed = dict(zip(fixed_cols, keys))
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
        note = (
                "Varied plotted parameters around the matched best P2P config; averages only "
                "repeated runs. "
                + compact_fixed_config(fixed)
        )
        files.append(plot_entry(name, "Interesting trends", note))
    return files


def plot_best_delta_trends(comp: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None or comp.empty:
        return []
    best = best_p2p_configs(comp)
    files = []
    for metric in analysis_metrics(best):
        metric_df = best[best["metric"].eq(metric)]
        for scenario, sub in metric_df.groupby("spawnScenario", dropna=False):
            sub = sub.copy()
            sub["clientCount"] = pd.to_numeric(sub["clientCount"], errors="coerce")
            sub["deltaPct"] = pd.to_numeric(sub["deltaPct"], errors="coerce")
            sub = sub[(sub["clientCount"] > 0) & np.isfinite(sub["deltaPct"])]
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


def plot_efficiency_tradeoff(summary: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None:
        return []
    matrix = add_operational_metrics(metric_matrix(summary))
    p2p = matrix[is_p2p(matrix)].dropna(
        subset=["waitingAvgMean", "distanceAvgMean", "servedRatio"]
    )
    single = matrix[~is_p2p(matrix)].dropna(
        subset=["waitingAvgMean", "distanceAvgMean", "servedRatio"]
    )
    files = []
    for (taxi_count, client_count, scenario), sub in p2p.groupby(
            ["taxiCount", "clientCount", "spawnScenario"], dropna=False
    ):
        if sub.empty:
            continue
        fig, axes = plt.subplots(1, 2, figsize=(13, 5.3), sharex=True, sharey=True)
        ax, pickup_ax = axes
        mode_labels = {
            "none": "None",
            "past-avg": "Past avg",
            "past-avg-revisit": "Revisit",
            "past-avg-total": "Past avg total",
            "random": "Random",
        }
        for mode, mode_df in sub.groupby("idleRoamingMode", dropna=False):
            ax.scatter(
                mode_df["distanceAvgMean"],
                mode_df["waitingAvgMean"],
                alpha=0.3,
                s=18,
                label=mode_labels.get(str(mode), str(mode)),
            )
        frontier = (
            sub.sort_values(["distanceAvgMean", "waitingAvgMean"])
            .drop_duplicates("distanceAvgMean", keep="first")
        )
        previous_best = frontier["waitingAvgMean"].cummin().shift(fill_value=np.inf)
        frontier = frontier[frontier["waitingAvgMean"].lt(previous_best)]
        ax.plot(
            frontier["distanceAvgMean"],
            frontier["waitingAvgMean"],
            color="black",
            linewidth=1.25,
            alpha=0.65,
            label="Pareto frontier",
        )
        pickup_points = pickup_ax.scatter(
            sub["distanceAvgMean"],
            sub["waitingAvgMean"],
            c=sub["servedRatio"] * 100.0,
            cmap="viridis",
            norm=matplotlib.colors.Normalize(vmin=0, vmax=100),
            alpha=0.65,
            s=22,
        )
        pickup_ax.plot(
            frontier["distanceAvgMean"],
            frontier["waitingAvgMean"],
            color="black",
            linewidth=1.25,
            alpha=0.65,
        )
        reference = single[
            (single["taxiCount"] == taxi_count)
            & (single["clientCount"] == client_count)
            & single["spawnScenario"].eq(scenario)
            ]
        if not reference.empty:
            reference_distance = reference["distanceAvgMean"].mean()
            reference_waiting = reference["waitingAvgMean"].mean()
            for target in axes:
                target.scatter(
                    reference_distance,
                    reference_waiting,
                    marker="x",
                    s=80,
                    color="red",
                    label="Central reference" if target is ax else None,
                )
        ax.set_title("Roaming modes")
        pickup_ax.set_title("Pickup rate")
        for target in axes:
            target.set_xlabel(DISTANCE_METRIC)
            target.grid(alpha=0.2)
        ax.set_ylabel(WAITING_METRIC)
        ax.legend(fontsize=7, ncol=4, loc="upper center", bbox_to_anchor=(0.5, -0.14))
        fig.colorbar(pickup_points, ax=pickup_ax, label="Pickup rate [%]")
        fig.suptitle(
            f"Service–movement trade-off | {city_label(taxi_count, client_count)} | {scenario}"
        )
        fig.tight_layout()
        name = f"efficiency_tradeoff_{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
        fig.savefig(out / name, dpi=140)
        plt.close(fig)
        files.append(
            plot_entry(
                name,
                "Interesting trends",
                "Each point is an exact P2P configuration; the second panel adds pickup rate "
                "to the same waiting-time–distance projection.",
            )
        )
    return files


def plot_k_hop_cost(summary: pd.DataFrame, out: Path) -> dict[str, str] | None:
    if plt is None:
        return None
    matrix = metric_matrix(summary)
    p2p = matrix[is_p2p(matrix)].dropna(
        subset=["kHops", "calculationAvgMean", "communicationAvgMean"]
    )
    if p2p.empty:
        return None
    data = (
        p2p.groupby("kHops", dropna=False)
        .agg(
            calculationMs=("calculationAvgMean", "mean"),
            communicationMicros=("communicationAvgMean", "mean"),
        )
        .reset_index()
        .sort_values("kHops")
    )
    data["communicationMs"] = data["communicationMicros"] / 1000.0
    data["remainingMs"] = (data["calculationMs"] - data["communicationMs"]).clip(lower=0)
    x = np.arange(len(data))
    fig, ax = plt.subplots(figsize=(7.5, 4.8))
    ax.bar(x, data["remainingMs"], color=CALCULATION_COLOR, label="Other calculation")
    ax.bar(
        x,
        data["communicationMs"],
        bottom=data["remainingMs"],
        color=CALCULATION_COLOR,
        hatch="///",
        edgecolor="#374151",
        label="Communication path",
    )
    ax.set_xticks(x, [str(int(value)) for value in data["kHops"]])
    ax.set_xlabel("Maximum hop depth")
    ax.set_ylabel("Mean calculation time per tick [ms]")
    ax.set_title("Calculation and communication cost by hop depth")
    ax.grid(axis="y", alpha=0.2)
    ax.legend()
    fig.tight_layout()
    name = "k_hop_calculation_communication.png"
    fig.savefig(out / name, dpi=150)
    plt.close(fig)
    return plot_entry(
        name,
        "Interesting trends",
        "Means across city-scenario groups and remaining P2P parameters; hatching marks the "
        "in-process communication path.",
    )


def plot_interesting_trends(summary: pd.DataFrame, comp: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    records = (
            plot_best_delta_trends(comp, out)
            + plot_efficiency_tradeoff(summary, out)
            + plot_fixed_parameter_trends(
        summary,
        comp,
        out,
        ["kHops", "p2pRqsRadius"],
        "kHops",
        "p2pRqsRadius",
        "Fixed-config k/RQS trend",
        "fixed_k_rqs_trend",
    )
            + plot_fixed_parameter_trends(
        summary,
        comp,
        out,
        ["kHops", "p2pOverlayShortcuts"],
        "kHops",
        "p2pOverlayShortcuts",
        "Fixed-config shortcut trend",
        "fixed_shortcut_trend",
    )
    )
    k_hop_cost = plot_k_hop_cost(summary, out)
    return records + ([k_hop_cost] if k_hop_cost is not None else [])


def plot_time_windows(time_df: pd.DataFrame, out: Path, tick_block_size: int) -> list[dict[str, str]]:
    if plt is None or time_df.empty:
        return []
    files = []
    metrics = [
        ("activeClientsMean", "Active clients"),
        ("servedRequestsMean", "Mean served / block"),
        ("finishedRequestsMean", "Mean finished / block"),
        ("waitingTimeAvg", "Avg wait [min] / block"),
        ("calcTimeMillisMean", "Calc. time [ms]"),
    ]
    styles = {
        "Central": ("#0072B2", "-"),
        "Selected P2P": ("#D55E00", "--"),
    }
    for (taxi_count, client_count, _seat_count, scenario), block in time_df.groupby(
            SCENARIO_COLS, dropna=False
    ):
        selected = block.loc[block["series"].eq("Selected P2P"), "selectedConfig"]
        if selected.empty:
            continue
        selected_label = selected.iloc[0]
        fig, axes = plt.subplots(5, 1, figsize=(10, 11), sharex=True)
        for ax, (metric, ylabel) in zip(axes, metrics):
            values = (
                block.groupby(["series", "tickBlock"], dropna=False)[metric]
                .agg(["mean", "std"])
                .reset_index()
            )
            for series, group in values.groupby("series", dropna=False):
                group = group.sort_values("tickBlock")
                x = group["tickBlock"].to_numpy(dtype=float)
                mean = group["mean"].to_numpy(dtype=float)
                std = group["std"].fillna(0).to_numpy(dtype=float)
                color, linestyle = styles[str(series)]
                ax.plot(
                    x,
                    mean,
                    color=color,
                    linestyle=linestyle,
                    linewidth=2,
                    label=str(series),
                    zorder=2,
                )
                ax.fill_between(
                    x,
                    np.maximum(mean - std, 0),
                    mean + std,
                    color=color,
                    alpha=0.06,
                    linewidth=0,
                    zorder=1,
                )
            ax.set_ylabel(ylabel)
        axes[4].set_xlabel(f"Tick block ({tick_block_size} ticks)")
        axes[0].set_title(
            f"Load windows | {city_label(taxi_count, client_count)} | {scenario}\n"
            f"Scenario Minimax P2P\n{selected_label}",
            fontsize=10,
        )
        for ax in axes:
            ax.grid(alpha=0.2)
        axes[0].legend(fontsize=8, ncol=2)
        fig.tight_layout()
        name = f"time_windows_{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
        fig.savefig(out / name, dpi=140)
        plt.close(fig)
        files.append(
            plot_entry(
                name,
                "Time windows",
                "Central reference and the scenario-specific P2P configuration with the lowest "
                "mean multi-objective Minimax regret; lines show seed means and bands ±1 SD.",
            )
        )
        if len(files) >= MAX_TIME_SERIES_PLOTS:
            break
    return files


def plot_spatial_maps(
        request_df: pd.DataFrame, summary: pd.DataFrame, out: Path
) -> list[dict[str, str]]:
    if plt is None or request_df.empty:
        return []
    files = []
    spatial = request_df[request_df["spawnScenario"].str.contains("SPATIAL", case=False, na=False)].copy()
    if spatial.empty:
        spatial = request_df.copy()
    spatial_cols = [
        "taxiCount",
        "clientCount",
        "spawnScenario",
        "algorithm",
        "idleRoamingMode",
        "zoneX",
        "zoneY",
    ]
    grouped = (
        spatial.groupby(spatial_cols, dropna=False)
        .agg(requests=("requestCount", "sum"), waiting=("waitingTimeSum", "sum"))
        .reset_index()
    )
    grouped["waitMean"] = grouped["waiting"] / grouped["requests"].where(grouped["requests"] != 0)
    panel_cols = ["taxiCount", "clientCount", "spawnScenario", "algorithm", "idleRoamingMode"]
    grouped["pickupShare"] = (
            grouped["requests"] / grouped.groupby(panel_cols, dropna=False)["requests"].transform("sum") * 100.0
    )
    metric_norm = {
        col: matplotlib.colors.PowerNorm(gamma=0.5, vmin=0, vmax=max(float(grouped[col].max()), 1.0))
        for col in ["waitMean", "pickupShare"]
        if grouped[col].notna().any()
    }

    pickup_rows = summary[summary["metric"].eq(WAITING_METRIC)].copy()
    pickup_rows["unpickedCount"] = (pickup_rows["clientCount"] - pickup_rows["countMean"]).clip(lower=0)
    pickup_rows["unpickedRatio"] = pickup_rows["unpickedCount"] / pickup_rows["clientCount"] * 100.0
    unpicked = {
        tuple(getattr(row, col) for col in panel_cols): (row.unpickedCount, row.unpickedRatio)
        for row in pickup_rows.groupby(panel_cols, dropna=False)[["unpickedCount", "unpickedRatio"]]
        .mean()
        .reset_index()
        .itertuples(index=False)
    }

    for (taxi_count, client_count, scenario), sub in grouped.groupby(
            ["taxiCount", "clientCount", "spawnScenario"], dropna=False
    ):
        modes = list(dict.fromkeys(zip(sub["algorithm"], sub["idleRoamingMode"])))
        modes = modes[:6]
        if not modes:
            continue
        for value_col, label, filename, normalized, note in [
            (
                    "waitMean",
                    "Waiting time [min]",
                    "spatial_waits",
                    False,
                    "Each panel uses its own color scale. Pickup zones cover picked-up clients only; "
                    "titles show mean clients not picked up per run.",
            ),
            (
                    "waitMean",
                    "Waiting time [min]",
                    "spatial_waits_normalized",
                    True,
                    "Equal values use equal colors across every plot; square-root normalization "
                    "preserves contrast in lower ranges. Pickup zones cover picked-up clients only; "
                    "titles show mean clients not picked up per run.",
            ),
            (
                    "pickupShare",
                    "Distribution of picked-up clients [%]",
                    "spatial_pickup_share",
                    False,
                    "Each panel uses its own color scale. Values show the spatial distribution of "
                    "picked-up clients, not a zone-specific pickup rate; titles show the true mean "
                    "unpicked total per run.",
            ),
            (
                    "pickupShare",
                    "Distribution of picked-up clients [%]",
                    "spatial_pickup_share_normalized",
                    True,
                    "Equal values use equal colors across every plot; square-root normalization "
                    "preserves contrast in lower ranges. Values show the spatial distribution of "
                    "picked-up clients, not a zone-specific pickup rate; titles show the true mean "
                    "unpicked total per run.",
            ),
        ]:
            fig, axes = plt.subplots(
                2, 3, figsize=(12, 7), squeeze=False, constrained_layout=True
            )
            axes_flat = axes.ravel()
            used_axes = []
            image = None
            for ax, (algorithm, mode) in zip(axes_flat, modes):
                plot_df = sub[
                    sub["algorithm"].eq(algorithm) & sub["idleRoamingMode"].eq(mode)
                    ].dropna(subset=["zoneX", "zoneY", value_col])
                if plot_df.empty:
                    ax.set_visible(False)
                    continue
                grid = plot_df.pivot(index="zoneY", columns="zoneX", values=value_col).sort_index()
                image = ax.imshow(
                    grid,
                    origin="lower",
                    aspect="auto",
                    cmap="viridis",
                    norm=metric_norm[value_col] if normalized else None,
                )
                missing = unpicked.get((taxi_count, client_count, scenario, algorithm, mode))
                pickup_note = (
                    f"\nNot picked up/run: {missing[0]:.1f} ({missing[1]:.1f}%)" if missing else ""
                )
                ax.set_title(
                    f"{str(algorithm).replace('TaxiAlgorithm', '')}\n{mode}{pickup_note}",
                    fontsize=9,
                )
                ax.set_xlabel("Zone X")
                ax.set_ylabel("Zone Y")
                if not normalized:
                    fig.colorbar(image, ax=ax, fraction=0.046, pad=0.04)
                used_axes.append(ax)
            for ax in axes_flat:
                if ax not in used_axes:
                    ax.set_visible(False)
            if image is None:
                plt.close(fig)
                continue
            scale_label = "shared colors" if normalized else "local colors"
            if normalized and image is not None and used_axes:
                fig.colorbar(
                    image,
                    ax=used_axes,
                    label=label,
                    shrink=0.82,
                    pad=0.02,
                )
            fig.suptitle(
                f"{label} by pickup zone | {city_label(taxi_count, client_count)} | "
                f"{scenario} | {scale_label}"
            )
            name = f"{filename}_{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
            fig.savefig(out / name, dpi=140)
            plt.close(fig)
            files.append(plot_entry(name, "Spatial maps", note))
            if len(files) >= MAX_REQUEST_PLOTS:
                return files
    return files


# Extended analysis plots


def scenario_labels(rows: pd.DataFrame) -> list[str]:
    return [
        f"{city_label(row.taxiCount, row.clientCount)}\n{str(row.spawnScenario).replace('_', ' ')}"
        for row in rows.itertuples(index=False)
    ]


def plot_architecture_roaming_control(data: pd.DataFrame, out: Path) -> dict[str, str] | None:
    if plt is None or data.empty:
        return None
    variants = ["Central", "P2P none", "P2P past-avg", "P2P past-avg-total", "P2P random", "P2P past-avg-revisit"]
    cities = sorted(data["taxiCount"].unique())
    fig, axes = plt.subplots(2, len(cities), figsize=(15, 8), squeeze=False)
    for column, taxi_count in enumerate(cities):
        city = data[data["taxiCount"].eq(taxi_count)]
        scenarios = sorted(city["spawnScenario"].unique())
        x = np.arange(len(scenarios))
        width = 0.13
        for i, variant in enumerate(variants):
            part = city[city["variant"].eq(variant)].set_index("spawnScenario")
            waits = [part["waitingAvgMean"].get(scenario, np.nan) for scenario in scenarios]
            served = [part["servedRatio"].get(scenario, np.nan) * 100 for scenario in scenarios]
            offset = (i - (len(variants) - 1) / 2) * width
            axes[0, column].bar(x + offset, waits, width, label=variant)
            axes[1, column].bar(x + offset, served, width, label=variant)
        title = city_label(taxi_count, city["clientCount"].iloc[0])
        axes[0, column].set_title(title)
        axes[0, column].set_yscale("log")
        axes[0, column].set_ylabel("Mean waiting time [min], log")
        axes[1, column].set_ylabel("Pickup rate [%]")
        axes[1, column].set_ylim(45, 101)
        for ax in axes[:, column]:
            ax.set_xticks(x)
            ax.set_xticklabels([scenario.replace("_", " ") for scenario in scenarios], rotation=30, ha="right")
            ax.grid(axis="y", alpha=0.2)
    axes[0, 0].legend(fontsize=8, ncol=2)
    fig.tight_layout()
    name = "architecture_roaming_control.png"
    fig.savefig(out / name, dpi=150)
    plt.close(fig)
    return plot_entry(
        name,
        "Extended analysis",
        "Central reference and P2P roaming modes; P2P bars average only remaining P2P "
        "parameters.",
    )


def plot_interaction_grid(
        data: pd.DataFrame,
        out: Path,
        row: str,
        column: str,
        filename: str,
        title: str,
) -> dict[str, str] | None:
    if plt is None or data.empty:
        return None
    groups = list(data.groupby(["taxiCount", "clientCount", "spawnScenario"], dropna=False))
    fig, axes = plt.subplots(2, 4, figsize=(18, 8), squeeze=False, constrained_layout=True)
    values = data["waitingDeltaPct"].replace([np.inf, -np.inf], np.nan).dropna()
    low = min(-1.0, float(values.quantile(0.05)))
    high = max(1.0, float(values.quantile(0.95)))
    norm = matplotlib.colors.TwoSlopeNorm(vmin=low, vcenter=0, vmax=high)
    image = None
    for index, (ax, (keys, part)) in enumerate(zip(axes.ravel(), groups)):
        grid = part.pivot(index=row, columns=column, values="waitingDeltaPct").sort_index()
        image = ax.imshow(grid, aspect="auto", cmap="coolwarm", norm=norm)
        ax.set_xticks(range(len(grid.columns)), [str(value) for value in grid.columns])
        ax.set_yticks(range(len(grid.index)), [str(value) for value in grid.index])
        ax.set_xlabel("Search radius [m]")
        if index % 4 == 0:
            ax.set_ylabel("Roaming mode" if row == "idleRoamingMode" else "Hop depth")
        ax.set_title(f"{city_label(keys[0], keys[1])} | {str(keys[2]).replace('_', ' ')}", fontsize=9)
        for y in range(len(grid.index)):
            for x in range(len(grid.columns)):
                value = grid.iloc[y, x]
                if pd.notna(value):
                    ax.text(x, y, f"{value:.0f}%", ha="center", va="center", fontsize=7)
    for ax in axes.ravel()[len(groups):]:
        ax.set_visible(False)
    if image is not None:
        fig.colorbar(
            image,
            ax=axes.ravel().tolist(),
            label="Waiting-time delta vs central [%]",
            shrink=0.82,
            pad=0.02,
        )
    fig.suptitle(title)
    fig.savefig(out / filename, dpi=150)
    plt.close(fig)
    return plot_entry(
        filename,
        "Extended analysis",
        "Cells average only parameters not shown; values are relative to the matched central "
        "reference.",
    )


def plot_robust_config(data: pd.DataFrame, out: Path) -> dict[str, str] | None:
    if plt is None or data.empty:
        return None
    data = data.sort_values(["taxiCount", "spawnScenario"])
    labels = scenario_labels(data)
    x = np.arange(len(data))
    width = 0.24
    fig, axes = plt.subplots(2, 1, figsize=(12, 7), sharex=True)
    for i, (column, label) in enumerate(
            [("waitingDeltaPct", "Waiting"), ("travelDeltaPct", "Client travel"), ("distanceDeltaPct", "Taxi distance")]
    ):
        axes[0].bar(x + (i - 1) * width, data[column], width, label=label)
    axes[0].axhline(0, color="#111827", linewidth=1)
    axes[0].axhline(5, color="#b91c1c", linestyle="--", linewidth=1, label="5% corridor")
    axes[0].set_ylabel("Delta vs central [%]")
    axes[0].legend(ncol=4)
    axes[1].bar(x, data["pickupGapPoints"], color="#0f766e")
    axes[1].axhline(1, color="#b91c1c", linestyle="--", linewidth=1)
    axes[1].set_ylabel("Pickup-rate gap [pp]")
    axes[1].set_xticks(x, labels, rotation=30, ha="right")
    for ax in axes:
        ax.grid(axis="y", alpha=0.2)
    fig.suptitle("Best scenario-robust fixed P2P configuration")
    fig.tight_layout()
    name = "robust_config_scenario_regret.png"
    fig.savefig(out / name, dpi=150)
    plt.close(fig)
    return plot_entry(
        name,
        "Extended analysis",
        "One fixed parameter configuration across all city-scenario groups; lower is better.",
    )


def plot_crossover_robustness(
        crossover: pd.DataFrame, seed_counts: pd.DataFrame, out: Path
) -> list[dict[str, str]]:
    if plt is None or crossover.empty or seed_counts.empty:
        return []
    mean = crossover[crossover["thresholdPct"].eq(5)].sort_values(["taxiCount", "spawnScenario"])
    seeds = seed_counts[seed_counts["thresholdPct"].eq(5)].sort_values(["taxiCount", "spawnScenario"])
    merged = mean.merge(seeds, on=SCENARIO_COLS + ["thresholdPct"], how="inner")
    labels = scenario_labels(merged)
    x = np.arange(len(merged))
    width = 0.26
    fig, ax = plt.subplots(figsize=(12, 5.5))
    ax.bar(x - width, merged["currentPass"], width, label="Mean passes")
    ax.bar(x, merged["allSeeds"], width, label="Passes in all 10 seed pairs")
    ax.bar(
        x + width,
        merged["withTravelAllSeeds"],
        width,
        label="All 10 seed pairs incl. travel time",
    )
    ax.set_ylabel("Configurations")
    ax.set_xticks(x, labels, rotation=30, ha="right")
    ax.legend(ncol=2)
    ax.grid(axis="y", alpha=0.2)
    fig.tight_layout()
    name = "crossover_seed_robustness_5pct.png"
    fig.savefig(out / name, dpi=150)
    plt.close(fig)

    fig, ax = plt.subplots(figsize=(12, 5))
    ax.bar(x - width / 2, merged["currentPass"], width, label="Existing 3 criteria")
    ax.bar(x + width / 2, merged["withTravelTime"], width, label="Also client travel time")
    ax.set_ylabel("Configurations in 5% corridor")
    ax.set_xticks(x, labels, rotation=30, ha="right")
    ax.legend()
    ax.grid(axis="y", alpha=0.2)
    fig.tight_layout()
    travel_name = "crossover_client_travel_validation_5pct.png"
    fig.savefig(out / travel_name, dpi=150)
    plt.close(fig)
    return [
        plot_entry(name, "Extended analysis", "Mean-based membership compared with paired-seed stability."),
        plot_entry(
            travel_name,
            "Extended analysis",
            "Existing crossover criteria compared with an added client-travel-time condition.",
        ),
    ]


def plot_best_waiting_cost(data: pd.DataFrame, out: Path) -> dict[str, str] | None:
    if plt is None or data.empty:
        return None
    data = data.sort_values(["taxiCount", "spawnScenario"])
    labels = scenario_labels(data)
    x = np.arange(len(data))
    width = 0.36
    fig, axes = plt.subplots(2, 1, figsize=(12, 7), sharex=True)
    axes[0].bar(x - width / 2, data["centralKmPerServedClient"], width, label="Central")
    axes[0].bar(x + width / 2, data["kmPerServedClient"], width, label="Best-waiting P2P")
    axes[0].set_ylabel("Taxi km / picked-up client")
    axes[1].bar(x - width / 2, data["centralCalculationMsPerServedClient"], width, label="Central")
    axes[1].bar(x + width / 2, data["calculationMsPerServedClient"], width, label="Best-waiting P2P")
    axes[1].set_ylabel("Total calculation [ms] / picked-up client")
    axes[1].set_yscale("log")
    axes[1].set_xticks(x, labels, rotation=30, ha="right")
    for ax in axes:
        ax.legend()
        ax.grid(axis="y", alpha=0.2)
    fig.tight_layout()
    name = "best_waiting_service_normalized_cost.png"
    fig.savefig(out / name, dpi=150)
    plt.close(fig)
    return plot_entry(name, "Extended analysis", "Operational effort normalized by actually picked-up clients.")


def plot_best_waiting_uncertainty(
        best: pd.DataFrame, paired: pd.DataFrame, out: Path
) -> dict[str, str] | None:
    if plt is None or best.empty or paired.empty:
        return None
    keys = SCENARIO_COLS + CONFIG_COLS
    data = best[keys].merge(paired[paired["metric"].eq("waiting")], on=keys, how="left")
    data = data.sort_values(["taxiCount", "spawnScenario"])
    labels = scenario_labels(data)
    x = np.arange(len(data))
    fig, ax = plt.subplots(figsize=(12, 5))
    ax.errorbar(
        x,
        data["deltaPctMean"],
        yerr=data["deltaPctStd"],
        fmt="o",
        capsize=4,
        color="#0f766e",
    )
    ax.axhline(0, color="#111827", linewidth=1)
    ax.axhline(5, color="#b91c1c", linestyle="--", linewidth=1)
    ax.set_ylabel("Paired waiting-time delta vs central [%]")
    ax.set_xticks(x, labels, rotation=30, ha="right")
    ax.grid(axis="y", alpha=0.2)
    fig.tight_layout()
    name = "best_waiting_paired_seed_uncertainty.png"
    fig.savefig(out / name, dpi=150)
    plt.close(fig)
    return plot_entry(
        name,
        "Extended analysis",
        "Mean and standard deviation across paired world seeds for the mean-selected best "
        "waiting configuration.",
    )


def plot_extended_analysis(tables: dict[str, pd.DataFrame], out: Path) -> list[dict[str, str]]:
    records = [
        plot_architecture_roaming_control(tables["roaming"], out),
        plot_interaction_grid(
            tables["radiusRoaming"],
            out,
            "idleRoamingMode",
            "p2pRqsRadius",
            "interaction_radius_roaming_waiting.png",
            "Interaction: search radius and roaming",
        ),
        plot_interaction_grid(
            tables["radiusK"],
            out,
            "kHops",
            "p2pRqsRadius",
            "interaction_radius_k_waiting.png",
            "Interaction: search radius and hop depth",
        ),
        plot_robust_config(tables["robustDetails"], out),
        plot_best_waiting_cost(tables["bestWaiting"], out),
        plot_best_waiting_uncertainty(tables["bestWaiting"], tables["pairedDeltas"], out),
    ]
    return [record for record in records if record is not None] + plot_crossover_robustness(
        tables["crossover"], tables["seedCounts"], out
    )


def plot_best_vs_single(comp: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None or comp.empty:
        return []
    best = best_p2p_configs(comp)
    files = []
    for metric, sub in best.groupby("metric", dropna=False):
        labels = [
            f"{int(r.taxiCount)}/{int(r.clientCount)}\n{r.spawnScenario}" for r in sub.itertuples(index=False)
        ]
        x = np.arange(len(sub))
        width = 0.38
        fig, ax = plt.subplots(figsize=(max(8, len(sub) * 1.25), 5))
        if metric == CALCULATION_METRIC:
            ax.bar(
                x - width / 2,
                sub["singleAvg"],
                width,
                yerr=sub["singleStd"].fillna(0),
                capsize=3,
                color="#6b7280",
                label="SinglePassenger",
            )
            add_calculation_bars(
                ax,
                x + width / 2,
                sub["avgMean"],
                sub[COMMUNICATION_COL].fillna(0),
                width,
                sub["avgStd"].fillna(0),
                labels=("Remaining calculation - Best P2P", "Communication - Best P2P"),
            )
        else:
            ax.bar(
                x - width / 2,
                sub["singleAvg"],
                width,
                yerr=sub["singleStd"].fillna(0),
                capsize=3,
                label="SinglePassenger",
            )
            ax.bar(
                x + width / 2,
                sub["avgMean"],
                width,
                yerr=sub["avgStd"].fillna(0),
                capsize=3,
                label="Best P2P config",
            )
        ax.set_ylabel(metric)
        set_sensible_y_span(ax, sub["singleAvg"], sub["avgMean"])
        ax.set_xticks(x)
        ax.set_xticklabels(labels, rotation=35, ha="right")
        ax.legend()
        fig.tight_layout()
        name = f"best_p2p_vs_single_{safe_name(metric)}.png"
        fig.savefig(out / name, dpi=140)
        plt.close(fig)
        note = (
            "P2P calculation time uses one color; hatching marks its communication part."
            if metric == CALCULATION_METRIC
            else ""
        )
        files.append(plot_entry(name, "Best P2P", note))
        if metric == CALCULATION_METRIC:
            continue

        configs = [config_label(row) for row in sub.itertuples(index=False)]
        colors = {
            cfg: OKABE_ITO_COLORS[i % len(OKABE_ITO_COLORS)]
            for i, cfg in enumerate(dict.fromkeys(configs))
        }
        fig, ax = plt.subplots(figsize=(max(9, len(sub) * 1.35), 5.4))
        seen = set()
        for i, (row, cfg) in enumerate(zip(sub.itertuples(index=False), configs)):
            label = cfg if cfg not in seen else None
            if i == 0:
                ax.bar(
                    i - width / 2,
                    row.singleAvg,
                    width,
                    yerr=0 if pd.isna(row.singleStd) else row.singleStd,
                    capsize=3,
                    color="#6b7280",
                    label="SinglePassenger",
                )
            else:
                ax.bar(
                    i - width / 2,
                    row.singleAvg,
                    width,
                    yerr=0 if pd.isna(row.singleStd) else row.singleStd,
                    capsize=3,
                    color="#6b7280",
                )
            ax.bar(
                i + width / 2,
                row.avgMean,
                width,
                yerr=0 if pd.isna(row.avgStd) else row.avgStd,
                capsize=3,
                color=colors[cfg],
                label=label,
            )
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
        files.append(plot_entry(name, "Best P2P", note))
    return files


def plot_thesis_focus(
        summary: pd.DataFrame,
        comp: pd.DataFrame,
        out: Path,
        time_df: pd.DataFrame,
        request_df: pd.DataFrame,
        tick_block_size: int,
) -> list[dict[str, str]]:
    return (
            plot_best_vs_single(comp, out)
            + plot_scale_trends(comp, out)
            + plot_information_trends(summary, out)
            + plot_roaming_trends(summary, out)
            + plot_topology_trends(summary, out)
            + plot_interesting_trends(summary, comp, out)
            + plot_time_windows(time_df, out, tick_block_size)
            + plot_spatial_maps(request_df, summary, out)
    )


# HTML report


PRINT_PLOT_SECTIONS = {
    "Best P2P",
    "Hypothesis plots",
    "Extended analysis",
    "Interesting trends",
    "Time windows",
    "Spatial maps",
}

PRINT_RESULT_FILES = {
    "system_comparison_summary.csv",
    "crossover_summary.csv",
    "robust_config_scenario_details.csv",
    "best_waiting_operational_cost.csv",
    "best_waiting_seed_uncertainty.csv",
    "scenario_minimax_configs.csv",
    "time_window_result_summary.csv",
    "spatial_result_summary.csv",
}


def report_result_cards(rows: pd.DataFrame, columns: list[str]) -> str:
    cards = []
    identity_columns = [
        column
        for column in ("city", "spawnScenario", "architecture", "series", "algorithm", "window")
        if column in columns
    ]
    for number, row in enumerate(rows[columns].round(3).to_dict("records"), start=1):
        title = " · ".join(str(row[column]) for column in identity_columns if pd.notna(row[column]))
        values = "".join(
            f"<div><dt>{html.escape(column)}</dt>"
            f"<dd>{'' if pd.isna(value) else html.escape(str(value))}</dd></div>"
            for column, value in row.items()
            if column not in identity_columns
        )
        cards.append(
            f'<article class="result-card"><h4>{html.escape(title or f"Result {number}")}</h4>'
            f"<dl>{values}</dl></article>"
        )
    return "".join(cards)


def report_csv_table(
        base: Path, title: str, filename: str, columns: list[str], opened: bool = False
) -> str:
    path = base / "tables" / filename
    if not path.exists():
        return ""
    rows = pd.read_csv(path)
    if rows.empty:
        return ""
    if {"taxiCount", "clientCount"}.issubset(rows.columns):
        rows.insert(
            0,
            "city",
            rows.apply(
                lambda row: "All" if pd.isna(row["taxiCount"]) else city_label(row["taxiCount"], row["clientCount"]),
                axis=1,
            ),
        )
    columns = [column for column in columns if column in rows.columns]
    table = rows[columns].round(3).to_html(
        index=False, border=0, classes="result-table", na_rep=""
    )
    print_result = filename in PRINT_RESULT_FILES
    print_cards = report_result_cards(rows, columns) if print_result else ""
    detail_class = "result print-result" if print_result else "result print-hidden"
    return (
        f'<details class="{detail_class}"{" open" if opened else ""}>'
        f'<summary>{html.escape(title)} <small>{len(rows)} rows</small></summary>'
        f'<p><a href="tables/{html.escape(filename)}">{html.escape(filename)}</a></p>'
        f'<div class="table-wrap">{table}</div>'
        f'<div class="result-cards">{print_cards}</div></details>'
    )


def write_report(base: Path, overview: dict, plot_files: list[dict[str, str]]) -> None:
    tables = sorted(p.name for p in (base / "tables").glob("*.csv"))
    stats_files = sorted(p.name for p in (base / "stats").glob("*.csv"))
    best_path = base / "tables" / "best_p2p_vs_single.csv"
    best_rows = pd.read_csv(best_path) if best_path.exists() else pd.DataFrame()
    if not best_rows.empty:
        best_rows = best_rows.copy()
        best_rows = best_rows[best_rows["metric"].isin(analysis_metrics(best_rows))]
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
    result_tables = "".join(
        [
            report_csv_table(
                base,
                "System comparison",
                "system_comparison_summary.csv",
                [
                    "city", "spawnScenario", "architecture", "waitingAvgMean",
                    "travelAvgMean", "distanceAvgMean", "pickupPct",
                    "calculationAvgMean", "communicationSharePct",
                ],
                True,
            ),
            report_csv_table(
                base,
                "Parameter effects",
                "parameter_effect_summary.csv",
                [
                    "factor", "fromValue", "toValue", "basis", "city", "spawnScenario",
                    "waitingMinFrom", "waitingMinTo", "waitingMinDeltaPct",
                    "distanceKmDeltaPct", "pickupDeltaPoints", "calculationMsFrom",
                    "calculationMsTo", "calculationMsDeltaPct", "communicationMicrosFrom",
                    "communicationMicrosTo", "communicationMicrosDeltaPct",
                    "communicationSharePctFrom", "communicationSharePctTo",
                    "communicationShareDeltaPoints",
                ],
            ),
            report_csv_table(
                base,
                "Overall parameter levels",
                "parameter_level_overall_summary.csv",
                [
                    "factor", "level", "basis", "waitingAvgMean", "travelAvgMean",
                    "distanceAvgMean", "pickupPct", "calculationAvgMean",
                    "communicationAvgMean", "communicationSharePct",
                ],
            ),
            report_csv_table(
                base,
                "Roaming-mode comparison",
                "architecture_roaming_control.csv",
                [
                    "city", "spawnScenario", "variant", "waitingAvgMean",
                    "travelAvgMean", "distanceAvgMean", "servedRatio",
                    "calculationAvgMean", "calculationMsPerServedClient",
                ],
            ),
            report_csv_table(
                base,
                "Radius / k interaction",
                "interaction_radius_k.csv",
                [
                    "city", "spawnScenario", "p2pRqsRadius", "kHops",
                    "waitingAvgMean", "servedRatio", "centralWaitingAvgMean",
                    "waitingDeltaPct",
                ],
            ),
            report_csv_table(
                base,
                "Radius / roaming interaction",
                "interaction_radius_roaming.csv",
                [
                    "city", "spawnScenario", "p2pRqsRadius", "idleRoamingMode",
                    "waitingAvgMean", "servedRatio", "centralWaitingAvgMean",
                    "waitingDeltaPct",
                ],
            ),
            report_csv_table(
                base,
                "k / radius / roaming interactions",
                "interaction_k_radius_roaming.csv",
                [
                    "city", "spawnScenario", "kHops", "p2pRqsRadius", "idleRoamingMode",
                    "waitingAvgMean", "travelAvgMean", "distanceAvgMean", "pickupPct",
                    "calculationAvgMean", "communicationSharePct",
                ],
            ),
            report_csv_table(
                base,
                "k / shortcut interactions",
                "interaction_k_shortcuts.csv",
                [
                    "city", "spawnScenario", "kHops", "p2pOverlayShortcuts",
                    "waitingAvgMean", "distanceAvgMean", "pickupPct",
                    "calculationAvgMean", "communicationSharePct",
                ],
            ),
            report_csv_table(
                base,
                "Seed-based moderation contrasts",
                "moderation_summary.csv",
                [
                    "scope", "city", "spawnScenario", "moderation", "outcome",
                    "moderatorFrom", "moderatorTo", "effectAtModeratorFromMean",
                    "effectAtModeratorToMean", "interactionMean", "ci95Low", "ci95High",
                    "sameDirectionSeeds", "seeds", "pValue", "pValueHolm",
                ],
            ),
            report_csv_table(
                base,
                "Crossover and seed stability",
                "crossover_summary.csv",
                [
                    "city", "spawnScenario", "thresholdPct", "configurations", "currentPass",
                    "withTravelTime", "allSeeds", "withTravelAllSeeds",
                ],
            ),
            report_csv_table(
                base,
                "Best fixed Minimax configuration",
                "robust_config_scenario_details.csv",
                [
                    "city", "spawnScenario", "kHops", "p2pRqsRadius", "p2pStrategy",
                    "p2pOverlayMinNeighbors", "p2pOverlayShortcuts", "idleRoamingMode",
                    "waitingDeltaPct", "travelDeltaPct", "distanceDeltaPct", "pickupGapPoints",
                ],
            ),
            report_csv_table(
                base,
                "Waiting-best configuration and cost",
                "best_waiting_operational_cost.csv",
                [
                    "city", "spawnScenario", "waitingAvgMean", "centralWaitingAvgMean",
                    "waitingDeltaPct", "travelDeltaPct", "distanceDeltaPct", "pickupPct",
                    "kmPerServedClientDeltaPct", "calculationMsPerServedClientDeltaPct",
                ],
            ),
            report_csv_table(
                base,
                "Waiting-best seed uncertainty",
                "best_waiting_seed_uncertainty.csv",
                [
                    "city", "spawnScenario", "kHops", "p2pRqsRadius", "p2pStrategy",
                    "p2pOverlayMinNeighbors", "p2pOverlayShortcuts", "idleRoamingMode",
                    "seeds", "deltaPctMean", "deltaPctStd", "deltaPctMin", "deltaPctMax",
                ],
            ),
            report_csv_table(
                base,
                "Scenario-specific Minimax configurations",
                "scenario_minimax_configs.csv",
                [
                    "city", "spawnScenario", "selectedConfig", "waitingDeltaPct",
                    "travelDeltaPct", "distanceDeltaPct", "pickupGapPoints",
                    "referenceWorstNormalizedRegret",
                ],
            ),
            report_csv_table(
                base,
                "Time-window results",
                "time_window_result_summary.csv",
                [
                    "window", "city", "spawnScenario", "series", "selectedConfig",
                    "firstBlock", "lastBlock",
                    "activeClientsMean", "servedRequestsMean", "meanBlockWaitingTime",
                    "pooledClientWaitingTime", "waitingTimeMax",
                ],
            ),
            report_csv_table(
                base,
                "Spatial results",
                "spatial_result_summary.csv",
                [
                    "city", "spawnScenario", "algorithm", "idleRoamingMode", "waitingMean",
                    "zoneWaitingMin", "zoneWaitingMax", "pickedUpPerRun", "notPickedUpPct",
                ],
            ),
        ]
    )
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
    sections = [
        "Best P2P",
        "Hypothesis plots",
        "Extended analysis",
        "Interesting trends",
        "Time windows",
        "Request tails",
        "Spatial maps",
        "Plots",
    ]
    for section in sections:
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
        print_class = "" if section in PRINT_PLOT_SECTIONS else " print-hidden"
        plot_sections.append(
            f'<section class="plot-group{print_class}"><h3>{html.escape(section)}</h3>'
            f'<div class="plots">{cards}</div></section>'
        )
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
    .hero-actions {{ display: flex; align-items: center; gap: 10px; }}
    .print-action {{
      border: 0;
      border-radius: 7px;
      padding: 9px 12px;
      background: var(--accent);
      color: #fff;
      cursor: pointer;
      font: inherit;
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
    details.result {{ margin-top: 10px; border-top: 1px solid var(--line); padding-top: 10px; }}
    details.result:first-child {{ margin-top: 0; border-top: 0; padding-top: 0; }}
    details.result summary {{ cursor: pointer; font-weight: 650; }}
    details.result summary small {{ color: var(--muted); font-weight: 400; margin-left: 6px; }}
    details.result p {{ margin: 8px 0; }}
    details.result p a {{ color: var(--accent); }}
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
    .print-only, .result-cards {{ display: none; }}
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
    @media print {{
      @page {{ size: A4 portrait; margin: 12mm; }}
      html {{ scroll-behavior: auto; }}
      body {{
        background: #fff;
        color: #000;
        font-size: 9pt;
        -webkit-print-color-adjust: exact;
        print-color-adjust: exact;
      }}
      aside, .print-hidden, .print-action, .screen-only {{ display: none !important; }}
      .layout {{ display: block; min-height: 0; }}
      main {{ max-width: none; width: auto; padding: 0; }}
      section {{ margin-bottom: 7mm; }}
      .hero {{ align-items: flex-start; margin-bottom: 7mm; padding-bottom: 4mm; }}
      h1 {{ font-size: 20pt; }}
      h2 {{ font-size: 14pt; }}
      h3 {{ font-size: 11pt; color: #000; }}
      .cards {{ grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 3mm; }}
      .card, .panel, .plot {{ box-shadow: none; }}
      .card {{ padding: 3mm; }}
      .card strong {{ font-size: 14pt; }}
      .panel {{ padding: 4mm; }}
      #results, #plots {{ break-before: page; }}
      .print-only {{ display: block; }}
      details.print-result {{ border: 0; padding-top: 4mm; }}
      details.print-result summary {{ font-size: 12pt; break-after: avoid; }}
      details.print-result > p, details.print-result > .table-wrap {{ display: none !important; }}
      details.print-result > .result-cards {{ display: grid !important; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 4mm; margin-top: 3mm; }}
      .result-card {{ break-inside: avoid; border: 1px solid var(--line); border-radius: 5px; padding: 3mm; }}
      .result-card h4 {{ margin: 0 0 2mm; font-size: 10pt; }}
      .result-card dl {{ display: grid; gap: 1mm; margin: 0; }}
      .result-card dl > div {{ display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 2mm; border-bottom: 1px solid #eef1f5; }}
      .result-card dt {{ color: var(--muted); font-size: 7pt; overflow-wrap: anywhere; }}
      .result-card dd {{ margin: 0; max-width: 42mm; font-size: 8pt; text-align: right; overflow-wrap: anywhere; }}
      .plot-group h3 {{ break-after: avoid; }}
      .plots {{ grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: start; gap: 5mm; }}
      .plot {{ align-self: start; break-inside: avoid; border-color: #aeb7c4; }}
      .plot img {{ height: auto; object-fit: contain; }}
      .plot span {{ display: none; }}
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
      <a href="#results">Results summary</a>
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
        <p>Summaries, Matched P2P vs SinglePassenger deltas, Plots</p>
      </div>
      <div class="hero-actions">
        <p><code>{day_text}</code></p>
        <button class="print-action" type="button" onclick="window.print()">Export A4 PDF</button>
      </div>
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

    <section id="results" class="panel">
      <h2>Results summary</h2>
      <p class="screen-only">All values used for result interpretation, calculated from the same exact-config aggregates. Open a group for its complete summary table.</p>
      <p class="print-only">Selected calculations are shown as portrait result sheets. Full tables and statistical outputs remain in the accompanying ZIP archive.</p>
      {result_tables or '<p>No result summaries generated.</p>'}
    </section>

    <section id="best" class="panel print-hidden">
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
        <li>Relative delta [%] = 100 * (P2P - central) / central.</li>
        <li>Pickup gap [percentage points] = central pickup rate - P2P pickup rate.</li>
        <li>Crossover pass: waiting and taxi-distance deltas stay below the selected threshold; pickup gap stays at or below one percentage point. The extended pass also applies the threshold to client travel time.</li>
        <li>Normalized Minimax regret is the maximum of waiting/5, travel/5, distance/5, and pickup-gap/1 for the same configuration.</li>
        <li>Seed stability counts only configurations satisfying the rule in every paired world seed.</li>
        <li>Overview hypothesis plots average over non-plotted P2P parameters; each caption states what varies.</li>
        <li>Interesting trend plots fix all non-plotted config columns; only repeated runs are averaged.</li>
      </ul>
    </section>

    <section id="tables" class="panel print-hidden">
      <h2>Tables</h2>
      <div class="files">{table_links}</div>
    </section>

    <section id="stats" class="panel print-hidden">
      <h2>Stats</h2>
      <div class="files">{stat_links}</div>
    </section>

    <section id="plots">
      <h2>Plots</h2>
      {plots or '<p>No plots generated.</p>'}
    </section>
  </main>
</div>
<script>
  window.addEventListener("beforeprint", () => {{
    document.querySelectorAll("details.print-result").forEach(detail => detail.open = true);
  }});
</script>
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
    extended = write_extended_analysis(df, summary, dirs["tables"])
    selected_configs = scenario_minimax_configs(extended["matched"])
    selected_configs.to_csv(dirs["tables"] / "scenario_minimax_configs.csv", index=False)
    time_df = load_time_series_summary(
        config.time_series_csv, config.tick_block_size, selected_configs
    )
    request_df = load_request_summary(config.requests_csv)
    write_time_window_summary(time_df, dirs["tables"], config.tick_block_size)
    write_request_tail_summary(request_df, dirs["tables"])
    write_spatial_result_summary(request_df, summary, dirs["tables"])
    write_effect_screens(df, dirs["stats"])
    plots = plot_thesis_focus(summary, comp, dirs["plots"], time_df, request_df, config.tick_block_size)
    plots += plot_extended_analysis(extended, dirs["plots"])
    write_report(dirs["base"], overview, plots)
    print(f"[analyze] {len(df)} rows -> {dirs['base'] / 'report.html'}")


if __name__ == "__main__":
    main()

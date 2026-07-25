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
CORE_METRIC_PATTERNS = ["Taxi Travel Distance", "Client Waiting Time", "Client Travel Time"]
CALCULATION_METRIC = "Calculation Time [millis]"
COMMUNICATION_METRIC = "Custom Time [micros]"
WAITING_METRIC = "Client Waiting Time [min]"
TRAVEL_METRIC = "Client Travel Time [min]"
DISTANCE_METRIC = "Taxi Travel Distance [km]"
COMMUNICATION_COL = "communicationTimeMillisMean"
CALCULATION_COLOR = "#4c78a8"
LEGACY_SECOND_METRICS = {"Client Waiting Time [min]", "Client Travel Time [min]"}
LEGACY_METER_METRICS = {"Taxi Travel Distance [km]"}
MAX_TIME_SERIES_PLOTS = 24
MAX_REQUEST_PLOTS = 24
DEFAULT_TICK_BLOCK_SIZE = 1000
AUX_CHUNK_SIZE = 100_000

TIME_SERIES_GROUP_COLS = [
    "algorithm",
    "taxiCount",
    "clientCount",
    "spawnScenario",
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


def load_data(path: Path, metrics: list[str]) -> pd.DataFrame:
    if not path.exists():
        raise FileNotFoundError(f"CSV not found: {path}")

    df = pd.read_csv(path)
    for col in NUMERIC_COLS:
        if col in df.columns:
            df[col] = pd.to_numeric(df[col], errors="coerce")

    # The simulator advances in seconds; the legacy metric names already say "[min]".
    time_rows = df["metric"].isin(LEGACY_SECOND_METRICS)
    df.loc[time_rows, ["min", "max", "avg", "sum", "spread"]] /= 60.0
    distance_rows = df["metric"].isin(LEGACY_METER_METRICS)
    df.loc[distance_rows, ["min", "max", "avg", "sum", "spread"]] /= 1000.0

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
    run_config_cols = [col for col in EXACT_CONFIG_COLS if col != "metric"] + [
        col for col in ["runIndex", "worldSeed"] if col in df.columns
    ]
    communication = (
        df[df["metric"].eq(COMMUNICATION_METRIC)]
        .groupby(run_config_cols, dropna=False)["avg"]
        .mean()
        .div(1000.0)
        .rename(COMMUNICATION_COL)
        .reset_index()
    )
    df = df.merge(communication, on=run_config_cols, how="left")
    calculation_rows = df["metric"].eq(CALCULATION_METRIC) & df["algorithm"].str.contains(
        "P2PCollector", case=False, na=False
    )
    df.loc[calculation_rows, COMMUNICATION_COL] = np.minimum(
        df.loc[calculation_rows, COMMUNICATION_COL].fillna(0).clip(lower=0),
        df.loc[calculation_rows, "avg"],
    )
    df.loc[~calculation_rows, COMMUNICATION_COL] = np.nan
    if metrics:
        df = df[df["metric"].isin(metrics)].copy()
        if df.empty:
            raise ValueError("No rows left after --metrics filter.")

    df["is_p2p"] = df["algorithm"].str.contains("P2PCollector", case=False, na=False)
    df.loc[~df["is_p2p"], P2P_COLS] = df.loc[~df["is_p2p"], P2P_COLS].where(
        df.loc[~df["is_p2p"], P2P_COLS].notna(), "n/a"
    )
    return df


def normalize_aux(df: pd.DataFrame, numeric_cols: list[str]) -> pd.DataFrame:
    for col in numeric_cols:
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
    for col in ["algorithm", "p2pStrategy", "idleRoamingStrategy", "idleRoamingMode", "spawnScenario"]:
        if col in df.columns:
            df[col] = df[col].astype(str).str.strip().replace({"": "n/a", "nan": "n/a"})
    return df


def load_time_series_summary(path: Path, tick_block_size: int) -> pd.DataFrame:
    if not path.exists():
        return pd.DataFrame()
    required = TIME_NUMERIC_COLS + ["algorithm", "spawnScenario"]
    parts = []
    for chunk in pd.read_csv(path, usecols=required, chunksize=AUX_CHUNK_SIZE, low_memory=False):
        chunk = normalize_aux(chunk, TIME_NUMERIC_COLS)
        chunk[["waitingTimeSum", "waitingTimeAvg"]] /= 60.0
        chunk["tickBlock"] = (chunk["tick"] // tick_block_size).astype("int64")
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


def compare_p2p_to_single(summary: pd.DataFrame, out: Path) -> pd.DataFrame:
    single = summary[~summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
    single = single[MATCH_COLS + ["avgMean", "avgStd", "countMean"]].rename(
        columns={
            "avgMean": "singleAvg",
            "avgStd": "singleStd",
            "countMean": "singleCount",
        }
    )

    p2p = summary[summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
    cols = MATCH_COLS + P2P_COLS + ["avgMean", "avgStd", "countMean", "servedRatioMean", "runs", COMMUNICATION_COL]
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
    result["servedRatio"] = result["servedClients"] / result["clientCount"]
    result["totalTaxiDistanceKm"] = result["distanceAvgMean"] * result["taxiCount"]
    result["kmPerServedClient"] = result["totalTaxiDistanceKm"] / result["servedClients"]
    result["calculationMsPerServedClient"] = result["calculationSumMean"] / result["servedClients"]
    result["communicationTotalMs"] = result["communicationSumMean"] / 1000.0
    result["communicationMsPerServedClient"] = result["communicationTotalMs"] / result["servedClients"]
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
    central = matrix[~matrix["algorithm"].str.contains("P2PCollector", case=False, na=False)][
        match + value_cols
    ].rename(columns={col: f"central{col[0].upper()}{col[1:]}" for col in value_cols})
    p2p = matrix[matrix["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
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


def paired_seed_tables(df: pd.DataFrame, out: Path) -> tuple[pd.DataFrame, pd.DataFrame]:
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
    seed_matrix = add_operational_metrics(metric_matrix(seed_rows, ["worldSeed"]))
    paired = add_crossover_flags(match_central(seed_matrix, ["worldSeed"]), "seed")

    deltas = []
    group_cols = SCENARIO_COLS + CONFIG_COLS
    for metric in ["waiting", "travel", "distance"]:
        column = f"{metric}DeltaPct"
        part = (
            paired.groupby(group_cols, dropna=False)[column]
            .agg(["count", "mean", "std"])
            .reset_index()
            .rename(columns={"count": "seeds", "mean": "deltaPctMean", "std": "deltaPctStd"})
        )
        part["metric"] = metric
        critical = part["seeds"].apply(
            lambda n: stats.t.ppf(0.975, n - 1) if stats is not None and n > 1 else 1.96
        )
        part["deltaPctCi95Half"] = critical * part["deltaPctStd"] / np.sqrt(part["seeds"])
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


def write_extended_analysis(
    df: pd.DataFrame, summary: pd.DataFrame, out: Path
) -> dict[str, pd.DataFrame]:
    matrix = add_operational_metrics(metric_matrix(summary))
    matched = add_crossover_flags(match_central(matrix))

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
    p2p = matrix[matrix["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
    central = matrix[~matrix["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()

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
        interaction_radius_k["waitingAvgMean"] / interaction_radius_k["centralWaitingAvgMean"]
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

    matched["referenceWorstDeltaPct"] = matched[
        ["waitingDeltaPct", "travelDeltaPct", "distanceDeltaPct", "pickupGapPoints"]
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
            worstReferenceDeltaPct=("referenceWorstDeltaPct", "max"),
        )
        .reset_index()
        .sort_values(
            ["worstReferenceDeltaPct", "groupsWithin5WithTravel", "groupsWithin5"],
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

    paired_deltas, seed_pass = paired_seed_tables(df, out)
    seed_counts = []
    for threshold in [0, 5, 10, 15]:
        grouped = seed_pass.groupby(SCENARIO_COLS, dropna=False).agg(
            configurations=(f"seedCurrentPass{threshold}", "size"),
            atLeastEightSeeds=(f"seedCurrentPass{threshold}", lambda s: int((s >= 0.8).sum())),
            allSeeds=(f"seedCurrentPass{threshold}", lambda s: int((s >= 1.0).sum())),
            withTravelAtLeastEightSeeds=(
                f"seedExtendedPass{threshold}",
                lambda s: int((s >= 0.8).sum()),
            ),
        )
        grouped["thresholdPct"] = threshold
        seed_counts.append(grouped.reset_index())
    seed_counts = pd.concat(seed_counts, ignore_index=True)
    seed_counts.to_csv(out / "crossover_seed_robustness.csv", index=False)

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
    }


def write_time_window_summary(time_df: pd.DataFrame, out: Path, tick_block_size: int) -> None:
    if time_df.empty:
        return
    time_df.to_csv(out / "time_window_summary.csv", index=False)


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
    p2p = summary[summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
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
    p2p = summary[summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
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
            files.append(plot_entry(name, "Hypothesis plots", "Overview: averages all P2P configs with the same metric, scale, scenario, and roaming mode. k, radius, strategy, and shortcuts vary. Empty roaming modes are skipped from the legend."))
    return files


def plot_topology_trends(summary: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None:
        return []
    p2p = summary[summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
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
            name = f"h4_shortcut_trend_{safe_name(metric)}_{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
            fig.savefig(out / name, dpi=140)
            plt.close(fig)
            files.append(plot_entry(name, "Hypothesis plots", "Overview: averages all P2P configs with the same metric, scale, scenario, k, and shortcut count. Radius, strategy, and roaming vary."))
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
    p2p = summary[summary["algorithm"].str.contains("P2PCollector", case=False, na=False)].copy()
    fixed_cols = [col for col in EXACT_CONFIG_COLS if col not in varied_cols]
    best = (
        comp.sort_values(["metric", "taxiCount", "clientCount", "spawnScenario", "avgMean"])
        .groupby(MATCH_COLS, dropna=False)
        .head(1)
    )
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
        note = "Varied plotted parameters around the matched best P2P config; averages only repeated runs. " + compact_fixed_config(fixed)
        files.append(plot_entry(name, "Interesting trends", note))
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
    metrics = summary["metric"].unique().tolist()
    wait = next((m for m in metrics if "Client Waiting Time" in m), None)
    distance = next((m for m in metrics if "Taxi Travel Distance" in m), None)
    if not wait or not distance:
        return []
    index_cols = [col for col in EXACT_CONFIG_COLS if col != "metric"]
    p2p_rows = summary[summary["algorithm"].str.contains("P2PCollector", case=False, na=False)]
    p2p = p2p_rows.pivot_table(
        index=index_cols, columns="metric", values="avgMean", aggfunc="first"
    ).reset_index().dropna(subset=[wait, distance])
    single_rows = summary[~summary["algorithm"].str.contains("P2PCollector", case=False, na=False)]
    single = single_rows.pivot_table(
        index=[col for col in BASE_COLS if col != "metric"],
        columns="metric",
        values="avgMean",
        aggfunc="first",
    ).reset_index().dropna(subset=[wait, distance])
    files = []
    for (taxi_count, client_count, scenario), sub in p2p.groupby(
        ["taxiCount", "clientCount", "spawnScenario"], dropna=False
    ):
        if sub.empty:
            continue
        fig, ax = plt.subplots(figsize=(7.5, 5.3))
        mode_labels = {
            "none": "None",
            "past-avg": "Past avg",
            "past-avg-revisit": "Revisit",
            "past-avg-total": "Past avg total",
            "random": "Random",
        }
        for mode, mode_df in sub.groupby("idleRoamingMode", dropna=False):
            ax.scatter(
                mode_df[distance],
                mode_df[wait],
                alpha=0.3,
                s=18,
                label=mode_labels.get(str(mode), str(mode)),
            )
        frontier = sub.sort_values(distance)
        frontier = frontier[frontier[wait].cummin().eq(frontier[wait])]
        ax.plot(frontier[distance], frontier[wait], color="black", linewidth=2, label="Pareto frontier")
        reference = single[(single["taxiCount"] == taxi_count) & (single["clientCount"] == client_count) & single["spawnScenario"].eq(scenario)]
        if not reference.empty:
            ax.scatter(reference[distance].mean(), reference[wait].mean(), marker="x", s=80, color="red", label="Central reference")
        ax.set_xlabel(distance)
        ax.set_ylabel(wait)
        ax.set_title(f"Service–movement trade-off | {city_label(taxi_count, client_count)} | {scenario}")
        ax.legend(fontsize=7, ncol=4, loc="upper center", bbox_to_anchor=(0.5, -0.14))
        ax.grid(alpha=0.2)
        fig.tight_layout()
        name = f"efficiency_tradeoff_{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
        fig.savefig(out / name, dpi=140)
        plt.close(fig)
        files.append(plot_entry(name, "Interesting trends", "Each point is an exact P2P configuration; lower-left is better."))
    return files


def plot_interesting_trends(summary: pd.DataFrame, comp: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    return (
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


def plot_time_windows(time_df: pd.DataFrame, out: Path, tick_block_size: int) -> list[dict[str, str]]:
    if plt is None or time_df.empty:
        return []
    files = []
    for (taxi_count, client_count, scenario), sub in time_df.groupby(
        ["taxiCount", "clientCount", "spawnScenario"], dropna=False
    ):
        block = sub.copy()
        if block.empty:
            continue
        fig, axes = plt.subplots(5, 1, figsize=(10, 11), sharex=True)
        for algorithm, group in block.groupby("algorithm", dropna=False):
            group = group.sort_values("tickBlock")
            label = str(algorithm).replace("TaxiAlgorithm", "")
            axes[0].plot(group["tickBlock"], group["activeClientsMean"], linewidth=1.8, label=label)
            axes[1].plot(group["tickBlock"], group["servedRequestsMean"], linewidth=1.8, label=label)
            axes[2].plot(group["tickBlock"], group["finishedRequestsMean"], linewidth=1.8, label=label)
            axes[3].plot(group["tickBlock"], group["waitingTimeAvg"], linewidth=1.8, label=label)
            axes[4].plot(group["tickBlock"], group["calcTimeMillisMean"], linewidth=1.8, label=label)
        axes[0].set_ylabel("Active clients")
        axes[1].set_ylabel("Mean served / block")
        axes[2].set_ylabel("Mean finished / block")
        axes[3].set_ylabel("Avg wait [min] / block")
        axes[4].set_ylabel("Calc. time [ms]")
        axes[4].set_xlabel(f"Tick block ({tick_block_size} ticks)")
        axes[0].set_title(f"Load windows | {city_label(taxi_count, client_count)} | {scenario}")
        for ax in axes:
            ax.grid(alpha=0.2)
        axes[0].legend(fontsize=8, ncol=2)
        fig.tight_layout()
        name = f"time_windows_{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
        fig.savefig(out / name, dpi=140)
        plt.close(fig)
        files.append(plot_entry(name, "Time windows", "Stream-aggregated windows; service counts are normalized per input block before comparing algorithms."))
        if len(files) >= MAX_TIME_SERIES_PLOTS:
            break
    return files


def plot_spatial_maps(request_df: pd.DataFrame, out: Path) -> list[dict[str, str]]:
    if plt is None or request_df.empty:
        return []
    files = []
    spatial = request_df[request_df["spawnScenario"].str.contains("SPATIAL", case=False, na=False)].copy()
    if spatial.empty:
        spatial = request_df.copy()
    for (taxi_count, client_count, scenario), sub in spatial.groupby(
        ["taxiCount", "clientCount", "spawnScenario"], dropna=False
    ):
        grouped = (
            sub.groupby(["algorithm", "idleRoamingMode", "zoneX", "zoneY"], dropna=False)
            .agg(
                requests=("requestCount", "sum"),
                finished=("finishedCount", "sum"),
                waiting=("waitingTimeSum", "sum"),
            )
            .reset_index()
        )
        grouped["waitMean"] = grouped["waiting"] / grouped["requests"].where(grouped["requests"] != 0)
        grouped["completionRatio"] = grouped["finished"] / grouped["requests"].where(grouped["requests"] != 0)
        modes = list(dict.fromkeys(zip(grouped["algorithm"], grouped["idleRoamingMode"])))
        modes = modes[:6]
        if not modes:
            continue
        for value_col, label, filename in [
            ("waitMean", "Waiting time [min]", "spatial_waits"),
            ("completionRatio", "Completion ratio", "spatial_completion"),
        ]:
            fig, axes = plt.subplots(2, 3, figsize=(12, 7), squeeze=False)
            axes_flat = axes.ravel()
            plotted = 0
            for ax, (algorithm, mode) in zip(axes_flat, modes):
                plot_df = grouped[
                    grouped["algorithm"].eq(algorithm) & grouped["idleRoamingMode"].eq(mode)
                ].dropna(subset=["zoneX", "zoneY", value_col])
                if plot_df.empty:
                    ax.set_visible(False)
                    continue
                grid = plot_df.pivot(index="zoneY", columns="zoneX", values=value_col).sort_index()
                image = ax.imshow(grid, origin="lower", aspect="auto", cmap="viridis")
                ax.set_title(f"{str(algorithm).replace('TaxiAlgorithm', '')}\n{mode}", fontsize=9)
                ax.set_xlabel("Zone X")
                ax.set_ylabel("Zone Y")
                fig.colorbar(image, ax=ax, fraction=0.046, pad=0.04)
                plotted += 1
            for ax in axes_flat[plotted:]:
                ax.set_visible(False)
            if not plotted:
                plt.close(fig)
                continue
            fig.suptitle(f"{label} by origin zone | {city_label(taxi_count, client_count)} | {scenario}")
            fig.tight_layout()
            name = f"{filename}_{safe_name(city_label(taxi_count, client_count))}_{safe_name(scenario)}.png"
            fig.savefig(out / name, dpi=140)
            plt.close(fig)
            files.append(plot_entry(name, "Spatial maps", "Heatmap aggregated by algorithm and roaming mode; no raw request rows retained."))
            if len(files) >= MAX_REQUEST_PLOTS:
                return files
    return files


def plot_new_metrics(time_df: pd.DataFrame, request_df: pd.DataFrame, out: Path, tick_block_size: int) -> list[dict[str, str]]:
    return (
        plot_time_windows(time_df, out, tick_block_size)
        + plot_spatial_maps(request_df, out)
    )


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
    return plot_entry(name, "Extended analysis", "Central reference and P2P roaming modes; P2P bars average only remaining P2P parameters.")


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
    return plot_entry(filename, "Extended analysis", "Cells average only parameters not shown; values are relative to the matched central reference.")


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
    return plot_entry(name, "Extended analysis", "One fixed parameter configuration across all city-scenario groups; lower is better.")


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
    width = 0.22
    fig, ax = plt.subplots(figsize=(12, 5.5))
    ax.bar(x - 1.5 * width, merged["currentPass"], width, label="Mean passes")
    ax.bar(x - 0.5 * width, merged["atLeastEightSeeds"], width, label="Passes in >=8/10 seeds")
    ax.bar(x + 0.5 * width, merged["allSeeds"], width, label="Passes in all seeds")
    ax.bar(x + 1.5 * width, merged["withTravelAtLeastEightSeeds"], width, label=">=8/10 incl. travel time")
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
        plot_entry(travel_name, "Extended analysis", "Existing crossover criteria compared with an added client-travel-time condition."),
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
        yerr=data["deltaPctCi95Half"],
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
    return plot_entry(name, "Extended analysis", "95% t intervals from paired world seeds for the mean-selected best waiting configuration.")


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
        + plot_new_metrics(time_df, request_df, out, tick_block_size)
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
        note = "P2P calculation time uses one color; hatching marks its communication part." if metric == CALCULATION_METRIC else ""
        files.append(plot_entry(name, "Best P2P", note))
        if metric == CALCULATION_METRIC:
            continue

        configs = [config_label(row) for row in sub.itertuples(index=False)]
        palette = plt.get_cmap("tab20")
        colors = {cfg: palette(i % 20) for i, cfg in enumerate(dict.fromkeys(configs))}
        fig, ax = plt.subplots(figsize=(max(9, len(sub) * 1.35), 5.4))
        seen = set()
        for i, (row, cfg) in enumerate(zip(sub.itertuples(index=False), configs)):
            label = cfg if cfg not in seen else None
            if i == 0:
                ax.bar(i - width / 2, row.singleAvg, width, yerr=0 if pd.isna(row.singleStd) else row.singleStd, capsize=3, color="#6b7280", label="SinglePassenger")
            else:
                ax.bar(i - width / 2, row.singleAvg, width, yerr=0 if pd.isna(row.singleStd) else row.singleStd, capsize=3, color="#6b7280")
            ax.bar(i + width / 2, row.avgMean, width, yerr=0 if pd.isna(row.avgStd) else row.avgStd, capsize=3, color=colors[cfg], label=label)
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
    for section in ["Best P2P", "Hypothesis plots", "Extended analysis", "Interesting trends", "Time windows", "Request tails", "Spatial maps", "Plots"]:
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
    time_df = load_time_series_summary(config.time_series_csv, config.tick_block_size)
    request_df = load_request_summary(config.requests_csv)
    overview = write_overview(df, dirs["base"] / "overview.json")
    summary = aggregate_exact_configs(df, dirs["tables"])
    comp = compare_p2p_to_single(summary, dirs["tables"])
    write_single_passenger(summary, dirs["tables"])
    extended = write_extended_analysis(df, summary, dirs["tables"])
    write_time_window_summary(time_df, dirs["tables"], config.tick_block_size)
    write_request_tail_summary(request_df, dirs["tables"])
    write_effect_screens(df, dirs["stats"])
    plots = plot_thesis_focus(summary, comp, dirs["plots"], time_df, request_df, config.tick_block_size)
    plots += plot_extended_analysis(extended, dirs["plots"])
    write_report(dirs["base"], overview, plots)
    print(f"[analyze] {len(df)} rows -> {dirs['base'] / 'report.html'}")


if __name__ == "__main__":
    main()

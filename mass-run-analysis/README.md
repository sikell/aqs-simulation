# Mass Run Analysis

Python script to analyze `mass-run-results.csv` files produced by the UI mass run.

## Setup

```powershell
python -m pip install -r mass-run-analysis/requirements.txt
```

## Run

```powershell
python mass-run-analysis/analyze_mass_run.py --input-csv mass-run-results/mass-run-results.csv --output-dir mass-run-results/analysis
```

Optional metric filter:

```powershell
python mass-run-analysis/analyze_mass_run.py --input-csv mass-run-results/mass-run-results.csv --output-dir mass-run-results/analysis --metrics "Taxi Travel Distance [km],Client Travel Time [min]"
```

## Semantics

Primary comparison axis is `algorithm_variant`:
- Collector algorithms are split by strategy, e.g. `TaxiAlgorithmP2PCollector+nearest`
- Non-collector algorithms are normalized to strategy `n/a`, e.g. `TaxiAlgorithmSinglePassenger`

This avoids over-interpreting `p2pStrategy` for algorithms where it has no effect.

Mass-run parameter relevance:
- `p2pStrategy`, `kHops`, `p2pOverlayMinNeighbors`, `p2pOverlayShortcuts` are only varied/applied for collector algorithms.
- For non-collector algorithms these dimensions are normalized (`strategy_effective = n/a`) to avoid artificial duplicate runs.
- `taxiCount`, `clientCount`, and `taxiSeatCount` are first-class experiment dimensions and appear in CSV, stats, and plots.

## Output

- `overview.json` – summary of rows, algorithms, metrics, and varying dimensions
- `report.html` – interactive HTML report with sidebar navigation and lightbox image viewer
- `tables/summary.csv` – per-variant mean/median/std/min/max per metric
- `tables/pivot.csv` – wide-format pivot of metric averages grouped by all parameter dimensions
- `stats/anova_results.csv` – one-way ANOVA results (F, p-value) per metric across variants

### Plot portfolio (`plots/*.png`)

| Prefix | Description |
|---|---|
| `algo_overview_<scenario>_<metric>` | Bar chart comparing all algorithm variants (with 95 % CI), one plot per spawn scenario |
| `scaling_<scenario>_<metric>` | Grid of line plots – one row per varying numeric dimension, showing how each metric scales |
| `dim_<dim>_<scenario>_<metric>` | Individual line plot for one numeric dimension effect (e.g. `taxiCount`, `kHops`) |
| `cat_<dim>_<metric>` | Grouped bar chart for categorical/discrete dimensions (e.g. `spawnScenario`, `p2pStrategy`) |
| `multi_<dimX>_x_<dimFacet>_<metric>` | Faceted line plot: effect of `dimX` with one panel per value of `dimFacet` |
| `dist_<scenario>_<metric>` | Violin + strip distribution plots per variant (multi-run data only) |
| `stability` | Mean vs. std scatter map – highlights unstable configurations (multi-run data only) |
| `3d_taxi_client_<scenario>_<metric>` | 3-D grouped bar chart with Taxi-Anzahl × Client-Anzahl as axes; all variants shown side-by-side for direct comparison |

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
- `taxiCount` and `taxiSeatCount` are first-class experiment dimensions and appear in CSV, stats, and plots.

## Output

- `overview.json`
- `report.html` (clickable overall report)
- `tables/avg_group_summary.csv`
- `tables/spread_group_summary.csv`
- `tables/avg_metric_pivot.csv`
- `stats/statistical_tests.csv`
- `stats/duplicate_diagnostics.csv` (flags identical result series)
- `plots/*.png`

Additional multidimensional plots (when dimensions vary):
- `multidim_khop_curves_*.png` (`kHops` effect with `taxiCount` interactions)
- `multidim_taxi_curves_*.png` (`taxiCount` effect with `kHops` interactions)
- `multidim_heatmap_*_*.png` (heatmaps per `algorithm_variant`)
- `multidim_bubble_*.png` (interaction bubble maps)
- `multidim_seat_effect_*.png` (only if multiple `taxiSeatCount` values are present)

All-dim visualizations (all dimensions in one plot family):
- `all_dims_parallel_*.png` (parallel coordinates over `kHops`, `taxiCount`, `taxiSeatCount`, `avg`, `spread`)
- `all_dims_facet_heatmap_*.png` (facet heatmaps across `algorithm_variant` x `taxiSeatCount`, with `taxiCount` x `kHops` cells)
- `all_dims_scatter_*.png` (`kHops` vs `taxiCount`, color=`algorithm_variant`, style=`taxiSeatCount`, size=`avg`)


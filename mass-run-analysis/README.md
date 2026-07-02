# Mass Run Analysis

Stratified analysis for UI-generated `mass-run-results.csv`.

## Run

```powershell
python mass-run-analysis/analyze_mass_run.py --input-csv mass-run-results/mass-run-results.csv --output-dir mass-run-results/analysis
```

Optional metric filter:

```powershell
python mass-run-analysis/analyze_mass_run.py --input-csv mass-run-results/mass-run-results.csv --output-dir mass-run-results/analysis --metrics "Taxi Travel Distance [m],Client Travel Time [s]"
```

If present next to the input CSV, these files are analyzed automatically:

- `mass-run-time-series.csv`
- `mass-run-requests.csv`

## Output

- `overview.json`
- `report.html`
- `tables/summary.csv`: exact config aggregates across runs
- `tables/p2p_vs_single.csv`: matched P2P vs SinglePassenger deltas
- `tables/best_p2p_vs_single.csv`: best P2P config per metric/scale/scenario
- `tables/single_passenger.csv`
- `tables/time_window_summary.csv`: tick-block load, served requests, wait, calc time
- `tables/request_tail_summary.csv`: per-request wait p50/p90/p95/max
- `stats/factor_screen.csv`
- `stats/strategy_effect.csv`
- `stats/roaming_effect.csv`
- `plots/*.png`: thesis H1-H4, time-window load, request-tail, spatial wait plots

No city/radius/roaming/topology blending in `summary.csv`.

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

- `mass-run-time-series.csv` (stream-read; 1000-tick blocks by default)
- `mass-run-request-heatmap.csv`

## Output

- `overview.json`
- `report.html`
- `tables/summary.csv`: exact config aggregates across runs
- `tables/p2p_vs_single.csv`: matched P2P vs SinglePassenger deltas
- `tables/best_p2p_vs_single.csv`: best P2P config per metric/scale/scenario
- `tables/system_comparison_summary.csv`: central and all-P2P means per city/scenario
- `tables/parameter_level_summary.csv` and `parameter_effect_summary.csv`: values and thesis contrasts for radius, hops, strategy, topology, and roaming
- `tables/interaction_k_radius_roaming.csv` and `interaction_k_shortcuts.csv`
- `tables/moderation_seed_contrasts.csv` and `moderation_summary.csv`: paired seed-level difference-in-differences and 95% confidence intervals
- `tables/crossover_summary.csv`: corridor counts, travel-time control, and seed stability
- `tables/time_window_selected_configs.csv`: scenario-specific P2P Minimax selections
- `tables/time_window_result_summary.csv`: central and selected-P2P values for the whole run and blocks 10--85
- `tables/spatial_result_summary.csv`: pickup gaps and spatial waiting-time range per roaming mode
- `tables/single_passenger.csv`
- `tables/time_window_summary.csv`: tick-block load, served requests, wait, calc time
- `tables/request_tail_summary.csv`: request count, completion ratio, wait, and travel summaries
- `stats/factor_screen.csv`
- `stats/strategy_effect.csv`
- `stats/roaming_effect.csv`
- `plots/*.png`: all six metrics, thesis H1-H4, error bars, time-window load, spatial wait/completion maps, and wait-distance trade-offs

The two auxiliary CSVs are aggregated in chunks (`100,000` rows) before plotting, so the
1.5 GB final export is not loaded into memory.

No city/radius/roaming/topology blending in `summary.csv`.

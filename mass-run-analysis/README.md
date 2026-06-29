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

## Output

- `overview.json`
- `report.html`
- `tables/summary.csv`: exact config aggregates across runs
- `tables/p2p_vs_single.csv`: matched P2P vs SinglePassenger deltas
- `tables/best_p2p_vs_single.csv`: best P2P config per metric/scale/scenario
- `tables/single_passenger.csv`
- `stats/factor_screen.csv`
- `stats/strategy_effect.csv`
- `stats/roaming_effect.csv`
- `plots/*.png`: thesis-focused H1-H4 trend plots

No city/radius/roaming/topology blending in `summary.csv`.

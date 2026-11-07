# Results

All files were produced with the code in this repository. Files marked * are inputs of the analysis scripts.

| File | Produced by | Used for |
|---|---|---|
| `compare_all_second.csv` * | `CompareRunner`, aligned layout | main comparison tables, medians, strongest-single / virtual-best comparison |
| `compare_all_first.csv` * | `CompareRunner`, headland layout | headland comparison |
| `report_2.txt` | `analysis/analyze_cpp_benchmark.py` on the aligned-layout file | win/tie/loss tables, Wilcoxon tests |
| `report_1.txt` | same, headland-layout file | headland tables |
| `summary_all.csv` | `benchmark/f2c_runner.py` | raw Fields2Cover runs including failures (robustness rates) |
| `structure_base.csv` * | `AnalysisProbe structure` | tree check, region counts, degrees, selected angles |
| `structure_fixAB.csv`, `structure_fixABT.csv` * | same with `-Dfix.width -Dfix.entries` (and `-Dfix.transit`) | effect of the optional corrections |
| `structure_full.csv` * | `AnalysisProbe structure` | boundary-pass ablation (coverage and path with and without the pass) |
| `grid_base_s5.csv` * | `AnalysisProbe grid ... 5` | region count versus path length over rotation angles |
| `alpha_base.csv` * | `AnalysisProbe alpha ... 0,2,10,50,200` | turn-penalty sensitivity |
| `orders_base.csv`, `orders_fixABT.csv` * | `AnalysisProbe orders ... 500` | greedy versus optimal visit order |
| `summary.txt` | run script | Java version, machine, row counts, failure counts |

Columns of the `AnalysisProbe` files are documented in the header line of each file; `*_bs` columns include the boundary pass and
`*_nobs` columns do not.

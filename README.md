# A Fast and Robust Coverage Path Planner for Irregular Polygonal Fields with Rotation-Optimised Decomposition

[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.24041131.svg)](https://doi.org/10.5281/zenodo.24041131)

Code, data and result files for the manuscript

> Samrat Dutta, Soumen Roy and Rajat Kumar Pal,
> *A Fast and Robust Coverage Path Planner for Irregular Polygonal Fields with Rotation-Optimised Decomposition*
> (under review).

The planner (1) chooses a rotation of the field that minimises the number of regions of a horizontal visibility
subdivision (HVS), (2) shrinks the field inwards by half the robot width, (3) decomposes it with HVS and builds a
doubly connected edge list (DCEL), (4) sequences the regions greedily on their adjacency tree (a tree by
construction), and (5) generates boustrophedon waypoints, optionally followed by one circuit of the offset-polygon
boundary (the "boundary pass"). It is compared with Fields2Cover on 299 fields of the Fields2Benchmark dataset.

## Repository layout

| Path | Content |
|---|---|
| `src/` | Java sources. Planner: `PipelineRunner`, `Polygon`, `PolygonVertex`, `PolygonGraph`, `PolygonSequencer`, `WayPointGenerator`, `DCELBuilder`, `Point`, `Edge`, `Graph`, `RunConfig`, `PipelineResult`, `MetricsCalculator`, plus helpers `EdgeGraph`, `MetaData`, `XlsxWriter`, `PolygonModifier`. Headless tools: `SingleTestProbe`, `MetricsProbe`, `AnalysisProbe`, `CompareRunner`, `BatchRunner`. Optional JavaFX/Swing GUI: `CppGuiApp`, `BatchModeWindow`, `PolygonVisualizer`, `SinglePolygonVisualizer`, `GraphGUI`, `HeatmapExporter` |
| `data/polys/` | The 299 hole-free field polygons used in the paper, see `data/README.md` |
| `benchmark/` | `f2c_runner.py`, the Python script that runs the Fields2Cover configurations |
| `results/` | Raw outputs behind the tables and figures of the paper; see `results/README.md` |
| `analysis/` | Python scripts that recompute the reported statistics from `results/` |
| `scripts/` | `build.sh`, `smoke_test.sh`, `run_all.sh` |

## Requirements
* JDK 21 (developed with Temurin 21 on macOS/arm64; also runs on Linux/x86-64)
* JTS Topology Suite `jts-core` 1.19.0 (downloaded by `scripts/build.sh`; not stored in the repository)
* Python 3.10+ with the packages in `requirements.txt` for the analysis scripts
* Only for the optional GUI: JavaFX 21 (`javafx.controls`, `javafx.swing`)
* Only for regenerating the Fields2Cover results: Fields2Cover and its Python bindings (see `benchmark/f2c_runner.py`)

## Build and quick check
```
git clone https://github.com/samratdutta2002/CPP-Algorithm.git && cd CPP-Algorithm
bash scripts/build.sh        # compiles the headless tools into ./out
bash scripts/smoke_test.sh   # re-runs three fields and compares with results/structure_full.csv (expects an exact match)
```

## Running the planner
Single field, both rotation settings, several robot widths (waypoints optionally written to a folder):
```
java -cp "out:lib/jts-core-1.19.0.jar" SingleTestProbe data/polys/ee_field_10.txt 4,8,24 0.05 [waypointsOutDir]
```
The arguments are the polygon file, a comma-separated list of robot widths (in the length unit of the polygon, metres),
and the overlap ratio. Defaults of the pipeline (`RunConfig`): turn penalty 2.0, overlap 0.05, optimal rotation on,
boundary pass on. `SingleTestProbe` prints one `RESULT` line per (width, rotation) combination.

## Analyses
`AnalysisProbe <mode> <polygonFolder> <widths> <overlap> [arg]` writes CSV to stdout (console messages of the
pipeline go to stderr):

| Mode | Output |
|---|---|
| `structure` | per (field, width, rotation): number of regions, graph degree/edges/connectivity, chosen angle, path and quality metrics with and without the boundary pass |
| `grid <stepDeg>` | forces every rotation angle on a grid and records regions and path metrics |
| `alpha <a1,a2,...>` | reruns with several turn penalties |
| `orders <maxOrders>` | compares the greedy visit order with all depth-first visit orders of small region trees |

`bash scripts/run_all.sh` regenerates all `results/*.csv` of these modes (tens of minutes on a laptop).
Optional implementation corrections, off by default so that the published numbers are reproduced:
`-Dfix.width=true`, `-Dfix.entries=true`, `-Dfix.transit=true` (see the supplementary material of the paper).

## Reproducing the Fields2Cover comparison
```
python benchmark/f2c_runner.py ...                                 # runs Fields2Cover configurations -> summary.csv + waypoints
java -cp out:lib/jts-core-1.19.0.jar CompareRunner summary.csv data/polys compare_all.csv
python analysis/analyze_cpp_benchmark.py compare_all.csv --f2c-summary results/summary_all.csv   # report + figures
```
The stored outputs are `results/compare_all_second.csv` and `results/report_2.txt` (aligned layout) and
`results/compare_all_first.csv` and `results/report_1.txt` (headland layout). `results/summary_all.csv` contains the
raw Fields2Cover summary, including its failed runs, from which the failure rates are computed.

## Reproducing the statistics of the paper from the stored results
```
pip install -r requirements.txt
python analysis/compare_f2c.py          # rotation pairs, strongest single / virtual-best Fields2Cover comparison -> results/numbers.json
python analysis/probe_results.py all    # tree check, region counts, greedy gap, alpha sensitivity, angle grid, boundary pass
```

## Reproducibility notes
* The outputs of `AnalysisProbe` are deterministic. Re-running three fields on Linux/x86-64 reproduced the stored
  macOS/arm64 results exactly (`scripts/smoke_test.sh`).
* Planning times in the paper were measured in a warmed-up JVM processing a batch of polygons, whereas Fields2Cover
  times include its Python bindings and OR-Tools route optimisation; absolute times depend on the machine.
* The comparison harness (`CompareRunner`) starts the planner at the start point supplied with each comparison row (the
  field centroid computed when the fields were converted, for the OR-Tools configurations; the first Fields2Cover waypoint
  for the `snake` configurations), whereas `AnalysisProbe` and `SingleTestProbe` start at `Polygon.computeCentroid`
  (a vertex-based centroid). The start points differ, so paths from the probes differ slightly from the rows of
  `compare_all_*.csv` (mean difference about 0.1%; aggregate conclusions are unaffected).
* The planner covers a field only after shrinking it by half the robot width; fields that split into several pieces
  are handled by keeping the largest piece (reported in the paper).

## Data and licences
* **Code:** MIT licence (`LICENSE`).
* **Data (`data/polys/`):** derived from the Fields2Benchmark dataset (Mier et al.,
  https://zenodo.org/records/14524735), which was extracted from EuroCrops for the Netherlands, Estonia and
  Lithuania. The derived files are redistributed under the licence of that dataset (CC BY-SA 4.0) with attribution;
  the changes made are described in `data/README.md` and `data/LICENSE.md`.

## Citation
Please cite the manuscript above and, for the software and data, the archived release
(https://doi.org/10.5281/zenodo.24041131, see also `CITATION.cff`). If you use the fields, please also cite
Fields2Benchmark:

> G. Mier, A. M. Casado Faulí, J. Valente and S. de Bruin, "Fields2Benchmark: An Open-Source Benchmark for Coverage
> Path Planning Methods in Agriculture," *Smart Agricultural Technology*, vol. 12, 101156, 2025,
> doi:10.1016/j.atech.2025.101156.


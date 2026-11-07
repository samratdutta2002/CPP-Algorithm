#!/bin/bash
# Rebuilds the tools and re-runs three fields; the output must match results/structure_full.csv exactly.
set -e
bash scripts/build.sh
TMP=$(mktemp -d)
for f in ee_field_10 lt_field_1 nl_field_1; do cp data/polys/$f.txt "$TMP/"; done
java -cp "out:lib/jts-core-1.19.0.jar" AnalysisProbe structure "$TMP" 4,8,24 0.05 > "$TMP/out.csv" 2> "$TMP/err.log"
python3 - "$TMP/out.csv" <<'PY'
import sys, pandas as pd
a = pd.read_csv(sys.argv[1]); b = pd.read_csv("results/structure_full.csv")
m = a.merge(b, on=["polygon", "width", "rotation"], suffixes=("_new", "_ref"))
assert len(m) == len(a) > 0, "no rows to compare"
bad = [c for c in ["k", "angle_deg", "path_bs", "path_nobs", "cov_bs", "cov_nobs", "turns_bs"]
       if ((m[c + "_new"] - m[c + "_ref"]).abs() > 1e-6).any()]
print("compared", len(m), "rows;", "MISMATCH in " + ", ".join(bad) if bad else "all columns match exactly")
sys.exit(1 if bad else 0)
PY

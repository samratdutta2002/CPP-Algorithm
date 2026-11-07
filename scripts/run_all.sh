#!/bin/bash
# Regenerates results/*.csv (run from the repository root after scripts/build.sh).
CP="out:lib/jts-core-1.19.0.jar"
POLYS="data/polys"
W="2,4,8,12,18,24"
OV="0.05"
mkdir -p results
run() { # name, extra java flags..., mode, arg
  name=$1; shift
  java -Xmx3g "$@" > results/$name.csv 2> results/$name.log
}
run structure_base    -cp "$CP" AnalysisProbe structure $POLYS $W $OV
run structure_fixAB   -Dfix.width=true -Dfix.entries=true -cp "$CP" AnalysisProbe structure $POLYS $W $OV
run structure_fixABT  -Dfix.width=true -Dfix.entries=true -Dfix.transit=true -cp "$CP" AnalysisProbe structure $POLYS $W $OV
run grid_base_s5      -cp "$CP" AnalysisProbe grid $POLYS $W $OV 5
run alpha_base        -cp "$CP" AnalysisProbe alpha $POLYS $W $OV 0,2,10,50,200
run orders_base       -cp "$CP" AnalysisProbe orders $POLYS $W $OV 500
run orders_fixABT     -Dfix.width=true -Dfix.entries=true -Dfix.transit=true -cp "$CP" AnalysisProbe orders $POLYS $W $OV 500
echo DONE

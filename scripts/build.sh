#!/bin/bash
# Compiles the planner and the probes into ./out (run from the repository root).
set -e
JTS=lib/jts-core-1.19.0.jar
if [ ! -f "$JTS" ]; then
  mkdir -p lib
  curl -L -o "$JTS" https://repo1.maven.org/maven2/org/locationtech/jts/jts-core/1.19.0/jts-core-1.19.0.jar
fi
mkdir -p out
javac -cp "$JTS" -sourcepath src -d out src/AnalysisProbe.java src/SingleTestProbe.java src/MetricsProbe.java
echo "built into ./out"

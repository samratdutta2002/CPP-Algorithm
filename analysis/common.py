"""Shared loading code for the analysis scripts.

Expected layout (relative to the repository root):
  results/compare_all_first.csv    headland-layout comparison rows (both methods succeeded)
  results/compare_all_second.csv   aligned-layout comparison rows
Set RESULTS_DIR to use another folder.
"""
import os
import numpy as np
import pandas as pd

RES = os.environ.get("RESULTS_DIR", "results")


def load(fname):
    d = pd.read_csv(os.path.join(RES, fname))
    for c in ["java_ok", "f2c_ok"]:
        d[c] = d[c].astype(str).str.lower() == "true"
    d["split"] = d["java_polygon_was_split"].astype(str).str.lower() == "true"
    d["java_rotation"] = d["java_rotation"].astype(str).str.lower() == "true"
    return d


def load_both():
    return load("compare_all_second.csv"), load("compare_all_first.csv")   # aligned, headland

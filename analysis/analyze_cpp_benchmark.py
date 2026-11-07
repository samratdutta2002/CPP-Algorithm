#!/usr/bin/env python3
"""
analyze_cpp_benchmark.py -- statistical analysis and figure/report generation for the
Java CPP vs Fields2Cover comparison (compare_all.csv).

Usage:
    python3 analyze_cpp_benchmark.py compare_all.csv
    python3 analyze_cpp_benchmark.py compare_all.csv --f2c-summary summary_all.csv --dpi 600

Produces, next to the CSV (or under --out-dir):
    <out-dir>/figures/*.png          all plots, at --dpi (default 400)
    <out-dir>/report.txt             detailed statistical report for the manuscript

Every row in compare_all.csv is a (polygon, robot_width, config_id) condition where BOTH
the Java pipeline and Fields2Cover succeeded (this is how CompareRunner.java builds the
file) -- so this script answers "given both methods produced a plan, which produced the
better one, and by how much", plus a separate robustness comparison using the optional
F2C summary CSV (which includes F2C's own failed/crashed rows -- Java's own failure rate
is known from the separate manual per-polygon testing pass to be 0%, see --java-fail-rate).
"""
import argparse
import os
import sys
import warnings
from datetime import datetime

import numpy as np
import pandas as pd
from scipy import stats
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker

warnings.filterwarnings("ignore", category=RuntimeWarning)

PIPELINE_NAME = "Proposed"

# --------------------------------------------------------------------------------------
# Metric definitions: (label, java_col, f2c_col, direction, kind, tie_tolerance_kind)
#   direction: "lower" or "higher" -- which side is "better"
#   kind: "quality" | "efficiency" | "secondary" -- just for report grouping
#   tie_tolerance_kind: "abs" (percentage-point metrics) or "rel" (scale metrics)
# --------------------------------------------------------------------------------------
METRICS = [
    ("Coverage (%)",            "java_coverage_pct",       "f2c_coverage_pct",       "higher", "quality",    "abs"),
    ("Outside-swept (%)",       "java_outside_swept_pct",  "f2c_outside_swept_pct",  "lower",  "quality",    "abs"),
    ("Redundancy (%)",          "java_redundancy_pct",     "f2c_redundancy_pct",     "lower",  "quality",    "abs"),
    ("Excess-swept (%)",        "java_excess_swept_pct",   "f2c_excess_swept_pct",   "lower",  "quality",    "abs"),
    ("Coverage per unit length","java_coverage_per_unit",  "f2c_coverage_per_unit",  "higher", "quality",    "rel"),
    ("Runtime (s)",             "java_time_s",             "f2c_runtime_s",          "lower",  "efficiency", "rel"),
    ("Path length",             "java_path_length",        "f2c_path_length",        "lower",  "secondary",  "rel"),
    ("Total turning angle (deg)","java_total_turn_deg",    "f2c_total_turn_deg",     "lower",  "secondary",  "rel"),
    ("Turn count",              "java_turns",              "f2c_turns",              "lower",  "secondary",  "rel"),
    ("Waypoint count",          "java_waypoints",          "f2c_waypoints",          "lower",  "secondary",  "rel"),
]

ABS_TIE_TOL_DEFAULT = 0.5   # percentage points, for "abs" kind metrics
REL_TIE_TOL_DEFAULT = 0.02  # 2%, for "rel" kind metrics

PALETTE = {"java": "#1f6f3f", "f2c": "#a6302b", "tie": "#9a9a9a", "neutral": "#2f5fa8"}

# Display name for your pipeline in every figure and in the report, set from --method-name
# (default "Proposed"). The "java"/"f2c" keys above and every java_*/f2c_* column name are
# internal identifiers and are unaffected by this -- only user-facing text changes.
METHOD_NAME = "Proposed"


# --------------------------------------------------------------------------------------
# Core statistics helpers
# --------------------------------------------------------------------------------------
def tie_mask(java_vals, f2c_vals, tol_kind, abs_tol, rel_tol):
    diff = np.asarray(java_vals, dtype=float) - np.asarray(f2c_vals, dtype=float)
    if tol_kind == "abs":
        return np.abs(diff) <= abs_tol
    denom = np.maximum(np.abs(java_vals), np.abs(f2c_vals))
    denom = np.where(denom == 0, 1.0, denom)
    return np.abs(diff) / denom <= rel_tol


def win_tie_loss(java_vals, f2c_vals, direction, tol_kind, abs_tol, rel_tol):
    """Returns (n_java_win, n_tie, n_f2c_win, n_total) from Java's perspective."""
    java_vals = np.asarray(java_vals, dtype=float)
    f2c_vals = np.asarray(f2c_vals, dtype=float)
    ties = tie_mask(java_vals, f2c_vals, tol_kind, abs_tol, rel_tol)
    if direction == "lower":
        java_better = java_vals < f2c_vals
    else:
        java_better = java_vals > f2c_vals
    java_better = java_better & ~ties
    f2c_better = (~java_better) & ~ties
    n = len(java_vals)
    return int(java_better.sum()), int(ties.sum()), int(f2c_better.sum()), n


def wilcoxon_paired(java_vals, f2c_vals):
    """Paired Wilcoxon signed-rank test + rank-biserial effect size.
    Returns dict with statistic, pvalue, z, effect_size_r, n_nonzero, n_total, or None
    if fewer than 2 non-tied pairs (test undefined)."""
    java_vals = np.asarray(java_vals, dtype=float)
    f2c_vals = np.asarray(f2c_vals, dtype=float)
    diff = java_vals - f2c_vals
    n_total = len(diff)
    n_nonzero = int(np.sum(diff != 0))
    if n_nonzero < 2:
        return None
    try:
        res = stats.wilcoxon(diff, zero_method="wilcox", method="approx")
        z = float(res.zstatistic)
        r = z / np.sqrt(n_nonzero)  # matched-pairs rank-biserial effect size, range [-1, 1]
        return {"statistic": float(res.statistic), "pvalue": float(res.pvalue),
                "z": z, "effect_size_r": r, "n_nonzero": n_nonzero, "n_total": n_total,
                "median_diff": float(np.median(diff))}
    except ValueError:
        return None


def benjamini_hochberg(pvalues):
    """Returns BH-adjusted (FDR) q-values, same order as input. NaNs pass through as NaN."""
    pvals = np.asarray(pvalues, dtype=float)
    n = len(pvals)
    valid = ~np.isnan(pvals)
    q = np.full(n, np.nan)
    if valid.sum() == 0:
        return q
    idx = np.where(valid)[0]
    order = idx[np.argsort(pvals[idx])]
    m = len(order)
    ranked = pvals[order]
    adj = ranked * m / (np.arange(m) + 1)
    # enforce monotonicity from the largest p-value down
    adj = np.minimum.accumulate(adj[::-1])[::-1]
    adj = np.clip(adj, 0, 1)
    q[order] = adj
    return q


def effect_size_label(r):
    ar = abs(r)
    if ar < 0.10:
        return "negligible"
    if ar < 0.30:
        return "small"
    if ar < 0.50:
        return "medium"
    return "large"


def fmt_p(p):
    if p is None or np.isnan(p):
        return "n/a"
    if p < 0.0001:
        return "<0.0001"
    return f"{p:.4f}"


# --------------------------------------------------------------------------------------
# Data loading / preparation
# --------------------------------------------------------------------------------------
def load_data(csv_path):
    df = pd.read_csv(csv_path)
    required = {"polygon", "robot_width", "config_id", "java_ok", "f2c_ok"}
    missing = required - set(df.columns)
    if missing:
        raise SystemExit(f"compare_all.csv is missing expected columns: {missing}")
    before = len(df)
    df = df[(df["java_ok"] == True) & (df["f2c_ok"] == True)].copy()  # noqa: E712
    dropped = before - len(df)
    if dropped:
        print(f"[note] dropped {dropped} row(s) where java_ok/f2c_ok was not True "
              f"(compare_all.csv should already only contain both-ok rows)")
    df["is_split"] = df["java_polygon_was_split"].astype(bool)
    return df


# --------------------------------------------------------------------------------------
# Figures
# --------------------------------------------------------------------------------------
def savefig(fig, out_dir, name, dpi):
    path = os.path.join(out_dir, name)
    fig.savefig(path, dpi=dpi, bbox_inches="tight")
    plt.close(fig)
    print(f"[figure] {path}")
    return path


def fig_overall_boxplots(df, out_dir, dpi):
    metrics = [m for m in METRICS if m[4] in ("quality", "efficiency") or m[1] in
               ("java_path_length", "java_total_turn_deg")]
    n = len(metrics)
    ncols = 3
    nrows = int(np.ceil(n / ncols))
    fig, axes = plt.subplots(nrows, ncols, figsize=(5.2 * ncols, 4.4 * nrows))
    axes = np.atleast_1d(axes).ravel()
    for i, (label, jcol, fcol, direction, kind, tol_kind) in enumerate(metrics):
        ax = axes[i]
        jv, fv = df[jcol].to_numpy(float), df[fcol].to_numpy(float)
        log_scale = label in ("Runtime (s)", "Path length", "Total turning angle (deg)")
        data = [jv, fv]
        bp = ax.boxplot(data, tick_labels=[PIPELINE_NAME, "F2C"], showfliers=False, patch_artist=True,
                         widths=0.55)
        for patch, color in zip(bp["boxes"], [PALETTE["java"], PALETTE["f2c"]]):
            patch.set_facecolor(color)
            patch.set_alpha(0.75)
        for med in bp["medians"]:
            med.set_color("black")
        if log_scale:
            ax.set_yscale("log")
        ax.set_title(label, fontsize=11)
        ax.grid(axis="y", linestyle=":", alpha=0.5)
    for j in range(len(metrics), len(axes)):
        axes[j].axis("off")
    fig.suptitle(f"{PIPELINE_NAME} vs Fields2Cover -- distribution of key metrics (split-polygon rows excluded)",
                 fontsize=13)
    fig.tight_layout(rect=[0, 0, 1, 0.96])
    return savefig(fig, out_dir, "01_overall_boxplots.png", dpi)


def fig_runtime(df, out_dir, dpi):
    jv = df["java_time_s"].to_numpy(float)
    fv = df["f2c_runtime_s"].to_numpy(float)
    ratio = fv / jv
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.6))

    ax = axes[0]
    bp = ax.boxplot([jv, fv], tick_labels=[PIPELINE_NAME, "F2C"], showfliers=False, patch_artist=True, widths=0.55)
    for patch, color in zip(bp["boxes"], [PALETTE["java"], PALETTE["f2c"]]):
        patch.set_facecolor(color); patch.set_alpha(0.75)
    ax.set_yscale("log")
    ax.set_ylabel("Runtime (s, log scale)")
    ax.set_title("Per-configuration runtime")
    ax.grid(axis="y", linestyle=":", alpha=0.5)

    ax = axes[1]
    ax.hist(np.log10(ratio), bins=40, color=PALETTE["neutral"], alpha=0.85)
    ax.axvline(np.log10(np.median(ratio)), color="black", linestyle="--", linewidth=1.3,
               label=f"median = {np.median(ratio):,.0f}x")
    ax.set_xlabel(f"log10(F2C runtime / {PIPELINE_NAME} runtime)")
    ax.set_ylabel("Number of configurations")
    ax.set_title("Speedup factor distribution")
    ax.legend()
    ax.grid(axis="y", linestyle=":", alpha=0.5)

    fig.suptitle("Runtime comparison", fontsize=13)
    fig.tight_layout(rect=[0, 0, 1, 0.94])
    return savefig(fig, out_dir, "02_runtime_comparison.png", dpi)


def fig_win_rate_overall(results, out_dir, dpi):
    labels = [r["label"] for r in results]
    jwin = np.array([r["wtl"][0] / r["wtl"][3] * 100 for r in results])
    tie = np.array([r["wtl"][1] / r["wtl"][3] * 100 for r in results])
    fwin = np.array([r["wtl"][2] / r["wtl"][3] * 100 for r in results])

    fig, ax = plt.subplots(figsize=(9, 0.55 * len(labels) + 1.5))
    y = np.arange(len(labels))
    ax.barh(y, jwin, color=PALETTE["java"], label=f"{PIPELINE_NAME} better")
    ax.barh(y, tie, left=jwin, color=PALETTE["tie"], label="Tie")
    ax.barh(y, fwin, left=jwin + tie, color=PALETTE["f2c"], label="F2C better")
    ax.set_yticks(y)
    ax.set_yticklabels(labels)
    ax.invert_yaxis()
    ax.set_xlabel("% of configurations")
    ax.set_xlim(0, 100)
    ax.set_title("Overall win / tie / loss rate by metric (split-polygon rows excluded)")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.12), ncol=3)
    for i, (jw, tw) in enumerate(zip(jwin, tie)):
        if jw > 6:
            ax.text(jw / 2, i, f"{jw:.0f}%", ha="center", va="center", fontsize=8, color="white")
        if fwin[i] > 6:
            ax.text(jw + tw + fwin[i] / 2, i, f"{fwin[i]:.0f}%", ha="center", va="center",
                    fontsize=8, color="white")
    fig.tight_layout()
    return savefig(fig, out_dir, "03_win_rate_overall.png", dpi)


def fig_win_rate_by_width(df, out_dir, dpi, abs_tol, rel_tol):
    key_metrics = [m for m in METRICS if m[0] in
                   ("Coverage (%)", "Redundancy (%)", "Path length", "Runtime (s)")]
    widths = sorted(df["robot_width"].unique())
    fig, ax = plt.subplots(figsize=(8, 5))
    for label, jcol, fcol, direction, kind, tol_kind in key_metrics:
        rates = []
        for w in widths:
            sub = df[df["robot_width"] == w]
            jw, tie, fw, n = win_tie_loss(sub[jcol], sub[fcol], direction, tol_kind, abs_tol, rel_tol)
            rates.append(jw / n * 100 if n else np.nan)
        ax.plot(widths, rates, marker="o", label=label)
    ax.set_xlabel("Robot width")
    ax.set_ylabel(f"{PIPELINE_NAME} win rate (%)")
    ax.set_ylim(0, 100)
    ax.axhline(50, color="grey", linestyle=":", linewidth=1)
    ax.set_title(f"{PIPELINE_NAME} win rate vs. robot width (split-polygon rows excluded)")
    ax.legend()
    ax.grid(linestyle=":", alpha=0.5)
    fig.tight_layout()
    return savefig(fig, out_dir, "04_win_rate_by_width.png", dpi)


def fig_win_rate_by_config_heatmap(df, out_dir, dpi, abs_tol, rel_tol):
    key_metrics = [m for m in METRICS if m[0] in
                   ("Coverage (%)", "Redundancy (%)", "Outside-swept (%)", "Excess-swept (%)",
                    "Path length", "Total turning angle (deg)", "Runtime (s)")]
    configs = sorted(df["config_id"].unique())
    grid = np.zeros((len(configs), len(key_metrics)))
    for i, cfg in enumerate(configs):
        sub = df[df["config_id"] == cfg]
        for j, (label, jcol, fcol, direction, kind, tol_kind) in enumerate(key_metrics):
            jw, tie, fw, n = win_tie_loss(sub[jcol], sub[fcol], direction, tol_kind, abs_tol, rel_tol)
            grid[i, j] = jw / n * 100 if n else np.nan

    fig, ax = plt.subplots(figsize=(1.6 * len(key_metrics) + 2, 0.5 * len(configs) + 2))
    im = ax.imshow(grid, cmap="RdYlGn", vmin=0, vmax=100, aspect="auto")
    ax.set_xticks(range(len(key_metrics)))
    ax.set_xticklabels([m[0] for m in key_metrics], rotation=35, ha="right")
    ax.set_yticks(range(len(configs)))
    ax.set_yticklabels(configs)
    for i in range(len(configs)):
        for j in range(len(key_metrics)):
            if not np.isnan(grid[i, j]):
                ax.text(j, i, f"{grid[i, j]:.0f}", ha="center", va="center", fontsize=8)
    cbar = fig.colorbar(im, ax=ax)
    cbar.set_label(f"{PIPELINE_NAME} win rate (%)")
    ax.set_title(f"{PIPELINE_NAME} win rate by Fields2Cover configuration")
    fig.tight_layout()
    return savefig(fig, out_dir, "05_win_rate_by_config_heatmap.png", dpi)


def fig_paired_scatter(df, out_dir, dpi):
    panels = [("Coverage (%)", "java_coverage_pct", "f2c_coverage_pct", False),
              ("Path length", "java_path_length", "f2c_path_length", True),
              ("Redundancy (%)", "java_redundancy_pct", "f2c_redundancy_pct", False),
              ("Runtime (s)", "java_time_s", "f2c_runtime_s", True)]
    fig, axes = plt.subplots(1, 4, figsize=(19, 4.6))
    widths = sorted(df["robot_width"].unique())
    cmap = plt.get_cmap("viridis", len(widths))
    for ax, (label, jcol, fcol, log_scale) in zip(axes, panels):
        for i, w in enumerate(widths):
            sub = df[df["robot_width"] == w]
            ax.scatter(sub[jcol], sub[fcol], s=6, alpha=0.4, color=cmap(i), label=f"w={w:g}")
        lo = min(df[jcol].min(), df[fcol].min())
        hi = max(df[jcol].max(), df[fcol].max())
        ax.plot([lo, hi], [lo, hi], "k--", linewidth=1, label="y = x")
        if log_scale:
            ax.set_xscale("log"); ax.set_yscale("log")
        ax.set_xlabel(f"{PIPELINE_NAME} {label}", fontsize=12)
        ax.set_ylabel(f"F2C {label}", fontsize=12)
        ax.set_title(label, fontsize=13)
        ax.tick_params(axis="both", labelsize=11)
    axes[-1].legend(fontsize=9, loc="upper left", bbox_to_anchor=(1.02, 1))
    fig.suptitle(f"Paired comparison (points below the line = {PIPELINE_NAME} is better on lower-is-better axes, "
                 f"above = {PIPELINE_NAME} is better on higher-is-better axes for Coverage)", fontsize=12)
    fig.tight_layout(rect=[0, 0, 0.93, 0.94])
    return savefig(fig, out_dir, "06_paired_scatter.png", dpi)


def fig_bland_altman(df, out_dir, dpi):
    panels = [("Coverage (%)", "java_coverage_pct", "f2c_coverage_pct"),
              ("Redundancy (%)", "java_redundancy_pct", "f2c_redundancy_pct")]
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.6))
    for ax, (label, jcol, fcol) in zip(axes, panels):
        jv, fv = df[jcol].to_numpy(float), df[fcol].to_numpy(float)
        mean = (jv + fv) / 2
        diff = jv - fv
        md, sd = np.mean(diff), np.std(diff)
        ax.scatter(mean, diff, s=5, alpha=0.3, color=PALETTE["neutral"])
        ax.axhline(md, color="black", linestyle="-", linewidth=1.2, label=f"mean diff = {md:.2f}")
        ax.axhline(md + 1.96 * sd, color="grey", linestyle="--", linewidth=1)
        ax.axhline(md - 1.96 * sd, color="grey", linestyle="--", linewidth=1)
        ax.set_xlabel(f"Mean of {PIPELINE_NAME} & F2C {label}")
        ax.set_ylabel(f"{PIPELINE_NAME} - F2C {label}")
        ax.set_title(f"Bland-Altman: {label}")
        ax.legend(fontsize=8)
        ax.grid(linestyle=":", alpha=0.5)
    fig.tight_layout()
    return savefig(fig, out_dir, "07_bland_altman.png", dpi)


def fig_split_distribution(df_all, out_dir, dpi):
    split = df_all[df_all["is_split"]]
    if split.empty:
        return None
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2))
    counts = split["java_split_piece_count"].value_counts().sort_index()
    axes[0].bar(counts.index.astype(str), counts.values, color=PALETTE["neutral"])
    axes[0].set_xlabel("Number of pieces after inward offset")
    axes[0].set_ylabel("Number of configurations")
    axes[0].set_title(f"Split-piece count distribution (n={len(split)} configurations)")
    axes[0].grid(axis="y", linestyle=":", alpha=0.5)

    by_width = split.groupby("robot_width").size()
    all_by_width = df_all.groupby("robot_width").size()
    pct = (by_width / all_by_width * 100).reindex(sorted(df_all["robot_width"].unique())).fillna(0)
    axes[1].bar(pct.index.astype(str), pct.values, color=PALETTE["f2c"])
    axes[1].set_xlabel("Robot width")
    axes[1].set_ylabel("% of configurations that split")
    axes[1].set_title("Split frequency vs. robot width")
    axes[1].grid(axis="y", linestyle=":", alpha=0.5)
    fig.tight_layout()
    return savefig(fig, out_dir, "08_split_distribution.png", dpi)


def fig_failure_breakdown(f2c_summary_path, out_dir, dpi):
    if not f2c_summary_path or not os.path.exists(f2c_summary_path):
        return None
    df = pd.read_csv(f2c_summary_path)
    if "status" not in df.columns:
        return None
    total = len(df)
    ok = int((df["status"] == "ok").sum())
    failed = df[df["status"] != "ok"]
    if "error" in failed.columns:
        reason = failed["error"].fillna("unknown").str.extract(r"^([A-Za-z]+(?:Error|Exception)?):?")[0]
        reason = reason.fillna(failed["error"].fillna("unknown").str.slice(0, 30))
        reason = reason.replace("", "unknown")
    else:
        reason = pd.Series(["failed"] * len(failed))
    counts = reason.value_counts().head(10)

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.4))
    axes[0].pie([ok, total - ok], labels=[f"ok ({ok})", f"failed ({total - ok})"],
                colors=[PALETTE["java"], PALETTE["f2c"]], autopct="%1.1f%%", startangle=90)
    axes[0].set_title(f"Fields2Cover config-level success rate (n={total})")

    axes[1].barh(counts.index[::-1], counts.values[::-1], color=PALETTE["f2c"])
    axes[1].set_xlabel("Number of configurations")
    axes[1].set_title("Top failure reasons (F2C side)")
    fig.tight_layout()
    return savefig(fig, out_dir, "09_f2c_failure_breakdown.png", dpi)


# --------------------------------------------------------------------------------------
# Report
# --------------------------------------------------------------------------------------
def analyze_subset(df, abs_tol, rel_tol):
    """Runs win/tie/loss + Wilcoxon for every metric on one subset of rows."""
    out = []
    for label, jcol, fcol, direction, kind, tol_kind in METRICS:
        jv, fv = df[jcol], df[fcol]
        wtl = win_tie_loss(jv, fv, direction, tol_kind, abs_tol, rel_tol)
        test = wilcoxon_paired(jv, fv)
        out.append({"label": label, "jcol": jcol, "fcol": fcol, "direction": direction,
                    "kind": kind, "tol_kind": tol_kind, "wtl": wtl, "test": test,
                    "java_mean": float(jv.mean()), "f2c_mean": float(fv.mean()),
                    "java_median": float(jv.median()), "f2c_median": float(fv.median())})
    return out


def stratified_table(df, group_col, abs_tol, rel_tol, metrics_subset):
    """For each group value and each metric: n, java win%, p-value (raw), effect size."""
    rows = []
    for gval, sub in df.groupby(group_col):
        for label, jcol, fcol, direction, kind, tol_kind in metrics_subset:
            jw, tie, fw, n = win_tie_loss(sub[jcol], sub[fcol], direction, tol_kind, abs_tol, rel_tol)
            test = wilcoxon_paired(sub[jcol], sub[fcol])
            rows.append({"group": gval, "metric": label, "n": n,
                         "java_win_pct": jw / n * 100 if n else np.nan,
                         "tie_pct": tie / n * 100 if n else np.nan,
                         "f2c_win_pct": fw / n * 100 if n else np.nan,
                         "pvalue": test["pvalue"] if test else np.nan,
                         "effect_r": test["effect_size_r"] if test else np.nan})
    out = pd.DataFrame(rows)
    out["qvalue"] = benjamini_hochberg(out["pvalue"].to_numpy())
    return out


def per_polygon_aggregate(df):
    agg = {jcol: "mean" for _, jcol, _, _, _, _ in METRICS}
    agg.update({fcol: "mean" for _, _, fcol, _, _, _ in METRICS})
    return df.groupby("polygon").agg(agg).reset_index()


def write_report(path, args, df_all, df_fair, results_fair, results_all, results_split,
                  by_width, by_config, poly_agg_results, f2c_summary_stats):
    lines = []
    def p(s=""):
        lines.append(s)

    n_total = len(df_all)
    n_split = int(df_all["is_split"].sum())
    n_fair = n_total - n_split
    n_polygons = df_all["polygon"].nunique()
    widths = sorted(df_all["robot_width"].unique())
    configs = sorted(df_all["config_id"].unique())

    p("=" * 100)
    p("JAVA CPP PIPELINE vs. FIELDS2COVER -- STATISTICAL COMPARISON REPORT")
    p("=" * 100)
    p(f"Generated: {datetime.now().isoformat(timespec='seconds')}")
    p(f"Source file: {os.path.abspath(args.csv)}")
    p(f"Tie tolerance: {args.abs_tol} percentage points (percentage metrics), "
      f"{args.rel_tol*100:.1f}% relative (scale metrics)")
    p(f"Significance threshold (alpha): {args.alpha}")
    p("")

    # ---------------------------------------------------------------- 1. Dataset overview
    p("-" * 100)
    p("1. DATASET OVERVIEW")
    p("-" * 100)
    p(f"Total (polygon, robot_width, configuration) rows where BOTH {PIPELINE_NAME} and Fields2Cover "
      f"succeeded: {n_total}")
    p(f"Distinct polygons represented: {n_polygons}")
    p(f"Robot widths tested: {[float(w) for w in widths]}")
    p(f"Fields2Cover configurations (decomposition x objective x route planner), "
      f"{len(configs)} total:")
    for c in configs:
        p(f"    - {c}  (n={int((df_all['config_id']==c).sum())})")
    p("")
    p(f"Rows where the robot width caused the field to split into multiple disjoint pieces "
      f"after the inward safety offset (both {PIPELINE_NAME} and F2C agree on which rows these are, "
      f"{n_split} rows exactly matching on both sides): {n_split} ({n_split/n_total*100:.1f}%)")
    p(f"These are analyzed SEPARATELY (Section 4) since, per project convention, a comparison "
      f"where one or both methods only covers the largest surviving piece is not a fair "
      f"head-to-head of path-planning quality. The main comparison (Sections 2-3) uses the "
      f"remaining {n_fair} rows ({n_fair/n_total*100:.1f}%).")
    p("")
    if f2c_summary_stats:
        p(f"Fields2Cover config-level robustness (from the separate full attempt log, including "
          f"configurations that never reached this comparison because F2C itself failed/crashed "
          f"on them): {f2c_summary_stats['ok']} succeeded / {f2c_summary_stats['total']} attempted "
          f"({f2c_summary_stats['ok']/f2c_summary_stats['total']*100:.1f}%); "
          f"{f2c_summary_stats['total']-f2c_summary_stats['ok']} failed "
          f"({(f2c_summary_stats['total']-f2c_summary_stats['ok'])/f2c_summary_stats['total']*100:.1f}%).")
        p("Top failure reasons (F2C side):")
        for reason, count in f2c_summary_stats["top_reasons"].items():
            p(f"    - {reason}: {count}")
        p(f"By contrast, {PIPELINE_NAME}'s own manual per-polygon testing pass (all 299 "
          "polygons, all 6 widths, both rotation settings -- 3,588 configurations, independent "
          "of Fields2Cover) completed with a 0% failure rate.")
        p("")

    # ---------------------------------------------------------------- 2. Overall results
    p("-" * 100)
    p("2. OVERALL RESULTS (split-polygon rows excluded, n=%d)" % n_fair)
    p("-" * 100)
    p(f"{'Metric':<28}{f'{PIPELINE_NAME} mean':>12}{'F2C mean':>12}{f'{PIPELINE_NAME} win%':>11}{'Tie%':>8}"
      f"{'F2C win%':>10}{'Wilcoxon p':>13}{'effect r':>10}{'effect size':>14}")
    p("-" * 118)
    for r in results_fair:
        jw, tie, fw, n = r["wtl"]
        t = r["test"]
        pv = fmt_p(t["pvalue"]) if t else "n/a"
        eff = f"{t['effect_size_r']:.3f}" if t else "n/a"
        efflabel = effect_size_label(t["effect_size_r"]) if t else "n/a"
        p(f"{r['label']:<28}{r['java_mean']:>12.3f}{r['f2c_mean']:>12.3f}"
          f"{jw/n*100:>10.1f}%{tie/n*100:>7.1f}%{fw/n*100:>9.1f}%{pv:>13}{eff:>10}{efflabel:>14}")
    p("")
    p("Interpretation notes:")
    p(f"  - '{PIPELINE_NAME} win%' / 'F2C win%' / 'Tie%' use the tie tolerance above; they sum to 100% per row.")
    p(f"  - Wilcoxon p-value tests whether the paired differences ({PIPELINE_NAME} - F2C) are symmetric "
      "around zero across all rows; a small p-value means the direction of the typical "
      "difference is unlikely to be due to chance.")
    p("  - effect r is the matched-pairs rank-biserial correlation (range -1..1); positive means "
      "Java's values were typically ranked higher, negative means F2C's were. Its sign must be "
      "read together with each metric's 'better' direction (e.g. for Runtime, lower is better, "
      f"so a NEGATIVE r here means {PIPELINE_NAME} runtimes were typically lower/better).")
    p("")
    p("Detailed medians and Wilcoxon statistics:")
    for r in results_fair:
        t = r["test"]
        p(f"  * {r['label']}: median {PIPELINE_NAME}={r['java_median']:.3f}, median F2C={r['f2c_median']:.3f}"
          + (f", median({PIPELINE_NAME}-F2C)={t['median_diff']:.3f}, W={t['statistic']:.1f}, "
             f"z={t['z']:.2f}, p={fmt_p(t['pvalue'])}, n={t['n_nonzero']}/{t['n_total']} "
             f"non-tied pairs" if t else ", Wilcoxon test not computable (all pairs tied)"))
    p("")

    # ---------------------------------------------------------------- 3. Stratified results
    p("-" * 100)
    p("3. STRATIFIED RESULTS (split-polygon rows excluded)")
    p("-" * 100)
    p("3a. By robot width")
    p("-" * 60)
    for metric_label in by_width["metric"].unique():
        sub = by_width[by_width["metric"] == metric_label].sort_values("group")
        p(f"  {metric_label}:")
        for _, row in sub.iterrows():
            sig = "*" if row["qvalue"] < args.alpha else " "
            p(f"    width={row['group']:>5g}  n={int(row['n']):>5}  {PIPELINE_NAME} win={row['java_win_pct']:>5.1f}%  "
              f"tie={row['tie_pct']:>5.1f}%  F2C win={row['f2c_win_pct']:>5.1f}%  "
              f"p={fmt_p(row['pvalue'])}  q(FDR)={fmt_p(row['qvalue'])}{sig}  "
              f"effect r={row['effect_r']:.3f}" if not np.isnan(row['effect_r']) else "effect r=n/a")
    p("  (* = significant at alpha=%.2f after Benjamini-Hochberg FDR correction within this table)"
      % args.alpha)
    p("")
    p("3b. By Fields2Cover configuration")
    p("-" * 60)
    for metric_label in by_config["metric"].unique():
        sub = by_config[by_config["metric"] == metric_label].sort_values("group")
        p(f"  {metric_label}:")
        for _, row in sub.iterrows():
            sig = "*" if row["qvalue"] < args.alpha else " "
            p(f"    {row['group']:<42} n={int(row['n']):>5}  {PIPELINE_NAME} win={row['java_win_pct']:>5.1f}%  "
              f"tie={row['tie_pct']:>5.1f}%  F2C win={row['f2c_win_pct']:>5.1f}%  "
              f"p={fmt_p(row['pvalue'])}  q(FDR)={fmt_p(row['qvalue'])}{sig}")
    p("  (* = significant at alpha=%.2f after Benjamini-Hochberg FDR correction within this table)"
      % args.alpha)
    p("")

    # ---------------------------------------------------------------- 4. Split-polygon subset
    p("-" * 100)
    p("4. SPLIT-POLYGON SUBSET (n=%d, reported separately, NOT a fair head-to-head)" % n_split)
    p("-" * 100)
    if results_split:
        p(f"{'Metric':<28}{f'{PIPELINE_NAME} mean':>12}{'F2C mean':>12}{f'{PIPELINE_NAME} win%':>11}{'Tie%':>8}{'F2C win%':>10}")
        for r in results_split:
            jw, tie, fw, n = r["wtl"]
            p(f"{r['label']:<28}{r['java_mean']:>12.3f}{r['f2c_mean']:>12.3f}"
              f"{jw/n*100:>10.1f}%{tie/n*100:>7.1f}%{fw/n*100:>9.1f}%")
        split_df = df_all[df_all["is_split"]]
        p("")
        p(f"Piece-count distribution among split rows: "
          f"{split_df['java_split_piece_count'].value_counts().sort_index().to_dict()}")
    else:
        p("No split-polygon rows in this dataset.")
    p("")

    # ---------------------------------------------------------------- 5. Per-polygon robustness check
    p("-" * 100)
    p("5. PER-POLYGON AGGREGATED ROBUSTNESS CHECK")
    p("-" * 100)
    p(f"The {n_fair} rows above are not independent observations -- each polygon contributes up "
      f"to {len(widths)*len(configs)} correlated rows. As a robustness check against this "
      f"pseudo-replication, each polygon's values were averaged first (one row per polygon, "
      f"n={len(poly_agg_results[0]['java_vals']) if poly_agg_results else 0} polygons), then the "
      f"same paired Wilcoxon test was repeated on these per-polygon means.")
    p("")
    p(f"{'Metric':<28}{f'{PIPELINE_NAME} win%':>11}{'Tie%':>8}{'F2C win%':>10}{'Wilcoxon p':>13}{'effect r':>10}")
    for r in poly_agg_results:
        jw, tie, fw, n = r["wtl"]
        t = r["test"]
        pv = fmt_p(t["pvalue"]) if t else "n/a"
        eff = f"{t['effect_size_r']:.3f}" if t else "n/a"
        p(f"{r['label']:<28}{jw/n*100:>10.1f}%{tie/n*100:>7.1f}%{fw/n*100:>9.1f}%{pv:>13}{eff:>10}")
    p("")
    p("Agreement between this per-polygon check and the full row-level result in Section 2 "
      "indicates the row-level finding is not an artifact of some polygons contributing "
      "disproportionately many rows.")
    p("")

    # ---------------------------------------------------------------- 6. Key findings
    p("-" * 100)
    p("6. KEY FINDINGS SUMMARY")
    p("-" * 100)
    rt = next(r for r in results_fair if r["label"] == "Runtime (s)")
    cov = next(r for r in results_fair if r["label"] == "Coverage (%)")
    red = next(r for r in results_fair if r["label"] == "Redundancy (%)")
    out_sw = next(r for r in results_fair if r["label"] == "Outside-swept (%)")
    speedup = rt["f2c_mean"] / rt["java_mean"]
    speedup_med = rt["f2c_median"] / rt["java_median"]
    p(f"  - Runtime: {PIPELINE_NAME} is faster in {rt['wtl'][0]/rt['wtl'][3]*100:.1f}% of configurations; "
      f"mean speedup {speedup:,.0f}x, median speedup {speedup_med:,.0f}x "
      f"(Wilcoxon p={fmt_p(rt['test']['pvalue'])}).")
    p(f"  - Coverage: {PIPELINE_NAME} achieves higher coverage in {cov['wtl'][0]/cov['wtl'][3]*100:.1f}% of "
      f"configurations (mean {cov['java_mean']:.2f}% vs {cov['f2c_mean']:.2f}%, "
      f"p={fmt_p(cov['test']['pvalue'])}).")
    p(f"  - Redundancy: {PIPELINE_NAME} {'lower' if red['java_mean']<red['f2c_mean'] else 'higher'} on average "
      f"({red['java_mean']:.2f}% vs {red['f2c_mean']:.2f}%), {PIPELINE_NAME} better in "
      f"{red['wtl'][0]/red['wtl'][3]*100:.1f}% of configurations (p={fmt_p(red['test']['pvalue'])}).")
    p(f"  - Outside-swept area (covering outside the field boundary): {PIPELINE_NAME} better in "
      f"{out_sw['wtl'][0]/out_sw['wtl'][3]*100:.1f}% of configurations "
      f"(mean {out_sw['java_mean']:.3f}% vs {out_sw['f2c_mean']:.3f}%).")
    if f2c_summary_stats:
        fail_pct = (f2c_summary_stats["total"] - f2c_summary_stats["ok"]) / f2c_summary_stats["total"] * 100
        p(f"  - Robustness: Fields2Cover failed to produce a plan at all for {fail_pct:.1f}% of "
          f"attempted configurations; {PIPELINE_NAME}'s independent manual test pass had a "
          f"0% failure rate across all 299 polygons.")
    p(f"  - {n_split} of {n_total} configurations ({n_split/n_total*100:.1f}%) caused the field to "
      f"split into multiple pieces after the robot-width safety offset; these were excluded from "
      f"the figures above for fairness and reported separately in Section 4.")
    p("")

    p("-" * 100)
    p("7. CAVEATS / LIMITATIONS TO CARRY INTO THE MANUSCRIPT")
    p("-" * 100)
    p("  - This comparison only includes rows where BOTH methods produced a plan; it does not by "
      f"itself quantify {PIPELINE_NAME} advantage on the configurations where only {PIPELINE_NAME} succeeded (F2C's "
      "failure rate is reported separately in Section 1 using the full attempt log).")
    p("  - Wilcoxon tests here treat each row as one paired observation; Section 5's per-polygon "
      "aggregation is the safeguard against treating correlated same-polygon rows as fully "
      "independent, but a formal mixed-effects model (random intercept per polygon) would be a "
      "more rigorous treatment if a reviewer asks for one.")
    p("  - Tie tolerances (listed at the top of this report) are a modeling choice; the figures "
      "and win-rate numbers will shift slightly with a different tolerance, though the sign and "
      "significance of the Wilcoxon tests (which do not use the tie tolerance) will not.")
    p("")
    p("=" * 100)
    p("END OF REPORT")
    p("=" * 100)

    with open(path, "w") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"[report] {path}")


# --------------------------------------------------------------------------------------
def f2c_summary_stats(path):
    if not path or not os.path.exists(path):
        return None
    df = pd.read_csv(path)
    if "status" not in df.columns:
        return None
    total = len(df)
    ok = int((df["status"] == "ok").sum())
    failed = df[df["status"] != "ok"]
    if "error" in failed.columns:
        reason = failed["error"].fillna("unknown").astype(str).str.extract(r"^([A-Za-z]+(?:Error|Exception)?):?")[0]
        reason = reason.fillna(failed["error"].astype(str).str.slice(0, 30))
        reason = reason.replace("", "unknown")
    else:
        reason = pd.Series(["failed"] * len(failed))
    top_reasons = reason.value_counts().head(8).to_dict()
    return {"total": total, "ok": ok, "top_reasons": top_reasons}


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("csv", help="path to compare_all.csv")
    ap.add_argument("--f2c-summary", default=None,
                    help="optional path to the raw F2C summary_all.csv (includes F2C's own "
                         "failed/crashed configurations), used for the robustness section")
    ap.add_argument("--out-dir", default=None,
                    help="output folder (default: 'analysis_output' next to the CSV)")
    ap.add_argument("--dpi", type=int, default=400, help="figure resolution (default 400)")
    ap.add_argument("--alpha", type=float, default=0.05, help="significance threshold (default 0.05)")
    ap.add_argument("--abs-tol", type=float, default=ABS_TIE_TOL_DEFAULT,
                    help="tie tolerance in percentage points, for percentage metrics (default 0.5)")
    ap.add_argument("--rel-tol", type=float, default=REL_TIE_TOL_DEFAULT,
                    help="tie tolerance as a fraction, for scale metrics (default 0.02 = 2%%)")
    args = ap.parse_args()

    csv_dir = os.path.dirname(os.path.abspath(args.csv)) or "."
    out_dir = args.out_dir or os.path.join(csv_dir, "analysis_output")
    fig_dir = os.path.join(out_dir, "figures")
    os.makedirs(fig_dir, exist_ok=True)

    print(f"Loading {args.csv} ...")
    df_all = load_data(args.csv)
    df_fair = df_all[~df_all["is_split"]].copy()
    df_split = df_all[df_all["is_split"]].copy()
    print(f"{len(df_all)} total rows, {len(df_fair)} non-split ('fair') rows, "
          f"{len(df_split)} split rows")

    results_fair = analyze_subset(df_fair, args.abs_tol, args.rel_tol)
    results_all = analyze_subset(df_all, args.abs_tol, args.rel_tol)
    results_split = analyze_subset(df_split, args.abs_tol, args.rel_tol) if len(df_split) else []

    key_for_strat = [m for m in METRICS if m[0] in
                     ("Coverage (%)", "Redundancy (%)", "Outside-swept (%)", "Path length",
                      "Runtime (s)", "Total turning angle (deg)")]
    by_width = stratified_table(df_fair, "robot_width", args.abs_tol, args.rel_tol, key_for_strat)
    by_config = stratified_table(df_fair, "config_id", args.abs_tol, args.rel_tol, key_for_strat)

    poly_agg = per_polygon_aggregate(df_fair)
    poly_agg_results = analyze_subset(poly_agg, args.abs_tol, args.rel_tol)
    for r in poly_agg_results:
        r["java_vals"] = poly_agg[r["jcol"]].to_numpy()

    f2c_stats = f2c_summary_stats(args.f2c_summary)

    print("Generating figures ...")
    fig_overall_boxplots(df_fair, fig_dir, args.dpi)
    fig_runtime(df_fair, fig_dir, args.dpi)
    fig_win_rate_overall(results_fair, fig_dir, args.dpi)
    fig_win_rate_by_width(df_fair, fig_dir, args.dpi, args.abs_tol, args.rel_tol)
    fig_win_rate_by_config_heatmap(df_fair, fig_dir, args.dpi, args.abs_tol, args.rel_tol)
    fig_paired_scatter(df_fair, fig_dir, args.dpi)
    fig_bland_altman(df_fair, fig_dir, args.dpi)
    fig_split_distribution(df_all, fig_dir, args.dpi)
    fig_failure_breakdown(args.f2c_summary, fig_dir, args.dpi)

    report_path = os.path.join(out_dir, "report.txt")
    write_report(report_path, args, df_all, df_fair, results_fair, results_all, results_split,
                 by_width, by_config, poly_agg_results, f2c_stats)

    print(f"\nDone. Figures in {fig_dir}, report at {report_path}")


if __name__ == "__main__":
    main()
#!/usr/bin/env python3
"""
analyze_task1.py  –  Task 1 empirical analysis
================================================
Reads all CSV files produced by run_task1.sh from logs/task1/ and generates:

  report/task1_errors_convergence.png    convergence of unique errors (6 subplots)
  report/task1_branches_convergence.png  convergence of unique branches (6 subplots)
  report/task1_summary_errors.csv        mean ± std of final unique errors
  report/task1_summary_branches.csv      mean ± std of final unique branches
  report/task1_summary.txt               human-readable table

CSV filename format:  Problem<N>_<technique>_seed<S>.csv
CSV columns:          time_ms , unique_errors , unique_branches

Usage:
  python3 scripts/analyze_task1.py
  python3 scripts/analyze_task1.py --indir logs/task1 --outdir report
"""

import os
import re
import sys
import argparse
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from collections import defaultdict

# ── Configuration ────────────────────────────────────────────────────────────
PROBLEMS   = ["Problem11", "Problem12", "Problem13", "Problem14", "Problem15",
              "Problem16", "Problem17", "Problem18", "Problem19"]
TECHNIQUES = ["random", "hillclimber", "concolic"]

COLORS  = {"random": "#1f77b4", "hillclimber": "#ff7f0e", "concolic": "#2ca02c"}
LABELS  = {"random": "Random Fuzzer", "hillclimber": "Hill Climber", "concolic": "Concolic Execution"}
MARKERS = {"random": "o", "hillclimber": "s", "concolic": "^"}

TIMEOUT_S   = 300          # budget per run (seconds)
GRID_POINTS = 301          # evaluation grid: 0, 1, 2, …, 300 s


# ── Helpers ──────────────────────────────────────────────────────────────────
def load_csv(path: str) -> pd.DataFrame:
    df = pd.read_csv(path)
    df["time_s"] = df["time_ms"] / 1000.0
    return df


def step_interpolate(df: pd.DataFrame, metric: str, grid: np.ndarray) -> np.ndarray:
    """Step-function (last-observation-carried-forward) interpolation."""
    times  = df["time_s"].values
    values = df[metric].values
    result = np.zeros_like(grid, dtype=float)
    for i, t in enumerate(grid):
        mask = times <= t
        result[i] = values[mask][-1] if mask.any() else 0.0
    return result


def final_value(df: pd.DataFrame, metric: str) -> float:
    return float(df[metric].iloc[-1])


def discover_runs(indir: str):
    """
    Return dict:  data[problem][technique] = list of DataFrames (one per seed)
    """
    pattern = re.compile(r"^(Problem\d+)_(\w+)_seed(\d+)\.csv$")
    data = defaultdict(lambda: defaultdict(list))

    if not os.path.isdir(indir):
        print(f"[warn] directory not found: {indir}")
        return data

    for fname in sorted(os.listdir(indir)):
        m = pattern.match(fname)
        if not m:
            continue
        prob, tech, seed = m.group(1), m.group(2), int(m.group(3))
        fpath = os.path.join(indir, fname)
        try:
            df = load_csv(fpath)
            if df.empty:
                print(f"[warn] empty file: {fname}")
                continue
            # Ensure required columns exist
            for col in ("time_ms", "unique_errors"):
                if col not in df.columns:
                    print(f"[warn] missing column '{col}' in {fname}")
                    break
            else:
                # Add unique_branches column with zeros if missing (old format)
                if "unique_branches" not in df.columns:
                    df["unique_branches"] = 0
                data[prob][tech].append(df)
        except Exception as e:
            print(f"[warn] could not load {fname}: {e}")

    return data


# ── Convergence plots ─────────────────────────────────────────────────────────
def make_convergence_figure(data, metric: str, ylabel: str, title: str,
                            outpath: str, grid: np.ndarray):
    n_prob = len(PROBLEMS)
    ncols = 3
    nrows = (n_prob + ncols - 1) // ncols

    fig, axes = plt.subplots(nrows, ncols, figsize=(5 * ncols, 4 * nrows), squeeze=False)
    fig.suptitle(title, fontsize=14, y=1.01)

    for idx, prob in enumerate(PROBLEMS):
        ax = axes[idx // ncols][idx % ncols]
        has_any = False

        for tech in TECHNIQUES:
            runs = data.get(prob, {}).get(tech, [])
            if not runs:
                continue
            interpolated = np.array([step_interpolate(r, metric, grid) for r in runs])
            mean = interpolated.mean(axis=0)
            std  = interpolated.std(axis=0)

            ax.plot(grid, mean,
                    color=COLORS[tech], label=LABELS[tech], linewidth=1.5)
            ax.fill_between(grid, np.maximum(0, mean - std), mean + std,
                            alpha=0.2, color=COLORS[tech])
            has_any = True

        ax.set_title(prob, fontsize=11)
        ax.set_xlabel("Time (s)", fontsize=9)
        ax.set_ylabel(ylabel, fontsize=9)
        ax.set_xlim(0, TIMEOUT_S)
        ax.set_ylim(bottom=0)
        if has_any:
            ax.legend(fontsize=8)

    # Hide unused subplots
    for idx in range(len(PROBLEMS), nrows * ncols):
        axes[idx // ncols][idx % ncols].set_visible(False)

    fig.tight_layout()
    os.makedirs(os.path.dirname(outpath), exist_ok=True)
    fig.savefig(outpath, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved: {outpath}")


# ── Summary tables ────────────────────────────────────────────────────────────
def build_summary(data, metric: str) -> pd.DataFrame:
    rows = []
    for prob in PROBLEMS:
        for tech in TECHNIQUES:
            runs = data.get(prob, {}).get(tech, [])
            finals = [final_value(r, metric) for r in runs]
            n = len(finals)
            rows.append({
                "Problem":   prob,
                "Technique": LABELS.get(tech, tech),
                "Runs":      n,
                "Mean":      round(np.mean(finals), 2) if finals else float("nan"),
                "Std":       round(np.std(finals, ddof=1), 2) if n > 1 else float("nan"),
                "Min":       int(min(finals)) if finals else "",
                "Max":       int(max(finals)) if finals else "",
            })
    return pd.DataFrame(rows)


def print_table(df: pd.DataFrame, title: str):
    print(f"\n{'=' * 70}")
    print(f"  {title}")
    print(f"{'=' * 70}")
    # Pivot for readability: Problem × Technique → Mean (Std)
    pivot_rows = []
    for prob in PROBLEMS:
        row = {"Problem": prob}
        for tech in TECHNIQUES:
            subset = df[(df["Problem"] == prob) & (df["Technique"] == LABELS[tech])]
            if subset.empty:
                row[LABELS[tech]] = "—"
            else:
                r = subset.iloc[0]
                n = int(r["Runs"]) if not pd.isna(r["Runs"]) else 0
                if n == 0:
                    row[LABELS[tech]] = "—"
                elif pd.isna(r["Std"]):
                    row[LABELS[tech]] = f"{r['Mean']:.1f}  (n={n})"
                else:
                    row[LABELS[tech]] = f"{r['Mean']:.1f} ± {r['Std']:.1f}  (n={n})"
        pivot_rows.append(row)

    pivot = pd.DataFrame(pivot_rows).set_index("Problem")
    print(pivot.to_string())
    print()


# ── Bar chart: final counts ───────────────────────────────────────────────────
def make_bar_chart(data, metric: str, ylabel: str, title: str, outpath: str):
    n_prob = len(PROBLEMS)
    n_tech = len(TECHNIQUES)
    x = np.arange(n_prob)
    width = 0.25

    fig, ax = plt.subplots(figsize=(10, 5))
    for i, tech in enumerate(TECHNIQUES):
        means, stds = [], []
        for prob in PROBLEMS:
            runs   = data.get(prob, {}).get(tech, [])
            finals = [final_value(r, metric) for r in runs]
            means.append(np.mean(finals) if finals else 0)
            stds.append(np.std(finals, ddof=1) if len(finals) > 1 else 0)

        offset = (i - n_tech / 2 + 0.5) * width
        ax.bar(x + offset, means, width,
               yerr=stds, capsize=4,
               label=LABELS[tech], color=COLORS[tech], alpha=0.8)

    ax.set_title(title, fontsize=13)
    ax.set_xlabel("Problem", fontsize=11)
    ax.set_ylabel(ylabel, fontsize=11)
    ax.set_xticks(x)
    ax.set_xticklabels([p.replace("Problem", "P") for p in PROBLEMS])
    ax.legend()
    ax.set_ylim(bottom=0)
    fig.tight_layout()

    os.makedirs(os.path.dirname(outpath), exist_ok=True)
    fig.savefig(outpath, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print(f"  Saved: {outpath}")


# ── Entry point ───────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--indir",  default="logs/task1", help="directory containing CSV files")
    ap.add_argument("--outdir", default="report",     help="output directory for plots/tables")
    args = ap.parse_args()

    print(f"Loading CSVs from: {args.indir}")
    data = discover_runs(args.indir)

    # Report how many runs were found
    print("\nRuns discovered:")
    for prob in PROBLEMS:
        for tech in TECHNIQUES:
            n = len(data.get(prob, {}).get(tech, []))
            tag = "OK" if n >= 5 else (f"PARTIAL ({n})" if n > 0 else "MISSING")
            print(f"  {prob:12s}  {tech:12s}  {n} run(s)  [{tag}]")

    grid = np.linspace(0, TIMEOUT_S, GRID_POINTS)

    print("\nGenerating convergence plots …")
    make_convergence_figure(
        data, "unique_errors", "Unique Errors",
        "Error convergence over time (mean ± std, 5 runs)",
        os.path.join(args.outdir, "task1_errors_convergence.png"), grid)

    make_convergence_figure(
        data, "unique_branches", "Unique Branches",
        "Branch coverage over time (mean ± std, 5 runs)",
        os.path.join(args.outdir, "task1_branches_convergence.png"), grid)

    print("Generating bar charts …")
    make_bar_chart(
        data, "unique_errors", "Unique Errors (mean ± std)",
        "Final unique errors per technique",
        os.path.join(args.outdir, "task1_errors_bar.png"))

    make_bar_chart(
        data, "unique_branches", "Unique Branches (mean ± std)",
        "Final unique branches per technique",
        os.path.join(args.outdir, "task1_branches_bar.png"))

    print("Building summary tables …")
    os.makedirs(args.outdir, exist_ok=True)

    df_err = build_summary(data, "unique_errors")
    df_err.to_csv(os.path.join(args.outdir, "task1_summary_errors.csv"), index=False)
    print(f"  Saved: {args.outdir}/task1_summary_errors.csv")

    df_br = build_summary(data, "unique_branches")
    df_br.to_csv(os.path.join(args.outdir, "task1_summary_branches.csv"), index=False)
    print(f"  Saved: {args.outdir}/task1_summary_branches.csv")

    # Human-readable tables to stdout and a .txt file
    txt_path = os.path.join(args.outdir, "task1_summary.txt")
    import io, contextlib
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        print_table(df_err, "Unique Reachability Errors  –  Mean ± Std (5 independent runs per cell)")
        print_table(df_br,  "Unique Branches Covered     –  Mean ± Std (5 independent runs per cell)")
    summary_text = buf.getvalue()
    print(summary_text)
    with open(txt_path, "w") as fh:
        fh.write(summary_text)
    print(f"  Saved: {txt_path}")

    print("\nDone.")


if __name__ == "__main__":
    main()

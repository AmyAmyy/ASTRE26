#!/usr/bin/env python3
"""
analyze_task2.py  –  Task 2 AFL analysis (vs Task 1 techniques)
===============================================================
Reads the AFL CSVs produced by run_task2.sh from logs/task2/ together with the
random / hill-climber / concolic CSVs from logs/task1/, and produces a four-way
comparison (the Task 2 rubric asks to compare AFL *against* the Task 1
techniques, not in isolation):

  report/task2_errors_convergence.png    unique errors over time (subplots)
  report/task2_branches_convergence.png  unique branches over time
  report/task2_errors_bar.png            final unique errors per technique
  report/task2_branches_bar.png          final unique branches
  report/task2_summary_errors.csv        mean ± std of final unique errors
  report/task2_summary_branches.csv      mean ± std of final unique branches
  report/task2_summary.txt               human-readable tables

CSV filename formats:
  AFL (logs/task2):  Problem<N>_afl_seed<S>.csv
  T1  (logs/task1):  Problem<N>_<technique>_seed<S>.csv
CSV columns (both): time_ms , unique_errors , unique_branches

UNITS: AFL's "unique_branches" must be counted in the SAME RERS (line, side)
branch unit as the Task 1 techniques — not AFL's own edge-coverage map. This is
done by scripts/remap_afl_branches.py, which replays every AFL input through the
Task 1 instrumentation and rewrites logs/task2/Problem*_afl_seed*.csv.

  ⚠ Run scripts/remap_afl_branches.py BEFORE this script whenever the AFL runs
    change, otherwise the AFL branch bars/curves will be stale.

Errors are already a common unit (a crash == a reachability error).

Usage:
  python3 scripts/remap_afl_branches.py    # first: AFL coverage -> RERS units
  python3 scripts/analyze_task2.py
  python3 scripts/analyze_task2.py --task1dir logs/task1 --task2dir logs/task2 --outdir report
"""

import os
import re
import argparse
import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from collections import defaultdict

# ── Configuration ────────────────────────────────────────────────────────────
PROBLEMS = ["Problem11", "Problem12", "Problem13", "Problem14", "Problem15",
            "Problem16", "Problem17", "Problem18", "Problem19"]

# Techniques in plot order; "afl" comes from logs/task2, the rest from logs/task1.
TECHNIQUES = ["random", "hillclimber", "concolic", "afl"]

COLORS  = {"random": "#1f77b4", "hillclimber": "#ff7f0e",
           "concolic": "#2ca02c", "afl": "#d62728"}
LABELS  = {"random": "Random Fuzzer", "hillclimber": "Hill Climber",
           "concolic": "Concolic Execution", "afl": "AFL"}
MARKERS = {"random": "o", "hillclimber": "s", "concolic": "^", "afl": "D"}

TIMEOUT_S   = 300          # budget per run (seconds)
GRID_POINTS = 301          # evaluation grid: 0, 1, 2, …, 300 s


# ── Helpers ──────────────────────────────────────────────────────────────────
def load_csv(path: str) -> pd.DataFrame:
    df = pd.read_csv(path)
    df["time_s"] = df["time_ms"] / 1000.0
    return df


def step_interpolate(df: pd.DataFrame, metric: str, grid: np.ndarray) -> np.ndarray:
    """Step-function (last-observation-carried-forward) interpolation.
    For times before the first observation (e.g. AFL only reports after ~60 s),
    the value is 0, which is correct: nothing was covered/found yet."""
    times  = df["time_s"].values
    values = df[metric].values
    result = np.zeros_like(grid, dtype=float)
    for i, t in enumerate(grid):
        mask = times <= t
        result[i] = values[mask][-1] if mask.any() else 0.0
    return result


def final_value(df: pd.DataFrame, metric: str) -> float:
    return float(df[metric].iloc[-1])


def discover_runs(task1dir: str, task2dir: str):
    """Return data[problem][technique] = list of DataFrames (one per seed)."""
    data = defaultdict(lambda: defaultdict(list))
    sources = [
        (task1dir, re.compile(r"^(Problem\d+)_(random|hillclimber|concolic)_seed(\d+)\.csv$")),
        (task2dir, re.compile(r"^(Problem\d+)_(afl)_seed(\d+)\.csv$")),
    ]
    for indir, pattern in sources:
        if not os.path.isdir(indir):
            print(f"[warn] directory not found: {indir}")
            continue
        for fname in sorted(os.listdir(indir)):
            m = pattern.match(fname)
            if not m:
                continue
            prob, tech = m.group(1), m.group(2)
            try:
                df = load_csv(os.path.join(indir, fname))
                if df.empty:
                    print(f"[warn] empty file: {fname}")
                    continue
                if "unique_branches" not in df.columns:
                    df["unique_branches"] = 0
                data[prob][tech].append(df)
            except Exception as e:
                print(f"[warn] could not load {fname}: {e}")
    return data


# ── Convergence plots ─────────────────────────────────────────────────────────
def make_convergence_figure(data, metric, ylabel, title, outpath, grid):
    ncols = 3
    nrows = (len(PROBLEMS) + ncols - 1) // ncols
    fig, axes = plt.subplots(nrows, ncols, figsize=(5 * ncols, 4 * nrows), squeeze=False)
    fig.suptitle(title, fontsize=14, y=1.01)

    for idx, prob in enumerate(PROBLEMS):
        ax = axes[idx // ncols][idx % ncols]
        has_any = False
        for tech in TECHNIQUES:
            runs = data.get(prob, {}).get(tech, [])
            if not runs:
                continue
            interp = np.array([step_interpolate(r, metric, grid) for r in runs])
            mean, std = interp.mean(axis=0), interp.std(axis=0)
            ax.plot(grid, mean, color=COLORS[tech], label=LABELS[tech], linewidth=1.5)
            ax.fill_between(grid, np.maximum(0, mean - std), mean + std,
                            alpha=0.18, color=COLORS[tech])
            has_any = True
        ax.set_title(prob, fontsize=11)
        ax.set_xlabel("Time (s)", fontsize=9)
        ax.set_ylabel(ylabel, fontsize=9)
        ax.set_xlim(0, TIMEOUT_S)
        ax.set_ylim(bottom=0)
        if has_any:
            ax.legend(fontsize=8)

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
            finals = [final_value(r, metric) for r in data.get(prob, {}).get(tech, [])]
            n = len(finals)
            rows.append({
                "Problem":   prob,
                "Technique": LABELS[tech],
                "Runs":      n,
                "Mean":      round(np.mean(finals), 2) if finals else float("nan"),
                "Std":       round(np.std(finals, ddof=1), 2) if n > 1 else float("nan"),
                "Min":       int(min(finals)) if finals else "",
                "Max":       int(max(finals)) if finals else "",
            })
    return pd.DataFrame(rows)


def print_table(df: pd.DataFrame, title: str):
    print(f"\n{'=' * 90}")
    print(f"  {title}")
    print(f"{'=' * 90}")
    pivot_rows = []
    for prob in PROBLEMS:
        row = {"Problem": prob}
        for tech in TECHNIQUES:
            subset = df[(df["Problem"] == prob) & (df["Technique"] == LABELS[tech])]
            if subset.empty or int(subset.iloc[0]["Runs"]) == 0:
                row[LABELS[tech]] = "—"
            else:
                r = subset.iloc[0]
                n = int(r["Runs"])
                if pd.isna(r["Std"]):
                    row[LABELS[tech]] = f"{r['Mean']:.1f}  (n={n})"
                else:
                    row[LABELS[tech]] = f"{r['Mean']:.1f} ± {r['Std']:.1f}  (n={n})"
        pivot_rows.append(row)
    print(pd.DataFrame(pivot_rows).set_index("Problem").to_string())
    print()


# ── Bar chart ─────────────────────────────────────────────────────────────────
def make_bar_chart(data, metric, ylabel, title, outpath):
    n_tech = len(TECHNIQUES)
    x = np.arange(len(PROBLEMS))
    width = 0.8 / n_tech
    fig, ax = plt.subplots(figsize=(11, 5))
    for i, tech in enumerate(TECHNIQUES):
        means, stds = [], []
        for prob in PROBLEMS:
            finals = [final_value(r, metric) for r in data.get(prob, {}).get(tech, [])]
            means.append(np.mean(finals) if finals else 0)
            stds.append(np.std(finals, ddof=1) if len(finals) > 1 else 0)
        offset = (i - n_tech / 2 + 0.5) * width
        ax.bar(x + offset, means, width, yerr=stds, capsize=3,
               label=LABELS[tech], color=COLORS[tech], alpha=0.85)
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
    ap.add_argument("--task1dir", default="logs/task1", help="random/hillclimber/concolic CSVs")
    ap.add_argument("--task2dir", default="logs/task2", help="AFL CSVs")
    ap.add_argument("--outdir",   default="report",     help="output dir for plots/tables")
    args = ap.parse_args()

    print(f"Loading Task 1 CSVs from: {args.task1dir}")
    print(f"Loading AFL    CSVs from: {args.task2dir}")
    data = discover_runs(args.task1dir, args.task2dir)

    print("\nRuns discovered:")
    for prob in PROBLEMS:
        for tech in TECHNIQUES:
            n = len(data.get(prob, {}).get(tech, []))
            tag = "OK" if n >= 5 else (f"PARTIAL ({n})" if n > 0 else "MISSING")
            print(f"  {prob:12s}  {tech:12s}  {n} run(s)  [{tag}]")

    grid = np.linspace(0, TIMEOUT_S, GRID_POINTS)

    print("\nGenerating convergence plots …")
    make_convergence_figure(data, "unique_errors", "Unique Errors",
        "AFL vs Task 1 — error convergence (mean ± std)",
        os.path.join(args.outdir, "task2_errors_convergence.png"), grid)
    make_convergence_figure(data, "unique_branches", "Unique Branches",
        "AFL vs Task 1 — coverage over time (mean ± std)",
        os.path.join(args.outdir, "task2_branches_convergence.png"), grid)

    print("Generating bar charts …")
    make_bar_chart(data, "unique_errors", "Unique Errors (mean ± std)",
        "Final unique errors per technique",
        os.path.join(args.outdir, "task2_errors_bar.png"))
    make_bar_chart(data, "unique_branches", "Unique Branches (mean ± std)",
        "Final coverage per technique",
        os.path.join(args.outdir, "task2_branches_bar.png"))

    print("Building summary tables …")
    os.makedirs(args.outdir, exist_ok=True)
    df_err = build_summary(data, "unique_errors")
    df_err.to_csv(os.path.join(args.outdir, "task2_summary_errors.csv"), index=False)
    print(f"  Saved: {args.outdir}/task2_summary_errors.csv")
    df_br = build_summary(data, "unique_branches")
    df_br.to_csv(os.path.join(args.outdir, "task2_summary_branches.csv"), index=False)
    print(f"  Saved: {args.outdir}/task2_summary_branches.csv")

    txt_path = os.path.join(args.outdir, "task2_summary.txt")
    import io, contextlib
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        print_table(df_err, "Unique Reachability Errors  –  Mean ± Std (5 runs/cell)")
        print_table(df_br,  "Unique Branches            –  Mean ± Std (5 runs/cell)")
    summary_text = buf.getvalue()
    print(summary_text)
    with open(txt_path, "w") as fh:
        fh.write(summary_text)
    print(f"  Saved: {txt_path}")

    print("\nDone.")


if __name__ == "__main__":
    main()

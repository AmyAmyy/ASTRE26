#!/usr/bin/env python3
"""
analyze_task3.py  –  Task 3 improvement ablation analysis
=========================================================
Reads the CSVs produced by run_task3.sh from logs/task3/{base,improved}/ and
generates, for the coverage-guided concrete seeding ablation:

  report/task3_errors_convergence.png    convergence of unique errors (subplots)
  report/task3_branches_convergence.png  convergence of unique branches (subplots)
  report/task3_errors_bar.png            final unique errors, base vs improved
  report/task3_branches_bar.png          final unique branches, base vs improved
  report/task3_summary_errors.csv        mean ± std of final unique errors
  report/task3_summary_branches.csv      mean ± std of final unique branches
  report/task3_summary.txt               human-readable tables incl. delta

Layout read:  logs/task3/<config>/Problem<N>_concolic_seed<S>.csv
CSV columns:  time_ms , unique_errors , unique_branches

Usage:
  python3 scripts/analyze_task3.py
  python3 scripts/analyze_task3.py --indir logs/task3 --outdir report
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
# Same problem set as Task 1.
PROBLEMS = ["Problem11", "Problem12", "Problem13", "Problem14", "Problem15",
            "Problem17"]

# Configs are the reuseProb values, auto-discovered from logs/task3/reuse<P>/.
# CONFIGS holds the directory names ("reuse0.0", "reuse0.7", …) sorted by P;
# these are populated in main() once the input directory is known. reuseProb=0.0
# is the base (improvement disabled); the highest probability is the improvement.
CONFIGS = []
LABELS  = {}   # cfg -> human label
COLORS  = {}   # cfg -> colour
PROBS   = {}   # cfg -> float probability

# Colormap used to assign distinct colours across however many probs we sweep.
_CMAP = plt.get_cmap("viridis")

BASE_CFG = "reuse0.0"   # the base (reuseProb=0) directory name

TIMEOUT_S   = 300          # budget per run (seconds)
GRID_POINTS = 301          # evaluation grid: 0, 1, 2, …, 300 s


def discover_configs(indir: str):
    """Find logs/task3/reuse<P>/ dirs, populate CONFIGS/LABELS/COLORS/PROBS."""
    global CONFIGS
    found = []
    if os.path.isdir(indir):
        for d in os.listdir(indir):
            m = re.match(r"^reuse([0-9]*\.?[0-9]+)$", d)
            if m and os.path.isdir(os.path.join(indir, d)):
                found.append((float(m.group(1)), d))
    found.sort()  # by probability
    CONFIGS = [d for _, d in found]
    n = max(len(CONFIGS) - 1, 1)
    for i, (p, d) in enumerate(found):
        PROBS[d]  = p
        LABELS[d] = ("Base (reuseProb=0)" if p == 0.0
                     else f"reuseProb={p:g}")
        COLORS[d] = _CMAP(i / n)


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
    Return dict:  data[problem][config] = list of DataFrames (one per seed).
    Reads logs/task3/reuse<P>/Problem<N>_concolic_seed<S>.csv
    """
    pattern = re.compile(r"^(Problem\d+)_concolic_seed(\d+)\.csv$")
    data = defaultdict(lambda: defaultdict(list))

    for cfg in CONFIGS:
        cfgdir = os.path.join(indir, cfg)
        if not os.path.isdir(cfgdir):
            print(f"[warn] directory not found: {cfgdir}")
            continue

        for fname in sorted(os.listdir(cfgdir)):
            m = pattern.match(fname)
            if not m:
                continue
            prob = m.group(1)
            fpath = os.path.join(cfgdir, fname)
            try:
                df = load_csv(fpath)
                if df.empty:
                    print(f"[warn] empty file: {cfg}/{fname}")
                    continue
                if "unique_branches" not in df.columns:
                    df["unique_branches"] = 0
                data[prob][cfg].append(df)
            except Exception as e:
                print(f"[warn] could not load {cfg}/{fname}: {e}")

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

        for cfg in CONFIGS:
            runs = data.get(prob, {}).get(cfg, [])
            if not runs:
                continue
            interpolated = np.array([step_interpolate(r, metric, grid) for r in runs])
            mean = interpolated.mean(axis=0)
            std  = interpolated.std(axis=0)

            ax.plot(grid, mean,
                    color=COLORS[cfg], label=LABELS[cfg], linewidth=1.5)
            ax.fill_between(grid, np.maximum(0, mean - std), mean + std,
                            alpha=0.2, color=COLORS[cfg])
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
        for cfg in CONFIGS:
            runs = data.get(prob, {}).get(cfg, [])
            finals = [final_value(r, metric) for r in runs]
            n = len(finals)
            rows.append({
                "Problem":   prob,
                "Config":    LABELS.get(cfg, cfg),
                "Runs":      n,
                "Mean":      round(np.mean(finals), 2) if finals else float("nan"),
                "Std":       round(np.std(finals, ddof=1), 2) if n > 1 else float("nan"),
                "Min":       int(min(finals)) if finals else "",
                "Max":       int(max(finals)) if finals else "",
            })
    return pd.DataFrame(rows)


def print_table(df: pd.DataFrame, title: str):
    """Pivot: Problem × each reuseProb config, plus Δ of the best vs base."""
    print(f"\n{'=' * 78}")
    print(f"  {title}")
    print(f"{'=' * 78}")
    # The "improvement" column for the delta is the highest reuseProb config.
    best_cfg = CONFIGS[-1] if CONFIGS else None
    pivot_rows = []
    for prob in PROBLEMS:
        row = {"Problem": prob}
        means = {}
        for cfg in CONFIGS:
            subset = df[(df["Problem"] == prob) & (df["Config"] == LABELS[cfg])]
            if subset.empty or int(subset.iloc[0]["Runs"]) == 0:
                row[LABELS[cfg]] = "—"
                means[cfg] = None
            else:
                r = subset.iloc[0]
                n = int(r["Runs"])
                means[cfg] = r["Mean"]
                if pd.isna(r["Std"]):
                    row[LABELS[cfg]] = f"{r['Mean']:.1f}  (n={n})"
                else:
                    row[LABELS[cfg]] = f"{r['Mean']:.1f} ± {r['Std']:.1f}  (n={n})"
        # Delta: best (highest-prob) config minus base, when both present.
        if (best_cfg and best_cfg != BASE_CFG
                and means.get(BASE_CFG) is not None
                and means.get(best_cfg) is not None):
            delta = means[best_cfg] - means[BASE_CFG]
            row[f"Δ ({LABELS[best_cfg]}−base)"] = f"{delta:+.1f}"
        pivot_rows.append(row)

    pivot = pd.DataFrame(pivot_rows).set_index("Problem")
    print(pivot.to_string())
    print()


# ── Bar chart: final counts ───────────────────────────────────────────────────
def make_bar_chart(data, metric: str, ylabel: str, title: str, outpath: str):
    n_prob = len(PROBLEMS)
    n_cfg = max(len(CONFIGS), 1)
    x = np.arange(n_prob)
    width = 0.8 / n_cfg

    fig, ax = plt.subplots(figsize=(10, 5))
    for i, cfg in enumerate(CONFIGS):
        means, stds = [], []
        for prob in PROBLEMS:
            runs   = data.get(prob, {}).get(cfg, [])
            finals = [final_value(r, metric) for r in runs]
            means.append(np.mean(finals) if finals else 0)
            stds.append(np.std(finals, ddof=1) if len(finals) > 1 else 0)

        offset = (i - n_cfg / 2 + 0.5) * width
        ax.bar(x + offset, means, width,
               yerr=stds, capsize=4,
               label=LABELS[cfg], color=COLORS[cfg], alpha=0.85)

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
    ap.add_argument("--indir",  default="logs/task3", help="root dir with reuse<P>/ subdirs")
    ap.add_argument("--outdir", default="report",     help="output dir for plots/tables")
    args = ap.parse_args()

    discover_configs(args.indir)
    if not CONFIGS:
        print(f"[error] no reuse<P>/ directories found under {args.indir}")
        return
    print(f"Configs found: {', '.join(CONFIGS)}")
    print(f"Loading CSVs from: {args.indir}/{{{','.join(CONFIGS)}}}")
    data = discover_runs(args.indir)

    print("\nRuns discovered:")
    for prob in PROBLEMS:
        for cfg in CONFIGS:
            n = len(data.get(prob, {}).get(cfg, []))
            tag = "OK" if n >= 5 else (f"PARTIAL ({n})" if n > 0 else "MISSING")
            print(f"  {prob:12s}  {cfg:10s}  {n} run(s)  [{tag}]")

    grid = np.linspace(0, TIMEOUT_S, GRID_POINTS)

    print("\nGenerating convergence plots …")
    make_convergence_figure(
        data, "unique_errors", "Unique Errors",
        "Task 3 ablation — error convergence (mean ± std, 5 runs)",
        os.path.join(args.outdir, "task3_errors_convergence.png"), grid)

    make_convergence_figure(
        data, "unique_branches", "Unique Branches",
        "Task 3 ablation — branch coverage (mean ± std, 5 runs)",
        os.path.join(args.outdir, "task3_branches_convergence.png"), grid)

    print("Generating bar charts …")
    make_bar_chart(
        data, "unique_errors", "Unique Errors (mean ± std)",
        "Final unique errors — base vs improved",
        os.path.join(args.outdir, "task3_errors_bar.png"))

    make_bar_chart(
        data, "unique_branches", "Unique Branches (mean ± std)",
        "Final unique branches — base vs improved",
        os.path.join(args.outdir, "task3_branches_bar.png"))

    print("Building summary tables …")
    os.makedirs(args.outdir, exist_ok=True)

    df_err = build_summary(data, "unique_errors")
    df_err.to_csv(os.path.join(args.outdir, "task3_summary_errors.csv"), index=False)
    print(f"  Saved: {args.outdir}/task3_summary_errors.csv")

    df_br = build_summary(data, "unique_branches")
    df_br.to_csv(os.path.join(args.outdir, "task3_summary_branches.csv"), index=False)
    print(f"  Saved: {args.outdir}/task3_summary_branches.csv")

    txt_path = os.path.join(args.outdir, "task3_summary.txt")
    import io, contextlib
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        print_table(df_err, "Unique Reachability Errors  –  Mean ± Std (5 runs/cell)")
        print_table(df_br,  "Unique Branches Covered     –  Mean ± Std (5 runs/cell)")
    summary_text = buf.getvalue()
    print(summary_text)
    with open(txt_path, "w") as fh:
        fh.write(summary_text)
    print(f"  Saved: {txt_path}")

    print("\nDone.")


if __name__ == "__main__":
    main()

import os
import pandas as pd
import matplotlib.pyplot as plt

LOG_DIR = "logs"
OUT_DIR = "report"
PROBLEMS = [11, 12, 13, 14, 15, 17]

SERIES = [
    ("concolic", "Problem{n}_concolic.csv", "tab:blue"),
    ("AFL",      "Problem{n}_afl.csv",      "tab:red"),
]

os.makedirs(OUT_DIR, exist_ok=True)


def load(n, pattern):
    path = os.path.join(LOG_DIR, pattern.format(n=n))
    if not os.path.exists(path):
        return None
    return pd.read_csv(path)


# ---- per-problem step plots ---------------------------------------------
for n in PROBLEMS:
    fig, ax = plt.subplots(figsize=(7, 4))
    any_plotted = False
    for label, pattern, color in SERIES:
        df = load(n, pattern)
        if df is None:
            continue
        ax.step(df["time_ms"] / 1000.0, df["unique_errors"],
                where="post", label=label, color=color)
        any_plotted = True
    if not any_plotted:
        plt.close()
        continue
    ax.set_xlabel("time (s)")
    ax.set_ylabel("unique error codes")
    ax.set_title(f"Problem {n}")
    ax.set_ylim(bottom=0)
    ax.grid(True, alpha=0.3)
    ax.legend(loc="lower right")
    plt.tight_layout()
    out = f"{OUT_DIR}/Problem{n}_convergence.png"
    plt.savefig(out, dpi=150)
    plt.close()
    print(f"wrote {out}")

# ---- combined 2x3 figure -------------------------------------------------
fig, axes = plt.subplots(2, 3, figsize=(14, 7))
for ax, n in zip(axes.flat, PROBLEMS):
    plotted = False
    for label, pattern, color in SERIES:
        df = load(n, pattern)
        if df is None:
            continue
        ax.step(df["time_ms"] / 1000.0, df["unique_errors"],
                where="post", label=label, color=color)
        plotted = True
    if not plotted:
        ax.set_visible(False)
        continue
    ax.set_title(f"Problem {n}")
    ax.set_xlabel("time (s)")
    ax.set_ylabel("unique errors")
    ax.set_ylim(bottom=0)
    ax.grid(True, alpha=0.3)
    ax.legend(loc="lower right", fontsize=8)
plt.tight_layout()
combined = f"{OUT_DIR}/concolic_vs_afl_convergence.png"
plt.savefig(combined, dpi=150)
plt.close()
print(f"wrote {combined}")

# ---- final-count bar comparison ------------------------------------------
final_concolic, final_afl = {}, {}
for n in PROBLEMS:
    dfc = load(n, "Problem{n}_concolic.csv")
    dfa = load(n, "Problem{n}_afl.csv")
    if dfc is not None:
        final_concolic[n] = int(dfc["unique_errors"].max())
    if dfa is not None:
        final_afl[n] = int(dfa["unique_errors"].max())

xs = list(PROBLEMS)
width = 0.35
fig, ax = plt.subplots(figsize=(8, 4.2))
ax.bar([i - width/2 for i in range(len(xs))],
       [final_concolic.get(n, 0) for n in xs],
       width=width, label="concolic", color="tab:blue")
ax.bar([i + width/2 for i in range(len(xs))],
       [final_afl.get(n, 0) for n in xs],
       width=width, label="AFL", color="tab:red")
ax.set_xticks(range(len(xs)))
ax.set_xticklabels([f"P{n}" for n in xs])
ax.set_ylabel("unique error codes (final)")
ax.set_title("Concolic vs AFL: final unique errors")
ax.grid(True, axis="y", alpha=0.3)
ax.legend()
for i, n in enumerate(xs):
    ax.text(i - width/2, final_concolic.get(n, 0) + 0.2,
            str(final_concolic.get(n, 0)), ha="center", fontsize=9)
    ax.text(i + width/2, final_afl.get(n, 0) + 0.2,
            str(final_afl.get(n, 0)), ha="center", fontsize=9)
plt.tight_layout()
bar = f"{OUT_DIR}/concolic_vs_afl_bar.png"
plt.savefig(bar, dpi=150)
plt.close()
print(f"wrote {bar}")

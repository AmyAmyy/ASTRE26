import pandas as pd
import matplotlib.pyplot as plt
import os

PROBLEMS = [11, 12, 13, 14, 15, 17]
DATA_DIR = "../logs/results_lab1_v2"
OUT_DIR = "../logs/results_lab1_v2"

for problem in PROBLEMS:
    fig, ax = plt.subplots(figsize=(10, 6))

    for version in [1, 2]:
        filename = os.path.join(DATA_DIR, f"Problem{problem}_v{version}.csv")

        if not os.path.exists(filename):
            print(f"Warning: {filename} not found, skipping.")
            continue

        df = pd.read_csv(filename)
        ax.plot(
            df["time_ms"] / 1000,  # convert to seconds
            df["unique_errors"],
            marker="o",
            markersize=3,
            linewidth=1.5,
            label=f"HillClimber v{version}",
        )

    ax.set_title(f"Problem {problem} — Unique Errors Over Time")
    ax.set_xlabel("Time (s)")
    ax.set_ylabel("Unique Errors Found")
    ax.legend()
    ax.grid(True, linestyle="--", alpha=0.5)

    out_name = os.path.join(OUT_DIR, f"Problem{problem}_versions_plot.png")
    fig.savefig(out_name, dpi=150, bbox_inches="tight")
    plt.close(fig)
    print(f"Saved {out_name}")

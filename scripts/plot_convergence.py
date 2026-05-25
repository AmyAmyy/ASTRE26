import os, glob
import pandas as pd
import matplotlib.pyplot as plt

LOG_DIR = "logs"
PROBLEMS = [11, 12, 13, 14, 15, 17]

# (label, filename pattern relative to LOG_DIR)
SERIES = [
    ("concolic",    "Problem{n}_concolic.csv"),
    ("hillclimber", "Problem{n}_fuzzer.csv"),    # rename if your previous lab used a different name
]

os.makedirs("report", exist_ok=True)

for n in PROBLEMS:
    fig, ax = plt.subplots(figsize=(7, 4))
    any_plotted = False
    for label, pattern in SERIES:
        path = os.path.join(LOG_DIR, pattern.format(n=n))
        if not os.path.exists(path):
            print(f"missing {path}")
            continue
        df = pd.read_csv(path)
        ax.step(df["time_ms"] / 1000.0, df["unique_errors"],
                where="post", label=label)
        any_plotted = True
    if not any_plotted:
        plt.close()
        continue
    ax.set_xlabel("time (s)")
    ax.set_ylabel("unique error codes")
    ax.set_title(f"Problem {n}")
    ax.legend()
    plt.tight_layout()
    out = f"report/Problem{n}_convergence.png"
    plt.savefig(out, dpi=150)
    plt.close()
    print(f"wrote {out}")

"""
Plot hypothesis convergence from RERS learning log files.

Expected log structure:
  logs/hypotheses/Problem<x>/output_<x>.log

Each log file should contain lines like:
  % Generated hypothesis iteration 1 with 27 states (hypothesis-iter-01.dot)
  % No counterexample found. Learning complete.

Usage:
  python plot_hypotheses.py
  python plot_hypotheses.py --log-dir path/to/logs/hypotheses
  python plot_hypotheses.py --output convergence.png
"""

import re
import argparse
from pathlib import Path

try:
    import matplotlib.pyplot as plt
    import matplotlib.ticker as ticker
except ImportError:
    raise SystemExit("matplotlib is required: pip install matplotlib")


ITERATION_RE = re.compile(
    r"Generated hypothesis iteration\s+(\d+)\s+with\s+(\d+)\s+states", re.IGNORECASE
)


def parse_log(path: Path) -> list[int]:
    """Return list of state counts per iteration from a single log file."""
    states = []
    for line in path.read_text(errors="replace").splitlines():
        m = ITERATION_RE.search(line)
        if m:
            states.append(int(m.group(2)))
    return states


def find_logs(log_dir: Path) -> dict[str, list[int]]:
    """Walk log_dir and return {problem_name: [states_per_iter]}."""
    results = {}
    # Support both flat files and Problem<x>/output_<x>.log structure
    for log_file in sorted(log_dir.rglob("*.log")):
        states = parse_log(log_file)
        if not states:
            continue
        # Use the parent folder name if it looks like a problem name, else the stem
        parent = log_file.parent.name
        label = parent if re.search(r"problem", parent, re.IGNORECASE) else log_file.stem
        results[label] = states
    return results


def plot(data: dict[str, list[int]], output: Path | None) -> None:
    colors = [
        "#378ADD", "#1D9E75", "#D85A30", "#7F77DD",
        "#D4537E", "#BA7517", "#A32D2D", "#085041",
    ]
    markers = ["o", "s", "^", "D", "v", "P", "X", "*"]

    fig, ax = plt.subplots(figsize=(9, 5))

    for i, (label, states) in enumerate(data.items()):
        iterations = list(range(1, len(states) + 1))
        color = colors[i % len(colors)]
        marker = markers[i % len(markers)]
        ax.plot(iterations, states, marker=marker, color=color,
                linewidth=2, markersize=6, label=label)
        # Annotate final state count
        ax.annotate(
            f"{states[-1]}",
            xy=(iterations[-1], states[-1]),
            xytext=(4, 4), textcoords="offset points",
            fontsize=9, color=color,
        )

    ax.set_xlabel("Hypothesis iteration", fontsize=11)
    ax.set_ylabel("Number of states", fontsize=11)
    ax.set_title("L* hypothesis convergence", fontsize=13, fontweight="normal")
    ax.xaxis.set_major_locator(ticker.MaxNLocator(integer=True))
    ax.yaxis.set_major_locator(ticker.MaxNLocator(integer=True))
    ax.legend(framealpha=0.7, fontsize=10)
    ax.grid(True, linestyle="--", alpha=0.4)
    fig.tight_layout()

    if output:
        fig.savefig(output, dpi=150)
        print(f"Saved plot to {output}")
    else:
        plt.show()


def main() -> None:
    parser = argparse.ArgumentParser(description="Plot L* hypothesis convergence.")
    parser.add_argument(
        "--log-dir", type=Path, default=Path("logs/hypotheses"),
        help="Root directory containing problem log folders (default: logs/hypotheses)",
    )
    parser.add_argument(
        "--output", type=Path, default=None,
        help="Save plot to this file instead of showing it (e.g. convergence.png)",
    )
    args = parser.parse_args()

    if not args.log_dir.exists():
        raise SystemExit(f"Log directory not found: {args.log_dir}\n"
                         f"Use --log-dir to specify the correct path.")

    data = find_logs(args.log_dir)
    if not data:
        raise SystemExit(f"No log files with hypothesis iterations found in {args.log_dir}")

    print("Found data:")
    for label, states in data.items():
        print(f"  {label}: {len(states)} iterations, final = {states[-1]} states")

    plot(data, args.output)


if __name__ == "__main__":
    main()
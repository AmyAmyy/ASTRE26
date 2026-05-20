import pandas as pd
import matplotlib.pyplot as plt

problems = [1, 4, 7, 11, 12, 15]
mutation_rates = [0.20, 0.10, 0.05]

for p in problems:
    fig, ax = plt.subplots(figsize=(8, 5))
 
    for mr in mutation_rates:
        if mr == 0.05:
            filename = f"logs/Problem{p}_mut{mr}_extra0.10_top15.csv"
        else:
            filename = f"logs/Problem{p}_mut{mr}0_extra0.10_top15.csv"
        try:
            df = pd.read_csv(filename)
            ax.plot(df["generation"], df["best_fitness"], label=f"mut={mr}")
        except FileNotFoundError:
            print(f"Warning: {filename} not found, skipping.")
 
    ax.set_title(f"Problem {p} — Best Fitness by Generation")
    ax.set_xlabel("Generation")
    ax.set_ylabel("Best Fitness")
    ax.legend()
    plt.tight_layout()
    plt.savefig(f"Problem{p}_plot.png", dpi=150)
    plt.close()
    print(f"Saved Problem{p}_plot.png")
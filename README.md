# Final Assignment — Empirical Comparison of Testing Techniques

This directory contains all code, scripts, and reproduction instructions for the
final assignment. It compares four automated test-generation techniques on the
RERS reachability problems **11–19**:

| Technique          | Task   | How it is run                                  |
|--------------------|--------|------------------------------------------------|
| Random fuzzer      | Task 1 | Java instrumentation (`--type=fuzzing`)        |
| Hill climber       | Task 1 | Java instrumentation (`--type=fuzzing`)        |
| Symbolic / concolic| Task 1+3 | Java instrumentation (`--type=concolic`) + Z3  |
| AFL                | Task 2 | `afl-fuzz` on the C version of each problem    |

> **All commands below are run from the repository root**, not from inside
> `scripts/final/`. The scripts resolve their paths relative to the repo root.

---

## Requirements

### Build / runtime
- **JDK 8 or 11** and **Maven** (to build `target/aistr.jar`).
- **Z3** with its native libraries. The Java bindings (`lib/com.microsoft.z3.jar`)
  are checked in; the native libs (`libz3.dylib` / `libz3java.dylib` on macOS,
  `libz3.so` / `libz3java.so` on Linux) must be present **in the repo root**
  (the default `Z3_LIB="."`). Override with `Z3_LIB=/path/to/libs`.
- **Python 3** with `pandas`, `numpy`, and `matplotlib` (for the analysis scripts):
  ```bash
  pip install pandas numpy matplotlib
  ```

### AFL only (Task 2)
- **AFL / AFL++** on `PATH`: `afl-fuzz` plus one of `afl-clang-fast`, `afl-cc`,
  or `afl-gcc`.
- A plain C compiler (`clang` / `gcc`).

### Datasets (expected in the repo root)
- `SeqReachabilityRers2020/` — Java RERS sources for Tasks 1 and 3.
- `afl/<N>/Problem<N>.c` and `afl/<N>/tests/` — C sources and seed inputs for AFL.

### Experimental setup
- **Time budget:** 300 s (5 min) per run (a hard 310 s kill guards the JVM).
- **Repetitions:** 5 independent seeds (`1 2 3 4 5`) per technique per problem.
- **Problems:** `11 12 13 14 15 16 17 18 19` by default

---

## Quick start (full reproduction)

```bash
mvn -DskipTests package                       # build target/aistr.jar

# --- Task 1: random / hill climber / concolic ---
bash scripts/final/preinstrument.sh           # instrument + compile all problems
bash scripts/final/run_task1.sh               # 3 techniques x 6 problems x 5 seeds
python3 scripts/final/analyze_task1.py --outdir report/final

# --- Task 2: AFL ---
bash scripts/final/run_task2.sh               # afl-fuzz x 6 problems x 5 seeds
python3 scripts/final/remap_afl_branches.py   # re-measure AFL branches in RERS units
python3 scripts/final/analyze_task2.py  --outdir report/final
python3 scripts/final/compare_errors_task2.py --outdir report/final

# --- Task 3: concolic ablation ---
bash scripts/final/run_task3.sh               # base + improved x 6 problems x 5 seeds
python3 scripts/final/analyze_task3.py  --outdir report/final
```

Each `run_*` step can take many hours (up to 12h) at the full budget.

---

## File overview

| File                      | Purpose                                                            |
|---------------------------|--------------------------------------------------------------------|
| `preinstrument.sh`        | Instrument + compile all problems (fuzzing & concolic).            |
| `run_task1.sh`            | Task 1: random, hill climber, concolic — 5 seeds each.             |
| `run_task2.sh`            | Task 2: compile + fuzz each problem with AFL — 5 seeds each.       |
| `run_task3.sh`            | Task 3: concolic `reuseProb` ablation/sweep — 5 seeds each.        |
| `analyze_task1.py`        | Plots + summary tables for Task 1.                                 |
| `analyze_task2.py`        | Four-way AFL-vs-Task1 plots + summary tables.                      |
| `analyze_task3.py`        | Base-vs-improved plots + summary tables (Task 3).                  |
| `process_afl_run.py`      | Convert one AFL run dir into the Task-1 CSV format.                |
| `remap_afl_branches.py`   | Re-measure AFL coverage in RERS-branch units (drives `AflReplay`). |
| `compare_errors_task2.py` | Per-technique error-set overlap analysis.                          |
| `AflReplay.java`          | Replays AFL inputs through the Task-1 fuzzing instrumentation.     |

All result CSVs/logs land under `logs/` (git-ignored, as it gets very large). Generated plots and
tables are written to `report/final/`.

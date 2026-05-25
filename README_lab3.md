# ASTRE26 - Lab 3: Automated Code Patching

Amy van der Meijden (5301513) and Jimmy Oei (6540031)

This archive contains our deliverables for Lab 3:

- our implementation of the EA-based patcher (`PatchingLab.java`),
- helper scripts for running the experiments and post-processing results,

## Contents
```
src/main/java/nl/tudelft/instrumentation/patching/
    PatchingLab.java         our EA implementation (Tasks 2 and 3)
    OperatorTracker.java     framework, unmodified
    OperatorVisitor.java     framework, unmodified
scripts/
    run_astor.sh             driver for Task 4 (ASTOR experiments)
    diff_astor_patches.sh    post-run diff helper for Task 4
    merge_astor_results.sh   merges results when two collaborators run halves
report/
    task4_astor.md           Task 4 write-up
patches/                     best patch per RERS problem (one file each)
logs/                        per-run convergence CSVs (generation, best, median)
astor_runs/                  per-run ASTOR output (Task 4)
```

`PatchingLab.java` is the only file we modified inside the framework
sources.

## Prerequisites

- JDK 11 or later, Maven 3.6+.
- The RERS 2020 buggy problems already instrumented under `instrumented/`
  (the framework's `scripts/instrument.sh` produces these).
- For Task 4 only: the Dev Container with ASTOR cloned at `/home/str/astor`
  (or any environment where ASTOR is built) and the Buggy_RERS-ASTOR
  problems extracted somewhere accessible.

## Building

```bash
mvn -DskipTests package
```

This produces `target/aistr.jar`, which contains our `PatchingLab` and is
referenced at runtime by every instrumented problem.

## Tasks 2 and 3 - Running the EA patcher

To run:
```bash
# Example for Problem1; adjust for Problem4, 7, 11, 12, 15.
java -cp target/aistr.jar:instrumented Problem1
```

The run prints the initial fitness, then a per-generation line with the best
fitness, and at the end a single line giving the stop reason
(`all tests pass`, `wall-clock budget exhausted`, `max generations reached`),
the elapsed wall-clock, and the final best fitness.

### Wall-clock and stopping criteria
- maximum 200 generations,
- maximum 30 minutes wall-clock,
- early termination on fitness 0.
These are constants in `PatchingLab.java` (`maxGenerations`,
`maxRuntimeMillis`).

### Outputs produced per run
- `patches/<ProblemName>.patch`
  Best patch found, written on every strict fitness improvement and once
  more at termination. Format:
  ```
  problem=Problem1
  generation=37
  fitness=0.083
  <operator-index>:<original>-><patched>
  ...
  ```
- `logs/<ProblemName>_mut<rate>_extra<rate>_top<N>.csv`
  Per-generation convergence log with columns
  `generation,best_fitness,median_fitness`. One row per generation
  including the baseline (generation 0). Used to plot the convergence
  curves in the report.

### Reproducing our experiments
We ran each of the six problems (1, 4, 7, 11, 12, 15) at three mutation
rates by editing the constants in `PatchingLab.java`, rebuilding with
`mvn -DskipTests package`, and re-running. 

## Task 4 - Running ASTOR
Use `scripts/run_astor.sh` from the JavaInstrumentation root inside the
Dev Container. The script builds each buggy problem, runs ASTOR with the
parameters from the lab brief, captures stdout/stderr, copies
`output-astor/` into `astor_runs/<problem>/`, and writes a CSV summary.
```bash
# one-time, in /home/str/astor
mvn install -DskipTests=true
mvn dependency:build-classpath -B \
  | egrep -v "(^\[INFO\]|^\[WARNING\])" \
  | tee /tmp/astor-classpath.txt
# from the JavaInstrumentation root
BUGGY_ROOT=/home/str/Buggy_RERS-ASTOR bash scripts/run_astor.sh
```

To split the work between two machines (to save time_:
```bash
HALF=A bash scripts/run_astor.sh   # first three problems
HALF=B bash scripts/run_astor.sh   # last three problems
# afterwards, copy both astor_runs/ trees together and merge:
bash scripts/merge_astor_results.sh
```
To produce diffs for the meaningfulness analysis:
```bash
ORIGINALS_DIR=/path/to/original/RERS bash scripts/diff_astor_patches.sh \
    > astor_runs/diffs.txt
```
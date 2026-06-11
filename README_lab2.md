# ASTRE26 - Lab 2: Concolic Execution
Amy van der Meijden (5301513) and Jimmy Oei (6540031)

# Manually build and run the tool 
To build the project, make sure you have navigated to the root of this project and run the following Maven command:

`mvn clean package`

To instrument a given Java file, use the following command:

`java -cp target/aistr.jar nl.tudelft.instrumentation.Main --type=*TypeOfInstrumentation* --file=*PathToJavaFile* > *OutputPath*`

Where `*PathToJavaFile*` is the path to the Java file to instrument, `*OutputPath*` is the file (file name and path) where you want to save the instrumented Java file. The `*TypeOfInstrumentation*` is the type of instrumentation that you want to do. You can choose between the following options: `line`, `branch`, `fuzzing`, `concolic`, `patching`, and `learning`.
Note that the flags `--file` and `--type` are required for instrumenting a Java file.

## Task 1 - Run the concolic execution

The script to run the experiments for the concolic execution is:

```bash
# all six problems, 5 minutes per problem (default)
bash scripts/run_concolic.sh

# subset
PROBLEMS="11 17" bash scripts/run_concolic.sh

# custom Z3 location (folder containing libz3java.{so,dylib})
Z3_LIB=/path/to/z3/bin bash scripts/run_concolic.sh
```

For every problem the script will:
1. Instrument `RERS2020Buggy/Problem<N>.java` with `--type=concolic`
   into `instrumented/Problem<N>.java`.
2. Compile the instrumented file against the Z3 jar.
3. Run it. `ConcolicExecutionLab.run()` enforces a 5-minute budget and
   exits cleanly, writing:
   - `logs/Problem<N>_concolic.csv` (`time_ms,unique_errors`)
   - `logs/Problem<N>_concolic.log` (full stdout/stderr)

## Convergence graphs (concolic vs AFL)

The Lab 1 AFL output already includes a `crashes/` directory per
problem. We turn it into a convergence timeline by replaying every
crash file through the corresponding AFL binary, using the `time:<ms>`
field embedded in each crash filename as the relative timestamp.

```bash
# 1. build AFL convergence CSVs from the existing afl/<N>/findings/default
python3 scripts/build_afl_timelines.py

# 2. produce the comparison plots
python3 scripts/plot_convergence.py
```

Outputs in `report/`:
- `Problem<N>_convergence.png` - one plot per problem with both curves.
- `concolic_vs_afl_convergence.png` - 2x3 grid (used in the report).
- `concolic_vs_afl_bar.png` - final-count bar chart (used in the report).

## Task 2 - KLEE

`scripts/run_klee.sh` then compiles each file to LLVM bitcode
with `clang -emit-llvm` and runs KLEE on it.

```bash
bash scripts/run_klee.sh
```

Per-problem outputs land in `klee/<N>/`:
`Problem<N>.bc`, `klee-out/`, `klee_full.log`, `klee_summary.txt`.

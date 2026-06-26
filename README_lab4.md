# ASTRE26 - Lab 4: Model Learning

Amy van der Meijden (5301513) and Jimmy Oei (6540031)

## Building

```bash
mvn -DskipTests package
```

This produces `target/aistr.jar`.

## Running all experiments

The script instruments, compiles, and runs all problems for both our L\*
implementation and LearnLib TTT:

```bash
bash scripts/run_learning.sh
```

This will:
1. Build the project
2. Instrument Problems 1, 2, 4, 7 and ProblemPin with `--type=learning`
3. Run **our L\*** on each problem (w=3 for RERS, w=4 for ProblemPin)
4. Run **LearnLib TTT** on each problem

## Output format

Each problem produces an `output.log` and a set of DOT files in its results folder.

### L\* output.log

One line per iteration showing the number of states discovered, cumulative
membership queries, and elapsed time. A final summary line when learning
completes:

```
ITERATION 1 | states=28 | queries=2810 | time_ms=89
ITERATION 2 | states=31 | queries=16194 | time_ms=249
...
DONE | final_states=35 | total_queries=889405 | total_time_ms=8814
```

### TTT output.log

LearnLib's built-in profiling output, including number of rounds, total
membership queries, and learning/equivalence check time split:

```
Done running
Learning [ms]: 60
Searching for counterexample [ms]: 44813
learning rounds [#]: 21
membership queries [queries]: 4691435
States: 35
```

### DOT files

Each iteration produces a `hypothesis-iter-<N>.dot` file. The final model
is also saved as `hypothesis-final.dot`.

## Converting DOT files to images

To convert a single DOT file to PNG:

```bash
dot -Tpng hypothesis-final.dot -o hypothesis-final.png
```
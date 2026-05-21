# Task 4 — Genetic Programming with ASTOR

> Run with `scripts/run_astor.sh` inside the Dev Container, then fill in the
> `<FILL_IN>` placeholders below from `astor_runs/results.csv` and the patches
> in `astor_runs/<Problem>/output-astor/`.

## 4.1 Setup

- Tool: ASTOR (SpoonLabs), mode `jgenprog`, built per the lab brief.
- Dataset: 6 buggy problems from `Buggy_RERS-ASTOR/`, selected to maximise
  overlap with our Task 3 set so we can compare ASTOR's behaviour to our own
  EA on the same programs.
- Selected problems: **Problem1, Problem2, Problem7, Problem11, Problem12,
  Problem13** (`_buggy` suffix). Problems 1, 7, 11, 12 also appear in Task 3.
- ASTOR parameters (from the brief):
  `-mode jgenprog -maxtime 5 -maxgen 3000 -scope local -population 8
   -operatorspace relational-Logical-op`.
- Build per problem: `mvn clean && mvn install -DskipTests` before each
  ASTOR invocation. All 6 problems built successfully: `<FILL_IN: yes/no>`.

## 4.2 Per-problem statistics

Wall-clock is the script's outer timer (includes ASTOR startup). ASTOR-time
is the value parsed from ASTOR's own `Time(s):` line. Patch count is the
number of solutions ASTOR reported finding within the time budget.

| Problem  | Wall-clock (s) | ASTOR-reported time (s) | Patches found |
|----------|----------------|--------------------------|---------------|
| Problem1_buggy  | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |
| Problem2_buggy  | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |
| Problem7_buggy  | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |
| Problem11_buggy | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |
| Problem12_buggy | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |
| Problem13_buggy | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |

(Source data: `astor_runs/results.csv`.)

## 4.3 Manual patch analysis

For each problem with at least one patch, we diffed three versions:

1. The original (non-buggy) RERS problem: `RERS2020/Problem<N>/Problem<N>.java`.
2. The buggy version ASTOR was run on: `Buggy_RERS-ASTOR/Problem<N>_buggy/src/main/java/.../Problem<N>.java`.
3. ASTOR's patched version: `astor_runs/Problem<N>_buggy/output-astor/<patch-folder>/...`.

Run `scripts/diff_astor_patches.sh` (set `ORIGINALS_DIR=...` if originals
are available) to dump all three-way diffs at once.

### Q1. Does ASTOR generate a meaningful patch?

A patch is "meaningful" if the modification ASTOR makes corresponds to a real
fix of the seeded fault — i.e. the changed operator/expression is the same
location and direction as the difference between buggy and original. A patch
that merely happens to make the failing tests pass without addressing the
underlying fault location is *plausible* but not meaningful.

| Problem  | Patch found? | Same location as seeded fault? | Same operator/direction? | Meaningful? |
|----------|--------------|---------------------------------|--------------------------|-------------|
| Problem1_buggy  | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |
| Problem2_buggy  | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |
| Problem7_buggy  | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |
| Problem11_buggy | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |
| Problem12_buggy | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |
| Problem13_buggy | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` | `<FILL_IN>` |

Representative diff (paste 5-15 lines from one of the more interesting
patches; show buggy → patched alongside the corresponding original snippet):

```diff
<FILL_IN: e.g. patch on Problem1_buggy>
- if (a == 5)
+ if (a != 5)
  ...
```

### Q2. Why are/aren't the patches meaningful?

Discuss using the diffs collected above. Plausible angles to cover (use the
ones that match what you actually observe; do not list points you can't
back up with the diffs):

- **Operator-space mutation is well matched to the seeded faults.** The bugs
  in `Buggy_RERS-ASTOR` are operator perturbations of relational/logical
  expressions, so a search restricted to `relational-Logical-op` operators
  has the right mutation alphabet to undo them. When ASTOR finds a patch
  quickly, this is usually why.

- **Test-suite oracle limits.** ASTOR optimises for "all provided tests
  pass". If the test suite under-specifies the desired behaviour, ASTOR can
  produce a *plausible* patch (tests pass) that is at a different operator
  or expression than the one the seeded bug touched. Note any such cases
  observed.

- **Search-budget effects.** With `-maxtime 5` minutes and `-maxgen 3000`,
  failures to find any patch are most often due to (a) the seeded fault being
  outside the operator-space mutation alphabet, or (b) the population not
  containing useful building blocks within the budget. Note which of the 6
  problems hit the budget without producing a patch and which timed out vs.
  exhausted generations.

- **Comparison with our Task 3 EA on the overlapping problems
  (1, 7, 11, 12).** `<FILL_IN: which tool patched which problem within its
  budget; whether ASTOR's patches landed at the same operator slots that our
  Tarantula localiser ranked highest>`.

## 4.4 Reproducibility

All artefacts produced by `scripts/run_astor.sh` are kept under
`astor_runs/` (per-problem stdout, stderr, raw `output-astor/`, and the
aggregate `results.csv`). To reproduce inside the Dev Container:

```bash
# from /home/str/astor (after mvn install -DskipTests=true)
mvn dependency:build-classpath -B \
  | egrep -v "(^\[INFO\]|^\[WARNING\])" \
  | tee /tmp/astor-classpath.txt

# then, from the JavaInstrumentation root:
BUGGY_ROOT=/home/str/Buggy_RERS-ASTOR \
ASTOR_DIR=/home/str/astor \
bash scripts/run_astor.sh
```

To change the maxtime per run, set `MAXTIME=<minutes>`.
To change the problem set, edit the `PROBLEMS=(...)` array in the script.

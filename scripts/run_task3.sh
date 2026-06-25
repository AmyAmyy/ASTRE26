#!/bin/bash
# ============================================================
# run_task3.sh  –  Task 3 improvement ablation / sweep (concolic)
#
# Runs the SAME concolic binary at one or more reuseProb values on each RERS
# problem, 5 independent seeds each, 5-minute (300 s) budget per run. reuseProb
# is the only knob that changes between runs (a clean ablation):
#
#   reuseProb=0.0   coverage-guided seeding disabled (= Task 1 base concolic)
#   reuseProb=0.7   seeding enabled (the improvement, default value)
#
# So "base" is simply reuseProb=0.0 and "improved" is reuseProb=0.7; the default
# REUSE_PROBS below runs both, giving the required base-vs-improved comparison.
# Add more values to get the sensitivity sweep for the report.
#
# Each run writes its CSV directly via the -DoutDir property, into a
# per-probability directory:
#   logs/task3/reuse<P>/Problem<N>_concolic_seed<S>.csv
# with columns:  time_ms , unique_errors , unique_branches
#
# Pre-instrumented files are expected at instrumented/concolic/ (run
# scripts/preinstrument.sh first); falls back to instrumenting from source.
#
# Usage:
#   bash scripts/run_task3.sh                              # base + improved (0.0, 0.7)
#   REUSE_PROBS="0.0 0.3 0.5 0.7 0.9" bash scripts/run_task3.sh   # full sweep
#   PROBLEMS="15 17" bash scripts/run_task3.sh             # subset of problems
#   SEEDS="1 2 3" bash scripts/run_task3.sh                # custom seeds
#
# Note: a full sweep is expensive. 5 probs x 9 problems x 5 seeds x 5 min ~= 31h,
# so consider limiting PROBLEMS to a few representatives for the sweep.
# ============================================================
set -u

# Same problems as Task 1 (see scripts/run_task1.sh).
PROBLEMS=${PROBLEMS:-"11 12 13 14 15 16 17 18 19"}
SEEDS=${SEEDS:-"1 2 3 4 5"}
REUSE_PROBS=${REUSE_PROBS:-"0.0 0.5 0.7 0.9"}
DATASET=${DATASET:-"./SeqReachabilityRers2020"}
Z3_LIB=${Z3_LIB:-"."}              # folder containing libz3java.{dylib,so}

CP_CONC="target/aistr.jar:lib/com.microsoft.z3.jar:instrumented/concolic:."
TIMEOUT_SECS=310                   # hard kill after 310 s (budget is 300 s inside JVM)

mkdir -p instrumented/concolic
for P in $REUSE_PROBS; do mkdir -p "logs/task3/reuse${P}"; done

# ── Build once ──────────────────────────────────────────────
if [ ! -f target/aistr.jar ]; then
    echo "[build] target/aistr.jar missing – running mvn package"
    mvn -q -DskipTests package || { echo "Maven build failed"; exit 1; }
fi

# ── Helper: portable timeout command ────────────────────────
run_with_timeout() {
    local secs="$1"; shift
    if command -v gtimeout &>/dev/null; then
        gtimeout "$secs" "$@"
    elif command -v timeout &>/dev/null; then
        timeout "$secs" "$@"
    else
        "$@" &
        local pid=$!
        ( sleep "$secs" && kill -TERM "$pid" 2>/dev/null ) &
        local watcher=$!
        wait "$pid" 2>/dev/null
        kill "$watcher" 2>/dev/null
        wait "$watcher" 2>/dev/null
    fi
}

# ── Ensure the concolic build is ready ──────────────────────
ensure_ready() {
    local N="$1"
    local DIR="instrumented/concolic"
    local JAVA="$DIR/Problem${N}.java"
    local CLS="$DIR/Problem${N}.class"

    if [ -f "$CLS" ] && [ -f "$JAVA" ] && [ "$CLS" -nt "$JAVA" ]; then
        echo "  [ready] Problem$N  concolic"
        return 0
    fi

    if [ ! -f "$JAVA" ]; then
        local SRC="$DATASET/Problem$N.java"
        [ -f "$SRC" ] || SRC="$DATASET/Problem$N/Problem$N.java"
        [ -f "$SRC" ] || { echo "  [ERROR] source not found for Problem$N"; return 1; }

        echo "  [instrument --type=concolic] Problem$N"
        java -XX:+UseG1GC -Xmx6g -cp target/aistr.jar \
            nl.tudelft.instrumentation.Main \
            --type=concolic --file="$SRC" \
            > "$JAVA" \
            || { echo "  [ERROR] instrumentation failed"; return 1; }
    fi

    echo "  [compile --type=concolic] Problem$N"
    javac -cp "$CP_CONC" -d "$DIR" Errors.java "$JAVA" \
        || { echo "  [ERROR] compile failed"; return 1; }
    return 0
}

# ════════════════════════════════════════════════════════════
# Main loop
# ════════════════════════════════════════════════════════════
for N in $PROBLEMS; do
    echo ""
    echo "==========================================================="
    echo " Problem $N"
    echo "==========================================================="

    if ! ensure_ready "$N"; then
        echo "  [ERROR] concolic setup failed for Problem$N — skipping"
        continue
    fi

    for RP in $REUSE_PROBS; do
        OUTDIR="logs/task3/reuse${RP}"

        for S in $SEEDS; do
            OUT="$OUTDIR/Problem${N}_concolic_seed${S}.csv"
            LOG="$OUTDIR/Problem${N}_concolic_seed${S}.log"
            if [ -f "$OUT" ]; then
                echo "  [skip] $OUT already exists"
                continue
            fi

            echo "  [run] reuseProb=$RP seed=$S  →  $OUT"
            run_with_timeout "$TIMEOUT_SECS" \
                java -Xmx4g \
                    -Djava.library.path="$Z3_LIB" \
                    -Dseed="$S" \
                    -DreuseProb="$RP" \
                    -DoutDir="$OUTDIR" \
                    -cp "$CP_CONC" \
                    "Problem$N" \
                > "$LOG" 2>&1
            STATUS=$?

            if [ -f "$OUT" ]; then
                echo "    exit=$STATUS  csv=$OUT"
            else
                echo "    exit=$STATUS  [WARN] no CSV produced (see $LOG)"
            fi
        done
    done
done

echo ""
echo "Done. Task 3 CSVs in logs/task3/reuse<P>/  (reuseProb in {$REUSE_PROBS})"
echo "  reuseProb=0.0 is the base (improvement disabled); 0.7 is the improvement."
echo "Run: python3 scripts/analyze_task3.py   to generate the table and plots."

#!/bin/bash
# ============================================================
# run_task1.sh  –  Task 1 empirical comparison
#
# Runs random fuzzer, hill climber, and concolic execution on
# each RERS problem, 5 independent seeds per technique.
# Each run is given a 5-minute (300 s) wall-clock budget.
#
# Pre-instrumented files are expected at:
#   instrumented/fuzzing/ProblemN.java  (.class in same dir)
#   instrumented/concolic/ProblemN.java (.class in same dir)
# Run preinstrument.sh first to generate them.
#
# Results land in  logs/task1/Problem<N>_<mode>_seed<S>.csv
# with columns:  time_ms , unique_errors , unique_branches
#
# Usage:
#   bash scripts/run_task1.sh
#   PROBLEMS="11 12" bash scripts/run_task1.sh   # subset
#   SEEDS="1 2 3" bash scripts/run_task1.sh      # custom seeds
#   TECHNIQUES="random hillclimber" bash scripts/run_task1.sh
# ============================================================
set -u

PROBLEMS=${PROBLEMS:-"11 12 13 14 15 16 17 18 19"}
SEEDS=${SEEDS:-"1 2 3 4 5"}
TECHNIQUES=${TECHNIQUES:-"random hillclimber concolic"}
DATASET=${DATASET:-"./SeqReachabilityRers2020"}
Z3_LIB=${Z3_LIB:-"."}          # folder containing libz3java.{dylib,so}

CP_FUZZ="target/aistr.jar:instrumented/fuzzing:."
CP_CONC="target/aistr.jar:lib/com.microsoft.z3.jar:instrumented/concolic:."
TIMEOUT_SECS=310                # hard kill after 310 s (budget is 300 s inside JVM)

mkdir -p logs/task1 instrumented/fuzzing instrumented/concolic

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

# ── Ensure a problem is instrumented and compiled ───────────
# Uses pre-built files from instrumented/<type>/ if present;
# falls back to instrumenting from source otherwise.
ensure_ready() {
    local N="$1" type="$2" cp="$3"
    local DIR="instrumented/$type"
    local JAVA="$DIR/Problem${N}.java"
    local CLS="$DIR/Problem${N}.class"

    # Fast path: already compiled
    if [ -f "$CLS" ] && [ -f "$JAVA" ] && [ "$CLS" -nt "$JAVA" ]; then
        echo "  [ready] Problem$N  $type"
        return 0
    fi

    # Instrument if .java missing
    if [ ! -f "$JAVA" ]; then
        local SRC="$DATASET/Problem$N.java"
        [ -f "$SRC" ] || SRC="$DATASET/Problem$N/Problem$N.java"
        [ -f "$SRC" ] || { echo "  [ERROR] source not found for Problem$N"; return 1; }

        echo "  [instrument --type=$type] Problem$N"
        java -XX:+UseG1GC -Xmx6g -cp target/aistr.jar \
            nl.tudelft.instrumentation.Main \
            --type="$type" --file="$SRC" \
            > "$JAVA" \
            || { echo "  [ERROR] instrumentation failed"; return 1; }
    fi

    echo "  [compile --type=$type] Problem$N"
    javac -cp "$cp" -d "$DIR" Errors.java "$JAVA" \
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

    # ── Fuzzing techniques (random + hillclimber) ────────────
    if echo "$TECHNIQUES" | grep -qE "(random|hillclimber)"; then

        if ensure_ready "$N" "fuzzing" "$CP_FUZZ"; then

            for TECH in $TECHNIQUES; do
                [ "$TECH" = "random" ] || [ "$TECH" = "hillclimber" ] || continue

                for S in $SEEDS; do
                    OUT="logs/task1/Problem${N}_${TECH}_seed${S}.csv"
                    if [ -f "$OUT" ]; then
                        echo "  [skip] $OUT already exists"
                        continue
                    fi

                    LOG="logs/task1/Problem${N}_${TECH}_seed${S}.log"
                    echo "  [run] $TECH seed=$S  →  $OUT"
                    run_with_timeout "$TIMEOUT_SECS" \
                        java -Xmx2g \
                            -Dmode="$TECH" \
                            -Dseed="$S" \
                            -cp "$CP_FUZZ" \
                            "Problem$N" \
                        > "$LOG" 2>&1
                    echo "    exit=$?  log=$LOG"
                done
            done

        else
            echo "  [ERROR] fuzzing setup failed for Problem$N — skipping fuzzing runs"
        fi
    fi

    # ── Concolic execution ───────────────────────────────────
    if echo "$TECHNIQUES" | grep -q "concolic"; then

        if ensure_ready "$N" "concolic" "$CP_CONC"; then

            for S in $SEEDS; do
                OUT="logs/task1/Problem${N}_concolic_seed${S}.csv"
                if [ -f "$OUT" ]; then
                    echo "  [skip] $OUT already exists"
                    continue
                fi

                LOG="logs/task1/Problem${N}_concolic_seed${S}.log"
                echo "  [run] concolic seed=$S  →  $OUT"
                run_with_timeout "$TIMEOUT_SECS" \
                    java -Xmx4g \
                        -Djava.library.path="$Z3_LIB" \
                        -Dseed="$S" \
                        -cp "$CP_CONC" \
                        "Problem$N" \
                    > "$LOG" 2>&1
                echo "    exit=$?  log=$LOG"
            done

        else
            echo "  [ERROR] concolic setup failed for Problem$N — skipping concolic runs"
        fi
    fi

done

echo ""
echo "Done. CSVs in logs/task1/"
echo "Run: python3 scripts/analyze_task1.py   to generate plots and tables."

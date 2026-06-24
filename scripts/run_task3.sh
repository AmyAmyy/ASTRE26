#!/bin/bash
# ============================================================
# run_task3.sh  –  Task 3 improvement ablation (concolic)
#
# Ablation of the coverage-guided concrete seeding improvement
# in ConcolicExecutionLab. Runs the SAME concolic binary in two
# configurations on each RERS problem, 5 independent seeds each,
# 5-minute (300 s) wall-clock budget per run:
#
#   base      -DreuseProb=0.0   seeding disabled (= Task 1 concolic)
#   improved  -DreuseProb=0.7   seeding enabled  (default)
#
# Both configs use the identical instrumented/concolic build, so
# the only difference is the reuseProb knob — a clean A/B ablation.
#
# Each run writes its own CSV directly (no post-hoc moving) via the
# -DoutDir property, into a config-specific directory:
#   logs/task3/<config>/Problem<N>_concolic_seed<S>.csv
# with columns:  time_ms , unique_errors , unique_branches
#
# Uses the same problem set as Task 1. Pre-instrumented files are
# expected at instrumented/concolic/ (run scripts/preinstrument.sh
# first); falls back to instrumenting from source otherwise.
#
# Usage:
#   bash scripts/run_task3.sh
#   PROBLEMS="11 12" bash scripts/run_task3.sh        # subset
#   SEEDS="1 2 3" bash scripts/run_task3.sh           # custom seeds
#   CONFIGS="improved" bash scripts/run_task3.sh      # one config only
#   REUSE_PROB_IMPROVED=0.5 bash scripts/run_task3.sh # tune improved knob
# ============================================================
set -u

# Same problems as Task 1 (see scripts/run_task1.sh).
PROBLEMS=${PROBLEMS:-"11 12 13 14 15 16 17 18 19"}
SEEDS=${SEEDS:-"1 2 3 4 5"}
CONFIGS=${CONFIGS:-"base improved"}
DATASET=${DATASET:-"./SeqReachabilityRers2020"}
Z3_LIB=${Z3_LIB:-"."}              # folder containing libz3java.{dylib,so}

# reuseProb value per config (base disables the improvement entirely)
REUSE_PROB_BASE=${REUSE_PROB_BASE:-"0.0"}
REUSE_PROB_IMPROVED=${REUSE_PROB_IMPROVED:-"0.7"}

CP_CONC="target/aistr.jar:lib/com.microsoft.z3.jar:instrumented/concolic:."
TIMEOUT_SECS=310                   # hard kill after 310 s (budget is 300 s inside JVM)

mkdir -p instrumented/concolic
for C in $CONFIGS; do mkdir -p "logs/task3/$C"; done

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

reuse_prob_for() {
    case "$1" in
        base)     echo "$REUSE_PROB_BASE" ;;
        improved) echo "$REUSE_PROB_IMPROVED" ;;
        *)        echo "$REUSE_PROB_IMPROVED" ;;   # unknown config -> improved default
    esac
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

    for CFG in $CONFIGS; do
        RP=$(reuse_prob_for "$CFG")
        OUTDIR="logs/task3/${CFG}"

        for S in $SEEDS; do
            OUT="$OUTDIR/Problem${N}_concolic_seed${S}.csv"
            LOG="$OUTDIR/Problem${N}_concolic_seed${S}.log"
            if [ -f "$OUT" ]; then
                echo "  [skip] $OUT already exists"
                continue
            fi

            echo "  [run] $CFG (reuseProb=$RP) seed=$S  →  $OUT"
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
echo "Done. Task 3 ablation CSVs in logs/task3/<config>/"
echo "  base    = reuseProb=$REUSE_PROB_BASE  (improvement disabled)"
echo "  improved= reuseProb=$REUSE_PROB_IMPROVED (improvement enabled)"
echo "Run: python3 scripts/analyze_task3.py   to generate the table and plots."

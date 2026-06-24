#!/bin/bash
# ============================================================
# run_task2.sh  –  Task 2: AFL experiment
#
# Compiles each RERS problem with afl-clang-fast and a plain
# C compiler, then runs afl-fuzz for 5 minutes per seed.
# Post-processes each run into a CSV matching Task 1 format:
#   time_ms , unique_errors , unique_branches
#
# Results land in:
#   logs/task2/Problem<N>_afl_seed<S>/   ← raw AFL output
#   logs/task2/Problem<N>_afl_seed<S>.csv
#
# Usage:
#   bash scripts/run_task2.sh
#   PROBLEMS="11 12" bash scripts/run_task2.sh
#   SEEDS="1 2 3" bash scripts/run_task2.sh
# ============================================================
set -u

PROBLEMS=${PROBLEMS:-"11 12 13 14 15 16 17 18 19"}
SEEDS=${SEEDS:-"1 2 3 4 5"}
DURATION=${DURATION:-300}          # seconds per AFL run (budget)

AFL_BASE="./afl"                   # afl/<N>/Problem<N>.c  and  afl/<N>/tests/
LOG_BASE="logs/task2"

PROCESS_SCRIPT="scripts/process_afl_run.py"

mkdir -p "$LOG_BASE"

# ── Detect AFL compiler ──────────────────────────────────────
if command -v afl-clang-fast &>/dev/null; then
    AFL_CC="afl-clang-fast"
elif command -v afl-cc &>/dev/null; then
    AFL_CC="afl-cc"
elif command -v afl-gcc &>/dev/null; then
    AFL_CC="afl-gcc"
else
    echo "[ERROR] No AFL compiler found (tried afl-clang-fast, afl-cc, afl-gcc)"
    exit 1
fi
echo "[info] AFL compiler: $AFL_CC"
echo "[info] afl-fuzz:     $(command -v afl-fuzz)"

# ── macOS: ensure Xcode linker is on PATH for afl-clang-fast ─
XCODE_BIN="/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin"
if [ -d "$XCODE_BIN" ] && [[ ":$PATH:" != *":$XCODE_BIN:"* ]]; then
    export PATH="$XCODE_BIN:$PATH"
    echo "[info] added Xcode toolchain to PATH: $XCODE_BIN"
fi

# ── Helper: portable timeout ─────────────────────────────────
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

# ── Compile both an AFL-instrumented and a plain binary ──────
compile_problem() {
    local N="$1"
    local DIR="$AFL_BASE/$N"
    local SRC="$DIR/Problem${N}.c"
    local AFL_BIN="$DIR/Problem${N}_afl"
    local PLAIN_BIN="$DIR/Problem${N}"

    [ -f "$SRC" ] || { echo "  [ERROR] $SRC not found"; return 1; }

    # AFL-instrumented binary (used for fuzzing)
    if [ ! -f "$AFL_BIN" ]; then
        echo "  [compile afl ] Problem$N  →  $AFL_BIN"
        AFL_SKIP_CPUFREQ=1 AFL_MAP_SIZE=65536 "$AFL_CC" "$SRC" -o "$AFL_BIN" -lm \
            || { echo "  [ERROR] AFL compile failed"; return 1; }
    else
        echo "  [compile afl ] Problem$N  (cached)"
    fi

    # Plain binary (used by process_afl_run.py to extract error codes)
    if [ ! -f "$PLAIN_BIN" ]; then
        echo "  [compile plain] Problem$N  →  $PLAIN_BIN"
        xcrun cc "$SRC" -o "$PLAIN_BIN" -lm \
            || { echo "  [ERROR] plain compile failed"; return 1; }
    else
        echo "  [compile plain] Problem$N  (cached)"
    fi

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

    if ! compile_problem "$N"; then
        echo "  [SKIP] compile failed for Problem$N"
        continue
    fi

    AFL_BIN="$AFL_BASE/$N/Problem${N}_afl"
    PLAIN_BIN="$AFL_BASE/$N/Problem${N}"
    TESTS_DIR="$AFL_BASE/$N/tests"

    for S in $SEEDS; do
        CSV="$LOG_BASE/Problem${N}_afl_seed${S}.csv"
        if [ -f "$CSV" ]; then
            echo "  [skip] $CSV already exists"
            continue
        fi

        OUT_DIR="$LOG_BASE/Problem${N}_afl_seed${S}"
        LOG="$LOG_BASE/Problem${N}_afl_seed${S}.log"
        mkdir -p "$OUT_DIR"

        echo "  [run] afl seed=$S  (${DURATION}s)  →  $OUT_DIR"
        AFL_SKIP_CPUFREQ=1 \
        AFL_I_DONT_CARE_ABOUT_MISSING_CRASHES=1 \
        AFL_MAP_SIZE=65536 \
        run_with_timeout $((DURATION + 30)) \
            afl-fuzz \
                -s "$S" \
                -i "$TESTS_DIR" \
                -o "$OUT_DIR" \
                -V "$DURATION" \
                -t 5000 \
                -- "$AFL_BIN" \
            > "$LOG" 2>&1
        echo "    exit=$?  log=$LOG"

        # Post-process: crash files → CSV
        echo "  [process] extracting errors and coverage  →  $CSV"
        python3 "$PROCESS_SCRIPT" "$OUT_DIR" "$PLAIN_BIN" "$CSV" \
            || echo "  [WARN] post-processing failed for seed $S"
    done
done

echo ""
echo "Done. CSVs in $LOG_BASE/"
echo "Run: python3 scripts/analyze_task1.py   (Task 1+2 combined analysis)"

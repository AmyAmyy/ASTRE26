#!/usr/bin/env bash
# Compile each prepared KLEE program to LLVM bitcode and run KLEE on it.
# Per-problem output:
#   klee/<N>/Problem<N>.bc            LLVM bitcode
#   klee/<N>/klee-out/                KLEE working directory (overwritten each run)
#   klee/<N>/klee_summary.txt         summary from analyze_klee_live.py
#
# Usage:
#   bash scripts/run_klee.sh                    # all defaults, 5 min each
#   PROBLEMS="11 17" DURATION=600 bash scripts/run_klee.sh
#
# Requires: clang (with klee.h on the include path), klee on PATH.
# On macOS arm64 you almost certainly want to run this inside the lab Dev
# Container or a klee/klee Docker image.

set -uo pipefail

PROBLEMS=${PROBLEMS:-"11 12 13 14 15 17"}
DURATION=${DURATION:-300}                            # seconds, per problem
CLANG=${CLANG:-clang}                                # clang-6.0 if available
KLEE_INCLUDE=${KLEE_INCLUDE:-/usr/local/include}     # where klee/klee.h lives
KLEE_BIN=${KLEE_BIN:-klee}
ANALYZE="$(cd "$(dirname "$0")" && pwd)/analyze_klee_live.py"

if ! command -v "$KLEE_BIN" >/dev/null; then
    echo "klee binary not found ('$KLEE_BIN'). Install KLEE or run in the Dev Container."
    exit 1
fi

for N in $PROBLEMS; do
    DIR="klee/$N"
    SRC="$DIR/Problem$N.c"
    BC="$DIR/Problem$N.bc"
    OUT="$DIR/klee-out"
    SUMMARY="$DIR/klee_summary.txt"

    if [ ! -f "$SRC" ]; then
        echo "[skip] $SRC missing -- run scripts/prepare_klee.sh first"
        continue
    fi

    echo "==========================================="
    echo " KLEE on Problem $N (budget ${DURATION}s)"
    echo "==========================================="

    echo "[compile] $SRC -> $BC"
    "$CLANG" -I "$KLEE_INCLUDE" -emit-llvm -g -c "$SRC" -o "$BC" \
        || { echo "compile failed"; continue; }

    rm -rf "$OUT"
    echo "[run] $KLEE_BIN --output-dir=$OUT --max-time=${DURATION} $BC"
    # Pipe stderr (where KLEE prints error_NN) through the analyzer so we get a
    # per-error timestamp summary.  Also keep the full log.
    "$KLEE_BIN" --output-dir="$OUT" --max-time="${DURATION}" --max-memory=500 "$BC" 2>&1 \
        | tee "$DIR/klee_full.log" \
        | python3 "$ANALYZE" > "$SUMMARY"

    echo "[summary] $SUMMARY"
    tail -n 20 "$SUMMARY"
    echo
done

echo "Done. Summaries in klee/*/klee_summary.txt"

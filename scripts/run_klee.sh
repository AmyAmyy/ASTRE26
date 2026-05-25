#!/usr/bin/env bash
# Compile each prepared KLEE program to LLVM bitcode and run KLEE on it.
# Designed to work inside the lab Dev Container (clintoncao/aistr:latest)
# with no extra configuration needed. Override any of the auto-detected
# paths via environment variables if needed:
#
#   PROBLEMS    space-separated list (default: 11 12 13 14 15 17)
#   DURATION    per-problem time budget in seconds (default: 300)
#   CLANG       clang binary (auto: clang-6.0 > clang)
#   KLEE_BIN    klee binary  (auto: klee on PATH, then common build dirs)
#   KLEE_INCLUDE  folder containing klee/klee.h  (auto-detected)
#
# Per-problem output:
#   klee/<N>/Problem<N>.bc
#   klee/<N>/klee-out/
#   klee/<N>/klee_full.log
#   klee/<N>/klee_summary.txt

set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROBLEMS=${PROBLEMS:-"11 12 13 14 15 17"}
DURATION=${DURATION:-300}
ANALYZE="$ROOT/scripts/analyze_klee_live.py"

# ---- locate clang ---------------------------------------------------------
if [ -z "${CLANG:-}" ]; then
    for c in clang-6.0 clang-9 clang-11 clang-13 clang; do
        if command -v "$c" >/dev/null 2>&1; then CLANG="$c"; break; fi
    done
fi
if ! command -v "${CLANG:-}" >/dev/null 2>&1; then
    echo "ERROR: no clang found on PATH. Install clang or set CLANG=..." >&2
    exit 1
fi

# ---- locate klee binary --------------------------------------------------
if [ -z "${KLEE_BIN:-}" ]; then
    if command -v klee >/dev/null 2>&1; then
        KLEE_BIN=klee
    else
        for candidate in \
            /home/str/klee/build/bin/klee \
            /home/klee/klee_build/bin/klee \
            /usr/local/bin/klee \
            /opt/klee/build/bin/klee; do
            if [ -x "$candidate" ]; then KLEE_BIN="$candidate"; break; fi
        done
    fi
fi
if [ -z "${KLEE_BIN:-}" ] || [ ! -x "$(command -v "$KLEE_BIN" 2>/dev/null || echo "$KLEE_BIN")" ]; then
    echo "ERROR: klee binary not found. Set KLEE_BIN=/path/to/klee or run in the Dev Container." >&2
    exit 1
fi

# ---- locate klee/klee.h --------------------------------------------------
if [ -z "${KLEE_INCLUDE:-}" ]; then
    for d in \
        /home/str/klee/source/include \
        /home/str/klee/include \
        /home/klee/klee_src/include \
        /usr/local/include \
        /usr/include; do
        if [ -f "$d/klee/klee.h" ]; then KLEE_INCLUDE="$d"; break; fi
    done
fi
if [ -z "${KLEE_INCLUDE:-}" ] || [ ! -f "$KLEE_INCLUDE/klee/klee.h" ]; then
    echo "ERROR: klee/klee.h not found. Set KLEE_INCLUDE=/path/containing/klee/ subfolder." >&2
    exit 1
fi

# ---- locate klee runtime lib (only needed for replay, not the experiment) -
KLEE_LIB=${KLEE_LIB:-}
if [ -z "$KLEE_LIB" ]; then
    for d in \
        /home/str/klee/build/lib \
        /home/klee/klee_build/lib \
        /usr/local/lib; do
        if [ -d "$d" ]; then KLEE_LIB="$d"; break; fi
    done
fi
export LD_LIBRARY_PATH="${KLEE_LIB:-}:${LD_LIBRARY_PATH:-}"

echo "Using:"
echo "  CLANG         = $CLANG"
echo "  KLEE_BIN      = $KLEE_BIN"
echo "  KLEE_INCLUDE  = $KLEE_INCLUDE"
echo "  KLEE_LIB      = ${KLEE_LIB:-(not set)}"
echo

# ---- run -----------------------------------------------------------------
for N in $PROBLEMS; do
    DIR="$ROOT/klee/$N"
    SRC="$DIR/Problem$N.c"
    BC="$DIR/Problem$N.bc"
    OUT="$DIR/klee-out"
    SUMMARY="$DIR/klee_summary.txt"
    FULL="$DIR/klee_full.log"

    if [ ! -f "$SRC" ]; then
        echo "[skip] $SRC missing -- run scripts/prepare_klee.sh first"
        continue
    fi

    echo "==========================================="
    echo " KLEE on Problem $N (budget ${DURATION}s)"
    echo "==========================================="

    echo "[compile] $SRC -> $BC"
    "$CLANG" -I "$KLEE_INCLUDE" -emit-llvm -g -c "$SRC" -o "$BC" \
        || { echo "[error] compile failed for Problem $N"; continue; }

    rm -rf "$OUT"
    echo "[run] $KLEE_BIN --output-dir=$OUT --max-time=${DURATION} $BC"
    "$KLEE_BIN" --output-dir="$OUT" --max-time="${DURATION}" "$BC" 2>&1 \
        | tee "$FULL" \
        | python3 "$ANALYZE" > "$SUMMARY"

    echo "[summary] $SUMMARY"
    tail -n 20 "$SUMMARY"
    echo
done

echo "Done. Summaries in klee/*/klee_summary.txt"

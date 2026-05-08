#!/usr/bin/env bash
#
# run_afl.sh - Compile, fuzz, and analyze a single RERS problem with AFL++
#
# Usage: ./run_afl.sh <problem_number> [duration_seconds]
#   e.g. ./run_afl.sh 11
#        ./run_afl.sh 14 300
#
# Default duration is 300 seconds (5 minutes).

set -euo pipefail

PROBLEM_NUM="${1:?Usage: $0 <problem_number> [duration_seconds]}"
DURATION="${2:-300}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROBLEM_DIR="${SCRIPT_DIR}/${PROBLEM_NUM}"
SRC="${PROBLEM_DIR}/Problem${PROBLEM_NUM}.c"
BIN="${PROBLEM_DIR}/Problem${PROBLEM_NUM}"
TESTS_DIR="${PROBLEM_DIR}/tests"
FINDINGS_DIR="${PROBLEM_DIR}/findings"
ANALYZE_SCRIPT="${SCRIPT_DIR}/../scripts/analyze_afl.py"

# Compile
echo "=== [Problem ${PROBLEM_NUM}] Compiling with afl-clang-fast ==="
afl-clang-fast "$SRC" -o "$BIN"

# Clean previous findings
if [ -d "$FINDINGS_DIR" ] && [ "$(ls -A "$FINDINGS_DIR" 2>/dev/null)" ]; then
    echo "WARNING: findings/ is not empty. Removing old results..."
    rm -rf "$FINDINGS_DIR"
    mkdir -p "$FINDINGS_DIR"
fi

echo ""
echo "=== [Problem ${PROBLEM_NUM}] Running AFL for ${DURATION}s ==="
echo "  Input dir:    $TESTS_DIR"
echo "  Output dir:   $FINDINGS_DIR"
echo "  Binary:       $BIN"
echo ""

# Run AFL with timeout. macOS lacks 'timeout', so use perl one-liner as fallback.
# AFL returns non-zero on timeout, so we || true.
if command -v gtimeout &>/dev/null; then
    gtimeout "${DURATION}" afl-fuzz -i "$TESTS_DIR" -o "$FINDINGS_DIR" "$BIN" || true
elif command -v timeout &>/dev/null; then
    timeout "${DURATION}" afl-fuzz -i "$TESTS_DIR" -o "$FINDINGS_DIR" "$BIN" || true
else
    # Use perl alarm as a portable fallback
    perl -e "alarm ${DURATION}; exec @ARGV" -- afl-fuzz -i "$TESTS_DIR" -o "$FINDINGS_DIR" "$BIN" || true
fi

echo ""
echo "=== [Problem ${PROBLEM_NUM}] Fuzzing complete ==="

# --- Step 4: Analyze ---
# AFL with auto -S creates findings/default/
AFL_RESULT_DIR="${FINDINGS_DIR}/default"

if [ ! -d "${AFL_RESULT_DIR}/crashes" ]; then
    echo "WARNING: No crashes directory found. AFL may not have run successfully."
    echo "You can re-run manually: afl-fuzz -i ${TESTS_DIR} -o ${FINDINGS_DIR} ${BIN}"
else
    echo ""
    echo "=== [Problem ${PROBLEM_NUM}] Analyzing results ==="
    python3 "$ANALYZE_SCRIPT" "$AFL_RESULT_DIR" "$BIN"
fi

echo ""
echo "=== [Problem ${PROBLEM_NUM}] Done ==="
echo "Crash files:     ${AFL_RESULT_DIR}/crashes/"
echo "Queue (traces):  ${AFL_RESULT_DIR}/queue/"

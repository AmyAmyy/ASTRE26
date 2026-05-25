#!/usr/bin/env bash
# Generate KLEE-ready C files from the AFL C sources in afl/<N>/Problem<N>.c.
# For each problem we replace the original `int main()` with a symbolic-input main
# that uses klee_make_symbolic, restricted to the problem-specific input alphabet
# read from the `int inputs[] = {...};` line in the source.
#
# Output goes to klee/<N>/Problem<N>.c
#
# Usage: bash scripts/prepare_klee.sh [length]
#   length = number of symbolic inputs per program (default 20)

set -euo pipefail

LEN="${1:-20}"
PROBLEMS=(11 12 13 14 15 17)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

for N in "${PROBLEMS[@]}"; do
    SRC="$ROOT/afl/$N/Problem$N.c"
    if [ ! -f "$SRC" ]; then echo "[skip] $SRC missing"; continue; fi

    DEST_DIR="$ROOT/klee/$N"
    mkdir -p "$DEST_DIR"
    DEST="$DEST_DIR/Problem$N.c"

    # Extract the inputs alphabet, e.g. "4,3,10,2,9,6,1,8,7,5"
    ALPHABET=$(grep -m1 "int inputs\[\]" "$SRC" \
        | awk -F'[{}]' '{print $2}' \
        | tr -d ' \t')
    if [ -z "$ALPHABET" ]; then
        echo "[skip] could not read inputs[] from $SRC"
        continue
    fi

    # Build the "input != A && input != B ..." guard.
    GUARD=""
    IFS=',' read -ra VALS <<< "$ALPHABET"
    for V in "${VALS[@]}"; do
        if [ -z "$GUARD" ]; then
            GUARD="(input != $V)"
        else
            GUARD="$GUARD && (input != $V)"
        fi
    done

    # Copy source up to (but not including) the original `int main()`.
    awk '/^int main\(\)/ {exit} {print}' "$SRC" > "$DEST"

    # Add klee.h include at the top (after the existing #includes).
    # We insert it by replacing the first #include with itself + klee include.
    # (sed -i differs between BSD/GNU; use a portable two-step.)
    awk 'BEGIN{added=0}
         /^#include/ && !added {print; print "#include <klee/klee.h>"; added=1; next}
         {print}' "$DEST" > "$DEST.tmp" && mv "$DEST.tmp" "$DEST"

    cat >> "$DEST" <<EOF

int main()
{
    int length = $LEN;
    int program[length];
    klee_make_symbolic(program, sizeof(program), "program");

    for (int i = 0; i < length; ++i) {
        int input = program[i];
        if ($GUARD) return 0;
        calculate_output(input);
    }
    return 0;
}
EOF

    echo "[ok] $DEST  (alphabet: $ALPHABET, length: $LEN)"
done

echo "Done."

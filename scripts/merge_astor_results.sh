#!/usr/bin/env bash
# merge_astor_results.sh
#
# Concatenates astor_runs/results_A.csv and astor_runs/results_B.csv (produced
# when two collaborators each ran "HALF=A bash scripts/run_astor.sh" and
# "HALF=B bash scripts/run_astor.sh") into a single astor_runs/results.csv.
#
# Usage:
#     bash scripts/merge_astor_results.sh
#
# After both halves' astor_runs/ directories have been copied or rsynced into
# the same OUT_DIR, this script:
#   - keeps the header from results_A.csv,
#   - appends the data rows from results_A.csv and results_B.csv,
#   - warns about duplicate problem rows (e.g. someone ran the same problem
#     twice).

set -u
OUT_DIR="${OUT_DIR:-$PWD/astor_runs}"
A="$OUT_DIR/results_A.csv"
B="$OUT_DIR/results_B.csv"
MERGED="$OUT_DIR/results.csv"

if [[ ! -f "$A" && ! -f "$B" ]]; then
    echo "ERROR: neither $A nor $B exist." >&2
    exit 1
fi

# Pick whichever exists for the header.
header_src="$A"
[[ ! -f "$A" ]] && header_src="$B"
head -n1 "$header_src" > "$MERGED"

for f in "$A" "$B"; do
    if [[ -f "$f" ]]; then
        tail -n +2 "$f" >> "$MERGED"
    else
        echo "  note: $f missing, skipping" >&2
    fi
done

# Detect duplicate problem names (column 1).
dups=$(tail -n +2 "$MERGED" | cut -d, -f1 | sort | uniq -d)
if [[ -n "$dups" ]]; then
    echo "WARNING: duplicate problem rows in $MERGED:" >&2
    echo "$dups" >&2
fi

echo "Merged into $MERGED:"
column -s, -t "$MERGED"

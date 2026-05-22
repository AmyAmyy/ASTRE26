#!/usr/bin/env bash
# diff_astor_patches.sh
#
# Walks every astor_runs/<Problem>_buggy/output_astor/ directory produced by
# run_astor.sh and prints a diff between:
#   - the buggy source ASTOR was given,
#   - each patched variant ASTOR produced.
#
# If you also have the original (non-buggy) RERS sources, set ORIGINALS_DIR
# to the folder that contains Problem<N>.java files; this script will then
# additionally diff buggy-vs-original so you can judge whether ASTOR's patch
# matches the seeded-fault location.
#
# Usage (from JavaInstrumentation root, after run_astor.sh has finished):
#     bash scripts/diff_astor_patches.sh                            # buggy vs patched only
#     ORIGINALS_DIR=/path/to/originals bash scripts/diff_astor_patches.sh
#
# Output: prints to stdout. Pipe to a file if you want to attach to the report.

set -u

OUT_DIR="${OUT_DIR:-$PWD/astor_runs}"
BUGGY_ROOT="${BUGGY_ROOT:-$PWD/Buggy_RERS-ASTOR}"
ORIGINALS_DIR="${ORIGINALS_DIR:-}"

if [[ ! -d "$OUT_DIR" ]]; then
    echo "ERROR: $OUT_DIR not found. Run scripts/run_astor.sh first." >&2
    exit 1
fi

# Locate the buggy Problem<N>.java for a given problem folder.
locate_buggy_source() {
    local pdir="$1"
    find "$pdir/src/main/java" -name 'Problem*.java' -type f 2>/dev/null | head -n1
}

# Locate the original (non-buggy) Problem<N>.java if ORIGINALS_DIR is set.
locate_original_source() {
    local n="$1"
    [[ -z "$ORIGINALS_DIR" ]] && return 1
    local cand
    cand=$(find "$ORIGINALS_DIR" -name "Problem${n}.java" -type f 2>/dev/null | head -n1)
    [[ -n "$cand" ]] && echo "$cand"
}

for rundir in "$OUT_DIR"/Problem*_buggy; do
    [[ -d "$rundir" ]] || continue
    pname=$(basename "$rundir")          # e.g. Problem11_buggy
    pnum=${pname#Problem}; pnum=${pnum%_buggy}

    echo "=========================================================="
    echo "  $pname"
    echo "=========================================================="

    buggy_src=$(locate_buggy_source "$BUGGY_ROOT/$pname")
    if [[ -z "$buggy_src" ]]; then
        echo "  (could not locate buggy source under $BUGGY_ROOT/$pname)"
        continue
    fi
    echo "  buggy:  $buggy_src"

    if orig_src=$(locate_original_source "$pnum"); then
        echo "  orig:   $orig_src"
        echo
        echo "  --- diff: original vs buggy (the seeded fault) ---"
        diff -u "$orig_src" "$buggy_src" || true
    fi

    patched_dir="$rundir/output_astor"
    if [[ ! -d "$patched_dir" ]]; then
        echo
        echo "  no output_astor for $pname (no patches found, or run failed)"
        continue
    fi

    # ASTOR puts validated solutions under output_astor/AstorMain-<p>/src/variant-*_f/.
    # Prefer those (final/validated); fall back to any variant-* if none found.
    # NOTE: macOS ships bash 3.2, so we avoid `mapfile` (bash 4+) here.
    patched_files=()
    while IFS= read -r line; do
        [[ -n "$line" ]] && patched_files+=("$line")
    done < <(find "$patched_dir" -type d -name 'variant-*_f' \
                  -exec find {} -type f -name 'Problem*.java' \; 2>/dev/null)
    if [[ ${#patched_files[@]} -eq 0 ]]; then
        while IFS= read -r line; do
            [[ -n "$line" ]] && patched_files+=("$line")
        done < <(find "$patched_dir" -type f -name 'Problem*.java')
    fi
    if [[ ${#patched_files[@]} -eq 0 ]]; then
        echo
        echo "  no patched Problem*.java found under $patched_dir"
        continue
    fi

    for pf in "${patched_files[@]}"; do
        echo
        echo "  --- diff: buggy vs patched ($pf) ---"
        diff -u "$buggy_src" "$pf" || true
    done
done

echo
echo "Done. Tip: redirect this script's stdout to a file and grep for ^@@ to"
echo "see all hunks at a glance."

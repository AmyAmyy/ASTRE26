#!/usr/bin/env bash
# run_astor.sh
#
# Runs ASTOR (jGenProg, operator-space) on a list of buggy RERS problems and
# collects timing + patch-count statistics into a CSV for the report.
#
# Intended environment: TU Delft Dev Container (or any setup with
# /home/str/astor cloned + built, JDK 8/11, Maven).
#
# Usage (from /home/str/astor):
#     bash /path/to/run_astor.sh
#
# Override defaults with environment variables, e.g.:
#     BUGGY_ROOT=/home/str/Buggy_RERS-ASTOR MAXTIME=10 bash run_astor.sh
#
# Outputs:
#     astor_runs/<Problem>/stdout.log     full ASTOR stdout
#     astor_runs/<Problem>/stderr.log     full ASTOR stderr
#     astor_runs/<Problem>/output_astor/  ASTOR's patch output (copied)
#     astor_runs/results.csv              problem,wallclock_seconds,patches_found,astor_reported_time_s,astor_status

set -u

# ---------- configuration ----------
ASTOR_DIR="${ASTOR_DIR:-/home/str/astor}"
CLASSPATH_FILE="${CLASSPATH_FILE:-/tmp/astor-classpath.txt}"
BUGGY_ROOT="${BUGGY_ROOT:-/home/str/Buggy_RERS-ASTOR}"
OUT_DIR="${OUT_DIR:-$PWD/astor_runs}"

# ASTOR parameters (match the lab brief; tweak if needed).
MAXTIME="${MAXTIME:-5}"          # minutes per problem
MAXGEN="${MAXGEN:-3000}"
POPULATION="${POPULATION:-8}"
SCOPE="${SCOPE:-local}"
OPERATORSPACE="${OPERATORSPACE:-relational-Logical-op}"
MODE="${MODE:-jgenprog}"

# Six problems for the experiment. Default selection: the four problems
# that overlap with our Task 3 set (Problem1, Problem7, Problem11, Problem12)
# plus two extras for breadth.
# Buggy_RERS-ASTOR available: 1, 2, 3, 5, 6, 7, 8, 11, 12, 13, 14, 17.
DEFAULT_PROBLEMS=(Problem1_buggy Problem2_buggy Problem7_buggy Problem11_buggy Problem12_buggy Problem13_buggy)

# Selecting which problems to run. Three ways, in order of precedence:
#
#   1. Pass them as positional args:
#          bash run_astor.sh Problem1_buggy Problem11_buggy
#
#   2. Set HALF=A or HALF=B to split DEFAULT_PROBLEMS in two for parallel
#      execution between two collaborators on different machines:
#          HALF=A bash run_astor.sh   # runs the first 3
#          HALF=B bash run_astor.sh   # runs the last 3
#      Results land in OUT_DIR/results_<HALF>.csv so the two CSVs can be
#      concatenated afterwards without collision.
#
#   3. Otherwise the full DEFAULT_PROBLEMS list is run.
HALF="${HALF:-}"
if [[ $# -gt 0 ]]; then
    PROBLEMS=("$@")
    RESULTS_NAME="results.csv"
elif [[ -n "$HALF" ]]; then
    n=${#DEFAULT_PROBLEMS[@]}
    half=$(( (n + 1) / 2 ))   # first half gets the extra item if n is odd
    case "$HALF" in
        A|a) PROBLEMS=("${DEFAULT_PROBLEMS[@]:0:$half}") ;;
        B|b) PROBLEMS=("${DEFAULT_PROBLEMS[@]:$half}") ;;
        *) echo "ERROR: HALF must be A or B (got: $HALF)" >&2; exit 1 ;;
    esac
    RESULTS_NAME="results_${HALF}.csv"
else
    PROBLEMS=("${DEFAULT_PROBLEMS[@]}")
    RESULTS_NAME="results.csv"
fi

# ---------- preflight ----------
if [[ ! -d "$ASTOR_DIR" ]]; then
    echo "ERROR: ASTOR_DIR not found: $ASTOR_DIR" >&2
    exit 1
fi
if [[ ! -f "$CLASSPATH_FILE" ]]; then
    echo "Classpath file missing; generating $CLASSPATH_FILE..." >&2
    echo "  (this runs 'mvn dependency:build-classpath' in $ASTOR_DIR" >&2
    echo "   and may take 1-5 minutes on first run while deps download.)" >&2
    # Stream maven output to stderr so the user sees progress; capture only
    # the classpath line(s) into the file.
    ( cd "$ASTOR_DIR" && \
      mvn dependency:build-classpath -B 2>&1 \
      | tee /dev/stderr \
      | egrep -v "(^\[INFO\]|^\[WARNING\]|^\[ERROR\])" \
      > "$CLASSPATH_FILE" ) \
      || { echo "ERROR: failed to build classpath" >&2; exit 1; }
    if [[ ! -s "$CLASSPATH_FILE" ]]; then
        echo "ERROR: classpath file is empty after generation; check $ASTOR_DIR build state" >&2
        exit 1
    fi
fi
if [[ ! -d "$BUGGY_ROOT" ]]; then
    echo "ERROR: BUGGY_ROOT not found: $BUGGY_ROOT" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
RESULTS_CSV="$OUT_DIR/$RESULTS_NAME"
echo "problem,wallclock_seconds,patches_found,astor_reported_time_s,astor_status" > "$RESULTS_CSV"

ASTOR_CP="$(cat "$CLASSPATH_FILE"):$ASTOR_DIR/target/classes"

# ---------- helpers ----------
build_problem() {
    local p="$1"
    local dir="$BUGGY_ROOT/$p"
    if [[ ! -d "$dir" ]]; then
        echo "  skipping $p: directory missing ($dir)" >&2
        return 1
    fi
    echo "  mvn clean + install for $p ..."
    ( cd "$dir" && mvn -q clean && mvn -q install -DskipTests ) \
        || { echo "  BUILD FAILED for $p" >&2; return 1; }
    return 0
}

run_astor_for() {
    local p="$1"
    local pdir="$BUGGY_ROOT/$p"
    local rundir="$OUT_DIR/$p"
    mkdir -p "$rundir"

    echo "  running ASTOR on $p (maxtime=${MAXTIME}m, maxgen=$MAXGEN, pop=$POPULATION) ..."
    local t0=$(date +%s)
    ( cd "$ASTOR_DIR" && \
      java -cp "$ASTOR_CP" fr.inria.main.evolution.AstorMain \
        -mode "$MODE" \
        -srcjavafolder /src/main/java/ \
        -srctestfolder /src/test/java/ \
        -binjavafolder /target/classes/ \
        -bintestfolder /target/test-classes/ \
        -location "$pdir" \
        -maxtime "$MAXTIME" \
        -maxgen "$MAXGEN" \
        -scope "$SCOPE" \
        -population "$POPULATION" \
        -operatorspace "$OPERATORSPACE" \
        > "$rundir/stdout.log" 2> "$rundir/stderr.log" )
    local rc=$?
    local t1=$(date +%s)
    local wall=$((t1 - t0))

    # ASTOR writes to $ASTOR_DIR/output_astor/AstorMain-<problem>/.
    # Copy that subtree into the run dir so each problem's artifacts are
    # self-contained alongside the logs.
    local astor_out="$ASTOR_DIR/output_astor/AstorMain-$p"
    if [[ -d "$astor_out" ]]; then
        rm -rf "$rundir/output_astor"
        mkdir -p "$rundir/output_astor"
        cp -R "$astor_out" "$rundir/output_astor/"
    fi

    # Parse the structured astor_output.json (much more reliable than stdout
    # regex). Falls back to stdout/file scans if the JSON is missing.
    local json="$rundir/output_astor/AstorMain-$p/astor_output.json"
    local patches="" astor_time="" astor_status=""
    if [[ -f "$json" ]]; then
        read -r patches astor_time astor_status < <(python3 -c '
import json, sys
with open(sys.argv[1]) as f:
    d = json.load(f)
g = d.get("general", {})
print(len(d.get("patches", [])), g.get("TOTAL_TIME", "NA"), g.get("OUTPUT_STATUS", "NA"))
' "$json" 2>/dev/null) || true
    fi
    if [[ -z "${patches:-}" ]]; then
        # Fallback: count variant-*_f folders (final/validated solutions).
        if [[ -d "$rundir/output_astor" ]]; then
            patches=$(find "$rundir/output_astor" -type d -name 'variant-*_f' | wc -l | tr -d ' ')
        else
            patches=0
        fi
    fi
    [[ -z "${astor_time:-}" ]] && astor_time="NA"
    [[ -z "${astor_status:-}" ]] && astor_status=""

    local status="ok"
    if [[ $rc -ne 0 ]]; then status="exit_$rc"; fi
    [[ -n "$astor_status" ]] && status="$status:$astor_status"

    echo "$p,$wall,$patches,$astor_time,$status" >> "$RESULTS_CSV"
    echo "  -> wallclock=${wall}s patches=$patches astor_time=${astor_time}s status=$status"
}

# ---------- main ----------
echo "ASTOR experiment driver"
echo "  ASTOR_DIR     = $ASTOR_DIR"
echo "  BUGGY_ROOT    = $BUGGY_ROOT"
echo "  OUT_DIR       = $OUT_DIR"
echo "  problems      = ${PROBLEMS[*]}"
echo "  results CSV   = $RESULTS_CSV"
[[ -n "$HALF" ]] && echo "  HALF          = $HALF"
echo "  MAXTIME (min) = $MAXTIME"
echo "  MAXGEN        = $MAXGEN"
echo "  POPULATION    = $POPULATION"
echo

for p in "${PROBLEMS[@]}"; do
    echo ">>> $p"
    if build_problem "$p"; then
        run_astor_for "$p"
    else
        echo "$p,0,0,NA,build_failed" >> "$RESULTS_CSV"
    fi
done

echo
echo "Done. Results written to $RESULTS_CSV"
column -s, -t "$RESULTS_CSV"

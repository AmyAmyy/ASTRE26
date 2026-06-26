#!/bin/bash
# ============================================================
# preinstrument.sh  –  Instrument and compile all RERS problems
#
# Produces:
#   instrumented/fuzzing/ProblemN.java  +  .class
#   instrumented/concolic/ProblemN.java +  .class
#
# Usage:
#   bash scripts/preinstrument.sh
#   PROBLEMS="16 18 19" bash scripts/preinstrument.sh   # subset
# ============================================================
set -u

PROBLEMS=${PROBLEMS:-"11 12 13 14 15 16 17 18 19"}
DATASET=${DATASET:-"./SeqReachabilityRers2020"}

CP_FUZZ="target/aistr.jar:."
CP_CONC="target/aistr.jar:lib/com.microsoft.z3.jar:."

PASS=0
FAIL=0
FAILED_LIST=""

# ── Build if needed ─────────────────────────────────────────
if [ ! -f target/aistr.jar ]; then
    echo "[build] target/aistr.jar missing – running mvn package"
    mvn -q -DskipTests package || { echo "Maven build failed"; exit 1; }
fi

mkdir -p instrumented/fuzzing instrumented/concolic

# ── Helper ──────────────────────────────────────────────────
instrument_and_compile() {
    local N="$1" type="$2" cp="$3"
    local DIR="instrumented/$type"
    local OUT="$DIR/Problem${N}.java"

    local SRC="$DATASET/Problem$N.java"
    [ -f "$SRC" ] || SRC="$DATASET/Problem$N/Problem$N.java"
    if [ ! -f "$SRC" ]; then
        echo "  [ERROR] source not found for Problem$N"
        return 1
    fi

    echo "  [instrument --type=$type] Problem$N"
    java -XX:+UseG1GC -Xmx6g -cp target/aistr.jar \
        nl.tudelft.instrumentation.Main \
        --type="$type" --file="$SRC" \
        > "$OUT" \
        || { echo "  [ERROR] instrumentation failed"; return 1; }

    echo "  [compile   --type=$type] Problem$N"
    javac -cp "$cp" -d "$DIR" Errors.java "$OUT" \
        || { echo "  [ERROR] compile failed"; return 1; }

    echo "  [OK] Problem$N  $type"
    return 0
}

# ── Main loop ────────────────────────────────────────────────
for N in $PROBLEMS; do
    echo ""
    echo "==========================================================="
    echo " Problem $N"
    echo "==========================================================="

    for TYPE in fuzzing concolic; do
        CP="$CP_FUZZ"
        [ "$TYPE" = "concolic" ] && CP="$CP_CONC"

        if instrument_and_compile "$N" "$TYPE" "$CP"; then
            PASS=$((PASS + 1))
        else
            FAIL=$((FAIL + 1))
            FAILED_LIST="$FAILED_LIST Problem${N}_${TYPE}"
        fi
    done
done

# ── Summary ──────────────────────────────────────────────────
echo ""
echo "==========================================================="
echo " Pre-instrumentation summary"
echo "==========================================================="
echo "  PASS : $PASS"
echo "  FAIL : $FAIL"
if [ -n "$FAILED_LIST" ]; then
    echo "  Failed:$FAILED_LIST"
fi

if [ "$FAIL" -eq 0 ]; then
    echo ""
    echo "All problems instrumented and compiled successfully."
    echo "You can now run:  bash scripts/run_task1.sh"
else
    echo ""
    echo "Some problems failed. Fix errors above before running the experiment."
    exit 1
fi

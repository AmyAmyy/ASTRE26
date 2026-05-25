#!/bin/bash
# Run the ConcolicExecutionLab on each RERS problem and collect convergence CSVs.
#
# Steps per problem:
#   1. (Re)instrument the problem with --type=concolic.
#   2. Compile the instrumented file.
#   3. Run it. ConcolicExecutionLab writes logs/Problem<N>_concolic.csv
#      with columns time_ms,unique_errors.
#
# Usage:
#   bash scripts/run_concolic.sh                # uses default problem list
#   PROBLEMS="11 12 17" bash scripts/run_concolic.sh
#
# Requires target/aistr.jar to be built (mvn -DskipTests package).

set -u

PROBLEMS=${PROBLEMS:-"12 13 14 15 17"}
DATASET=${DATASET:-"./RERS2020Buggy"}
Z3_LIB=${Z3_LIB:-"."}     # folder containing libz3java.dylib (and libz3.dylib on macOS)
CP="target/aistr.jar:lib/com.microsoft.z3.jar:."

mkdir -p instrumented logs

if [ ! -f target/aistr.jar ]; then
    echo "[build] target/aistr.jar missing - running mvn package"
    mvn -q -DskipTests package || { echo "Maven build failed"; exit 1; }
fi

for N in $PROBLEMS; do
    SRC="$DATASET/Problem$N.java"
    if [ ! -f "$SRC" ]; then
        SRC="$DATASET/Problem$N/Problem$N.java"
    fi
    if [ ! -f "$SRC" ]; then
        echo "[skip] Problem$N source not found"
        continue
    fi

    echo "==========================================="
    echo " Problem $N"
    echo "==========================================="

    echo "[instrument] $SRC"
    java -XX:+UseG1GC -Xmx4g -cp target/aistr.jar \
        nl.tudelft.instrumentation.Main --type=concolic --file="$SRC" \
        > "instrumented/Problem$N.java" || { echo "instrument failed"; continue; }

    echo "[compile]"
    javac -cp "$CP" Errors.java "instrumented/Problem$N.java" \
        || { echo "compile failed"; continue; }

    echo "[run] (logs/Problem${N}_concolic.csv)"
    java -Djava.library.path="$Z3_LIB" \
        -cp "target/aistr.jar:lib/com.microsoft.z3.jar:./instrumented:." \
        "Problem$N" 2>&1 | tee "logs/Problem${N}_concolic.log" | grep -E "iter|error_|Wrote|Exception"
    echo
done

echo "Done. CSVs in logs/."

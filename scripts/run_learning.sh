#!/bin/bash
# Script to run all Lab 4 experiments (L* and LearnLib TTT)
# Results are saved to logs/results_lab_4/

set -e

BASEDIR="$(cd "$(dirname "$0")/.." && pwd)"
RESULTS="$BASEDIR/logs/results_lab_4"
CP="$BASEDIR/target/aistr.jar:$BASEDIR/lib/com.microsoft.z3.jar:$BASEDIR/instrumented:."

# Build
echo "=== Building project ==="
mvn -f "$BASEDIR/pom.xml" clean package -DskipTests -q
echo "Build complete."

# Instrument all problems
echo "=== Instrumenting problems ==="
mkdir -p "$BASEDIR/instrumented"

# RERS problems 1, 2, 4, 7
for p in 1 2 4 7; do
    echo "Instrumenting Problem${p}..."
    java -cp "$BASEDIR/target/aistr.jar" nl.tudelft.instrumentation.Main \
        --type=learning \
        --file="$BASEDIR/src/main/resources/rers2020_test_cases/Problem${p}.java" \
        > "$BASEDIR/instrumented/Problem${p}.java"
    javac -cp "$BASEDIR/target/aistr.jar:$BASEDIR/lib/com.microsoft.z3.jar:." \
        "$BASEDIR/Errors.java" "$BASEDIR/instrumented/Problem${p}.java"
done

# ProblemPin
echo "Instrumenting ProblemPin..."
java -cp "$BASEDIR/target/aistr.jar" nl.tudelft.instrumentation.Main \
    --type=learning \
    --file="$BASEDIR/custom_problems/ProblemPin.java" \
    > "$BASEDIR/instrumented/ProblemPin.java"
javac -cp "$BASEDIR/target/aistr.jar:$BASEDIR/lib/com.microsoft.z3.jar:." \
    "$BASEDIR/Errors.java" "$BASEDIR/instrumented/ProblemPin.java"

echo "Instrumentation complete."
echo ""

# Create results directories
mkdir -p "$RESULTS"


echo "=== Running L* (our implementation) ==="

# RERS problems with w=3
for p in 1 2 4 7; do
    OUTDIR="$RESULTS/lstar/Problem${p}"
    mkdir -p "$OUTDIR"
    echo "Running Problem${p} (w=3)..."
    java -Dw=3 -DoutputDir="$OUTDIR" \
        -cp "$CP" Problem${p} \
        > "$OUTDIR/output.log" 2>&1
    echo "  Done. See $OUTDIR/output.log"
done

# ProblemPin with w=4
OUTDIR="$RESULTS/lstar/ProblemPin"
mkdir -p "$OUTDIR"
echo "Running ProblemPin (w=4)..."
java -Dw=4 -DoutputDir="$OUTDIR" \
    -cp "$CP" ProblemPin \
    > "$OUTDIR/output.log" 2>&1
echo "  Done. See $OUTDIR/output.log"

echo ""

# ============================================================
# Run LearnLib TTT
# ============================================================
echo "=== Running LearnLib TTT ==="

# RERS problems with w=3
for p in 1 2 4 7; do
    OUTDIR="$RESULTS/ttt/Problem${p}"
    mkdir -p "$OUTDIR"
    echo "Running Problem${p} (TTT, w=3)..."
    java -Dlearnlib -Dw=3 -DoutputDir="$OUTDIR" \
        -cp "$CP" Problem${p} \
        > "$OUTDIR/output.log" 2>&1
    # Move learnlib dot file if generated
    [ -f "$BASEDIR/learnlib-final.dot" ] && mv "$BASEDIR/learnlib-final.dot" "$OUTDIR/hypothesis-final.dot"
    echo "  Done. See $OUTDIR/output.log"
done

# ProblemPin with w=4
OUTDIR="$RESULTS/ttt/ProblemPin"
mkdir -p "$OUTDIR"
echo "Running ProblemPin (TTT, w=4)..."
java -Dlearnlib -Dw=4 -DoutputDir="$OUTDIR" \
    -cp "$CP" ProblemPin \
    > "$OUTDIR/output.log" 2>&1
[ -f "$BASEDIR/learnlib-final.dot" ] && mv "$BASEDIR/learnlib-final.dot" "$OUTDIR/hypothesis-final.dot"
echo "  Done. See $OUTDIR/output.log"

echo ""
echo "=== All experiments complete ==="
echo "Results saved to: $RESULTS"
echo ""
echo "Directory structure:"
find "$RESULTS" -type f | sort

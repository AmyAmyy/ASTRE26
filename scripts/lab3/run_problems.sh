#!/bin/bash

PROBLEMS=(1 4 7 11 12 15)

for N in "${PROBLEMS[@]}"; do
    echo "========================================="
    echo " Problem $N"
    echo "========================================="

    echo "[Compiling Problem$N...]"
    javac -cp target/aistr.jar:. instrumented/Problem$N.java
    if [ $? -ne 0 ]; then
        echo "[ERROR] Compilation failed for Problem$N. Skipping."
        echo ""
        continue
    fi

    echo "[Running Problem$N...]"
    java -cp target/aistr.jar:./instrumented:. Problem$N
    echo ""
done

echo "Done."
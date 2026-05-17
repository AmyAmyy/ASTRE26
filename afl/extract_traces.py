#!/usr/bin/env python3
"""
extract_traces.py - Extract the input traces that triggered each error code.

Usage: python3 extract_traces.py <problem_number>
   e.g. python3 extract_traces.py 11

Outputs for each error: the sequence of integers that triggered it.
This allows comparison with Hill Climber traces.
"""
import os
import re
import subprocess
import sys
import string

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 extract_traces.py <problem_number>")
        sys.exit(1)

    problem_num = sys.argv[1]
    script_dir = os.path.dirname(os.path.abspath(__file__))
    problem_dir = os.path.join(script_dir, problem_num)
    binary = os.path.join(problem_dir, f"Problem{problem_num}")
    findings_dir = os.path.join(problem_dir, "findings", "default")

    # Fallback if no 'default' subdirectory
    if not os.path.isdir(findings_dir):
        findings_dir = os.path.join(problem_dir, "findings")

    crash_path = os.path.join(findings_dir, "crashes")

    if not os.path.isfile(binary):
        print(f"ERROR: Binary not found: {binary}")
        sys.exit(1)
    if not os.path.isdir(crash_path):
        print(f"ERROR: Crashes directory not found: {crash_path}")
        sys.exit(1)

    # Map: error_code -> (trace_as_int_list, crash_file_name)
    error_traces = {}

    for crash_file_name in sorted(os.listdir(crash_path)):
        if not crash_file_name.startswith("id:"):
            continue

        crash_file = os.path.join(crash_path, crash_file_name)

        with open(crash_file, "rb") as f:
            content = f.read()

        # Run binary with this input
        p = subprocess.Popen(
            [binary], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE
        )
        try:
            stdout, stderr = p.communicate(content, timeout=10)
        except subprocess.TimeoutExpired:
            p.kill()
            continue

        stderr_text = stderr.decode(errors="replace")

        # Find error codes
        errors_found = re.findall(r"error_(\d+)", stderr_text)

        # Parse the input trace: extract integers from the raw content
        # AFL feeds raw text to scanf, so we parse the integers from it
        try:
            text = content.decode("utf-8", errors="replace")
            # Extract all integers from the input
            trace = re.findall(r"-?\d+", text)
            trace = [int(x) for x in trace]
        except:
            trace = [f"<raw: {content.hex()}>"]

        for err_num in errors_found:
            err_key = f"error_{err_num}"
            if err_key not in error_traces:
                error_traces[err_key] = {
                    "trace": trace,
                    "file": crash_file_name,
                    "output": stdout.decode(errors="replace").strip(),
                }

    # Print results
    if not error_traces:
        print("No errors found.")
        return

    def int_to_letter(n):
        """Convert integer to letter: 1->A, 2->B, ..., 26->Z"""
        if 1 <= n <= 26:
            return string.ascii_uppercase[n - 1]
        return str(n)

    for err_key in sorted(error_traces.keys(), key=lambda x: int(x.split("_")[1])):
        info = error_traces[err_key]
        trace_ints = " ".join(str(x) for x in info["trace"])
        trace_letters = " ".join(int_to_letter(x) if isinstance(x, int) else str(x) for x in info["trace"])
        print(f"{err_key}:")
        print(f"  Trace (integers):  [{trace_ints}]")
        print(f"  Trace (letters):   [{trace_letters}]")
        print()


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
process_afl_run.py  –  Convert one AFL run directory into a CSV file.

Produces the same format as Task 1:
    time_ms , unique_errors , unique_branches

  time_ms         : milliseconds since AFL started
  unique_errors   : cumulative count of unique reachability error IDs found
  unique_branches : AFL edges_found (from plot_data) – proxy for branch coverage

Usage:
    python3 scripts/process_afl_run.py <afl_output_dir> <binary> <out_csv>

    afl_output_dir : path to the AFL findings directory that contains
                     default/plot_data and default/crashes/
    binary         : the compiled (NON-afl-instrumented) C binary used to
                     replay crash files and extract error codes
    out_csv        : path to write the result CSV
"""
import argparse
import os
import re
import subprocess
import sys


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def read_plot_data(afl_dir: str):
    """
    Parse AFL's plot_data file.
    Returns list of dicts with keys: relative_time_s, edges_found, saved_crashes
    """
    # AFL++ >= 4.x stores results under afl_dir/default/
    for candidate in [
        os.path.join(afl_dir, "default", "plot_data"),
        os.path.join(afl_dir, "plot_data"),
    ]:
        if os.path.isfile(candidate):
            plot_path = candidate
            break
    else:
        print(f"[WARN] plot_data not found in {afl_dir}", file=sys.stderr)
        return []

    rows = []
    with open(plot_path) as f:
        for line in f:
            line = line.strip()
            if line.startswith("#") or not line:
                continue
            parts = [p.strip().rstrip("%") for p in line.split(",")]
            try:
                rows.append({
                    "relative_time_s": float(parts[0]),
                    "edges_found":     int(parts[12]) if len(parts) > 12 else int(parts[6].rstrip("%")),
                    "saved_crashes":   int(parts[7]) if len(parts) > 7 else 0,
                })
            except (ValueError, IndexError):
                pass
    return rows


def get_start_time(afl_dir: str) -> float:
    """Read start_time (unix epoch) from fuzzer_stats."""
    for candidate in [
        os.path.join(afl_dir, "default", "fuzzer_stats"),
        os.path.join(afl_dir, "fuzzer_stats"),
    ]:
        if os.path.isfile(candidate):
            with open(candidate) as f:
                content = f.read()
            m = re.search(r"start_time\s*:\s*(\d+)", content)
            if m:
                return float(m.group(1))
    return None


def extract_errors_from_crashes(afl_dir: str, binary: str):
    """
    Run each crash file through `binary`, extract error codes from stderr.
    Returns list of (discovery_time_s_since_start, error_id) sorted by time.
    """
    start_time = get_start_time(afl_dir)

    crashes_dir = None
    for candidate in [
        os.path.join(afl_dir, "default", "crashes"),
        os.path.join(afl_dir, "crashes"),
    ]:
        if os.path.isdir(candidate):
            crashes_dir = candidate
            break

    if not crashes_dir:
        print(f"[WARN] crashes/ not found in {afl_dir}", file=sys.stderr)
        return []

    if not os.path.isfile(binary):
        print(f"[WARN] binary not found: {binary}", file=sys.stderr)
        return []

    events = []  # (time_s_since_start, error_id)

    for fname in sorted(os.listdir(crashes_dir)):
        if not fname.startswith("id:"):
            continue
        fpath = os.path.join(crashes_dir, fname)
        mtime = os.path.getmtime(fpath)
        if start_time is not None:
            rel_time = mtime - start_time
        else:
            rel_time = 0.0

        with open(fpath, "rb") as f:
            content = f.read()

        try:
            proc = subprocess.run(
                [binary],
                input=content,
                capture_output=True,
                timeout=10,
            )
            stderr_text = proc.stderr.decode(errors="replace")
        except subprocess.TimeoutExpired:
            continue
        except Exception as e:
            print(f"[WARN] running binary on {fname}: {e}", file=sys.stderr)
            continue

        for err_id in re.findall(r"error_(\d+)", stderr_text):
            events.append((rel_time, int(err_id)))

    events.sort(key=lambda x: x[0])
    return events


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Convert one AFL run directory into a Task-1-compatible CSV"
    )
    parser.add_argument("afl_output_dir", help="AFL findings directory (contains default/)")
    parser.add_argument("binary", help="Compiled C binary (not AFL-instrumented) for error extraction")
    parser.add_argument("out_csv", help="Output CSV path")
    args = parser.parse_args()

    # --- Branch coverage timeline from plot_data ---
    plot_rows = read_plot_data(args.afl_output_dir)

    # --- Error discovery timeline from crash files ---
    error_events = extract_errors_from_crashes(args.afl_output_dir, args.binary)

    # --- Merge into a single sorted timeline ---
    # Each entry in the final CSV represents a moment when something new happened.
    # We track running state: (time_ms, unique_errors, unique_branches)

    # Build branch timeline: list of (time_ms, edges_found)
    branch_timeline = [
        (int(r["relative_time_s"] * 1000), r["edges_found"])
        for r in plot_rows
    ]

    # Build error discovery timeline: (time_ms, cumulative_unique_errors)
    seen_errors = set()
    error_timeline = []  # (time_ms, unique_error_count)
    for rel_s, err_id in error_events:
        if err_id not in seen_errors:
            seen_errors.add(err_id)
            error_timeline.append((int(rel_s * 1000), len(seen_errors)))

    # Merge: produce one row per second (using plot_data as the clock),
    # interpolating/stepping error count.
    rows = []
    err_ptr = 0
    current_errors = 0
    for time_ms, edges in branch_timeline:
        # Advance error pointer to consume all error events up to this time
        while err_ptr < len(error_timeline) and error_timeline[err_ptr][0] <= time_ms:
            current_errors = error_timeline[err_ptr][1]
            err_ptr += 1
        rows.append((time_ms, current_errors, edges))

    # If no plot_data (AFL failed/was empty), write a minimal single-row CSV
    if not rows:
        final_errors = len(seen_errors)
        rows = [(300_000, final_errors, 0)]

    # Write CSV
    os.makedirs(os.path.dirname(args.out_csv) or ".", exist_ok=True)
    with open(args.out_csv, "w") as f:
        f.write("time_ms,unique_errors,unique_branches\n")
        for time_ms, ue, ub in rows:
            f.write(f"{time_ms},{ue},{ub}\n")

    print(f"[OK] {args.out_csv}  ({len(rows)} rows, "
          f"{rows[-1][1]} errors, {rows[-1][2]} branches)")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Build AFL convergence CSVs (time_ms, unique_errors) from each problem's
crash directory by replaying every crash file through the binary."""
import os, re, subprocess, sys, csv

PROBLEMS = [11, 12, 13, 14, 15, 17]
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "logs")
os.makedirs(OUT_DIR, exist_ok=True)

ERR_RE = re.compile(rb"error_(\d+)")
TIME_RE = re.compile(r"time:(\d+)")

for n in PROBLEMS:
    base = os.path.join(ROOT, "afl", str(n))
    binary = os.path.join(base, f"Problem{n}")
    crashes_dir = os.path.join(base, "findings", "default", "crashes")
    if not (os.path.isfile(binary) and os.path.isdir(crashes_dir)):
        print(f"[skip] Problem {n}: missing binary or crashes dir")
        continue

    events = []  # (time_ms, error_code)
    for fname in os.listdir(crashes_dir):
        if not fname.startswith("id:"):
            continue
        m = TIME_RE.search(fname)
        if not m:
            continue
        t_ms = int(m.group(1))
        fpath = os.path.join(crashes_dir, fname)
        with open(fpath, "rb") as f:
            content = f.read()
        try:
            p = subprocess.run([binary], input=content,
                               capture_output=True, timeout=5)
        except subprocess.TimeoutExpired:
            continue
        for em in ERR_RE.finditer(p.stderr + p.stdout):
            events.append((t_ms, int(em.group(1))))

    events.sort()
    seen = set()
    rows = [("time_ms", "unique_errors")]
    last_t, last_count = -1, 0
    # always start at 0
    rows.append((0, 0))
    for t, code in events:
        if code in seen:
            continue
        seen.add(code)
        rows.append((t, len(seen)))
    out = os.path.join(OUT_DIR, f"Problem{n}_afl.csv")
    with open(out, "w", newline="") as f:
        csv.writer(f).writerows(rows)
    print(f"wrote {out}  ({len(seen)} unique errors from {len(events)} crash hits)")

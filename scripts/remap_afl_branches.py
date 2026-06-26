#!/usr/bin/env python3
"""
remap_afl_branches.py
=====================
Re-measure AFL's coverage in the *same RERS-branch unit* the Task-1 tools use.

Background
----------
AFL's per-run CSVs (logs/task2/Problem<N>_afl_seed<S>.csv) previously reported
``unique_branches`` = AFL's own edge-coverage map (instrumented edge tuples /
bucketed hit counts).  That is a different unit from the (line, side) branches
counted by the Task-1 random fuzzer / hill climber / concolic tools, so the
cross-technique branch plots were not comparable.

This script fixes that by:
  1. Taking every input AFL generated for each run (queue entries +
     findings/crashes), in AFL's real discovery order/time.
  2. Parsing each input the way the *un-instrumented* RERS program would
     (scanf("%d") loop), mapping each integer to its RERS letter symbol.
  3. Replaying the resulting symbol sequences through the **Task-1 fuzzing
     instrumentation** (AflReplay.java -> DistanceTracker.myIf ->
     FuzzingLab.encounteredNewBranch), so coverage is counted as unique
     (line, value) branch pairs — identical units to Task 1.
  4. Reconstructing the coverage-over-time curve from AFL's timestamps, and
     re-emitting each run's CSV in the Task-1 format:
         time_ms , unique_errors , unique_branches

Errors are already a common unit (a crash == a reachability error); we keep
extracting them from crash files via the plain C binary, preserving AFL's real
discovery timestamps (from the crash filename's ``time:`` field).

P16: AFL produced no inputs beyond the seed corpus (queue == seed files, no
crashes) for any seed, i.e. it never explored.  We DELETE P16's CSVs so the
plots show it as absent rather than zero-filled.

Usage:
  python3 scripts/remap_afl_branches.py
  python3 scripts/remap_afl_branches.py --task2dir logs/task2 --problems 11 12 ...
"""
import argparse
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PROBLEMS_DEFAULT = [11, 12, 13, 14, 15, 16, 17, 18, 19]
SEEDS_DEFAULT = [1, 2, 3, 4, 5]

CP = f"{ROOT}/target/aistr.jar:{ROOT}/instrumented/fuzzing:{ROOT}/scripts:."

# Filename time field, e.g.  id:000273,...,time:66754,...
TIME_RE = re.compile(r"time:(\d+)")
ERR_RE = re.compile(rb"error_(\d+)")
INT_TOKEN_RE = re.compile(r"^[+-]?\d+$")


# ── input parsing ────────────────────────────────────────────────────────────
def scanf_ints(data: bytes):
    """Reproduce the RERS C program's input consumption:
        while (scanf("%d", &input) == 1) ...
    scanf("%d") skips leading whitespace then reads an optionally-signed run of
    digits; it stops at the first token that does not start a valid integer.
    We therefore split on whitespace and consume integer tokens until the first
    non-integer token (mirroring scanf returning != 1)."""
    text = data.decode("latin-1")
    out = []
    for tok in text.split():
        if INT_TOKEN_RE.match(tok):
            out.append(int(tok))
        else:
            break
    return out


def ints_to_symbols(ints, n_symbols):
    """Map each integer edge label to its RERS letter (1->A, 2->B, ...).
    The C ``inputs[]`` and Java ``inputs[]`` arrays are index-aligned, so the
    integer *is* the alphabetical symbol index.  Inputs outside the alphabet
    are dropped (the C program returns -2 on them, consuming no transition)."""
    syms = []
    for v in ints:
        if 1 <= v <= n_symbols:
            syms.append(chr(ord("A") + v - 1))
        else:
            # Unknown symbol: the un-instrumented program rejects it and stops.
            break
    return syms


def n_symbols_for(problem: int) -> int:
    """Number of input symbols = length of the Java inputs[] array."""
    java = os.path.join(ROOT, "instrumented", "fuzzing", f"Problem{problem}.java")
    with open(java) as f:
        for line in f:
            m = re.search(r"inputs\s*=\s*\{(.*?)\}", line)
            if m:
                return len([x for x in m.group(1).split(",") if x.strip()])
    raise RuntimeError(f"could not find inputs[] for Problem{problem}")


# ── input gathering ──────────────────────────────────────────────────────────
def gather_inputs(run_dir: str):
    """Collect (time_ms, raw_bytes, is_crash) for every queue + crash input.
    time_ms comes from the filename's ``time:`` field (ms since AFL start)."""
    default = os.path.join(run_dir, "default")
    base = default if os.path.isdir(default) else run_dir
    items = []
    for sub, is_crash in [("queue", False), ("crashes", True)]:
        d = os.path.join(base, sub)
        if not os.path.isdir(d):
            continue
        for fname in os.listdir(d):
            if not fname.startswith("id:"):
                continue
            m = TIME_RE.search(fname)
            t_ms = int(m.group(1)) if m else 0
            with open(os.path.join(d, fname), "rb") as f:
                items.append((t_ms, f.read(), is_crash))
    return items


def is_explored(run_dir: str) -> bool:
    """True if AFL discovered anything beyond the initial seed corpus.
    Seed (orig) queue entries carry ``orig:`` in their filename; if every queue
    entry is an orig seed and there are no crashes, AFL never explored."""
    default = os.path.join(run_dir, "default")
    base = default if os.path.isdir(default) else run_dir
    qdir = os.path.join(base, "queue")
    cdir = os.path.join(base, "crashes")
    crashes = [f for f in os.listdir(cdir)] if os.path.isdir(cdir) else []
    crashes = [f for f in crashes if f.startswith("id:")]
    if crashes:
        return True
    if not os.path.isdir(qdir):
        return False
    for f in os.listdir(qdir):
        if f.startswith("id:") and "orig:" not in f:
            return True
    return False


# ── branch timeline via Java replay ──────────────────────────────────────────
def branch_timeline(problem: int, items, n_syms):
    """Replay inputs (sorted by AFL time) through the Task-1 instrumentation and
    return a list of (time_ms, cumulative_unique_branches).

    AflReplay writes its CSV to a file (the instrumented Problem classes print
    diagnostics to stdout, so stdout cannot carry the result)."""
    import tempfile
    ordered = sorted(items, key=lambda x: x[0])
    tsv_lines = []
    for t_ms, raw, _ in ordered:
        syms = ints_to_symbols(scanf_ints(raw), n_syms)
        tsv_lines.append(f"{t_ms}\t{' '.join(syms)}")
    stdin_blob = "\n".join(tsv_lines) + "\n"

    with tempfile.NamedTemporaryFile(suffix=".csv", delete=False) as tf:
        out_path = tf.name
    try:
        proc = subprocess.run(
            ["java", "-Xmx2g", "-cp", CP, "AflReplay", f"Problem{problem}", out_path],
            input=stdin_blob.encode(),
            capture_output=True,
            cwd=ROOT,
        )
        if proc.returncode != 0:
            sys.stderr.write(proc.stderr.decode(errors="replace"))
            raise RuntimeError(f"AflReplay failed for Problem{problem}")

        timeline = []  # (time_ms, cumulative_branches)
        with open(out_path) as f:
            for line in f:
                if line.startswith("time_ms") or not line.strip():
                    continue
                t_str, b_str = line.split(",")
                timeline.append((int(t_str), int(b_str)))
    finally:
        os.unlink(out_path)
    return timeline


def collapse_branch_timeline(timeline):
    """Keep only points where the cumulative branch count increased, plus the
    final point.  Returns sorted (time_ms, branches) with monotone branches."""
    best = -1
    points = []
    for t_ms, b in timeline:
        if b > best:
            best = b
            points.append((t_ms, b))
    return points


# ── error timeline via plain C binary ────────────────────────────────────────
def error_timeline(run_dir: str, binary: str):
    """Replay crash files through the plain C binary, returning
    (time_ms, cumulative_unique_errors) using the crash filename ``time:``."""
    default = os.path.join(run_dir, "default")
    base = default if os.path.isdir(default) else run_dir
    cdir = os.path.join(base, "crashes")
    if not (os.path.isdir(cdir) and os.path.isfile(binary)):
        return []

    events = []  # (time_ms, error_id)
    for fname in sorted(os.listdir(cdir)):
        if not fname.startswith("id:"):
            continue
        m = TIME_RE.search(fname)
        t_ms = int(m.group(1)) if m else 0
        with open(os.path.join(cdir, fname), "rb") as f:
            content = f.read()
        try:
            p = subprocess.run([binary], input=content, capture_output=True, timeout=10)
        except subprocess.TimeoutExpired:
            continue
        for em in ERR_RE.finditer(p.stderr + p.stdout):
            events.append((t_ms, int(em.group(1))))

    events.sort(key=lambda x: x[0])
    seen = set()
    timeline = []
    for t_ms, eid in events:
        if eid not in seen:
            seen.add(eid)
            timeline.append((t_ms, len(seen)))
    return timeline


# ── merge & write ────────────────────────────────────────────────────────────
def write_csv(out_csv, branch_pts, error_pts):
    """Merge branch + error discovery timelines into one CSV
    (time_ms, unique_errors, unique_branches), step-held, starting at 0,0,0
    and ending at the 300 s budget so curves extend across the full window."""
    events = []  # (time_ms, errors_or_-1, branches_or_-1)
    for t, e in error_pts:
        events.append((t, e, -1))
    for t, b in branch_pts:
        events.append((t, -1, b))
    events.sort(key=lambda x: x[0])

    rows = [(0, 0, 0)]
    cur_e, cur_b = 0, 0
    for t, e, b in events:
        if e >= 0:
            cur_e = e
        if b >= 0:
            cur_b = b
        rows.append((t, cur_e, cur_b))
    # Extend to the full 300 s budget (AFL's wall-clock window).
    if rows[-1][0] < 300_000:
        rows.append((300_000, cur_e, cur_b))

    with open(out_csv, "w") as f:
        f.write("time_ms,unique_errors,unique_branches\n")
        for t, e, b in rows:
            f.write(f"{t},{e},{b}\n")
    return rows


# ── main ─────────────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--task2dir", default=os.path.join(ROOT, "logs", "task2"))
    ap.add_argument("--problems", nargs="*", type=int, default=PROBLEMS_DEFAULT)
    ap.add_argument("--seeds", nargs="*", type=int, default=SEEDS_DEFAULT)
    args = ap.parse_args()

    for problem in args.problems:
        try:
            n_syms = n_symbols_for(problem)
        except Exception as e:
            print(f"[skip] Problem{problem}: {e}")
            continue
        bin_path = os.path.join(ROOT, "afl", str(problem), f"Problem{problem}")

        for seed in args.seeds:
            run_dir = os.path.join(args.task2dir, f"Problem{problem}_afl_seed{seed}")
            out_csv = os.path.join(args.task2dir, f"Problem{problem}_afl_seed{seed}.csv")
            if not os.path.isdir(run_dir):
                continue

            # P16-style runs with no real exploration -> remove CSV (keep absent).
            if not is_explored(run_dir):
                if os.path.isfile(out_csv):
                    os.remove(out_csv)
                print(f"[absent] Problem{problem} seed{seed}: no AFL exploration "
                      f"(seed corpus only) — CSV removed")
                continue

            items = gather_inputs(run_dir)
            timeline = branch_timeline(problem, items, n_syms)
            branch_pts = collapse_branch_timeline(timeline)
            error_pts = error_timeline(run_dir, bin_path)
            rows = write_csv(out_csv, branch_pts, error_pts)
            print(f"[ok] Problem{problem} seed{seed}: "
                  f"{len(items)} inputs, "
                  f"final branches={rows[-1][2]} (RERS units), "
                  f"errors={rows[-1][1]}  -> {os.path.relpath(out_csv, ROOT)}")

    print("\nDone.  Regenerate plots with: python3 scripts/analyze_task2.py")


if __name__ == "__main__":
    main()

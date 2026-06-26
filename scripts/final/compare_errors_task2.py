#!/usr/bin/env python3
"""
compare_errors_task2.py  –  Which errors does each technique find? (overlap analysis)
==============================================================================
Answers the Task 2 question: does AFL find the SAME reachability errors as
random / hill-climbing / concolic, or COMPLEMENTARY ones?

For each problem it builds the set of unique error codes (error_<N>) found by
each technique (union over all seeds), then reports:
  - the count per technique,
  - errors found by ALL techniques (common core),
  - errors UNIQUE to each technique (complementary / only-this-technique),
  - a per-error presence matrix.

Where the error codes come from:
  random / hillclimber / concolic : grepped from logs/task1/*.log
                                     (the Java harness prints "error_<N>")
  afl                             : replayed from the saved AFL crash files
                                     through the plain C binary afl/<N>/Problem<N>
                                     (same method as process_afl_run.py), since
                                     the codes are not stored in the CSV/log.

Usage:
  python3 scripts/compare_errors_task2.py
  python3 scripts/compare_errors_task2.py --no-afl        # skip crash replay (faster)
  python3 scripts/compare_errors_task2.py --outdir report
"""

import os
import re
import sys
import glob
import argparse
import subprocess
from collections import defaultdict

PROBLEMS = [11, 12, 13, 14, 15, 16, 17, 18, 19]
T1_TECHS = ["random", "hillclimber", "concolic"]
TECHS    = T1_TECHS + ["afl"]
LABELS   = {"random": "Random", "hillclimber": "HillClimber",
            "concolic": "Concolic", "afl": "AFL"}

ERR_RE = re.compile(r"error_(\d+)")


def errors_from_logs(task1dir: str, n: int, tech: str) -> set:
    """Union of error_<N> codes across all seed logs for one technique."""
    found = set()
    for log in glob.glob(os.path.join(task1dir, f"Problem{n}_{tech}_seed*.log")):
        try:
            with open(log, errors="replace") as fh:
                for m in ERR_RE.finditer(fh.read()):
                    found.add(int(m.group(1)))
        except OSError:
            pass
    return found


def errors_from_afl(task2dir: str, afl_base: str, n: int, timeout: float) -> set:
    """Replay every AFL crash file through the plain binary and collect codes."""
    binary = os.path.join(afl_base, str(n), f"Problem{n}")
    if not (os.path.isfile(binary) and os.access(binary, os.X_OK)):
        print(f"  [warn] plain binary missing/not executable: {binary} "
              f"(skipping AFL for Problem{n})")
        return set()

    found = set()
    crash_dirs = glob.glob(os.path.join(task2dir, f"Problem{n}_afl_seed*",
                                        "default", "crashes"))
    for cdir in crash_dirs:
        for cf in glob.glob(os.path.join(cdir, "id:*")):
            try:
                with open(cf, "rb") as fh:
                    data = fh.read()
                proc = subprocess.run([binary], input=data,
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                      timeout=timeout)
                text = (proc.stdout + proc.stderr).decode(errors="replace")
                for m in ERR_RE.finditer(text):
                    found.add(int(m.group(1)))
            except (subprocess.TimeoutExpired, OSError):
                continue
    return found


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--task1dir", default="logs/task1")
    ap.add_argument("--task2dir", default="logs/task2")
    ap.add_argument("--afl-base", default="afl", help="afl/<N>/Problem<N> plain binaries")
    ap.add_argument("--outdir",   default="report")
    ap.add_argument("--no-afl",   action="store_true", help="skip AFL crash replay")
    ap.add_argument("--replay-timeout", type=float, default=5.0,
                    help="per-crash replay timeout in seconds")
    args = ap.parse_args()

    techs = T1_TECHS if args.no_afl else TECHS

    # errors[problem][tech] = set of error ids
    errors = defaultdict(dict)
    for n in PROBLEMS:
        for tech in T1_TECHS:
            errors[n][tech] = errors_from_logs(args.task1dir, n, tech)
        if not args.no_afl:
            print(f"Replaying AFL crashes for Problem{n} …")
            errors[n]["afl"] = errors_from_afl(args.task2dir, args.afl_base, n,
                                               args.replay_timeout)

    # ── Build report text ────────────────────────────────────────────────────
    lines = []
    def out(s=""):
        lines.append(s)
        print(s)

    for n in PROBLEMS:
        present = {t: errors[n].get(t, set()) for t in techs}
        all_errs = set().union(*present.values()) if present else set()
        if not all_errs:
            out(f"\n=== Problem{n} === (no errors found by any technique)")
            continue

        # techniques that actually have data for this problem
        active = [t for t in techs if present[t]]
        common = set.intersection(*(present[t] for t in active)) if active else set()

        out(f"\n{'=' * 60}")
        out(f"  Problem{n}")
        out(f"{'=' * 60}")
        for t in techs:
            out(f"  {LABELS[t]:12s}: {len(present[t]):3d} errors")
        out(f"  Found by ALL active techniques: {len(common)} "
            f"({sorted(common) if common else '—'})")

        for t in techs:
            others = set().union(*(present[o] for o in techs if o != t)) if len(techs) > 1 else set()
            unique = present[t] - others
            if unique:
                out(f"  Only {LABELS[t]}: {sorted(unique)}")

        # presence matrix (compact): error id -> which techniques
        out("  Presence matrix (error : techniques):")
        for e in sorted(all_errs):
            tags = "".join(LABELS[t][0] if e in present[t] else "·" for t in techs)
            out(f"    error_{e:<3d}  {tags}")
        out(f"  (columns: {' '.join(LABELS[t][0] + '=' + LABELS[t] for t in techs)})")

    # ── Overall summary across problems ──────────────────────────────────────
    out(f"\n{'=' * 60}")
    out("  OVERALL (union over all problems)")
    out(f"{'=' * 60}")
    union_per_tech = {t: set() for t in techs}
    for n in PROBLEMS:
        for t in techs:
            union_per_tech[t] |= {(n, e) for e in errors[n].get(t, set())}
    for t in techs:
        others = set().union(*(union_per_tech[o] for o in techs if o != t)) if len(techs) > 1 else set()
        uniq = union_per_tech[t] - others
        out(f"  {LABELS[t]:12s}: {len(union_per_tech[t]):3d} (problem,error) pairs, "
            f"{len(uniq):3d} found by no other technique")

    os.makedirs(args.outdir, exist_ok=True)
    txt_path = os.path.join(args.outdir, "task2_error_overlap.txt")
    with open(txt_path, "w") as fh:
        fh.write("\n".join(lines) + "\n")
    print(f"\nSaved: {txt_path}")


if __name__ == "__main__":
    main()

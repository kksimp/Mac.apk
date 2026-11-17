#!/usr/bin/env python3
"""Merges HotSpot class lists into windows/java/winapk-framework.classlist.

    merge_classlists.py [--min-apps N] <out> <list>...

The lists come from app starts with WINAPK_CDS_RECORD_DIR=<dir> (host/cds.h):
one <app>.classlist per app, every class its JVM loaded. The merge is what the
runtime's own merge does (host/cds_util.cpp, merge_classlists): comments and
the per-file " id: <n>" dropped, classes of the app's own loader (lines with
super:/source:) left out, each line once, in the order first seen.

--min-apps N keeps only the lines at least N of the lists have (default 3).
An archived class no app loads still costs every app that maps the archive:
HotSpot relocates the whole archive at each start, so its pages become the
process's own (about 1 MB of private memory per MB of archive, measured). Classes that three of the ten smoke apps load
cover 98.6% of the median app's; the first start on a machine adds its own
app's classes anyway.
"""

import argparse
import collections


def read(path):
    lines = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\r\n")
            if not line or line.startswith("#"):
                continue
            if not line.startswith("@"):
                if " super: " in line or " source: " in line:
                    continue
                line = line.split(" id: ", 1)[0]
            lines.append(line)
    return lines


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--min-apps", type=int, default=3)
    ap.add_argument("out")
    ap.add_argument("lists", nargs="+")
    args = ap.parse_args()
    count, order = collections.Counter(), []
    for path in args.lists:
        seen = set()
        for line in read(path):
            if line in seen:
                continue
            seen.add(line)
            if count[line] == 0:
                order.append(line)
            count[line] += 1
    need = min(args.min_apps, len(args.lists))
    keep = [line for line in order if count[line] >= need]
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# Win.apk's framework class list (host/cds.h): the classes at least {need} of the\n")
        f.write(f"# {len(args.lists)} smoke apps load, merged by windows/scripts/merge_classlists.py.\n")
        f.write("\n".join(keep) + "\n")
    print(f"[merge_classlists] {len(keep)} of {len(order)} lines (in at least {need} of {len(args.lists)} lists) "
          f"-> {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

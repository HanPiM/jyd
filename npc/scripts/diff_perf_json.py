#!/usr/bin/env python3
"""Diff numeric counters in two JSON performance snapshots."""

import argparse
import json
from pathlib import Path


def flatten(value, prefix=""):
    result = {}
    if isinstance(value, dict):
        for key, child in value.items():
            result.update(flatten(child, f"{prefix}.{key}" if prefix else str(key)))
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        result[prefix] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("--all", action="store_true", help="include unchanged counters")
    args = parser.parse_args()

    before = flatten(json.loads(args.baseline.read_text(encoding="utf-8")))
    after = flatten(json.loads(args.candidate.read_text(encoding="utf-8")))
    print("| counter | baseline | candidate | delta | delta % |")
    print("| --- | ---: | ---: | ---: | ---: |")
    for key in sorted(before.keys() & after.keys()):
        delta = after[key] - before[key]
        if not args.all and delta == 0:
            continue
        percent = "—" if before[key] == 0 else f"{delta / before[key] * 100:+.3f}%"
        print(f"| {key} | {before[key]} | {after[key]} | {delta:+} | {percent} |")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys
from pathlib import Path


REPORT_NAME = "top_timing_summary_routed.rpt"
DEFAULT_REPORT_PATH = Path("digital_twin.runs") / "impl_1" / REPORT_NAME
TITLE_MARKER = "| Design Timing Summary"


def resolve_report_path(path: Path) -> Path:
    if path.is_dir():
        return path / REPORT_NAME
    return path


def is_section_rule(line: str) -> bool:
    return line.startswith("----")


def extract_design_timing_summary(text: str) -> str:
    lines = text.splitlines()
    title_index = next((i for i, line in enumerate(lines) if TITLE_MARKER in line), None)
    if title_index is None:
        raise ValueError(f"marker not found: {TITLE_MARKER}")

    start_index = None
    for i in range(title_index - 1, -1, -1):
        if is_section_rule(lines[i]):
            start_index = i
            break
    if start_index is None:
        start_index = title_index

    output: list[str] = []
    section_rules = 0
    for line in lines[start_index:]:
        if is_section_rule(line):
            section_rules += 1
            if section_rules >= 3:
                break
        output.append(line.rstrip())

    return "\n".join(output).rstrip()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Extract the Design Timing Summary section from a routed Vivado timing report."
    )
    parser.add_argument(
        "path",
        nargs="?",
        default=str(DEFAULT_REPORT_PATH),
        help=f"Result directory or {REPORT_NAME} path. Defaults to {DEFAULT_REPORT_PATH}.",
    )
    args = parser.parse_args(argv)

    report_path = resolve_report_path(Path(args.path))
    if not report_path.is_file():
        print(f"timing report not found: {report_path}", file=sys.stderr)
        return 1

    try:
        summary = extract_design_timing_summary(report_path.read_text(encoding="utf-8", errors="replace"))
    except ValueError as exc:
        print(f"{report_path}: {exc}", file=sys.stderr)
        return 1

    print(summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

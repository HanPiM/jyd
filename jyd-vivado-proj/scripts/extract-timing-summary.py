#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys
from pathlib import Path


POSTROUTE_REPORT_NAME = "top_timing_summary_postroute_physopted.rpt"
ROUTED_REPORT_NAME = "top_timing_summary_routed.rpt"
REPORT_NAMES = (POSTROUTE_REPORT_NAME, ROUTED_REPORT_NAME)
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_REPORT_PATH = PROJECT_ROOT / "digital_twin.runs" / "impl_1" / POSTROUTE_REPORT_NAME
TITLE_MARKER = "| Design Timing Summary"


def resolve_report_path(path: Path) -> Path:
    if path.is_dir():
        for report_name in REPORT_NAMES:
            report_path = path / report_name
            if report_path.is_file():
                return report_path
        return path / POSTROUTE_REPORT_NAME
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
        description="Extract the Design Timing Summary section from the final Vivado timing report."
    )
    parser.add_argument(
        "path",
        nargs="?",
        default=str(DEFAULT_REPORT_PATH),
        help=(
            "Result directory or timing report path. Directories prefer the post-route physopt report "
            f"({POSTROUTE_REPORT_NAME}) and fall back to {ROUTED_REPORT_NAME}. Defaults to {DEFAULT_REPORT_PATH}."
        ),
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

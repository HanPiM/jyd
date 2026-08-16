#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path


POSTROUTE_REPORT_NAME = "top_timing_summary_postroute_physopted.rpt"
ROUTED_REPORT_NAME = "top_timing_summary_routed.rpt"
REPORT_NAMES = (POSTROUTE_REPORT_NAME, ROUTED_REPORT_NAME)
PROJECT_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_REPORT_PATH = PROJECT_ROOT / "digital_twin.runs" / "impl_1" / POSTROUTE_REPORT_NAME
SLACK_RE = re.compile(r"^Slack(?: \((?P<status>[^)]+)\))?\s*:\s*(?P<value>-?\d+(?:\.\d+)?)ns\b")
FIELD_RE = re.compile(r"^\s{2}(?P<name>[A-Za-z][A-Za-z ]+):\s+(?P<value>.+?)\s*$")
FROM_CLOCK_RE = re.compile(r"^From Clock:\s+(?P<clock>.+?)\s*$")
TO_CLOCK_RE = re.compile(r"^\s+To Clock:\s+(?P<clock>.+?)\s*$")


@dataclass(frozen=True)
class TimingPath:
    slack: float
    status: str
    line_number: int
    from_clock: str
    to_clock: str
    fields: dict[str, str]
    lines: list[str]


def resolve_report_path(path: Path) -> Path:
    if path.is_dir():
        for report_name in REPORT_NAMES:
            report_path = path / report_name
            if report_path.is_file():
                return report_path
        return path / POSTROUTE_REPORT_NAME
    return path


def collect_block(lines: list[str], start_index: int) -> list[str]:
    block = [lines[start_index].rstrip()]
    for line in lines[start_index + 1 :]:
        if SLACK_RE.match(line):
            break
        if line.startswith("------") and block[-1].strip() == "":
            break
        block.append(line.rstrip())
    return block


def extract_fields(block: list[str]) -> dict[str, str]:
    fields: dict[str, str] = {}
    for line in block:
        match = FIELD_RE.match(line)
        if match:
            fields[match.group("name").strip()] = match.group("value").strip()
    return fields


def extract_wns_violations(text: str) -> list[TimingPath]:
    lines = text.splitlines()
    paths: list[TimingPath] = []
    from_clock = ""
    to_clock = ""

    for index, line in enumerate(lines):
        if from_match := FROM_CLOCK_RE.match(line):
            from_clock = from_match.group("clock")
            to_clock = ""
            continue
        if to_match := TO_CLOCK_RE.match(line):
            to_clock = to_match.group("clock")
            continue

        slack_match = SLACK_RE.match(line)
        if slack_match is None:
            continue

        slack = float(slack_match.group("value"))
        status = slack_match.group("status") or ""
        if status.upper() != "VIOLATED" and slack >= 0:
            continue

        block = collect_block(lines, index)
        fields = extract_fields(block)
        path_type = fields.get("Path Type", "")
        if path_type and "Setup" not in path_type:
            continue

        paths.append(
            TimingPath(
                slack=slack,
                status=status,
                line_number=index + 1,
                from_clock=from_clock,
                to_clock=to_clock,
                fields=fields,
                lines=block,
            )
        )

    return sorted(paths, key=lambda path: path.slack)


def format_summary(paths: list[TimingPath]) -> str:
    output: list[str] = []
    for number, path in enumerate(paths, start=1):
        source = path.fields.get("Source", "(unknown)")
        destination = path.fields.get("Destination", "(unknown)")
        path_group = path.fields.get("Path Group", "(unknown)")
        path_type = path.fields.get("Path Type", "(unknown)")
        requirement = path.fields.get("Requirement", "(unknown)")
        data_delay = path.fields.get("Data Path Delay", "(unknown)")
        logic_levels = path.fields.get("Logic Levels", "(unknown)")
        clock_pair = " -> ".join(part for part in (path.from_clock, path.to_clock) if part)

        output.extend(
            [
                f"#{number}: slack {path.slack:.3f}ns"
                + (f" ({path.status})" if path.status else "")
                + f" at line {path.line_number}",
                f"  Clock: {clock_pair or '(unknown)'}",
                f"  Source: {source}",
                f"  Destination: {destination}",
                f"  Path Group: {path_group}",
                f"  Path Type: {path_type}",
                f"  Requirement: {requirement}",
                f"  Data Path Delay: {data_delay}",
                f"  Logic Levels: {logic_levels}",
                "",
            ]
        )
    return "\n".join(output).rstrip()


def format_full(paths: list[TimingPath], start: int = 1) -> str:
    output: list[str] = []
    for number, path in enumerate(paths, start=start):
        output.append(f"# {number}: slack {path.slack:.3f}ns at line {path.line_number}")
        output.extend(path.lines)
        output.append("")
    return "\n".join(output).rstrip()


def positive_int(value: str) -> int:
    try:
        number = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be an integer") from exc
    if number <= 0:
        raise argparse.ArgumentTypeError("must be greater than 0")
    return number


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Extract the worst setup/WNS timing violations from the final Vivado timing report."
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
    parser.add_argument("-n", "--limit", type=positive_int, help="Number of worst violations to print. Defaults to 10.")
    parser.add_argument(
        "--index",
        type=positive_int,
        help="Print only the Nth worst violation using full output. N is 1-based.",
    )
    parser.add_argument("--full", action="store_true", help="Print the full timing path blocks.")
    args = parser.parse_args(argv)
    if args.index is not None and args.limit is not None:
        parser.error("--index cannot be used with -n/--limit")

    report_path = resolve_report_path(Path(args.path))
    if not report_path.is_file():
        print(f"timing report not found: {report_path}", file=sys.stderr)
        return 1

    paths = extract_wns_violations(report_path.read_text(encoding="utf-8", errors="replace"))
    if not paths:
        print("No WNS/setup timing violations found.")
        return 0

    if args.index is not None:
        if args.index > len(paths):
            print(
                f"requested WNS/setup violation #{args.index}, but only {len(paths)} found.",
                file=sys.stderr,
            )
            return 1
        print(format_full([paths[args.index - 1]], start=args.index))
        return 0

    selected = paths[: args.limit if args.limit is not None else 10]
    print(format_full(selected) if args.full else format_summary(selected))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

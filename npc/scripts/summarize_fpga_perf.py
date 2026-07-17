#!/usr/bin/env python3
"""Summarize Vivado timing and JYD board-run output.

The board client may print arbitrary log lines before its final JSON object.  This
tool deliberately scans from the end and accepts the last JSON object on a line.
"""

import argparse
import datetime as dt
import json
import re
import statistics
import sys
from pathlib import Path


NUMBER = r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)"


def read_text(path):
    if path == "-":
        return sys.stdin.read()
    return Path(path).read_text(encoding="utf-8", errors="replace")


def parse_timing(text):
    result = {"wns_ns": None, "tns_ns": None, "whs_ns": None, "ths_ns": None}
    patterns = {
        "wns_ns": [rf"\bWNS(?:\(ns\))?\s*[:=]\s*({NUMBER})", rf"slack\s+({NUMBER})ns"],
        "tns_ns": [rf"\bTNS(?:\(ns\))?\s*[:=]\s*({NUMBER})"],
        "whs_ns": [rf"\bWHS(?:\(ns\))?\s*[:=]\s*({NUMBER})"],
        "ths_ns": [rf"\bTHS(?:\(ns\))?\s*[:=]\s*({NUMBER})"],
    }
    for key, candidates in patterns.items():
        for pattern in candidates:
            match = re.search(pattern, text, re.IGNORECASE)
            if match:
                result[key] = float(match.group(1))
                break

    # Vivado's timing summary table has headings on one line and values below it.
    lines = text.splitlines()
    for index, line in enumerate(lines):
        headings = re.findall(r"\b(?:WNS|TNS|WHS|THS)\(ns\)", line, re.IGNORECASE)
        if not headings:
            continue
        for value_line in lines[index + 1 : index + 5]:
            values = re.findall(NUMBER, value_line)
            if len(headings) == 4 and len(values) >= 8:
                # Standard Vivado layout inserts two endpoint-count columns
                # between TNS and WHS, and another two after THS.
                for key, position in (("wns_ns", 0), ("tns_ns", 1), ("whs_ns", 4), ("ths_ns", 5)):
                    result[key] = float(values[position])
                return result
            if len(values) >= len(headings):
                keys = [heading[:3].lower() + "_ns" for heading in headings]
                for key, value in zip(keys, values):
                    result[key] = float(value)
                return result
    return result


def last_json(text):
    for line in reversed(text.splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict):
            return value
    raise ValueError("no JSON object found on a line")


def decode_display(value):
    display = str(value or "")
    if re.fullmatch(r"3700\d{4}", display):
        return {"format": "3700", "elapsed_ms": int(display[4:])}
    if re.fullmatch(r"3780\d{4}", display):
        return {"format": "3780", "elapsed_ms": int(display[3:])}
    raise ValueError(f"unsupported or invalid display value: {display!r}")


def parse_board_run(text, source, frequency_mhz, instruction_count):
    raw = last_json(text)
    display = raw.get("parsed_result")
    if display is None:
        display = (raw.get("task_judgment") or {}).get("display_value")
    item = {
        "source": source,
        "valid": False,
        "display": None if display is None else str(display),
        "success": raw.get("success"),
        "burned": raw.get("burned"),
        "task_success": raw.get("task_success"),
        "error": raw.get("error"),
        "bitfile": raw.get("bitfile"),
        "fpga_name": raw.get("fpga_name"),
    }
    try:
        decoded = decode_display(display)
        item.update(decoded)
        item["cpi"] = decoded["elapsed_ms"] * frequency_mhz * 1000.0 / instruction_count
        item["valid"] = bool(raw.get("success") and raw.get("task_success"))
    except ValueError as error:
        item["parse_error"] = str(error)
    return item


def markdown(record):
    timing = record["timing"]
    valid = record["summary"]["valid_runs"]
    median = record["summary"]["median_elapsed_ms"]
    cpi = record["summary"]["median_cpi"]
    cells = [
        ("候选", record["candidate"]),
        ("频率", f'{record["frequency_mhz"]:g} MHz'),
        ("有效上板", f'{valid}/{len(record["board_runs"])}'),
        ("中位时间", "—" if median is None else f"{median:g} ms"),
        ("中位 CPI", "—" if cpi is None else f"{cpi:.4f}"),
        ("WNS/TNS", f'{timing["wns_ns"]}/{timing["tns_ns"]} ns'),
        ("硬目标", "PASS" if record["summary"]["meets_target"] else "未达到/数据不足"),
        ("冲刺目标", "PASS" if record["summary"]["meets_stretch_target"] else "未达到/数据不足"),
    ]
    return "| " + " | ".join(name for name, _ in cells) + " |\n| " + " | ".join("---" for _ in cells) + " |\n| " + " | ".join(str(value) for _, value in cells) + " |"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, help="experiment/candidate name")
    parser.add_argument("--frequency-mhz", required=True, type=float)
    parser.add_argument("--instructions", type=int, default=380_344_412,
                        help="dynamic instruction count (default: withMext-v2 baseline)")
    parser.add_argument("--target-ms", type=float, default=1750,
                        help="hard elapsed-time target (default: 1750)")
    parser.add_argument("--stretch-target-ms", type=float, default=1700,
                        help="stretch elapsed-time target (default: 1700)")
    parser.add_argument("--timing-report", help="Vivado timing report or extracted WNS output")
    parser.add_argument("--board-log", action="append", default=[], help="board CLI log; repeatable, '-' reads stdin")
    parser.add_argument("--strategy")
    parser.add_argument("--commit")
    parser.add_argument("--coe-sha256")
    parser.add_argument("--bitstream-sha256")
    parser.add_argument("--jsonl", help="append the complete record to this file")
    parser.add_argument("--markdown", action="store_true", help="print a Markdown summary instead of JSON")
    args = parser.parse_args()

    timing = parse_timing(read_text(args.timing_report)) if args.timing_report else {
        "wns_ns": None, "tns_ns": None, "whs_ns": None, "ths_ns": None
    }
    runs = []
    for path in args.board_log:
        try:
            runs.append(parse_board_run(read_text(path), path, args.frequency_mhz, args.instructions))
        except ValueError as error:
            runs.append({"source": path, "valid": False, "parse_error": str(error)})
    valid_runs = [run for run in runs if run.get("valid")]
    times = [run["elapsed_ms"] for run in valid_runs]
    cpis = [run["cpi"] for run in valid_runs]
    median_elapsed_ms = statistics.median(times) if times else None
    record = {
        "schema_version": 2,
        "recorded_at": dt.datetime.now(dt.timezone.utc).isoformat(),
        "candidate": args.candidate,
        "commit": args.commit,
        "frequency_mhz": args.frequency_mhz,
        "instruction_count": args.instructions,
        "target_ms": args.target_ms,
        "stretch_target_ms": args.stretch_target_ms,
        "target_cycles": round(args.target_ms * args.frequency_mhz * 1000),
        "stretch_target_cycles": round(args.stretch_target_ms * args.frequency_mhz * 1000),
        "strategy": args.strategy,
        "coe_sha256": args.coe_sha256,
        "bitstream_sha256": args.bitstream_sha256,
        "timing": timing,
        "board_runs": runs,
        "summary": {
            "valid_runs": len(valid_runs),
            "median_elapsed_ms": median_elapsed_ms,
            "median_cpi": statistics.median(cpis) if cpis else None,
            "meets_target": median_elapsed_ms is not None and median_elapsed_ms <= args.target_ms,
            "meets_stretch_target": median_elapsed_ms is not None and median_elapsed_ms <= args.stretch_target_ms,
        },
    }
    encoded = json.dumps(record, ensure_ascii=False, sort_keys=True)
    if args.jsonl:
        with Path(args.jsonl).open("a", encoding="utf-8") as output:
            output.write(encoded + "\n")
    print(markdown(record) if args.markdown else json.dumps(record, ensure_ascii=False, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()

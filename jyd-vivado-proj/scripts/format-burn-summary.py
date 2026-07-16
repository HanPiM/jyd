#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def format_led_ascii(value: int | str | dict[str, Any] | None) -> str:
    led_value = coerce_led_value(value)
    if led_value is None:
        return ""
    lines: list[str] = []
    for row in range(3, -1, -1):
        row_value = (led_value >> (row * 8)) & 0xFF
        cells = "".join("x" if row_value & (1 << bit) else "." for bit in range(7, -1, -1))
        lines.append(f"[ {cells} ]")
    return "\n".join(lines)


def coerce_led_value(value: int | str | dict[str, Any] | None) -> int | None:
    if value is None:
        return None
    if isinstance(value, int):
        return value & 0xFFFFFFFF
    if isinstance(value, dict):
        return coerce_led_value(value.get("hex") or value.get("bits"))

    text = str(value).strip()
    if not text:
        return None
    if text.startswith("{"):
        try:
            decoded = json.loads(text)
        except json.JSONDecodeError:
            decoded = None
        if isinstance(decoded, dict):
            return coerce_led_value(decoded)
    if set(text) <= {"0", "1"} and len(text) == 32:
        return int(text[::-1], 2) & 0xFFFFFFFF
    return int(text, 0) & 0xFFFFFFFF


def led_hex(value: Any) -> str:
    try:
        coerced = coerce_led_value(value)
    except ValueError:
        return ""
    if coerced is None:
        return ""
    return f"0x{coerced:08x}"


def load_last_json(path: Path) -> dict[str, Any]:
    for line in reversed(path.read_text(encoding="utf-8", errors="replace").splitlines()):
        line = line.strip()
        if not line:
            continue
        try:
            data = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(data, dict):
            return data
    raise ValueError(f"no JSON object found in {path}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Format burn.py JSON output for GitHub step summary.")
    parser.add_argument("json_output", help="File containing call_submit.py stdout.")
    parser.add_argument("--sample", required=True, help="Matrix sample name.")
    args = parser.parse_args(argv)

    try:
        result = load_last_json(Path(args.json_output))
    except (OSError, ValueError) as exc:
        print(f"### FPGA burn test ({args.sample})")
        print()
        print(f"- Error: `{exc}`")
        return 1

    burned = result.get("burned")
    seg = result.get("seg") or result.get("parsed_result") or ""
    led = result.get("led", "")
    led_text = led_hex(led) or str(led or "")

    print(f"### FPGA burn test ({args.sample})")
    print()
    print(f"- Burned: `{burned}`")
    if seg != "":
        print(f"- SEG: `{seg}`")
    if led_text:
        print(f"- LED: `{led_text}`")
    if "has_error" in result:
        print(f"- Has error: `{bool(result['has_error'])}`")

    try:
        board = format_led_ascii(led)
    except ValueError:
        board = ""
    if board:
        print()
        print("```text")
        print(board)
        print("```")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

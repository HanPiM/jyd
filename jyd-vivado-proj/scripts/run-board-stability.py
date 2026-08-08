#!/usr/bin/env python3
"""Run one bitstream repeatedly on remote FPGA boards with strict result checks.

Adapted from the jyd repo's fpga-stability-archive script for the current
CoreMark/UART program: each iteration uses `jyd_client.cli capture` (default
board allocation, no --fpga forcing) and a run passes only when the UART
payload contains a validated CoreMark completion.
"""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
import sys
from typing import Any


DEFAULT_CLIENT_DIR = Path("/home/hanpi/gitclone/submit-bits")
PASS_MARKER = "Correct operation validated."
TIME_MARKER = "Total time"


def run_once(
    index: int,
    bitstream: Path,
    client_dir: Path,
    output_dir: Path,
    first_byte_timeout: int,
    duration: int,
) -> dict[str, Any]:
    command = [
        str(client_dir / ".venv/bin/python3"),
        "-m",
        "jyd_client.cli",
        "capture",
        "--skip-login",
        "--first-byte-timeout",
        str(first_byte_timeout),
        "--duration",
        str(duration),
        str(bitstream),
    ]
    started_at = datetime.now(timezone.utc).isoformat()
    completed = subprocess.run(
        command,
        cwd=client_dir,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
    )
    log_path = output_dir / f"run-{index:03d}.log"
    log_path.write_text(completed.stdout + completed.stderr, encoding="utf-8")
    stdout = completed.stdout
    passed = PASS_MARKER in stdout and TIME_MARKER in stdout
    total_time = None
    for line in stdout.splitlines():
        if "Total time (secs):" in line:
            total_time = line.split(":", 1)[1].strip()
            break
    return {
        "iteration": index,
        "started_at": started_at,
        "finished_at": datetime.now(timezone.utc).isoformat(),
        "returncode": completed.returncode,
        "passed": passed,
        "total_time": total_time,
        "log": str(log_path),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bitstream", type=Path)
    parser.add_argument("--count", type=int, default=15, help="logical runs (default: 15)")
    parser.add_argument("--jobs", type=int, default=3, help="concurrent captures, 1-10 (default: 3)")
    parser.add_argument("--client-dir", type=Path, default=DEFAULT_CLIENT_DIR)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--first-byte-timeout", type=int, default=75)
    parser.add_argument("--duration", type=int, default=90)
    args = parser.parse_args()

    if args.count < 1:
        parser.error("--count must be positive")
    if not 1 <= args.jobs <= 10:
        parser.error("--jobs must be between 1 and 10")

    bitstream = args.bitstream.expanduser().resolve()
    client_dir = args.client_dir.expanduser().resolve()
    client_python = client_dir / ".venv/bin/python3"
    if not bitstream.is_file():
        parser.error(f"bitstream does not exist: {bitstream}")
    if not client_python.is_file():
        parser.error(f"board client interpreter does not exist: {client_python}")

    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    output_dir = (args.output_dir or Path("board-stability") / timestamp).expanduser().resolve()
    output_dir.mkdir(parents=True, exist_ok=False)
    results_path = output_dir / "results.jsonl"
    results: list[dict[str, Any]] = []

    with results_path.open("w", encoding="utf-8") as output:
        with ThreadPoolExecutor(max_workers=args.jobs) as executor:
            futures = {
                executor.submit(
                    run_once,
                    index,
                    bitstream,
                    client_dir,
                    output_dir,
                    args.first_byte_timeout,
                    args.duration,
                ): index
                for index in range(1, args.count + 1)
            }
            for future in as_completed(futures):
                record = future.result()
                results.append(record)
                line = json.dumps(record, ensure_ascii=False)
                output.write(line + "\n")
                output.flush()
                print(line, flush=True)

    passed = sum(result["passed"] for result in results)
    summary = {
        "bitstream": str(bitstream),
        "total": len(results),
        "success": passed,
        "failure": len(results) - passed,
        "success_rate": passed / len(results),
        "jobs": args.jobs,
        "results": str(results_path),
    }
    (output_dir / "SUMMARY.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())

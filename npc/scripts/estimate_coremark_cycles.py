#!/usr/bin/env python3
"""Estimate full CoreMark cycles from two short NPC runs.

The two runs use different compile-time ITERATIONS values.  Their cycle-count
difference removes startup, reporting, and other iteration-independent work.
The remaining slope is extrapolated to the requested iteration count.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
from fractions import Fraction
from pathlib import Path


CYCLE_RE = re.compile(r"total cycle count:\s*([0-9]+)")
INSTRUCTION_RE = re.compile(r"total instruction count:\s*([0-9]+)")
IMAGE_RE = re.compile(r"load image (.+?), size = [0-9]+")
CRC_RE = re.compile(r"\[0\](crc(?:list|matrix|state|final))\s*:\s*0x([0-9a-fA-F]+)")
RESERVED_MAKE_VARS = {
    "ARCH",
    "BUILD_DIR",
    "ITERATIONS",
    "VSIM_difftest",
    "VSIM_en_inst_trace",
    "VSIM_etrace",
    "VSIM_max_instructions",
    "VSIM_showdisasm",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__,
        epilog=(
            "example: %(prog)s --name combined-gcc -- COREMARK_GCC_MD=1 "
            "COREMARK_XEXTS=_xmbm_xcrcu8_xlistrev_xmsum_xdfa4p_xlistfind_xmacacc "
            "'EXTRA_CFLAGS=-mxmbm -mxcrcu8 -mxlistrev "
            "-mclipped-rising-score-reduce -mxdfa4p -mxlistfind -mxmacacc'"
        ),
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("--name", default="candidate", help="filesystem-safe experiment name")
    parser.add_argument("--arch", default="riscv32-jyd", help="AM architecture used for both runs")
    parser.add_argument("--low-iterations", type=int, default=10)
    parser.add_argument("--high-iterations", type=int, default=100)
    parser.add_argument("--target-iterations", type=int, default=10000)
    parser.add_argument("--frequency-mhz", type=float, default=300.0)
    parser.add_argument(
        "--reference-cycles",
        type=int,
        help="optional measured target-cycle count used only to report model error",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        help="result directory; defaults below JYD_DATA_ROOT/archive",
    )
    parser.add_argument(
        "make_args",
        nargs=argparse.REMAINDER,
        help="CoreMark make variable assignments after -- (quote assignments containing spaces)",
    )
    args = parser.parse_args()
    if args.make_args[:1] == ["--"]:
        args.make_args = args.make_args[1:]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", args.name):
        parser.error("--name may contain only letters, digits, '.', '_' and '-'")
    if not (0 < args.low_iterations < args.high_iterations <= args.target_iterations):
        parser.error("iterations must satisfy 0 < low < high <= target")
    if args.frequency_mhz <= 0:
        parser.error("--frequency-mhz must be positive")
    if args.reference_cycles is not None and args.reference_cycles <= 0:
        parser.error("--reference-cycles must be positive")
    for arg in args.make_args:
        if "=" not in arg:
            parser.error(f"make argument is not a variable assignment: {arg!r}")
        key = arg.split("=", 1)[0]
        if key in RESERVED_MAKE_VARS:
            parser.error(f"{key} is controlled by this script and must not be supplied")
    return args


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fraction_record(value: Fraction) -> dict[str, int | float | str]:
    return {
        "numerator": value.numerator,
        "denominator": value.denominator,
        "decimal": float(value),
        "expression": str(value),
    }


def run_one(
    repo_root: Path,
    bench_dir: Path,
    output_dir: Path,
    arch: str,
    iterations: int,
    difftest: bool,
    make_args: list[str],
) -> dict[str, object]:
    run_dir = output_dir / f"iter{iterations}"
    run_dir.mkdir(parents=True)
    command = [
        "make",
        "-C",
        str(bench_dir),
        "run",
        f"ARCH={arch}",
        f"ITERATIONS={iterations}",
        f"BUILD_DIR={run_dir / 'build'}",
        *make_args,
        f"VSIM_difftest={int(difftest)}",
        "VSIM_en_inst_trace=0",
        "VSIM_showdisasm=0",
        "VSIM_etrace=0",
        "VSIM_max_instructions=0",
    ]
    (run_dir / "command.sh").write_text(shlex.join(command) + "\n", encoding="utf-8")
    log_path = run_dir / "run.log"
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            command,
            cwd=repo_root,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1,
        )
        assert process.stdout is not None
        for line in process.stdout:
            sys.stdout.write(line)
            sys.stdout.flush()
            log.write(line)
        status = process.wait()

    text = log_path.read_text(encoding="utf-8", errors="replace")
    cycles = [int(value) for value in CYCLE_RE.findall(text)]
    instructions = [int(value) for value in INSTRUCTION_RE.findall(text)]
    if status != 0:
        raise RuntimeError(f"ITERATIONS={iterations} run failed with status {status}; see {log_path}")
    if "HIT GOOD TRAP" not in text:
        raise RuntimeError(f"ITERATIONS={iterations} did not hit a good trap; see {log_path}")
    if not cycles or not instructions:
        raise RuntimeError(f"ITERATIONS={iterations} has no NPC cycle/instruction totals; see {log_path}")

    image_matches = IMAGE_RE.findall(text)
    image = Path(image_matches[-1]).resolve() if image_matches else None
    image_record = None
    if image is not None and image.is_file():
        artifact_dir = run_dir / "image"
        artifact_dir.mkdir()
        image_stem = image.name.removesuffix(".bin")
        image_files = sorted(image.parent.glob(f"{image_stem}*"))
        archived_files = []
        for source in image_files:
            if not source.is_file():
                continue
            destination = artifact_dir / source.name
            shutil.copy2(source, destination)
            archived_files.append(
                {
                    "path": str(destination),
                    "source_path": str(source),
                    "sha256": sha256(destination),
                    "bytes": destination.stat().st_size,
                }
            )
        image_record = {"loaded_path": str(image), "files": archived_files}
    crcs = {name: value.lower() for name, value in CRC_RE.findall(text)}
    result = {
        "iterations": iterations,
        "difftest": difftest,
        "cycles": cycles[-1],
        "instructions": instructions[-1],
        "cpi": cycles[-1] / instructions[-1],
        "crcs": crcs,
        "image": image_record,
        "log": str(log_path),
        "command": command,
    }
    (run_dir / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> int:
    args = parse_args()
    repo_root = Path(__file__).resolve().parents[2]
    bench_dir = repo_root / "jyd-tests" / "coremark-official"
    if not (bench_dir / "Makefile").is_file():
        raise SystemExit(f"CoreMark project not found: {bench_dir}")

    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    data_root = Path(os.environ.get("JYD_DATA_ROOT", "/srv/data/jyd"))
    output_dir = (args.output_dir or data_root / "archive" / f"coremark-cycle-estimate-{args.name}-{stamp}").resolve()
    output_dir.mkdir(parents=True, exist_ok=False)

    metadata = {
        "schema_version": 2,
        "name": args.name,
        "created_utc": stamp,
        "repo_root": str(repo_root),
        "git_commit": subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=repo_root, text=True
        ).strip(),
        "git_status": subprocess.check_output(
            ["git", "status", "--short"], cwd=repo_root, text=True
        ).splitlines(),
        "arch": args.arch,
        "low_iterations": args.low_iterations,
        "high_iterations": args.high_iterations,
        "target_iterations": args.target_iterations,
        "frequency_mhz": args.frequency_mhz,
        "reference_cycles": args.reference_cycles,
        "run_difftest": {"low": True, "high": False},
        "make_args": args.make_args,
    }
    (output_dir / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")

    try:
        low = run_one(
            repo_root, bench_dir, output_dir, args.arch, args.low_iterations, True, args.make_args
        )
        high = run_one(
            repo_root, bench_dir, output_dir, args.arch, args.high_iterations, False, args.make_args
        )
    except Exception as error:
        (output_dir / "FAILED.txt").write_text(f"{error}\n", encoding="utf-8")
        raise

    iteration_delta = args.high_iterations - args.low_iterations
    cycle_delta = int(high["cycles"]) - int(low["cycles"])
    instruction_delta = int(high["instructions"]) - int(low["instructions"])
    if cycle_delta <= 0 or instruction_delta <= 0:
        raise RuntimeError("high-iteration run did not increase both cycles and instructions")
    core_crc_names = ("crclist", "crcmatrix", "crcstate")
    for name in core_crc_names:
        low_crc = low["crcs"].get(name)
        high_crc = high["crcs"].get(name)
        if low_crc is None or high_crc is None or low_crc != high_crc:
            raise RuntimeError(f"short runs disagree on {name}: low={low_crc} high={high_crc}")

    cycles_per_iteration = Fraction(cycle_delta, iteration_delta)
    fixed_cycles = Fraction(int(low["cycles"])) - args.low_iterations * cycles_per_iteration
    estimated_cycles = fixed_cycles + args.target_iterations * cycles_per_iteration
    instructions_per_iteration = Fraction(instruction_delta, iteration_delta)
    fixed_instructions = Fraction(int(low["instructions"])) - args.low_iterations * instructions_per_iteration
    estimated_instructions = fixed_instructions + args.target_iterations * instructions_per_iteration
    estimated_cycles_rounded = round(estimated_cycles)
    estimated_seconds = float(estimated_cycles) / (args.frequency_mhz * 1_000_000.0)
    reference = None
    if args.reference_cycles is not None:
        error_cycles = estimated_cycles - args.reference_cycles
        reference = {
            "cycles": args.reference_cycles,
            "error_cycles": fraction_record(error_cycles),
            "error_percent": float(error_cycles / args.reference_cycles * 100),
            "absolute_error_seconds": abs(float(error_cycles)) / (args.frequency_mhz * 1_000_000.0),
        }

    result = {
        **metadata,
        "runs": {"low": low, "high": high},
        "model": {
            "assumption": "total = fixed + iterations * per_iteration",
            "cycles_per_iteration": fraction_record(cycles_per_iteration),
            "fixed_cycles": fraction_record(fixed_cycles),
            "estimated_target_cycles": fraction_record(estimated_cycles),
            "estimated_target_cycles_rounded": estimated_cycles_rounded,
            "estimated_target_seconds": estimated_seconds,
            "instructions_per_iteration": fraction_record(instructions_per_iteration),
            "fixed_instructions": fraction_record(fixed_instructions),
            "estimated_target_instructions": fraction_record(estimated_instructions),
            "estimated_target_cpi": float(estimated_cycles / estimated_instructions),
            "reference": reference,
        },
    }
    result_path = output_dir / "estimate.json"
    result_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

    summary = (
        f"low: ITERATIONS={args.low_iterations} difftest=on "
        f"cycles={low['cycles']} instructions={low['instructions']}\n"
        f"high: ITERATIONS={args.high_iterations} difftest=off "
        f"cycles={high['cycles']} instructions={high['instructions']}\n"
        f"cycles/iteration: {float(cycles_per_iteration):.6f} ({cycles_per_iteration})\n"
        f"fixed cycles: {float(fixed_cycles):.6f} ({fixed_cycles})\n"
        f"estimated ITERATIONS={args.target_iterations}: {estimated_cycles_rounded} cycles\n"
        f"estimated time at {args.frequency_mhz:g} MHz: {estimated_seconds:.9f} s\n"
        f"estimated instructions: {round(estimated_instructions)}\n"
        f"estimated CPI: {float(estimated_cycles / estimated_instructions):.6f}\n"
    )
    if reference is not None:
        summary += (
            f"reference cycles: {args.reference_cycles}\n"
            f"model error: {float(estimated_cycles - args.reference_cycles):+.3f} cycles "
            f"({reference['error_percent']:+.6f}%)\n"
        )
    summary += f"result: {result_path}\n"
    (output_dir / "summary.txt").write_text(summary, encoding="utf-8")
    print(summary, end="")
    print("Note: this affine estimate is directional evidence; retain full-run/board validation.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

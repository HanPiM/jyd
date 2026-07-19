#!/usr/bin/env python3
"""Audit all instruction encodings in a RISC-V objdump text listing.

Classify every 32-bit entry against the current JYD ISA surface and highlight
both unknown opcode families and arithmetic encodings that coarse B decode
would route to BExtensionUnit without a matching implementation.
"""

from __future__ import annotations

import argparse
import collections
import dataclasses
import json
import re
from pathlib import Path
from typing import Iterable


INSTRUCTION_RE = re.compile(
    r"^\s*(?P<address>[0-9a-fA-F]+):\s+"
    r"(?P<encoding>[0-9a-fA-F]{4,16})\s+"
    r"(?P<mnemonic>\S+)(?:\s+(?P<operands>.*?))?\s*$"
)
SYMBOL_RE = re.compile(r"^\s*(?P<address>[0-9a-fA-F]+)\s+<(?P<symbol>[^>]+)>:\s*$")


@dataclasses.dataclass(frozen=True)
class Instruction:
    address: int
    encoding_text: str
    word: int
    size: int
    mnemonic: str
    operands: str
    source_line: int
    text: str
    symbol: str | None


def classify_arithmetic(word: int) -> tuple[str, str] | None:
    """Classify a 32-bit OP-IMM/OP instruction using current RTL policy."""
    opcode = word & 0x7F
    if opcode not in (0x13, 0x33):
        return None

    funct3 = (word >> 12) & 0x7
    funct7 = (word >> 25) & 0x7F
    rs2_or_imm5 = (word >> 20) & 0x1F

    if opcode == 0x13:
        if funct3 not in (0b001, 0b101):
            return "rv32i", "RV32I OP-IMM"
        if funct3 == 0b001 and funct7 == 0:
            return "rv32i", "RV32I SLLI"
        if funct3 == 0b101 and funct7 in (0, 0b0100000):
            return "rv32i", "RV32I SRLI/SRAI"

        supported = {
            (0b001, 0b0110000, 0): "clz",
            (0b001, 0b0110000, 1): "ctz",
            (0b001, 0b0110000, 2): "cpop",
            (0b101, 0b0010100, 7): "orc.b",
        }
        operation = supported.get((funct3, funct7, rs2_or_imm5))
        if operation:
            return "supported-b", operation
        return (
            "coarse-b-unsupported",
            f"unimplemented OP-IMM: funct7=0x{funct7:02x} funct3=0x{funct3:x} imm[4:0]=0x{rs2_or_imm5:02x}",
        )

    if funct7 == 0 or (funct7 == 0b0100000 and funct3 in (0b000, 0b101)):
        return "rv32i", "RV32I OP"
    if funct7 == 0b0000001:
        return "rv32m", "RV32M"

    supported = {
        (0b001, 0b0000101): "clmul",
        (0b010, 0b0010100): "xperm4",
        (0b101, 0b0110000): "ror",
        (0b100, 0b0000100): "pack",
    }
    operation = supported.get((funct3, funct7))
    if operation:
        return "supported-b", operation
    return (
        "coarse-b-unsupported",
        f"unimplemented OP: funct7=0x{funct7:02x} funct3=0x{funct3:x}",
    )


def classify_instruction(word: int) -> tuple[str, str]:
    """Classify one 32-bit encoding against the CPU's implemented ISA surface."""
    opcode = word & 0x7F
    funct3 = (word >> 12) & 0x7

    arithmetic = classify_arithmetic(word)
    if arithmetic:
        category, detail = arithmetic
        if category == "coarse-b-unsupported":
            return "unknown", f"current IDU routes this encoding to BExtensionUnit; {detail}"
        return category, detail

    # RV32I encodings implemented by InstInfoDecoder and EXU/LSU. Reject
    # reserved funct3 values instead of treating the opcode alone as support.
    if opcode == 0x37:
        return "rv32i", "LUI"
    if opcode == 0x17:
        return "rv32i", "AUIPC"
    if opcode == 0x6F:
        return "rv32i", "JAL"
    if opcode == 0x67:
        if funct3 == 0:
            return "rv32i", "JALR"
        return "unknown", f"reserved/unsupported JALR funct3=0x{funct3:x}"
    if opcode == 0x63:
        if funct3 in (0b000, 0b001, 0b100, 0b101, 0b110, 0b111):
            return "rv32i", "BRANCH"
        return "unknown", f"reserved/unsupported BRANCH funct3=0x{funct3:x}"
    if opcode == 0x03:
        if funct3 in (0b000, 0b001, 0b010, 0b100, 0b101):
            return "rv32i", "LOAD"
        return "unknown", f"reserved/unsupported LOAD funct3=0x{funct3:x}"
    if opcode == 0x23:
        if funct3 in (0b000, 0b001, 0b010):
            return "rv32i", "STORE"
        return "unknown", f"reserved/unsupported STORE funct3=0x{funct3:x}"
    if opcode == 0x0F:
        # Plain FENCE is accepted as a uniprocessor ordering/no-op operation.
        # FENCE.I (0000100f) is explicitly rejected by an IDU assertion.
        rd = (word >> 7) & 0x1F
        rs1 = (word >> 15) & 0x1F
        if funct3 == 0 and rd == 0 and rs1 == 0:
            return "rv32i", "FENCE"
        if funct3 == 1:
            return "unknown", "FENCE.I is explicitly unsupported by the current IDU"
        return "unknown", f"reserved/unsupported MISC-MEM encoding funct3=0x{funct3:x}"
    if opcode == 0x73:
        if word == 0x00000073:
            return "supported-system", "ECALL"
        if word == 0x30200073:
            return "supported-system", "MRET"
        if funct3 in (0b001, 0b010, 0b011, 0b101, 0b110, 0b111):
            return "supported-system", "CSR"
        if word == 0x00100073:
            return "unknown", "EBREAK is legal RV32I but is not implemented by the current CPU"
        return "unknown", f"unsupported SYSTEM/privileged encoding funct3=0x{funct3:x}"

    return "unknown", f"opcode=0x{opcode:02x} is outside the current CPU support set"


def parse_listing(path: Path) -> list[Instruction]:
    instructions: list[Instruction] = []
    current_symbol: str | None = None
    for line_number, raw_line in enumerate(path.read_text(errors="replace").splitlines(), 1):
        symbol_match = SYMBOL_RE.match(raw_line)
        if symbol_match:
            current_symbol = symbol_match.group("symbol")
            continue
        match = INSTRUCTION_RE.match(raw_line)
        if not match:
            continue
        encoding_text = match.group("encoding")
        size = len(encoding_text) // 2
        instructions.append(
            Instruction(
                address=int(match.group("address"), 16),
                encoding_text=encoding_text.lower(),
                word=int(encoding_text, 16),
                size=size,
                mnemonic=match.group("mnemonic"),
                operands=(match.group("operands") or "").strip(),
                source_line=line_number,
                text=raw_line.rstrip(),
                symbol=current_symbol,
            )
        )
    return instructions


def sign_extend(value: int, width: int) -> int:
    sign = 1 << (width - 1)
    return (value ^ sign) - sign


def direct_target(inst: Instruction) -> int | None:
    word = inst.word
    opcode = word & 0x7F
    if inst.size != 4:
        return None
    if opcode == 0x6F:  # JAL
        immediate = (
            ((word >> 31) & 0x1) << 20
            | ((word >> 12) & 0xFF) << 12
            | ((word >> 20) & 0x1) << 11
            | ((word >> 21) & 0x3FF) << 1
        )
        return inst.address + sign_extend(immediate, 21)
    if opcode == 0x63:  # conditional branch
        immediate = (
            ((word >> 31) & 0x1) << 12
            | ((word >> 7) & 0x1) << 11
            | ((word >> 25) & 0x3F) << 5
            | ((word >> 8) & 0xF) << 1
        )
        return inst.address + sign_extend(immediate, 13)
    return None


def direct_cfg_reachable(instructions: list[Instruction], entries: Iterable[int]) -> set[int]:
    """Conservative direct-CFG approximation, without register/value analysis."""
    by_address = {inst.address: inst for inst in instructions}
    pending = list(entries)
    reached: set[int] = set()
    while pending:
        address = pending.pop()
        if address in reached or address not in by_address:
            continue
        reached.add(address)
        inst = by_address[address]
        opcode = inst.word & 0x7F if inst.size == 4 else None
        next_address = inst.address + inst.size

        if opcode == 0x63:  # conditional branch: both successors
            target = direct_target(inst)
            if target is not None:
                pending.append(target)
            pending.append(next_address)
        elif opcode == 0x6F:  # JAL: call returns; J (rd=x0) does not
            target = direct_target(inst)
            if target is not None:
                pending.append(target)
            if ((inst.word >> 7) & 0x1F) != 0:
                pending.append(next_address)
        elif opcode == 0x67:  # indirect jump/call; target cannot be resolved here
            if ((inst.word >> 7) & 0x1F) != 0:
                pending.append(next_address)
        elif opcode == 0x73 and inst.word in (0x30200073,):  # MRET
            pass
        else:
            pending.append(next_address)
    return reached


def format_location(inst: Instruction, reachable: set[int]) -> str:
    reachability = "direct-CFG-reachable" if inst.address in reachable else "not-reached-by-direct-CFG"
    symbol = f" <{inst.symbol}>" if inst.symbol else ""
    return f"0x{inst.address:08x}{symbol} [{reachability}] line {inst.source_line}: {inst.text.strip()}"


def build_report(instructions: list[Instruction], entries: list[int], context: int) -> dict[str, object]:
    reachable = direct_cfg_reachable(instructions, entries)
    arithmetic: list[tuple[Instruction, str, str]] = []
    for inst in instructions:
        if inst.size != 4:
            continue
        classification = classify_arithmetic(inst.word)
        if classification:
            arithmetic.append((inst, *classification))

    mnemonic_counts = collections.Counter(inst.mnemonic for inst in instructions)
    category_counts = collections.Counter(category for _, category, _ in arithmetic)
    all_32bit: list[tuple[Instruction, str, str]] = []
    for inst in instructions:
        if inst.size == 4:
            category, detail = classify_instruction(inst.word)
            all_32bit.append((inst, category, detail))
    instruction_category_counts = collections.Counter(category for _, category, _ in all_32bit)
    supported_b_counts = collections.Counter(
        detail for _, category, detail in arithmetic if category == "supported-b"
    )
    suspicious = [item for item in arithmetic if item[1] == "coarse-b-unsupported"]

    by_index = {inst.address: index for index, inst in enumerate(instructions)}
    suspicious_rows = []
    for inst, _, detail in suspicious:
        index = by_index[inst.address]
        nearby = instructions[max(0, index - context) : index + context + 1]
        suspicious_rows.append(
            {
                "address": f"0x{inst.address:08x}",
                "encoding": inst.encoding_text,
                "mnemonic": inst.mnemonic,
                "detail": detail,
                "symbol": inst.symbol,
                "direct_cfg_reachable": inst.address in reachable,
                "source_line": inst.source_line,
                "context": [item.text.strip() for item in nearby],
            }
        )

    supported_locations = collections.defaultdict(list)
    for inst, category, detail in arithmetic:
        if category == "supported-b":
            supported_locations[detail].append(format_location(inst, reachable))

    pack_focus = []
    for inst, category, detail in arithmetic:
        if category == "supported-b" and detail == "pack":
            index = by_index[inst.address]
            nearby = instructions[max(0, index - context) : index + context + 1]
            pack_focus.append(
                {
                    "address": f"0x{inst.address:08x}",
                    "direct_cfg_reachable": inst.address in reachable,
                    "symbol": inst.symbol,
                    "context": [item.text.strip() for item in nearby],
                }
            )

    non_32bit = [
        {
            "address": f"0x{inst.address:08x}",
            "encoding": inst.encoding_text,
            "mnemonic": inst.mnemonic,
            "direct_cfg_reachable": inst.address in reachable,
            "source_line": inst.source_line,
        }
        for inst in instructions
        if inst.size != 4
    ]

    unknown_32bit = []
    for inst, category, detail in all_32bit:
        if category != "unknown":
            continue
        index = by_index[inst.address]
        nearby = instructions[max(0, index - context) : index + context + 1]
        unknown_32bit.append(
            {
                "address": f"0x{inst.address:08x}",
                "encoding": inst.encoding_text,
                "mnemonic": inst.mnemonic,
                "operands": inst.operands,
                "detail": detail,
                "symbol": inst.symbol,
                "direct_cfg_reachable": inst.address in reachable,
                "source_line": inst.source_line,
                "context": [item.text.strip() for item in nearby],
            }
        )

    return {
        "instruction_count": len(instructions),
        "entries": [f"0x{entry:08x}" for entry in entries],
        "direct_cfg_reachable_count": len(reachable),
        "mnemonic_counts": dict(sorted(mnemonic_counts.items())),
        "instruction_category_counts": dict(sorted(instruction_category_counts.items())),
        "arithmetic_category_counts": dict(sorted(category_counts.items())),
        "supported_b_counts": dict(sorted(supported_b_counts.items())),
        "supported_b_locations": dict(sorted(supported_locations.items())),
        "pack_focus": pack_focus,
        "coarse_b_unsupported": suspicious_rows,
        "unknown_32bit": unknown_32bit,
        "non_32bit_entries": non_32bit,
    }


def print_text_report(path: Path, report: dict[str, object]) -> None:
    print(f"Input: {path}")
    print(f"Parsed instructions: {report['instruction_count']}")
    print(
        "Direct-CFG approximation: "
        f"{report['direct_cfg_reachable_count']} instructions reachable from "
        f"{', '.join(report['entries'])}"
    )
    print("\nMnemonic counts:")
    counts = report["mnemonic_counts"]
    print("  " + ", ".join(f"{name}={count}" for name, count in counts.items()))

    print("\nAll 32-bit encoding categories (current CPU support set):")
    for category, count in report["instruction_category_counts"].items():
        print(f"  {category}: {count}")

    print("\nOP-IMM (0x13) / OP (0x33) encoding categories:")
    for category, count in report["arithmetic_category_counts"].items():
        print(f"  {category}: {count}")

    print("\nCurrent BExtensionUnit operations:")
    if not report["supported_b_counts"]:
        print("  (none)")
    for operation, count in report["supported_b_counts"].items():
        locations = report["supported_b_locations"][operation]
        reachable_count = sum("[direct-CFG-reachable]" in location for location in locations)
        print(f"  {operation}: {count} static, {reachable_count} direct-CFG-reachable")
        for location in locations:
            print(f"    {location}")

    print("\nPACK focus (with listing context):")
    if not report["pack_focus"]:
        print("  (none)")
    for row in report["pack_focus"]:
        reachability = "direct-CFG-reachable" if row["direct_cfg_reachable"] else "not-reached-by-direct-CFG"
        symbol = f" <{row['symbol']}>" if row["symbol"] else ""
        print(f"  {row['address']}{symbol} [{reachability}]")
        for context_line in row["context"]:
            print(f"      {context_line}")

    suspicious = report["coarse_b_unsupported"]
    print("\nUnsupported encodings that current IDU would coarsely route to BExtensionUnit:")
    if not suspicious:
        print("  (none)")
    for row in suspicious:
        reachability = "direct-CFG-reachable" if row["direct_cfg_reachable"] else "not-reached-by-direct-CFG"
        symbol = f" <{row['symbol']}>" if row["symbol"] else ""
        print(
            f"  {row['address']}{symbol} {row['encoding']} {row['mnemonic']} "
            f"[{reachability}]: {row['detail']}"
        )
        for context_line in row["context"]:
            print(f"      {context_line}")

    print("\nUnknown or potentially unimplemented 32-bit encodings:")
    if not report["unknown_32bit"]:
        print("  (none)")
    for row in report["unknown_32bit"]:
        reachability = "direct-CFG-reachable" if row["direct_cfg_reachable"] else "not-reached-by-direct-CFG"
        symbol = f" <{row['symbol']}>" if row["symbol"] else ""
        print(
            f"  {row['address']}{symbol} {row['encoding']} {row['mnemonic']} "
            f"[{reachability}]: {row['detail']}"
        )
        for context_line in row["context"]:
            print(f"      {context_line}")

    print("\nNon-32-bit listing entries (may be compressed instructions, padding, or data):")
    if not report["non_32bit_entries"]:
        print("  (none)")
    for row in report["non_32bit_entries"]:
        reachability = "direct-CFG-reachable" if row["direct_cfg_reachable"] else "not-reached-by-direct-CFG"
        print(f"  {row['address']} {row['encoding']} {row['mnemonic']} [{reachability}]")

    print(
        "\nReachability note: 'direct-CFG-reachable' is static evidence only. "
        "It follows direct jumps/calls and both branch arms, but does not prove dynamic execution "
        "and cannot resolve indirect JALR/MRET targets."
    )


def parse_int(value: str) -> int:
    return int(value, 0)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("listing", type=Path, help="objdump -d/-D text listing")
    parser.add_argument(
        "--entry",
        action="append",
        type=parse_int,
        help="static CFG entry address (repeatable; default: first parsed instruction)",
    )
    parser.add_argument("--context", type=int, default=2, help="nearby lines for suspicious encodings (default: 2)")
    parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    args = parser.parse_args()

    instructions = parse_listing(args.listing)
    if not instructions:
        parser.error(f"no disassembled instructions found in {args.listing}")
    entries = args.entry or [instructions[0].address]
    report = build_report(instructions, entries, max(0, args.context))
    if args.json:
        print(json.dumps(report, indent=2, ensure_ascii=False))
    else:
        print_text_report(args.listing, report)
    return 1 if report["unknown_32bit"] else 0


if __name__ == "__main__":
    raise SystemExit(main())

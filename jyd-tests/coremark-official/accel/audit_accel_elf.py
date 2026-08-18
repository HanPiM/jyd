#!/usr/bin/env python3
import argparse
import subprocess


ENCODINGS = {
    "xcrcu8": (0x0000000B,),
    "xdup8lo": (0x0200100B,),
    "xpaddh2": (0x0400100B,),
    "xmac16": (0x0000300B,),
    "xdot16": (0x0000400B,),
    "xdotn": (0x0600400B, 0x0800400B, 0x0A00400B, 0x0C00400B, 0x0E00400B),
    "xbmul": (0x0000500B,),
    "xmbm": (0x0200500B,),
    "xlistrev": (0x0400600B,),
    "xlistfind": (0x0200600B, 0x0600600B),
    "xmacacc": tuple(0x0800300B + (funct7 - 4) * 0x02000000 for funct7 in range(4, 10)),
    "xmsum": (0x0400700B,),
    "xdfacnt": (0x0000005B,),
    "xdfa2": (0x0000405B,),
    "xdfa4": (0x0000505B,),
    "xdfa4h": (0x0200505B,),
    "xdfa4p": (0x0400505B,),
    "xdfascan": (0x0600505B,),
}
MASK = 0xFE00707F
MASKS = {"xdup8lo": 0xFFF0707F}
EXACT_COUNTS = {
    "xcrcu8": 44,
    "xdup8lo": 2,
    "xpaddh2": 2,
    "xdfascan": 2,
    "xdotn": 8,
}


def matches_encoding(name, instruction, encoding):
    return instruction & MASKS.get(name, MASK) == encoding


def output(*args):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE).stdout


def main():
    parser = argparse.ArgumentParser(description="Audit inlined custom accelerator instructions")
    parser.add_argument("--elf", required=True)
    parser.add_argument("--accels", default="")
    parser.add_argument("--objdump", default="riscv64-unknown-linux-gnu-objdump")
    args = parser.parse_args()

    requested = [name for name in args.accels.split(",") if name]
    unknown = sorted(set(requested) - ENCODINGS.keys())
    if unknown:
        parser.error("unknown accelerators: " + ", ".join(unknown))
    enabled = requested.copy()
    superseded = []
    if "xmacacc" in enabled and "xmbm" in enabled:
        enabled.remove("xmbm")
        superseded.append("xmbm (covered by whole-loop xmacacc lowering)")

    disassembly = output(args.objdump, "-d", args.elf)
    counts = dict.fromkeys(enabled, 0)
    instructions = []
    for line in disassembly.splitlines():
        fields = line.split()
        if len(fields) < 2 or len(fields[1]) != 8:
            continue
        try:
            instruction = int(fields[1], 16)
        except ValueError:
            continue
        instructions.append(instruction)
        for name in enabled:
            if any(matches_encoding(name, instruction, encoding) for encoding in ENCODINGS[name]):
                counts[name] += 1

    for name in ("xlistfind", "xmacacc", "xdotn"):
        if name not in counts:
            continue
        missing_subops = [
            f"0x{encoding:08x}"
            for encoding in ENCODINGS[name]
            if not any(matches_encoding(name, instruction, encoding) for instruction in instructions)
        ]
        if missing_subops:
            raise SystemExit(f"{name} missing sub-operations: {', '.join(missing_subops)}")

    if "xdfa2" in counts or "xdfa4" in counts:
        commit_count = 0
        for line in disassembly.splitlines():
            fields = line.split()
            if len(fields) < 2 or len(fields[1]) != 8:
                continue
            try:
                instruction = int(fields[1], 16)
            except ValueError:
                continue
            if instruction & MASK == 0x0000305B:
                commit_count += 1
        if commit_count == 0:
            raise SystemExit("xdfa word image has no counter-mask commit instruction")

    if any(name in counts for name in ("xdfa4h", "xdfa4p", "xdfascan")):
        final_read_count = 0
        for line in disassembly.splitlines():
            fields = line.split()
            if len(fields) < 2 or len(fields[1]) != 8:
                continue
            try:
                instruction = int(fields[1], 16)
            except ValueError:
                continue
            if instruction & MASK == 0x0200205B:
                final_read_count += 1
        if final_read_count == 0:
            raise SystemExit("xdfa image has no final-counter read instruction")
        if "xdfascan" in counts and final_read_count != 1:
            raise SystemExit(
                f"xdfascan final-counter read count mismatch: expected 1, got {final_read_count}"
            )

    missing = [name for name, count in counts.items() if count == 0]
    if missing:
        raise SystemExit("enabled instructions absent from final ELF: " + ", ".join(missing))
    for name, expected in EXACT_COUNTS.items():
        if name in counts and counts[name] != expected:
            raise SystemExit(
                f"{name} static instruction count mismatch: expected {expected}, got {counts[name]}"
            )
    print("custom-instruction encoding audit: PASS")
    for name in superseded:
        print(f"superseded: {name}")
    for name, count in counts.items():
        print(f"{name}: {count} static instruction site(s)")
    if "xdfa2" in counts or "xdfa4" in counts:
        print(f"xdfa word commit: {commit_count} static instruction site(s)")
    if any(name in counts for name in ("xdfa4h", "xdfa4p", "xdfascan")):
        print(f"xdfa final read: {final_read_count} static instruction site(s)")


if __name__ == "__main__":
    main()

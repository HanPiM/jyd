#!/usr/bin/env python3
import argparse
import subprocess


ENCODINGS = {
    "xcrcu8": (0x0000000B,),
    "xmac16": (0x0000300B,),
    "xdot16": (0x0000400B,),
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
}
MASK = 0xFE00707F


def output(*args):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE).stdout


def main():
    parser = argparse.ArgumentParser(description="Audit inlined custom accelerator instructions")
    parser.add_argument("--elf", required=True)
    parser.add_argument("--accels", default="")
    args = parser.parse_args()

    requested = [name for name in args.accels.split(",") if name]
    unknown = sorted(set(requested) - ENCODINGS.keys())
    if unknown:
        parser.error("unknown accelerators: " + ", ".join(unknown))
    enabled = requested.copy()
    superseded = []
    if "xmacacc" in enabled and "xmbm" in enabled:
        enabled.remove("xmbm")
        superseded.append("xmbm (matrix bit-extract is replaced by xmacacc)")

    symbols = output("riscv64-linux-gnu-nm", args.elf)
    wrappers = [line for line in symbols.splitlines() if "__xaccel_" in line]
    if wrappers:
        raise SystemExit("wrapper symbols survived final link:\n" + "\n".join(wrappers))

    disassembly = output("riscv64-linux-gnu-objdump", "-d", args.elf)
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
            if instruction & MASK in ENCODINGS[name]:
                counts[name] += 1

    for name in ("xlistfind", "xmacacc"):
        if name not in counts:
            continue
        missing_subops = [
            f"0x{encoding:08x}"
            for encoding in ENCODINGS[name]
            if not any(instruction & MASK == encoding for instruction in instructions)
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

    if "xdfa4h" in counts or "xdfa4p" in counts:
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
            raise SystemExit("xdfa4h image has no final-counter read instruction")

    missing = [name for name, count in counts.items() if count == 0]
    if missing:
        raise SystemExit("enabled instructions absent from final ELF: " + ", ".join(missing))
    print("no __xaccel_ wrapper symbols or calls remain")
    for name in superseded:
        print(f"superseded: {name}")
    for name, count in counts.items():
        print(f"{name}: {count} static instruction site(s)")
    if "xdfa2" in counts or "xdfa4" in counts:
        print(f"xdfa word commit: {commit_count} static instruction site(s)")
    if "xdfa4h" in counts or "xdfa4p" in counts:
        print(f"xdfa4h final read: {final_read_count} static instruction site(s)")


if __name__ == "__main__":
    main()

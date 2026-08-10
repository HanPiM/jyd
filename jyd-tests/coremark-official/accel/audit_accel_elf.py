#!/usr/bin/env python3
import argparse
import re
import subprocess


ENCODINGS = {
    "xcrcu8": 0x0000000B,
    "xmac16": 0x0000300B,
    "xdot16": 0x0000400B,
    "xbmul": 0x0000500B,
    "xlrev2": 0x0400600B,
    "xlrev1": 0x0000600B,
    "xlrev": 0x0000700B,
    "xstate": 0x0200700B,
    "xmsum": 0x0400700B,
    "xstatec": 0x0000005B,
    "xstate2": 0x0000405B,
    "xstate4": 0x0000505B,
}
MASK = 0xFE00707F


def output(*args):
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE).stdout


def main():
    parser = argparse.ArgumentParser(description="Audit inlined CoreMark custom instructions")
    parser.add_argument("--elf", required=True)
    parser.add_argument("--accels", default="")
    parser.add_argument("--fp12-report", action="store_true")
    args = parser.parse_args()

    enabled = [name for name in args.accels.split(",") if name]
    unknown = sorted(set(enabled) - ENCODINGS.keys())
    if unknown:
        parser.error("unknown accelerators: " + ", ".join(unknown))

    symbols = output("riscv64-linux-gnu-nm", args.elf)
    wrappers = [line for line in symbols.splitlines() if "__cm_" in line]
    if wrappers:
        raise SystemExit("wrapper symbols survived final link:\n" + "\n".join(wrappers))
    if args.fp12_report:
        float_helpers = [
            line
            for line in symbols.splitlines()
            if re.search(r"(?:softfloat|__[a-z]+(?:df|sf|tf)3)\b", line, re.IGNORECASE)
        ]
        if float_helpers:
            raise SystemExit(
                "floating-point helper symbols survived fp12 reporting:\n"
                + "\n".join(float_helpers)
            )

    disassembly = output("riscv64-linux-gnu-objdump", "-d", args.elf)
    counts = dict.fromkeys(enabled, 0)
    for line in disassembly.splitlines():
        fields = line.split()
        if len(fields) < 2 or len(fields[1]) != 8:
            continue
        try:
            instruction = int(fields[1], 16)
        except ValueError:
            continue
        for name in enabled:
            if instruction & MASK == ENCODINGS[name]:
                counts[name] += 1

    if "xstate2" in counts or "xstate4" in counts:
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
            raise SystemExit("xstate word image has no counter-mask commit instruction")

    missing = [name for name, count in counts.items() if count == 0]
    if missing:
        raise SystemExit("enabled instructions absent from final ELF: " + ", ".join(missing))
    print("no __cm_ wrapper symbols or calls remain")
    if args.fp12_report:
        print("no floating-point helper symbols remain")
    for name, count in counts.items():
        print(f"{name}: {count} static instruction site(s)")
    if "xstate2" in counts or "xstate4" in counts:
        print(f"xstate word commit: {commit_count} static instruction site(s)")


if __name__ == "__main__":
    main()

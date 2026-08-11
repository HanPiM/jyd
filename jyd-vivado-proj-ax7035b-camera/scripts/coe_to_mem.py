#!/usr/bin/env python3
import re
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: coe_to_mem.py INPUT.coe OUTPUT.mem DEPTH", file=sys.stderr)
        return 2

    source = Path(sys.argv[1])
    destination = Path(sys.argv[2])
    depth = int(sys.argv[3])
    text = source.read_text(encoding="ascii", errors="strict")
    marker = "memory_initialization_vector="
    if marker not in text.lower():
        raise ValueError(f"missing COE initialization vector: {source}")
    vector = re.split(marker, text, flags=re.IGNORECASE, maxsplit=1)[1]
    words = [word for word in re.split(r"[,;\s]+", vector) if word]
    if len(words) > depth:
        raise ValueError(f"{source} contains {len(words)} words; memory depth is {depth}")
    if any(not re.fullmatch(r"[0-9a-fA-F]{1,8}", word) for word in words):
        raise ValueError(f"invalid 32-bit hexadecimal word in {source}")

    words.extend(["00000000"] * (depth - len(words)))
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text("\n".join(word.zfill(8) for word in words) + "\n", encoding="ascii")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

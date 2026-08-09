#!/usr/bin/env python3
import argparse
import re
from pathlib import Path


def convert(source: Path, destination: Path, depth: int) -> None:
    text = source.read_text(encoding="ascii")
    radix_match = re.search(r"memory_initialization_radix\s*=\s*(\d+)\s*;", text, re.I)
    vector_match = re.search(r"memory_initialization_vector\s*=\s*(.*?)\s*;", text, re.I | re.S)
    if not radix_match or not vector_match:
        raise ValueError(f"invalid COE file: {source}")
    if int(radix_match.group(1)) != 16:
        raise ValueError(f"only radix-16 COE is supported: {source}")
    words = [word.strip().replace("_", "") for word in vector_match.group(1).split(",") if word.strip()]
    if len(words) > depth:
        raise ValueError(f"{source} has {len(words)} words; depth is {depth}")
    for word in words:
        if not re.fullmatch(r"[0-9a-fA-F]{1,8}", word):
            raise ValueError(f"invalid 32-bit word {word!r} in {source}")
    words.extend(["00000000"] * (depth - len(words)))
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text("\n".join(f"{int(word, 16):08x}" for word in words) + "\n", encoding="ascii")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("depth", type=int)
    args = parser.parse_args()
    convert(args.source, args.destination, args.depth)


if __name__ == "__main__":
    main()

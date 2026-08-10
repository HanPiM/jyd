#!/usr/bin/env python3
"""Generate COMPILER_FLAGS from the actual Make variables."""

import argparse
from pathlib import Path


parser = argparse.ArgumentParser()
parser.add_argument("--output", required=True)
parser.add_argument("flags", nargs=argparse.REMAINDER)
args = parser.parse_args()
flags = args.flags[1:] if args.flags[:1] == ["--"] else args.flags
value = " ".join(flags).replace("\\", "\\\\").replace('"', '\\"')
contents = f'#define COMPILER_FLAGS "{value}"\n'
output = Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
if not output.exists() or output.read_text() != contents:
    output.write_text(contents)

#!/usr/bin/env python3
"""Fail on accidental disclosure or unresolved LaTeX references."""

from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
text = "\n".join(p.read_text(encoding="utf-8") for p in root.rglob("*.tex"))

forbidden = {
    "software wrappers": r"xaccel_wrappers|__builtin|\.insn|inline assembly|\u5185\u8054\u6c47\u7f16",
    "failed experiments": r"\u5931\u8d25\u5c1d\u8bd5|\u56de\u9000\u5c1d\u8bd5|\u88ab\u5426\u51b3\u65b9\u6848",
    "unapproved benchmark coupling": r"list_find|core_bench_list|core_state_transition",
}

errors = []
for label, pattern in forbidden.items():
    if re.search(pattern, text, flags=re.IGNORECASE):
        errors.append(f"forbidden {label}: /{pattern}/")

if "fabb5995bdd394578fbf989b6b20d5eae45b320c" not in text:
    errors.append("frozen commit is missing")
if "ReginalFinal-Post-Report-Version" not in text:
    errors.append("regional baseline tag is missing")

if errors:
    raise SystemExit("document audit failed:\n- " + "\n- ".join(errors))
print("document audit passed")


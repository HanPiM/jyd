#!/usr/bin/env python3
"""Fail on wording and implementation details excluded from the final report."""

from pathlib import Path
import re
import sys

root = Path(sys.argv[1])
source_files = [*root.rglob("*.tex"), *root.rglob("*.csv")]
text = "\n".join(p.read_text(encoding="utf-8") for p in source_files)

# Instruction-selection comparisons deliberately show highlighted assembler encodings.
# Keep rejecting unmarked .insn text, which usually indicates a software wrapper.
software_text = re.sub(r"\\CustomInsn\{\.insn[^{}]*\}\{[^{}]*\}", "", text)
software_text = software_text.replace("morekeywords={.insn,", "morekeywords={")

forbidden = {
    "software wrappers": r"xaccel_wrappers|__builtin|\.insn|inline assembly|\u5185\u8054\u6c47\u7f16",
    "failed experiments": r"\u5931\u8d25\u5c1d\u8bd5|\u56de\u9000\u5c1d\u8bd5|\u88ab\u5426\u51b3\u65b9\u6848",
    "unapproved benchmark coupling": r"list_find|core_bench_list|core_state_transition",
    "version-control identifiers": r"\b[0-9a-f]{40}\b|ReginalFinal-Post-Report-Version|\u51bb\u7ed3",
    "verification-viewpoint wording": r"(?:\u4ee3\u7801|RTL|\u6e90\u7801).{0,10}(?:\u786e\u8ba4|\u6838\u5bf9)|(?:\u786e\u8ba4|\u6838\u5bf9).{0,10}(?:\u4ee3\u7801|RTL|\u6e90\u7801)",
    "defensive benchmark wording": r"\u4e0d\u9488\u5bf9|\u4e0d\u7ed1\u5b9a|\u4e0d\u5c55\u5f00\u4efb\u4f55\u8f6f\u4ef6|\u800c\u4e0d\u662f\u7ed1\u5b9a",
    "audience wording": r"\u8bc4\u59d4",
}

errors = []
for label, pattern in forbidden.items():
    search_text = software_text if label == "software wrappers" else text
    if re.search(pattern, search_text, flags=re.IGNORECASE):
        errors.append(f"forbidden {label}: /{pattern}/")

if errors:
    raise SystemExit("document audit failed:\n- " + "\n- ".join(errors))
print("document audit passed")

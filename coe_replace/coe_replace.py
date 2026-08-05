#!/usr/bin/env python3
"""Replace the IROM/DRAM contents embedded in a JYD bitstream.

This implements the "bit-file memory replacement" trick without re-running
Vivado synthesis/implementation:

  1. A routed/post-route DCP from the same implementation tells us exactly
     which BRAM primitives hold the IROM and DRAM and where they are placed.
  2. The COE pairs are converted into per-BRAM INIT_xx / INITP_xx strings
     using the same layout rules that Vivado used when it built the original
     bitstream (including the 4Kx9 parity bits used for instruction bits 14
     and 23 of IROM cells 2..5).
  3. The INIT/INITP properties are written into a copy of the DCP and
     ``write_bitstream`` regenerates only the bitstream, preserving the exact
     placement/routing.

The result is a new .bit that is byte-identical to the original everywhere
except the header timestamp and the BRAM initialization frames.

Requirements:
  - Vivado 2024.2 (or another version that can open the DCP)
  - Python 3 (standard library only)

Example:
  python3 coe_replace.py \
      --dcp /srv/data/jyd/archive/.../top_postroute_physopt.dcp \
      --irom-coe irom.coe --dram-coe dram.coe \
      --out top_new.bit \
      --bit original.bit          # optional: print a change report
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path


# ---------------------------------------------------------------------------
# Layout rules reverse-engineered from the digital_twin xc7k325t design.
#
# IROM (8192 x 32, base 0x80000000):
#   ramloop[0] : RAMB18E1, 8192 x 2  -> data bits [1:0],   addresses 0..8191
#   ramloop[1] : RAMB36E1, 8192 x 4  -> data bits [5:2],   addresses 0..8191
#   ramloop[2] : RAMB36E1, 4096 x 8  -> data bits [13:6],  addr 0..4095
#                                       parity bit 14 (INITP)
#   ramloop[3] : RAMB36E1, 4096 x 8  -> data bits [13:6],  addr 4096..8191
#                                       parity bit 14 (INITP)
#   ramloop[4] : RAMB36E1, 4096 x 8  -> data bits [22:15], addr 0..4095
#                                       parity bit 23 (INITP)
#   ramloop[5] : RAMB36E1, 4096 x 8  -> data bits [22:15], addr 4096..8191
#                                       parity bit 23 (INITP)
#   ramloop[6] : RAMB36E1, 4096 x 8  -> data bits [31:24], addr 0..4095
#   ramloop[7] : RAMB36E1, 4096 x 8  -> data bits [31:24], addr 4096..8191
#
# DRAM (65536 x 32, base 0x80100000): 64 x RAMB36E1, each 4096 x 8.
#   ramloop[i] : bank = i % 16, byte lane = i // 16
#                addresses [bank*4096 .. bank*4096+4095]
#                data bits [lane*8+7 : lane*8]
# ---------------------------------------------------------------------------

IROM_SPECS = [
    # (ramloop_idx, depth, first_bit, nbits, base_addr, parity_bit)
    (0, 8192, 0, 2, 0, None),
    (1, 8192, 2, 4, 0, None),
    (2, 4096, 6, 8, 0, 14),
    (3, 4096, 6, 8, 4096, 14),
    (4, 4096, 15, 8, 0, 23),
    (5, 4096, 15, 8, 4096, 23),
    (6, 4096, 24, 8, 0, None),
    (7, 4096, 24, 8, 4096, None),
]

DRAM_NUM_CELLS = 64
DRAM_DEPTH = 4096


def parse_coe(path: Path) -> list[int]:
    """Parse a Xilinx COE file into a list of 32-bit words."""
    words: list[int] = []
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("memory_initialization") or line.startswith(";"):
                continue
            line = line.rstrip(",").rstrip(";").strip()
            if re.fullmatch(r"[0-9A-Fa-f]+", line):
                words.append(int(line, 16))
    return words


def bits_to_hex(bits: list[int]) -> str:
    """Format a 256-bit array (index 0 = bit 0) as ``256'h<hex>`` content."""
    out: list[str] = []
    for i in range(0, 256, 4):
        v = 0
        for j in range(4):
            v = (v << 1) | bits[255 - (i + j)]
        out.append(f"{v:X}")
    return "".join(out)


def compute_init(
    words: list[int],
    depth: int,
    first_bit: int,
    nbits: int,
    base_addr: int,
    nkeys: int,
    parity_bit: int | None = None,
) -> tuple[dict[str, str], dict[str, str]]:
    """Compute INIT_xx/INITP_xx strings for one BRAM cell.

    ``nkeys`` is the number of 256-bit INIT strings the primitive exposes:
    64 for RAMB18E1, 128 for RAMB36E1.
    """
    addrs_per_string = 256 // nbits
    strings: dict[int, list[int]] = {}
    for a in range(depth):
        w = words[base_addr + a] if base_addr + a < len(words) else 0
        sidx = a // addrs_per_string
        pos = (a % addrs_per_string) * nbits
        for k in range(nbits):
            strings.setdefault(sidx, [0] * 256)[pos + k] = (w >> (first_bit + k)) & 1
    init = {
        f"INIT_{sidx:02X}": bits_to_hex(strings.get(sidx, [0] * 256))
        for sidx in range(nkeys)
    }

    initp: dict[str, str] = {}
    if parity_bit is not None:
        pstrings: dict[int, list[int]] = {}
        for a in range(depth):
            w = words[base_addr + a] if base_addr + a < len(words) else 0
            sidx = a // 256
            pstrings.setdefault(sidx, [0] * 256)[a % 256] = (w >> parity_bit) & 1
        for sidx in range(16):
            initp[f"INITP_{sidx:02X}"] = bits_to_hex(pstrings.get(sidx, [0] * 256))
    return init, initp


def dump_cells_tcl(dcp: Path, out_json: Path, vivado: str, workdir: Path) -> list[dict]:
    """Ask Vivado to list the IROM/DRAM BRAM cells of the DCP."""
    tcl = workdir / "dump_cells.tcl"
    tcl.write_text(
        f"""\
open_checkpoint {dcp}
set fh [open {out_json} w]
foreach c [lsort [get_cells -hier -filter {{REF_NAME =~ RAMB* && (NAME =~ *irom* || NAME =~ *dram*)}}]] {{
  set name [get_property NAME $c]
  set loc [get_property LOC $c]
  set ref [get_property REF_NAME $c]
  set idx -1
  if {{[regexp {{ramloop\\[(\\d+)\\]}} $name m idx]}} {{ }}
  puts $fh [format {{"%s","%s","%s","%s"}} $name $loc $ref $idx]
}}
close $fh
close_design
""",
        encoding="utf-8",
    )
    run_vivado(vivado, tcl, workdir)
    cells: list[dict] = []
    with open(out_json, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            name, loc, ref, idx = json.loads("[" + line + "]")
            cells.append({"name": name, "loc": loc, "ref": ref, "idx": int(idx)})
    return cells


def run_vivado(vivado: str, tcl: Path, workdir: Path) -> None:
    cmd = [vivado, "-mode", "batch", "-nolog", "-nojournal", "-source", str(tcl)]
    proc = subprocess.run(cmd, cwd=workdir, capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stdout[-4000:])
        sys.stderr.write(proc.stderr[-4000:])
        raise SystemExit(f"Vivado failed with exit code {proc.returncode}")


def build_specs(cells: list[dict]) -> tuple[dict[int, dict], dict[int, dict]]:
    """Group cells by memory and ramloop index; validate the expected layout."""
    irom: dict[int, dict] = {}
    dram: dict[int, dict] = {}
    for c in cells:
        if "irom" in c["name"]:
            irom[c["idx"]] = c
        elif "dram" in c["name"]:
            dram[c["idx"]] = c

    if sorted(irom) != [i for i, *_ in IROM_SPECS]:
        raise SystemExit(
            f"unexpected IROM cell indices {sorted(irom)}; this script supports "
            "the digital_twin 8192x32 IROM layout only"
        )
    if sorted(dram) != list(range(DRAM_NUM_CELLS)):
        raise SystemExit(
            f"unexpected DRAM cell indices {sorted(dram)}; expected "
            f"{DRAM_NUM_CELLS} cells"
        )
    return irom, dram


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--dcp", required=True, type=Path, help="routed/post-route DCP of the same implementation")
    ap.add_argument("--irom-coe", required=True, type=Path, help="new IROM COE file")
    ap.add_argument("--dram-coe", required=True, type=Path, help="new DRAM COE file")
    ap.add_argument("--out", required=True, type=Path, help="output .bit path")
    ap.add_argument("--bit", type=Path, default=None, help="original .bit for a change report")
    ap.add_argument("--vivado", default="vivado", help="Vivado executable (default: vivado)")
    ap.add_argument("--workdir", type=Path, default=None, help="scratch directory (default: mktemp)")
    args = ap.parse_args()

    for p in (args.dcp, args.irom_coe, args.dram_coe):
        if not p.is_file():
            raise SystemExit(f"missing file: {p}")
    args.out.parent.mkdir(parents=True, exist_ok=True)

    workdir = args.workdir or Path(tempfile.mkdtemp(prefix="coe_replace-"))
    workdir.mkdir(parents=True, exist_ok=True)

    cells_json = workdir / "cells.json"
    cells = dump_cells_tcl(args.dcp, cells_json, args.vivado, workdir)
    irom, dram = build_specs(cells)

    irom_words = parse_coe(args.irom_coe)
    dram_words = parse_coe(args.dram_coe)
    print(f"IROM words: {len(irom_words)}, DRAM words: {len(dram_words)}")

    tcl_lines = [
        f"open_checkpoint {args.dcp}",
    ]
    for idx, depth, first_bit, nbits, base_addr, parity_bit in IROM_SPECS:
        cell = irom[idx]
        nkeys = 64 if cell["ref"] == "RAMB18E1" else 128
        init, initp = compute_init(
            irom_words, depth, first_bit, nbits, base_addr, nkeys, parity_bit
        )
        tcl_lines.append(f"set cell [get_cells {{{cell['name']}}}]")
        for key, val in init.items():
            tcl_lines.append(f"set_property {key} 256'h{val} $cell")
        for key, val in initp.items():
            tcl_lines.append(f"set_property {key} 256'h{val} $cell")

    for idx in range(DRAM_NUM_CELLS):
        cell = dram[idx]
        bank = idx % 16
        lane = idx // 16
        first_bit = lane * 8
        base_addr = bank * DRAM_DEPTH
        init, initp = compute_init(
            dram_words, DRAM_DEPTH, first_bit, 8, base_addr, 128
        )
        tcl_lines.append(f"set cell [get_cells {{{cell['name']}}}]")
        for key, val in init.items():
            tcl_lines.append(f"set_property {key} 256'h{val} $cell")

    tcl_lines.append(f"write_bitstream -force {args.out}")
    tcl_lines.append("close_design")
    apply_tcl = workdir / "apply.tcl"
    apply_tcl.write_text("\n".join(tcl_lines) + "\n", encoding="utf-8")

    run_vivado(args.vivado, apply_tcl, workdir)
    if not args.out.is_file():
        raise SystemExit("write_bitstream did not produce the expected output file")
    print(f"wrote {args.out} ({args.out.stat().st_size} bytes)")

    if args.bit is not None and args.bit.is_file():
        a = args.bit.read_bytes()
        b = args.out.read_bytes()
        if len(a) != len(b):
            print(f"size differs: original {len(a)} vs new {len(b)}")
        else:
            diffs = [i for i in range(len(a)) if a[i] != b[i]]
            frame_diffs = [i for i in diffs if not (0x50 <= i <= 0x60)]
            print(f"changed bytes: {len(diffs)} (header {len(diffs) - len(frame_diffs)}, "
                  f"BRAM init frames {len(frame_diffs)})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

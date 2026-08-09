# AX7035B resource feasibility audit

Audit baseline: `feat/aht10-mmio` commit
`ee79ad06e5d5a667dfba061eb616d0ec964eff2f`.

Vivado 2024.2 synthesized the complete current contest top for
`xc7k325tffg900-2` from a disposable project copy.  The retained report is:

`E:\jyd_data\archive\ax7035b-aht10\stage2\current_jyd_utilization_synth.rpt`

| Scope | LUT | FF | RAMB36 | RAMB18 | DSP |
|---|---:|---:|---:|---:|---:|
| Complete current top | 5164 | 3049 | 71 | 4 | 11 |
| JYDFPGATop | 4909 | 2659 | 71 | 2 | 11 |
| CPUCore | 4185 | 1840 | 0 | 1 | 9 |
| AHT10 MMIO/controller | 332 | 396 | 0 | 0 | 2 |
| 256 KiB DRAM | 144 | 36 | 64 | 0 | 0 |
| 32 KiB IROM | 32 | 2 | 7 | 1 | 0 |
| Contest UART shell | 251 | 383 | 0 | 2 | 0 |

The XC7A35T provides 20,800 LUTs, 41,600 FFs, 50 RAMB36 (1,800 Kibit)
and 90 DSP48E1 blocks.  The unmodified design exceeds block RAM capacity;
CPU logic and DSP use do not approach the device limits.

## Board-local memory decision

The AX7035B port retains the 32 KiB IROM and reduces only the physical DRAM
from 256 KiB to 64 KiB.  This removes approximately 48 RAMB36 blocks.  The
board UART is implemented in ordinary logic and does not require the contest
UART's two RAMB18 FIFOs.

Measured software requirements before the board workloads were added:

| Image | IROM text | DRAM initialized | DRAM span including BSS |
|---|---:|---:|---:|
| deterministic AHT10 MMIO smoke | 1,792 B | 180 B | 180 B |
| RT-Thread Nano with CoreMark | 25,576 B | 3,841 B | 43,296 B |

Thus 32 KiB IROM accommodates even the larger existing RT image, and 64 KiB
DRAM leaves more than 22 KiB above its measured 43,296-byte DRAM span.
The board flow will build RT-Thread with `COREMARK_ENABLE=0`, reducing the
requirement further.  No CPU microarchitecture or ISA feature is removed.

## Final AX7035B routed result

Vivado 2024.2 completed synthesis, placement, routing, DRC, and bitstream
generation for `xc7a35tfgg484-2` at a 100 MHz CPU clock.  All three software
initializations have the same post-route logic result:

| Resource | Used | Available | Utilization |
|---|---:|---:|---:|
| LUT | 4,796 | 20,800 | 23.06% |
| FF | 2,612 | 41,600 | 6.28% |
| BRAM | 24 RAMB36 + 1 RAMB18 | 50 RAMB36 equivalent | 49.00% |
| DSP48E1 | 11 | 90 | 12.22% |

Post-route timing is WNS `+0.860 ns`, TNS `0.000 ns`, WHS `+0.076 ns`, and
THS `0.000 ns`.  Route status reports zero routing errors.  The log reports
zero errors and zero critical warnings.

The `COREMARK_ENABLE=0` compatibility RT-Thread image built from upstream
commit `8afd041631ec44653c2de45581bcdae15fb2ae06` uses 13,168 bytes of IROM,
2,348 bytes of initialized DRAM, and about 39.8 KiB of DRAM including BSS.
The repository's pinned commit `d7f96f676053860085e35a76504a3c873e42d9d3`
was unavailable from the configured upstream and is not represented as tested.

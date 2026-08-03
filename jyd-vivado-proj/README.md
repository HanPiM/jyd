# JYD Vivado Project

Vivado digital twin project used by the `jyd` GitHub Actions FPGA flow. This
directory is tracked in the `jyd` workbench so CPU and Vivado changes can be
committed together.

## UART topology

`top.sv` connects the CPU's legacy byte-wide UART device to
`jyd_uart_subsystem.sv`. The subsystem crosses the AXI4-Lite interface from the
CPU clock to 50 MHz with `jyd_axi_clock_converter`, then drives
`jyd_axi_uartlite` at 9600 baud, 8N1. The IP configurations are checked in as
XCI files under `digital_twin.srcs/sources_1/ip/`.

The former `uart` and `twin_controller` instances are intentionally not part of
the top-level design. Board LED and seven-segment outputs are still driven by
`student_top`; UART RX/TX are reserved for direct program I/O.

The FPGA CPU also instantiates `JYDFPGATickCounter`, a JYD-owned raw tick
counter running at 50 MHz. The legacy contest `counter.sv` file remains in the
project for reference but is not instantiated. Counter enable and the Gray tick
bus use two-stage synchronizers constrained in `my.xdc`.

## CI Scripts

- `scripts/run-vivado-flow.sh`: runs synth, implementation, or bitstream generation and copies Vivado reports into the selected result directory.
- `scripts/extract-timing-summary.py`: extracts the `Design Timing Summary` section from `top_timing_summary_routed.rpt` for GitHub Actions summaries.
- `scripts/extract-wns-violations.py`: extracts the worst setup/WNS timing violations from `top_timing_summary_routed.rpt` (default 10 paths).
- `scripts/format-burn-summary.py`: formats the `jyd-submit-bits` JSON result for the caller's GitHub Actions summary, including LED hex, LED ASCII output, and the public `has_error` flag.

Example:

```bash
python3 scripts/extract-timing-summary.py
python3 scripts/extract-timing-summary.py result/src0
python3 scripts/extract-timing-summary.py result/src0/top_timing_summary_routed.rpt
python3 scripts/extract-wns-violations.py
python3 scripts/extract-wns-violations.py result/src0
python3 scripts/extract-wns-violations.py result/src0 --index 3
python3 scripts/extract-wns-violations.py result/src0 -n 20 --full
python3 scripts/format-burn-summary.py burn-result-src0.json --sample src0
```

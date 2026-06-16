# JYD Vivado Project

Vivado digital twin project used by the `jyd` GitHub Actions FPGA flow.

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

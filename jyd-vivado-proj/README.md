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

- `scripts/run-vivado-flow.sh`: runs synth, implementation, or bitstream generation, archives the relevant logs/DCPs, and runs the strict audit by default.
- `scripts/extract-vivado-run-summary.py`: the machine-readable audit entry point. It emits versioned JSON with timing, path families, path primitive/net/clock evidence, utilization resource counts, congestion evidence, all discovered Vivado logs, hard constraint errors, DRC/unconstrained/IP checks, and hashed identity artifacts.
- `scripts/iteration-audit.py`: validates iteration sidecars, builds deterministic `index.jsonl`, rejects conflicting or duplicate records, and queries the aggregate without opening Markdown or raw Vivado text.
- `scripts/extract-timing-summary.py`: extracts the `Design Timing Summary` section from `top_timing_summary_routed.rpt` for GitHub Actions summaries.
- `scripts/extract-wns-violations.py`: extracts the worst setup/WNS timing violations from `top_timing_summary_routed.rpt` (default 10 paths).
- `scripts/format-burn-summary.py`: formats the `jyd-submit-bits` JSON result for the caller's GitHub Actions summary, including LED hex, LED ASCII output, and the public `has_error` flag.

### Audit workflow

Use the JSON extractor and `iteration-audit.py` as the concise first view for
routine reviews. Raw Vivado `.rpt`/`.log` files and iteration records remain
available for direct inspection whenever they are useful.
The extractor prefers `top_timing_summary_postroute_physopted.rpt`; a routed
fallback is explicitly marked and is not eligible for formal screening. It
audits `runme`, `vivado`, `impl`, and `runner` logs whenever they are present,
and hashes the source/input manifests, DCP, bitstream, reports, and logs.
Pass metadata containing the code commit, input, manifests, requested
frequency, strategy, DCP, bitstream, and measured runtime:

```sh
python3 scripts/extract-vivado-run-summary.py result/src0 \
  --metadata result/src0/identity.json \
  --output result/src0/vivado-run-summary.json

python3 scripts/extract-vivado-run-summary.py result/src0 \
  --path-report result/src0/timing_paths_postroute.rpt \
  --utilization-report result/src0/top_utilization_placed.rpt \
  --congestion-report result/src0/top_congestion.rpt \
  --baseline-summary baseline/vivado-run-summary.json \
  --output result/src0/vivado-run-summary.json

python3 scripts/extract-vivado-run-summary.py result/src0 \
  --path-report result/src0/timing_paths_postroute.rpt \
  --view optimization
```

`run-vivado-flow.sh` copies all matching flow logs and DCPs, emits the summary,
and returns non-zero unless the strict audit passes. Use `--summary-metadata`
to provide the required identity and runtime data. The hard gates are strict:
`WNS > -0.3 ns` and `runtime < 10.75 s`; equality at either boundary fails.
The summary is versioned by `schema`/`schema_version` and its analysis extension
by `analysis_schema`/`analysis_schema_version`; the contracts live under
`scripts/schemas/`. Auxiliary reports are auto-discovered beside the selected
timing report, or can be supplied explicitly with `--path-report`, repeated
`--utilization-report`, and repeated `--congestion-report`. A baseline summary
or utilization report is opt-in with `--baseline-summary` or
`--baseline-utilization-report`. The default `audit` view is the complete
machine-readable record used by sidecars. `--view optimization` emits a bounded
decision view: duplicate paths are collapsed, each path carries at most three
data nets, and the global ranking carries at most five nets. The full audit
keeps at most five data nets per unique path and bounds aggregate lists with
`--path-limit`.

Iteration notes should have one adjacent `*.sidecar.json` following
`scripts/schemas/iteration-sidecar.schema.json`. Each sidecar records
`goal_checks`, `evidence_quality`, `promotion_level`, `supersedes`,
`baseline_id`, `method_class`, `decision`, `validation_debt`, and multiple
board samples. Every artifact and summary reference carries a SHA-256; the
validator checks commits, hashes, summary identity/metrics, conflicts, and
duplicate experiment IDs before indexing:

```sh
python3 scripts/iteration-audit.py validate path/to/EXP-001.sidecar.json
python3 scripts/iteration-audit.py build-index path/to/iterations \
  --output path/to/iterations/index.jsonl
python3 scripts/iteration-audit.py query path/to/iterations/index.jsonl \
  --frequency 300 --wns-gt -0.3 --runtime-lt 10.75 \
  --audit-status ok --evidence-quality partial \
  --report-kind postroute_physopted --clock-groups-coverage complete \
  --path-families-coverage sampled \
  --path-family setup:btb_lookup_to_fetch_prediction \
  --has-primitive RAMD64E --resources-coverage complete \
  --resource-delta-lt RAMD64E=0 --board-valid --sort wns_ns
```

`clock_groups`, `path_families`, `resources`, and `congestion` explicitly carry
a `coverage` value of `complete`, `sampled`, `partial`, or `unknown`. The timing
summary table provides complete clock-group worst slack/TNS/endpoint metrics;
it is never presented as semantic path-family coverage. Semantic path families,
primitive histograms, top data-delay nets, and clock delay/skew details come
from bounded max-path samples and are always marked `sampled`. Identical path
rows and clock-delay tuples are compacted with an `occurrences` count. Launch
and capture clock-tree primitives/nets are excluded from data-path histograms
and top-net rankings. Utilization counts are normalized for `RAM64M`,
`RAMD64E`, `LUT`, `FF`, and `BRAM`; relative baseline changes are null unless
both current and baseline counts are available. A parser never upgrades an
unrecognized or truncated report to complete. Sampled, partial, or unknown
evidence is never promoted to complete. Prefer the optimization view and the
compact iteration query for routine comparison because they expose the fields
needed for structural decisions without flooding the session. This is a
workflow preference, not a restriction: full `.rpt`, `.log`, or Markdown may
be read whenever compact evidence is insufficient or direct verification is
useful. Update the extractor test when direct inspection reveals a parser gap.
Use
the compact index fields `clock_groups_coverage`, `path_families_coverage`,
`path_family_names`, `critical_path_primitives`, `top_delay_nets`,
`clock_delay_samples`, `resources_coverage`, `resource_counts`, and
`resource_deltas` for iteration comparisons instead of opening reports. The
primary summary fields are `timing.critical_paths[].primitive_histogram`,
`timing.top_delay_nets.items`, `timing.clock_delays.items`,
`timing.clock_groups.items`, `timing.path_families.items`, and
`timing.resources.counts`.
Iteration queries return compact entries by default; pass `--full` for the
complete indexed sidecar projection.
`accepted` is a candidate decision, not `final`; a routed fallback is never a
formal selection result.

For isolated low-frequency implementation and UART performance probes, use
`../npc/scripts/run_digital_twin_board_perf.sh`. Its `calibrate` command compares
Quick at 75 MHz with timing-closed, board-valid Default profiles at 200 MHz or
150 MHz. It seeds the isolated project from completed IP/OOC runs, reuses valid
unchanged IP checkpoints, and always rebuilds the clock and COE-backed memory
IPs. Top synthesis and implementation still start as fresh runs.

Measured on 2026-08-04 with Vivado 2024.2, `--jobs 16`, four IP/OOC jobs,
reusable IP cache, and the current CoreMark COE:

| Profile | Vivado flow | Board workload | Flow + workload |
| --- | ---: | ---: | ---: |
| Quick 75 MHz | 171.729 s | 44.780 s | about 216.509 s |
| Default 200 MHz | 176.069 s | 16.792 s | 192.861 s |

Default 200 MHz is therefore the current fast-performance choice. Budget about
3 minutes to produce the bitstream, or about 5 to 5.5 minutes for the complete
command including remote programming and the conservative 90-second capture
window. These are host-load-dependent reference values, not timing guarantees.

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

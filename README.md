# "一生一芯"工程项目

这是"一生一芯"的工程项目

## Finals reviewer reproduction package

The portable reviewer package is maintained at `~/jyd/finals-coe-repro/`.
When an export of the reviewer reproduction version is requested, that existing
directory is refreshed in place from the current submission branch. The package
includes a prebuilt audited GCC toolchain, offline build inputs, reference COE files,
and a path-independent `build-coe.sh`; see `jyd-tests/BUILD-GCC-AND-COE.md` for the
quick and full reconstruction procedures.

## Vivado FPGA CI

The Vivado digital twin project lives in `jyd-vivado-proj/`, so each workbench
commit directly selects the matching CPU and Vivado project versions. The FPGA
workflow uses the project and timing/burn helper scripts from the same checkout.

### XSim display smoke test

Use the full-top XSim smoke test to check that the current generated CPU and the
Vivado IP behavior models can execute far enough to write a display value whose
top byte is `0x37`:

```sh
./npc/scripts/run-xsim-seg37.sh
```

The script rebuilds `npc/build/pack-fpga`, creates an isolated temporary Vivado
project, regenerates simulation products from the checked-in XCI files, and
compiles the full `JYDFPGATop`. It does not modify the in-tree Vivado project,
implementation runs, or bitstreams. By default it reads the COE files currently
imported by `digital_twin`; select another image directory with `COE_DIR`:

```sh
COE_DIR="$PWD/jyd-tests/2026/coe/xibei-withMext_clz" \
  ./npc/scripts/run-xsim-seg37.sh
```

The default host timeout is 60 seconds. Override it with
`TIMEOUT_SECONDS=<seconds>`. A successful run prints `SEG37_RESULT=PASS`; failure
output includes the temporary work directory containing the Vivado and XSim
logs. The script also checks the retained fallback divider XCI's configured
clocks-per-division, latency, and `TREADY` interface before simulation, even
when the generated CPU is using the iterative RTL divider instead of binding
that IP.

The smoke test is only meaningful for a COE image that writes a `0x37xxxxxx`
value to SEG. Long-running images such as the 10000-iteration CoreMark COE do
not use that completion convention and should be validated with a bitstream and
direct UART capture instead of waiting for full RTL simulation.

### FPGA UART output

The packaged FPGA top keeps the software UART register at `0x802000a0`, but
implements it with an AXI UART Lite IP clocked at 50 MHz through an AXI clock
converter. The physical port is 9600 baud, 8 data bits, no parity, and one stop
bit. The simulation build continues to use the inline `SimpleBusUART` model;
the Vivado-only adapter and IP products are selected by `pack-fpga` packaging.

The old top-level LED/SEG serial protocol controller is no longer instantiated.
LED and SEG remain connected to the board display outputs, while the physical
UART carries program output directly. For unattended capture, use the sibling
`submit-bits` client after generating a bitstream:

```sh
cd /home/hanpi/gitclone/submit-bits
.venv/bin/python -m jyd_client.cli capture \
  /home/hanpi/gitclone/jyd/jyd-vivado-proj/digital_twin.runs/impl_1/top.bit \
  --skip-login --first-byte-timeout 60 --duration 20
```

The serial connection is armed before programming. The first timeout covers
programs that produce no output during their initial computation; the capture
window starts only when the first decoded payload byte arrives.

A timeout before the first byte is reported as a failed sample. When exploring
a bitstream with negative setup slack, keep the bitstream hash fixed and retry
on more than one board: a later successful run does not erase an earlier
no-output sample, and an intermittently starting image is not a stable release.

### FPGA CoreMark timer

The packaged top uses the JYD-owned `JYDFPGATickCounter`, not the contest
`counter.sv` instance. Both use the 50 MHz board clock, start/stop commands at
`0x80200050`, and a Gray-coded crossing back to the CPU. The JYD counter differs
by returning the raw 32-bit 50 MHz tick count; it does not divide to one
millisecond in hardware. The contest source is retained in the project but is
not instantiated.

Standalone CoreMark reads the raw register directly and converts with
`COREMARK_TICKS_PER_SEC=50000000`. AM's general JYD timer backend converts the
same ticks to microseconds for other programs. A 32-bit raw counter wraps after
about 85.9 seconds, so the benchmark interval must remain below that limit.

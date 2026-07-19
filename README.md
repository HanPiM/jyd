# "一生一芯"工程项目

这是"一生一芯"的工程项目

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

# JYD SoC AX7035B Vivado project

This is the independent Vivado 2024.2 flow for `xc7a35tfgg484-2`.  It consumes
the ordinary `npc/build/pack-fpga` JYD SoC RTL and the AX7035B-only wrappers in
`board/ax7035b/`.  It never modifies the contest project or its XCI files.

Build from PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\jyd-vivado-proj-ax7035b\scripts\build.ps1 `
  -ImageName soc-smoke `
  -IromCoe .\jyd-tests\ax7035b-soc-smoke\build\ax7035b-soc-smoke-riscv32-jyd.text.coe `
  -DramCoe .\jyd-tests\ax7035b-soc-smoke\build\ax7035b-soc-smoke-riscv32-jyd.data.coe
```

Outputs are kept locally under `bitstreams/<image>.bit` and
`reports/<image>/`.  They are generated artifacts and are not committed.

The three validated image names are `soc-smoke`, `aht10-board`, and
`rtthread-nano-compat`.  The last name is intentional: the repository-pinned
RT-Thread Nano commit was unavailable, so it uses the compatible upstream
commit documented in `board/ax7035b/RESOURCE_AUDIT.md`.

# AX7035B JYD + OV5640 + HDMI Camera SoC

This branch integrates the stable JYD CPU/SoC and RT-Thread Nano with the
hardware-validated `ax7035b_ov5640_hdmi_vivado2024_J9_FINAL_STABLE` video
pipeline for Vivado 2024.2 and `xc7a35tfgg484-2`.

The real-time path remains entirely in FPGA video hardware:

`OV5640 -> crop/downsample -> RGB332 ping-pong framebuffer -> HDMI`

The CPU does not move frames. It reads a passive status/sample monitor over
SimpleBus and can force the existing HDMI color bars. The SCCB register table,
DVP phase, framebuffer, HDMI timing, TMDS implementation, and J9 pin
assignments are unchanged from FINAL_STABLE. The only extension inside the
copied video top is a two-flop synchronized `cpu_force_colorbar` control; its
reset value is zero, so normal video behavior is identical to FINAL_STABLE.

## MMIO map

| Address | Register | Access | Meaning |
|---|---|---|---|
| `0x80200070` | `CAMERA_STATUS` | RO | bit 0 cfg done, bit 1 frame valid, bit 2 video locked, bit 3 cfg error, bit 4 HPD, bit 5 sample valid |
| `0x80200074` | `CAMERA_FRAME_COUNT` | RO | CPU-domain count of synchronized camera frame events |
| `0x80200078` | `CAMERA_SAMPLE_RGB` | RO | low 8 bits are one live center RGB332 sample |
| `0x8020007c` | `CAMERA_CONTROL` | RW | bit 0 forces the existing HDMI color bars |

The monitor samples on falling PCLK, permanently skips the first HREF sample,
and decodes low-byte-first RGB565, exactly matching FINAL_STABLE. Its RGB data
uses a bundled-data/toggle CDC; frame events use a toggle synchronizer; scalar
status bits use independent two-flop synchronizers.

## Build

Vivado 2024.2 is expected at `E:\VIVADO\Vivado\2024.2`. From PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File .\jyd-vivado-proj-ax7035b-camera\build_camera_soc.ps1 `
  -Jobs 6
```

The script builds RT-Thread with `CAMERA_DEMO=1`, which forces
`COREMARK_ENABLE=0`, checks the ELF size, regenerates `JYDFPGATop`, converts
the AM COE files, and runs synthesis, optimization, placement, routing, DRC,
timing analysis, and bitstream generation.

The camera build uses a 75 MHz CPU, 16 KiB distributed IROM, and 48 KiB distributed DRAM
with the established one-cycle IROM and two-cycle DRAM read latencies. This
preserves the nearly full block-RAM budget of the frozen double framebuffer.

Generated deliverables are under `jyd-vivado-proj-ax7035b-camera/output/`:

- `ax7035b_jyd_ov5640_soc.bit`
- `utilization_impl.rpt`
- `timing_summary.rpt`
- `drc.rpt`

The validated post-route build uses 13,882 LUTs, 4,956 FFs, 48 RAMB36,
2 RAMB18, and 11 DSP48E1 blocks. That is 66.74% LUT, 11.91% FF, 98% block-RAM
tile, and 12.22% DSP utilization. At 75 MHz CPU clock, setup WNS is +0.980 ns,
hold WHS is +0.071 ns, TNS/THS are zero, routing errors are zero, and DRC has
zero errors. The bitstream SHA-256 is
`9f0aab6c172617b7346cb78f8e7295b10e203bff6e66357b06d508584435d82d`.

## Software checks

The deterministic MMIO simulation test is:

```bash
make -C jyd-tests/ov5640-mmio run ARCH=riscv32-jyd
```

RT-Thread camera image only:

```bash
make -C jyd-tests/rtthread-nano clean ARCH=riscv32-jyd CAMERA_DEMO=1
make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd CAMERA_DEMO=1 image
```

## Board verification

1. Wire the ACM5640-V5 to J9 exactly as in FINAL_STABLE; strap camera PWDN to
   J9-38 GND. Do not change any camera or HDMI wiring.
2. Program `output/ax7035b_jyd_ov5640_soc.bit` into the AX7035B.
3. With no UART input, verify live 640x480 HDMI, normal color and motion,
   LED1/2/3 on, LED4 off, and KEY1 hold-to-color-bars behavior.
4. Open the CP2102 serial port at 115200, 8N1, no flow control and wait for
   `msh />`.
5. Run `camera` repeatedly. `frame_count` must increase and the RGB332 sample
   should be able to change as the scene changes.
6. Run `camera_bars on`; HDMI must immediately show color bars.
7. Run `camera_bars off`; live camera video must return without resetting or
   reconfiguring the OV5640.

The AHT10 and six-digit SEG pins remain exposed using the existing verified
AX7035B assignments and do not overlap the frozen camera/HDMI pins.

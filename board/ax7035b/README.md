# ALINX AX7035B JYD SoC validation port

This directory records the board-only evidence and wrappers used to validate
the complete JYD SoC on an ALINX AX7035B.  It does not replace or retarget the
contest-board project in `jyd-vivado-proj/`.

## Board definition

- Board: ALINX AX7035B
- FPGA: XC7A35T-2FGG484I; Vivado target part `xc7a35tfgg484-2`
- Input clock: 50 MHz single-ended oscillator on Y18, LVCMOS33
- Validation CPU clock: 100 MHz from a board-local MMCM
- AHT10/controller/SEG clock: 50 MHz
- UART: CP2102, RX G15, TX G16, 115200 baud, 8N1, no flow control
- Seven-segment display: six-digit common-anode, active-low segment and digit selects
- AHT10: J10 pin 3/P17 SCL and J10 pin 4/N17 SDA, LVCMOS33, module pull-ups
- AHT10 power: J10 pin 39/3.3 V and J10 pin 37/GND (not FPGA signals)

The AHT10 lines are open-drain only: the FPGA may drive zero or release to
high impedance.  It must never drive a logic one.

## Memory configuration

The board port keeps the architectural bases unchanged:

- IROM base `0x80000000`, physical size 32 KiB
- DRAM base `0x80100000`, physical size 64 KiB

The contest project uses a 32 KiB IROM and a 256 KiB DRAM.  The 256 KiB DRAM
alone consumes 64 RAMB36 blocks and cannot fit in the A35T's 50 RAMB36 blocks.
The 64 KiB board DRAM consumes 16 RAMB36 blocks.  See
`RESOURCE_AUDIT.md` for the synthesis and ELF measurements behind the choice.

## Difference from the contest board

The contest board uses an XC7K325T, a differential approximately 200 MHz
input, a 280 MHz performance clock, a different UART/SEG shell, and a separate
pinout.  AX7035B is a 100 MHz functional-validation target only.  CPU ISA,
pipeline, MMIO addresses and software interfaces remain unchanged.

## Local source evidence

Only the small constraint excerpts required for this port are checked in under
`reference/`.  The original local sources remain at:

- `E:\AX7035B_2019\AX7035b\01_demo_document\demo\04_uart_test.rar`
- `E:\AX7035B_2019\AX7035b\01_demo_document\demo\24_smg_interface_demo.rar`
- `E:\AX7035B_2019\AX7035b\01_demo_document\demo\25_temp_lm75_test.rar`
- `E:\AX7035B_2019\AX7035b\AX7035开发板用户手册REV1.1.pdf`
- `E:\FPGA\AX7035B_AHT10_DEMO`

The large ALINX archive and PDFs are intentionally not copied into Git.

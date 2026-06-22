include $(JYD_AM_HOME)/scripts/isa/riscv.mk
include $(JYD_AM_HOME)/scripts/platform/nemu.mk
CFLAGS  += -DISA_H=\"riscv/riscv.h\"

RISCV_ZEXTS = _zba_zbb_zbc_zbs_zbkb_zbkx
COMMON_CFLAGS += -march=rv32im$(RISCV_MARCH_EXT_CSRS_AND_FENCE_I)$(RISCV_ZEXTS) -mabi=ilp32   # overwrite
LDFLAGS       += -melf32lriscv                     # overwrite

AM_SRCS += riscv/nemu/start.S \
           riscv/nemu/cte.c \
           riscv/nemu/trap.S \
           riscv/nemu/vme.c

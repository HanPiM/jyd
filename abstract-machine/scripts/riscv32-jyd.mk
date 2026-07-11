include $(JYD_AM_HOME)/scripts/isa/riscv.mk
include $(JYD_AM_HOME)/scripts/platform/jyd.mk

RISCV_ZEXTS = #_zba_zbb_zbc_zbs_zbkb_zbkx
COMMON_CFLAGS += -march=rv32im_zicsr_$(RISCV_ZEXTS) -mabi=ilp32  # overwrite
LDFLAGS       += -melf32lriscv                    # overwrite

AM_SRCS += riscv/npc/libgcc/div.S \
           riscv/npc/libgcc/muldi3.S \
           riscv/npc/libgcc/multi3.c \
           riscv/npc/libgcc/ashldi3.c \
           riscv/npc/libgcc/unused.c

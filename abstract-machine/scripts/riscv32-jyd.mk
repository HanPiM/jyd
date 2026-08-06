include $(JYD_AM_HOME)/scripts/isa/riscv.mk
include $(JYD_AM_HOME)/scripts/platform/jyd.mk

# B-extension subset used by default for riscv32-jyd images (the JYD CPU
# implements zba/zbb/zbc/zbs). Set RISCV_ZEXTS= on the make command line to
# build a plain rv32im_zicsr image (e.g. for difftest or for ports that need
# the base ISA only).
RISCV_ZEXTS ?= _zba_zbb_zbc_zbs
COMMON_CFLAGS += -march=rv32im_zicsr$(RISCV_ZEXTS) -mabi=ilp32  # overwrite
LDFLAGS       += -melf32lriscv                    # overwrite

AM_SRCS += riscv/npc/libgcc/div.S \
           riscv/npc/libgcc/muldi3.S \
           riscv/npc/libgcc/multi3.c \
           riscv/npc/libgcc/ashldi3.c \
           riscv/npc/libgcc/unused.c

NAME = coremark-official

ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
JYD_HOME := $(abspath $(ROOT_DIR)/../..)

ITERATIONS ?= 1000
TOTAL_DATA_SIZE ?= 2000
# Keep CoreMark's default ISA aligned with the current JYD CPU/FPGA baseline.
# Callers evaluating an unextended control image can still pass RISCV_ZEXTS=.
RISCV_ZEXTS ?= _zba_zbb_zbc_zbs_zbkb_zbkx
ENABLE_LTO ?= 0
PSEUDO_FLOAT ?= 1
COREMARK_OPT ?= -Os
COREMARK_CRC_ACCEL ?= none

COREMARK_CRC_ACCEL_MODES := none u8 native
ifeq ($(filter $(COREMARK_CRC_ACCEL),$(COREMARK_CRC_ACCEL_MODES)),)
$(error COREMARK_CRC_ACCEL must be one of: $(COREMARK_CRC_ACCEL_MODES))
endif

empty :=
space := $(empty) $(empty)
comma := ,
sanitize = $(subst $(space),_,$(subst /,_,$(subst :,_,$(subst =,_,$(subst ',_,$(1))))))
COREMARK_CONFIG_ID = iter$(ITERATIONS)-data$(TOTAL_DATA_SIZE)-z$(call sanitize,$(if $(strip $(RISCV_ZEXTS)),$(RISCV_ZEXTS),none))-c$(call sanitize,$(if $(strip $(EXTRA_CFLAGS)),$(EXTRA_CFLAGS),default))-lto$(ENABLE_LTO)-pf$(PSEUDO_FLOAT)-crc$(COREMARK_CRC_ACCEL)
BUILD_DIR ?= build/$(COREMARK_CONFIG_ID)
LIB_BUILD_DIR_softfloat = build/$(ARCH)-z$(call sanitize,$(if $(strip $(RISCV_ZEXTS)),$(RISCV_ZEXTS),none))

SRCS = $(shell find src/ -name "*.c")
ifeq ($(PSEUDO_FLOAT),1)
SRCS := $(filter-out src/cvt.c src/coremark_softfloat.c,$(SRCS))
else
LIBS += softfloat
endif
COREMARK_BENCH_SRCS := src/core_list_join.c src/core_main.c src/core_matrix.c \
                       src/core_state.c src/core_util.c
COREMARK_CRC_CALLER_SRCS := $(filter-out src/core_util.c,$(COREMARK_BENCH_SRCS))

INC_PATH += $(CURDIR)/src

include $(JYD_HOME)/abstract-machine/Makefile
LIB_MAKE_ARGS_softfloat = \
	SOFTFLOAT_MARCH=$(patsubst -march=%,%,$(filter -march=%,$(COMMON_CFLAGS))) \
	SOFTFLOAT_MABI=$(patsubst -mabi=%,%,$(filter -mabi=%,$(COMMON_CFLAGS)))

# Optimize the image for IROM size by default.  The benchmark translation
# units (core_list_join/core_main/core_matrix/core_state/core_util) override
# this with COREMARK_OPT (default -O3); setup, reporting and formatting stay
# optimized for size.  Set COREMARK_OPT=-Os to reproduce the historical
# all--Os image used by the 11.374198420 s board baseline.
CFLAGS += -Os

COREMARK_BENCH_OBJS = $(addprefix $(DST_DIR)/, $(addsuffix .o, $(basename $(COREMARK_BENCH_SRCS))))
$(COREMARK_BENCH_OBJS): CFLAGS += $(COREMARK_OPT)

ifneq ($(COREMARK_CRC_ACCEL),none)
COREMARK_CRC_CALLER_OBJS = $(addprefix $(DST_DIR)/, $(addsuffix .o, $(basename $(COREMARK_CRC_CALLER_SRCS))))
$(COREMARK_CRC_CALLER_OBJS): CFLAGS += -include $(CURDIR)/src/coremark_crc_hw.h
ifeq ($(COREMARK_CRC_ACCEL),u8)
$(COREMARK_CRC_CALLER_OBJS): CFLAGS += -DCOREMARK_CRC_ACCEL_U8=1
else
$(COREMARK_CRC_CALLER_OBJS): CFLAGS += -DCOREMARK_CRC_ACCEL_NATIVE=1
endif
endif

ifeq ($(ENABLE_LTO),1)
CFLAGS += -flto
# AM normally invokes ld directly.  LTO objects need to be linked through the
# compiler driver so GCC can load the matching LTO plugin.  Replacing the
# recipe locally keeps this CoreMark experiment from changing every AM image.
COREMARK_GCC_LDFLAGS = $(foreach flag,$(LDFLAGS),-Wl$(comma)$(flag))
$(IMAGE).elf: $(LINKAGE) $(LDSCRIPTS)
	@mkdir -p $(dir $@)
	@echo \# Creating LTO image [$(ARCH)]
	@echo + LD "->" $(IMAGE_REL).elf
	@$(CC) $(COMMON_CFLAGS) -nostdlib -flto -o $@ \
		$(COREMARK_GCC_LDFLAGS) -Wl,--start-group $(LINKAGE) $(LDLIBS) -Wl,--end-group
endif

ifndef COREMARK_COMPILER_FLAGS
COREMARK_COMPILER_FLAGS := $(strip $(filter -O%,$(CFLAGS)) $(filter -march=% -mabi=%,$(COMMON_CFLAGS)) $(EXTRA_CFLAGS) COREMARK_CRC_ACCEL=$(COREMARK_CRC_ACCEL))
endif
escape_spaces = $(subst $(space),\ ,$(1))

CFLAGS += -DITERATIONS=$(ITERATIONS) -DTOTAL_DATA_SIZE=$(TOTAL_DATA_SIZE)
CFLAGS += -DCOREMARK_PSEUDO_FLOAT=$(PSEUDO_FLOAT)
CFLAGS += -DCOMPILER_FLAGS=\"$(call escape_spaces,$(COREMARK_COMPILER_FLAGS))\"
CFLAGS += $(EXTRA_CFLAGS)

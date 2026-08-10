NAME = coremark-official

ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
JYD_HOME := $(abspath $(ROOT_DIR)/../..)

# Formal FPGA evaluation uses 10000 iterations.  Keep the default formal so a
# caller that omits ITERATIONS cannot silently produce a short board image.
ITERATIONS ?= 10000
TOTAL_DATA_SIZE ?= 2000
# Keep CoreMark's default ISA aligned with the current JYD CPU/FPGA baseline.
# Callers evaluating an unextended control image can still pass RISCV_ZEXTS=.
RISCV_ZEXTS ?= _zba_zbb_zbc_zbs_zbkb_zbkx
ENABLE_LTO ?= 0
PSEUDO_FLOAT ?= 1
COREMARK_OPT ?= -Os
COREMARK_CRC_ACCEL ?= none
COREMARK_ACCELS ?=

COREMARK_CRC_ACCEL_MODES := none u8 native
ifeq ($(filter $(COREMARK_CRC_ACCEL),$(COREMARK_CRC_ACCEL_MODES)),)
$(error COREMARK_CRC_ACCEL must be one of: $(COREMARK_CRC_ACCEL_MODES))
endif

empty :=
space := $(empty) $(empty)
comma := ,
sanitize = $(subst $(space),_,$(subst /,_,$(subst :,_,$(subst =,_,$(subst ',_,$(1))))))
COREMARK_CONFIG_ID = iter$(ITERATIONS)-data$(TOTAL_DATA_SIZE)-z$(call sanitize,$(if $(strip $(RISCV_ZEXTS)),$(RISCV_ZEXTS),none))-c$(call sanitize,$(if $(strip $(EXTRA_CFLAGS)),$(EXTRA_CFLAGS),default))-lto$(ENABLE_LTO)-pf$(PSEUDO_FLOAT)-crc$(COREMARK_CRC_ACCEL)-xa$(call sanitize,$(if $(strip $(COREMARK_ACCELS)),$(COREMARK_ACCELS),none))
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

ifneq ($(strip $(COREMARK_ACCELS)),)
HOST_CXX ?= g++
GCC_PLUGIN_DIR := $(shell $(CC) -print-file-name=plugin)
GCC_PLUGIN_GMP_INCLUDE ?= /home/hanpi/jyd-vivado-projs/jyd-vivado-proj-regional/digital_twin.gen/sources_1/ip/fp_add_sub_test/cmodel/floating_point_v7_1_bitacc_cmodel_lin64
COREMARK_ACCEL_PLUGIN := $(CURDIR)/build/tools/coremark_accel_plugin.so

$(COREMARK_ACCEL_PLUGIN): accel/coremark_accel_plugin.cc
	@mkdir -p $(dir $@)
	$(HOST_CXX) -std=gnu++17 -fPIC -shared -fno-rtti -fno-exceptions \
		-I$(GCC_PLUGIN_DIR)/include \
		-I$(GCC_PLUGIN_GMP_INCLUDE) \
		-o $@ $<

$(COREMARK_BENCH_OBJS): $(COREMARK_ACCEL_PLUGIN)
$(COREMARK_BENCH_OBJS): CFLAGS += -fplugin=$(COREMARK_ACCEL_PLUGIN) \
	-fplugin-arg-coremark_accel_plugin-accels=$(COREMARK_ACCELS) \
	-include $(CURDIR)/accel/coremark_accel.c
ifneq ($(filter xstatec,$(subst $(comma), ,$(COREMARK_ACCELS))),)
$(DST_DIR)/src/core_list_join.o: CFLAGS += -DCOREMARK_XSTATEC_CALLSITE=1
endif
endif

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
COREMARK_COMPILER_FLAGS := $(strip $(filter -O%,$(CFLAGS)) $(filter -march=% -mabi=%,$(COMMON_CFLAGS)) $(EXTRA_CFLAGS) COREMARK_CRC_ACCEL=$(COREMARK_CRC_ACCEL) COREMARK_ACCELS=$(if $(COREMARK_ACCELS),$(COREMARK_ACCELS),none))
endif
escape_spaces = $(subst $(space),\ ,$(1))

CFLAGS += -DITERATIONS=$(ITERATIONS) -DTOTAL_DATA_SIZE=$(TOTAL_DATA_SIZE)
CFLAGS += -DCOREMARK_PSEUDO_FLOAT=$(PSEUDO_FLOAT)
CFLAGS += -DCOMPILER_FLAGS=\"$(call escape_spaces,$(COREMARK_COMPILER_FLAGS))\"
CFLAGS += $(EXTRA_CFLAGS)

.PHONY: audit-accel
audit-accel: $(IMAGE).elf
	python3 accel/audit_accel_elf.py --elf $< --accels '$(COREMARK_ACCELS)'

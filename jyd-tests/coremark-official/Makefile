NAME = coremark-official

ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
JYD_HOME := $(abspath $(ROOT_DIR)/../..)

ITERATIONS ?= 10000
TOTAL_DATA_SIZE ?= 2000
# Keep CoreMark's default ISA aligned with the current JYD CPU/FPGA baseline.
# Callers evaluating an unextended control image can still pass RISCV_ZEXTS=.
RISCV_ZEXTS ?= _zba_zbb_zbc_zbs_zbkb_zbkx
ENABLE_LTO ?= 0
PSEUDO_FLOAT ?= 1
COREMARK_OPT ?= -Os
COREMARK_XEXTS ?=

empty :=
space := $(empty) $(empty)
comma := ,
COREMARK_SUPPORTED_XEXTS := xbmul xcrcu8 xdot16 xlrev xmac16 xmsum xstate xstatec xstate2 xstate4
COREMARK_XEXT_WORDS := $(filter x%,$(subst _, ,$(strip $(COREMARK_XEXTS))))
COREMARK_UNKNOWN_XEXTS := $(filter-out $(COREMARK_SUPPORTED_XEXTS),$(COREMARK_XEXT_WORDS))
ifneq ($(strip $(COREMARK_UNKNOWN_XEXTS)),)
$(error Unsupported COREMARK_XEXTS: $(COREMARK_UNKNOWN_XEXTS))
endif
COREMARK_ACCEL_WORDS := $(filter-out xcrcu8,$(COREMARK_XEXT_WORDS))
COREMARK_ACCELS := $(subst $(space),$(comma),$(strip $(COREMARK_ACCEL_WORDS)))
COREMARK_PLUGIN_ACCELS := $(if $(COREMARK_ACCELS),$(COREMARK_ACCELS),none)
sanitize = $(subst $(space),_,$(subst /,_,$(subst :,_,$(subst =,_,$(subst ',_,$(1))))))
COREMARK_CONFIG_ID = iter$(ITERATIONS)-data$(TOTAL_DATA_SIZE)-z$(call sanitize,$(if $(strip $(RISCV_ZEXTS)),$(RISCV_ZEXTS),none))-x$(call sanitize,$(if $(strip $(COREMARK_XEXTS)),$(COREMARK_XEXTS),none))-c$(call sanitize,$(if $(strip $(EXTRA_CFLAGS)),$(EXTRA_CFLAGS),default))-lto$(ENABLE_LTO)-pf$(PSEUDO_FLOAT)
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
COREMARK_O3_SRCS := src/core_list_join.c src/core_matrix.c src/core_state.c src/core_util.c
ifeq ($(PSEUDO_FLOAT),1)
SRCS += accel/float_dump.c
endif

INC_PATH += $(CURDIR)/src

ifeq ($(MAKECMDGOALS),check)
ARCH ?= riscv32-nemu
endif
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
CFLAGS += -DITERATIONS=$(ITERATIONS) -DTOTAL_DATA_SIZE=$(TOTAL_DATA_SIZE)
CFLAGS += -DCOREMARK_PSEUDO_FLOAT=$(PSEUDO_FLOAT)
CFLAGS += -D__COREMARK_XEXTS=\"$(COREMARK_XEXTS)\"
CFLAGS += $(EXTRA_CFLAGS)
COREMARK_COMMON_FLAGS := $(strip $(CFLAGS))
COREMARK_REPORT_COMMON_FLAGS := $(filter -O% -march=% -mabi=% -flto \
	-fno-builtin -ffreestanding -fdata-sections -ffunction-sections, \
	$(COREMARK_COMMON_FLAGS))

COREMARK_BENCH_OBJS = $(addprefix $(DST_DIR)/, $(addsuffix .o, $(basename $(COREMARK_BENCH_SRCS))))
COREMARK_CRC_CALLER_OBJS = $(addprefix $(DST_DIR)/, $(addsuffix .o, $(basename $(COREMARK_CRC_CALLER_SRCS))))
COREMARK_O3_OBJS = $(addprefix $(DST_DIR)/, $(addsuffix .o, $(basename $(COREMARK_O3_SRCS))))
COREMARK_MAIN_OBJ = $(DST_DIR)/src/core_main.o
COREMARK_FP12_OBJ = $(DST_DIR)/accel/float_dump.o

HOST_CXX ?= g++
GCC_PLUGIN_DIR := $(shell $(CC) -print-file-name=plugin)
GCC_PLUGIN_GMP_INCLUDE ?= /home/hanpi/jyd-vivado-projs/jyd-vivado-proj-regional/digital_twin.gen/sources_1/ip/fp_add_sub_test/cmodel/floating_point_v7_1_bitacc_cmodel_lin64
COREMARK_ACCEL_PLUGIN := $(CURDIR)/build/tools/xaccel_plugin.so

$(COREMARK_ACCEL_PLUGIN): accel/xaccel_plugin.cc
	@mkdir -p $(dir $@)
	$(HOST_CXX) -std=gnu++17 -fPIC -shared -fno-rtti -fno-exceptions \
		-I$(GCC_PLUGIN_DIR)/include \
		-I$(GCC_PLUGIN_GMP_INCLUDE) \
		-o $@ $<

COREMARK_FLAGS_HEADER := $(BUILD_DIR)/generated/compiler_flags.h
COREMARK_PLUGIN_FLAGS = -fplugin=$(COREMARK_ACCEL_PLUGIN) \
	-fplugin-arg-xaccel_plugin-accels=$(COREMARK_PLUGIN_ACCELS) \
	-fplugin-arg-xaccel_plugin-report=$(if $(filter 1,$(PSEUDO_FLOAT)),fp12,none) \
	-include $(CURDIR)/accel/xaccel_wrappers.c
COREMARK_BENCH_FLAGS = $(COREMARK_OPT) $(COREMARK_PLUGIN_FLAGS) \
	-include $(abspath $(COREMARK_FLAGS_HEADER))
COREMARK_O3_FLAGS = -include $(CURDIR)/accel/force_o3.h
COREMARK_CRC_FLAGS = -include $(CURDIR)/accel/xcrc_hw.h \
	-D__COREMARK_XCRCU8=1
COREMARK_FP12_FLAGS = -include $(CURDIR)/accel/float_dump.h

$(COREMARK_BENCH_OBJS): $(COREMARK_ACCEL_PLUGIN) accel/xaccel_wrappers.c
$(COREMARK_BENCH_OBJS): $(COREMARK_FLAGS_HEADER)
$(COREMARK_BENCH_OBJS): CFLAGS += $(COREMARK_BENCH_FLAGS)
$(COREMARK_O3_OBJS): CFLAGS += $(COREMARK_O3_FLAGS)
ifeq ($(PSEUDO_FLOAT),1)
$(COREMARK_MAIN_OBJ): CFLAGS += $(COREMARK_FP12_FLAGS)
$(COREMARK_FP12_OBJ): $(COREMARK_FLAGS_HEADER)
$(COREMARK_FP12_OBJ): CFLAGS += -include $(abspath $(COREMARK_FLAGS_HEADER))
endif

ifneq ($(filter xcrcu8,$(COREMARK_XEXT_WORDS)),)
$(COREMARK_CRC_CALLER_OBJS): CFLAGS += $(COREMARK_CRC_FLAGS)
endif

.PHONY: FORCE
FORCE:

$(COREMARK_FLAGS_HEADER): FORCE accel/gen_flags_header.py
	@python3 accel/gen_flags_header.py --output $@ -- \
		'$(COREMARK_OPT)' $(foreach flag,$(COREMARK_REPORT_COMMON_FLAGS),'$(flag)') \
		'-fplugin=$(notdir $(COREMARK_ACCEL_PLUGIN))' \
		'-fplugin-arg-xaccel_plugin-accels=$(COREMARK_PLUGIN_ACCELS)' \
		'-fplugin-arg-xaccel_plugin-report=$(if $(filter 1,$(PSEUDO_FLOAT)),fp12,none)' \
		'-include accel/xaccel_wrappers.c' \
		'-include accel/force_o3.h' \
		$(if $(filter xcrcu8,$(COREMARK_XEXT_WORDS)),'-include accel/xcrc_hw.h') \
		$(if $(filter 1,$(PSEUDO_FLOAT)),'-include accel/float_dump.h')

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

.PHONY: audit-accel check
audit-accel: $(IMAGE).elf
	python3 accel/audit_accel_elf.py --elf $< \
		--accels '$(subst $(space),$(comma),$(strip $(COREMARK_XEXT_WORDS)))' \
		$(if $(filter 1,$(PSEUDO_FLOAT)),--fp12-report)

check:
	cd src && md5sum -c ../coremark.md5

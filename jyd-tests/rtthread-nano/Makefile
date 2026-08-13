ARCH ?= riscv32-jyd
ifneq ($(filter gdb-server,$(MAKECMDGOALS)),)
NANO_OPT ?= -O0
else
NANO_OPT ?= -Os
endif

COREMARK_PORT_DIR ?= $(abspath $(ROOT_DIR)/../coremark-official/src)
COREMARK_BENCH_DIR ?= $(COREMARK_PORT_DIR)
COREMARK_ACCEL_DIR ?= $(abspath $(COREMARK_PORT_DIR)/../accel)
COREMARK_ITERATIONS ?= 10000
COREMARK_DATA_SIZE ?= 2000
COREMARK_OPT ?= -Os
COREMARK_SUPPORT_OPT ?= -Os
COREMARK_ENABLE ?= 1
COREMARK_PSEUDO_FLOAT ?= 1
# B-extension subset used ONLY for the embedded CoreMark benchmark sources
# (same set as the official CoreMark image). The RT-Thread kernel and the AM
# port stay on the base ISA.
COREMARK_ZEXTS ?= _zba_zbb_zbc_zbs_zbkb_zbkx
# Source-transparent custom-instruction set, same as the official CoreMark
# image: the GCC plugin redirects selected calls to the JYD accelerator
# instructions (xbmul/xcrcu8/xlistrev/xmsum/xdfa4h).
COREMARK_XEXTS ?= _xbmul_xcrcu8_xlistrev_xmsum_xdfa4h

empty :=
space := $(empty) $(empty)
comma := ,
escape_spaces = $(subst $(space),\ ,$(1))
COREMARK_SUPPORTED_XEXTS := xbmul xcrcu8 xdot16 xlistrev xmac16 xmsum xdfacnt xdfa2 xdfa4 xdfa4h
COREMARK_XEXT_WORDS := $(filter x%,$(subst _, ,$(strip $(COREMARK_XEXTS))))
COREMARK_UNKNOWN_XEXTS := $(filter-out $(COREMARK_SUPPORTED_XEXTS),$(COREMARK_XEXT_WORDS))
ifneq ($(strip $(COREMARK_UNKNOWN_XEXTS)),)
$(error Unsupported COREMARK_XEXTS: $(COREMARK_UNKNOWN_XEXTS))
endif
COREMARK_ACCEL_WORDS := $(filter-out xcrcu8,$(COREMARK_XEXT_WORDS))
COREMARK_ACCELS := $(subst $(space),$(comma),$(strip $(COREMARK_ACCEL_WORDS)))
COREMARK_PLUGIN_ACCELS := $(if $(COREMARK_ACCELS),$(COREMARK_ACCELS),none)

ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
JYD_HOME := $(abspath $(ROOT_DIR)/../..)
NANO_REPO := https://github.com/RT-Thread/rtthread-nano.git
NANO_COMMIT := $(shell tr -d '[:space:]' < $(ROOT_DIR)/SOURCE_COMMIT)
NANO_CHECKOUT := $(ROOT_DIR)/upstream
NANO_DIR := $(NANO_CHECKOUT)/rt-thread

ifeq ($(COREMARK_ENABLE),1)
COREMARK_BENCH_SRCS := $(addprefix $(COREMARK_BENCH_DIR)/, \
        core_list_join.c core_main.c core_matrix.c core_state.c core_util.c)
COREMARK_SUPPORT_SRCS := $(addprefix $(COREMARK_PORT_DIR)/, \
        core_portme.c ee_printf.c)
COREMARK_CRC_CALLER_SRCS := $(filter-out $(COREMARK_BENCH_DIR)/core_util.c,$(COREMARK_BENCH_SRCS))
COREMARK_O3_SRCS := $(addprefix $(COREMARK_BENCH_DIR)/, \
        core_list_join.c core_matrix.c core_state.c core_util.c)
COREMARK_CFLAGS := -DITERATIONS=$(COREMARK_ITERATIONS) \
                   -DTOTAL_DATA_SIZE=$(COREMARK_DATA_SIZE) \
                   -DCOREMARK_PSEUDO_FLOAT=$(COREMARK_PSEUDO_FLOAT) \
                   -D__COREMARK_XEXTS=\"$(COREMARK_XEXTS)\"
endif

# src/tinyprintf.c is picked up by the wildcard below and replaces the
# upstream $(NANO_DIR)/src/tinyprintf.c with the local formatter.
NAME := rtthread-nano
SRCS := $(wildcard $(ROOT_DIR)/src/*.c) \
        $(NANO_DIR)/src/clock.c \
        $(NANO_DIR)/src/cpu.c \
        $(NANO_DIR)/src/idle.c \
        $(NANO_DIR)/src/ipc.c \
        $(NANO_DIR)/src/irq.c \
        $(NANO_DIR)/src/kservice.c \
        $(NANO_DIR)/src/mempool.c \
        $(NANO_DIR)/src/object.c \
        $(NANO_DIR)/src/scheduler.c \
        $(NANO_DIR)/src/thread.c \
        $(NANO_DIR)/src/timer.c \
        $(NANO_DIR)/components/finsh/msh.c \
        $(NANO_DIR)/components/finsh/shell.c \
        $(COREMARK_BENCH_SRCS) \
        $(COREMARK_SUPPORT_SRCS)
ifeq ($(COREMARK_ENABLE),1)
SRCS += $(COREMARK_ACCEL_DIR)/float_dump.c
endif

INC_PATH += $(ROOT_DIR)/include $(NANO_DIR)/include $(NANO_DIR)/components/finsh \
            $(COREMARK_BENCH_DIR) $(COREMARK_PORT_DIR) $(COREMARK_ACCEL_DIR)
CFLAGS += -DHAVE_CCONFIG_H -D__RTTHREAD__ -DCOREMARK_ENABLE=$(COREMARK_ENABLE) \
          $(COREMARK_CFLAGS) -Wno-nonnull-compare -Wno-maybe-uninitialized
LDFLAGS += -T $(ROOT_DIR)/extra.ld
LIB_BUILD_DIR_softfloat := build/$(ARCH)-coremark-$(subst -O,,$(COREMARK_SUPPORT_OPT))
LIB_MAKE_ARGS_softfloat = \
	SOFTFLOAT_MARCH=$(patsubst -march=%,%,$(filter -march=%,$(COMMON_CFLAGS))) \
	SOFTFLOAT_MABI=$(patsubst -mabi=%,%,$(filter -mabi=%,$(COMMON_CFLAGS))) \
	SOFTFLOAT_OPT=$(COREMARK_SUPPORT_OPT)

include $(JYD_HOME)/abstract-machine/Makefile

# Keep the RTT kernel / AM port on the base ISA: strip whatever -march the AM
# platform defaults to (e.g. RISCV_ZEXTS) and force plain rv32im_zicsr.
COMMON_CFLAGS := $(filter-out -march=%,$(COMMON_CFLAGS)) -march=rv32im_zicsr

$(OBJS): CFLAGS += $(NANO_OPT)
COREMARK_BENCH_OBJS := $(addprefix $(DST_DIR)/,$(addsuffix .o,$(basename $(COREMARK_BENCH_SRCS))))
COREMARK_SUPPORT_OBJS := $(addprefix $(DST_DIR)/,$(addsuffix .o,$(basename $(COREMARK_SUPPORT_SRCS))))
COREMARK_CRC_CALLER_OBJS := $(addprefix $(DST_DIR)/,$(addsuffix .o,$(basename $(COREMARK_CRC_CALLER_SRCS))))
COREMARK_O3_OBJS := $(addprefix $(DST_DIR)/,$(addsuffix .o,$(basename $(COREMARK_O3_SRCS))))
# Only the CoreMark benchmark translation units get the B extensions; the
# last -march on the command line wins, so this overrides the base ISA above.
$(COREMARK_BENCH_OBJS): CFLAGS += -march=rv32im_zicsr$(COREMARK_ZEXTS)
$(COREMARK_BENCH_OBJS): CFLAGS += $(COREMARK_OPT)
$(COREMARK_SUPPORT_OBJS): CFLAGS += $(COREMARK_SUPPORT_OPT)
$(COREMARK_SUPPORT_OBJS): CFLAGS += -march=rv32im_zicsr$(COREMARK_ZEXTS)

# The embedded CoreMark is built exactly like the official accelerated image:
# raw 50 MHz CNT_REG timing, COREMARK_TICKS_PER_SEC=50000000, no
# COREMARK_EMBEDDED_RTT. The only integration change is renaming CoreMark's
# main() to coremark_main() so it does not collide with RT-Thread's entry
# main(); the code is unchanged.
COREMARK_ACCEL_PLUGIN := $(DST_DIR)/tools/xaccel_plugin.so
HOST_CXX ?= g++
# Some distro cross GCC packages (e.g. Ubuntu gcc-13-riscv64-linux-gnu) do not
# ship the plugin headers.  Fall back to the host compiler's plugin dir, which
# is ABI-compatible as long as both compilers share the same base version.
GCC_PLUGIN_DIR := $(shell $(CC) -print-file-name=plugin)
ifeq ($(wildcard $(GCC_PLUGIN_DIR)/include/gcc-plugin.h),)
GCC_PLUGIN_DIR := $(shell $(HOST_CXX) -print-file-name=plugin)
endif
# Only needed when the plugin headers' <gmp.h> does not resolve through the
# default include search path; set it on the make command line in that case.
GCC_PLUGIN_GMP_INCLUDE ?=
$(COREMARK_ACCEL_PLUGIN): $(COREMARK_ACCEL_DIR)/xaccel_plugin.cc
	@mkdir -p $(dir $@)
	$(HOST_CXX) -std=gnu++17 -fPIC -shared -fno-rtti -fno-exceptions \
		-I$(GCC_PLUGIN_DIR)/include \
		$(if $(GCC_PLUGIN_GMP_INCLUDE),-I$(GCC_PLUGIN_GMP_INCLUDE),) \
		-o $@ $<

COREMARK_COMMON_FLAGS := $(strip $(CFLAGS))
COREMARK_REPORT_COMMON_FLAGS := $(filter -O% -mabi=% -flto \
	-fno-builtin -ffreestanding -fdata-sections -ffunction-sections, \
	$(COREMARK_COMMON_FLAGS)) -march=rv32im_zicsr$(COREMARK_ZEXTS)
COREMARK_FLAGS_HEADER := $(DST_DIR)/coremark-compiler-flags.h
COREMARK_PLUGIN_FLAGS = -fplugin=$(COREMARK_ACCEL_PLUGIN) \
	-fplugin-arg-xaccel_plugin-accels=$(COREMARK_PLUGIN_ACCELS) \
	-fplugin-arg-xaccel_plugin-report=$(if $(filter 1,$(COREMARK_PSEUDO_FLOAT)),fp12,none) \
	-include $(COREMARK_ACCEL_DIR)/xaccel_wrappers.c
COREMARK_BENCH_FLAGS = $(COREMARK_OPT) $(COREMARK_PLUGIN_FLAGS) \
	-include $(abspath $(COREMARK_FLAGS_HEADER))
COREMARK_O3_FLAGS = -include $(COREMARK_ACCEL_DIR)/force_o3.h
COREMARK_CRC_FLAGS = -include $(COREMARK_ACCEL_DIR)/xcrc_hw.h \
	-D__COREMARK_XCRCU8=1
COREMARK_FP12_FLAGS = -include $(COREMARK_ACCEL_DIR)/float_dump.h

$(COREMARK_BENCH_OBJS): $(COREMARK_ACCEL_PLUGIN) $(COREMARK_ACCEL_DIR)/xaccel_wrappers.c
$(COREMARK_BENCH_OBJS): $(COREMARK_FLAGS_HEADER)
$(COREMARK_BENCH_OBJS): CFLAGS += $(COREMARK_BENCH_FLAGS)
$(COREMARK_O3_OBJS): CFLAGS += $(COREMARK_O3_FLAGS)
$(COREMARK_CRC_CALLER_OBJS): CFLAGS += $(COREMARK_CRC_FLAGS)
ifeq ($(COREMARK_PSEUDO_FLOAT),1)
$(DST_DIR)/$(COREMARK_BENCH_DIR)/core_main.o: CFLAGS += $(COREMARK_FP12_FLAGS)
$(DST_DIR)/$(COREMARK_ACCEL_DIR)/float_dump.o: $(COREMARK_FLAGS_HEADER)
$(DST_DIR)/$(COREMARK_ACCEL_DIR)/float_dump.o: CFLAGS += -include $(abspath $(COREMARK_FLAGS_HEADER))
endif
$(DST_DIR)/$(COREMARK_BENCH_DIR)/core_main.o: CFLAGS += -Dmain=coremark_main

$(COREMARK_FLAGS_HEADER): FORCE $(COREMARK_ACCEL_DIR)/gen_flags_header.py
	@mkdir -p $(dir $@)
	@python3 $(COREMARK_ACCEL_DIR)/gen_flags_header.py --output $@ -- \
		'$(COREMARK_OPT)' $(foreach flag,$(COREMARK_REPORT_COMMON_FLAGS),'$(flag)') \
		'-fplugin=$(notdir $(COREMARK_ACCEL_PLUGIN))' \
		'-fplugin-arg-xaccel_plugin-accels=$(COREMARK_PLUGIN_ACCELS)' \
		'-fplugin-arg-xaccel_plugin-report=$(if $(filter 1,$(COREMARK_PSEUDO_FLOAT)),fp12,none)'

.PHONY: FORCE
FORCE:

$(OBJS): | fetch

# Parallel make decides whether an object can be built from the state of its
# source at startup.  When the pinned checkout is fetched during the same make
# run, the upstream sources are missing at that point and the objects are
# silently skipped.  Declaring the sources as targets of a rule that performs
# the fetch makes the build work with `make -j` from a clean tree.
NANO_SRCS := $(NANO_DIR)/src/clock.c \
             $(NANO_DIR)/src/cpu.c \
             $(NANO_DIR)/src/idle.c \
             $(NANO_DIR)/src/ipc.c \
             $(NANO_DIR)/src/irq.c \
             $(NANO_DIR)/src/kservice.c \
             $(NANO_DIR)/src/mempool.c \
             $(NANO_DIR)/src/object.c \
             $(NANO_DIR)/src/scheduler.c \
             $(NANO_DIR)/src/thread.c \
             $(NANO_DIR)/src/timer.c \
             $(NANO_DIR)/components/finsh/msh.c \
             $(NANO_DIR)/components/finsh/shell.c

$(NANO_SRCS): fetch
	@test -f "$@"

fetch: $(NANO_CHECKOUT)/.pinned-$(NANO_COMMIT)

$(NANO_CHECKOUT)/.pinned-$(NANO_COMMIT):
	@if [ ! -d "$(NANO_CHECKOUT)/.git" ]; then \
		git clone --filter=blob:none "$(NANO_REPO)" "$(NANO_CHECKOUT)"; \
	fi
	@if ! git -C "$(NANO_CHECKOUT)" cat-file -e "$(NANO_COMMIT)^{commit}" 2>/dev/null; then \
		git -C "$(NANO_CHECKOUT)" fetch --depth 1 origin "$(NANO_COMMIT)"; \
	fi
	@git -C "$(NANO_CHECKOUT)" checkout -f --detach "$(NANO_COMMIT)"
	@test "$$(git -C "$(NANO_CHECKOUT)" rev-parse HEAD)" = "$(NANO_COMMIT)"
	@if ! git -C "$(NANO_CHECKOUT)" apply --reverse --check $(ROOT_DIR)/patches/*.patch 2>/dev/null; then \
		git -C "$(NANO_CHECKOUT)" apply $(ROOT_DIR)/patches/*.patch; \
	fi
	@touch "$@"

.PHONY: fetch

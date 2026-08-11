ARCH ?= riscv32-jyd
ifneq ($(filter gdb-server,$(MAKECMDGOALS)),)
NANO_OPT ?= -O0
else
NANO_OPT ?= -Os
endif

COREMARK_PORT_DIR ?= $(abspath $(ROOT_DIR)/../coremark-official/src)
COREMARK_BENCH_DIR ?= $(COREMARK_PORT_DIR)
COREMARK_ITERATIONS ?= 10000
COREMARK_DATA_SIZE ?= 2000
COREMARK_OPT ?= -Os
COREMARK_SUPPORT_OPT ?= -Os
COREMARK_ENABLE ?= 1
CAMERA_DEMO ?= 0
# B-extension subset used ONLY for the embedded CoreMark benchmark sources
# (same set as the official CoreMark image). The RT-Thread kernel and the AM
# port stay on the base ISA.
COREMARK_ZEXTS ?= _zba_zbb_zbc_zbs

empty :=
space := $(empty) $(empty)
escape_spaces = $(subst $(space),\ ,$(1))

ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
JYD_HOME := $(abspath $(ROOT_DIR)/../..)
NANO_REPO := https://github.com/RT-Thread/rtthread-nano.git
NANO_COMMIT := $(shell tr -d '[:space:]' < $(ROOT_DIR)/SOURCE_COMMIT)
NANO_CHECKOUT := $(ROOT_DIR)/upstream
NANO_DIR := $(NANO_CHECKOUT)/rt-thread

ifeq ($(CAMERA_DEMO),1)
override COREMARK_ENABLE := 0
JYD_LINKER_SCRIPT := $(JYD_HOME)/abstract-machine/am/src/riscv/jyd/linker_ax7035b_camera.ld
endif

ifeq ($(COREMARK_ENABLE),1)
COREMARK_BENCH_SRCS := $(addprefix $(COREMARK_BENCH_DIR)/, \
        core_list_join.c core_main.c core_matrix.c core_state.c core_util.c)
COREMARK_SUPPORT_SRCS := $(addprefix $(COREMARK_PORT_DIR)/, \
        core_portme.c ee_printf.c)
# Match the standalone cdefault image exactly: the AM release profile adds
# -O2/-O3 to CFLAGS, the app keeps -Os and the CoreMark HOT/COLD attributes
# lift only the marked functions; the report string mirrors the standalone
# COREMARK_COMPILER_FLAGS formula.
COREMARK_REPORT_FLAGS := -O2 -O3 -Os -march=rv32im_zicsr$(COREMARK_ZEXTS) -mabi=ilp32
COREMARK_CFLAGS := -DITERATIONS=$(COREMARK_ITERATIONS) \
                   -DTOTAL_DATA_SIZE=$(COREMARK_DATA_SIZE) \
                   -DCOREMARK_PSEUDO_FLOAT=1 \
                   -DCOMPILER_FLAGS=\"$(call escape_spaces,$(COREMARK_REPORT_FLAGS))\"
endif

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
        $(NANO_DIR)/src/tinyprintf.c \
        $(NANO_DIR)/src/timer.c \
        $(NANO_DIR)/components/finsh/msh.c \
        $(NANO_DIR)/components/finsh/shell.c \
        $(COREMARK_BENCH_SRCS) \
        $(COREMARK_SUPPORT_SRCS)

INC_PATH += $(ROOT_DIR)/include $(JYD_HOME)/abstract-machine/am/include \
            $(NANO_DIR)/include $(NANO_DIR)/components/finsh \
            $(COREMARK_BENCH_DIR) $(COREMARK_PORT_DIR)
CFLAGS += -DHAVE_CCONFIG_H -D__RTTHREAD__ -DCOREMARK_ENABLE=$(COREMARK_ENABLE) \
          $(COREMARK_CFLAGS) -Wno-nonnull-compare
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

# Current GCC releases diagnose one legacy Nano control-flow path as maybe
# uninitialized. Keep the upstream source untouched and demote only that
# diagnostic for the compatibility checkout used by the board port.
$(OBJS): CFLAGS += $(NANO_OPT) -Wno-error=maybe-uninitialized
COREMARK_BENCH_OBJS := $(addprefix $(DST_DIR)/,$(addsuffix .o,$(basename $(COREMARK_BENCH_SRCS))))
COREMARK_SUPPORT_OBJS := $(addprefix $(DST_DIR)/,$(addsuffix .o,$(basename $(COREMARK_SUPPORT_SRCS))))
# Only the CoreMark benchmark translation units get the B extensions; the
# last -march on the command line wins, so this overrides the base ISA above.
$(COREMARK_BENCH_OBJS): CFLAGS += -march=rv32im_zicsr$(COREMARK_ZEXTS)
$(COREMARK_BENCH_OBJS): CFLAGS += $(COREMARK_OPT)
$(COREMARK_SUPPORT_OBJS): CFLAGS += $(COREMARK_SUPPORT_OPT)
$(COREMARK_SUPPORT_OBJS): CFLAGS += -march=rv32im_zicsr$(COREMARK_ZEXTS)
# The embedded CoreMark is built exactly like the standalone image (raw 50 MHz
# CNT_REG timing, COREMARK_TICKS_PER_SEC=50000000, no COREMARK_EMBEDDED_RTT).
# The only integration change is renaming CoreMark's main() to coremark_main()
# so it does not collide with RT-Thread's entry main(); the code is unchanged.
$(DST_DIR)/$(COREMARK_BENCH_DIR)/core_main.o: CFLAGS += -Dmain=coremark_main
$(OBJS): | fetch

fetch: $(NANO_CHECKOUT)/.pinned-$(NANO_COMMIT)

$(NANO_CHECKOUT)/.pinned-$(NANO_COMMIT):
	@if [ ! -d "$(NANO_CHECKOUT)/.git" ]; then \
		git clone --filter=blob:none "$(NANO_REPO)" "$(NANO_CHECKOUT)"; \
	fi
	@if ! git -C "$(NANO_CHECKOUT)" cat-file -e "$(NANO_COMMIT)^{commit}" 2>/dev/null; then \
		git -C "$(NANO_CHECKOUT)" fetch --depth 1 origin "$(NANO_COMMIT)"; \
	fi
	@git -C "$(NANO_CHECKOUT)" checkout --detach "$(NANO_COMMIT)"
	@test "$$(git -C "$(NANO_CHECKOUT)" rev-parse HEAD)" = "$(NANO_COMMIT)"
	@touch "$@"

.PHONY: fetch

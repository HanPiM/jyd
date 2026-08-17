ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
JYD_HOME := $(abspath $(ROOT_DIR)/../..)

ARCH ?= riscv32-jyd
ifneq ($(filter gdb-server,$(MAKECMDGOALS)),)
NANO_OPT ?= -O0
else
NANO_OPT ?= -Os
endif

COREMARK_PORT_DIR ?= $(abspath $(ROOT_DIR)/../coremark-official/src)
COREMARK_BENCH_DIR ?= $(COREMARK_PORT_DIR)
COREMARK_ACCEL_DIR ?= $(abspath $(COREMARK_PORT_DIR)/../accel)
COREMARK_DEFAULTS ?= $(abspath $(COREMARK_PORT_DIR)/../coremark-defaults.mk)
include $(COREMARK_DEFAULTS)
ITERATIONS ?= 10000
COREMARK_ITERATIONS ?= $(ITERATIONS)
COREMARK_DATA_SIZE ?= 2000
COREMARK_OPT ?= -Os
COREMARK_SUPPORT_OPT ?= -Os
COREMARK_ENABLE ?= 1
# These settings come from coremark-official and apply only to the embedded
# benchmark objects. The RT-Thread kernel and AM port remain rv32im_zicsr.
COREMARK_ZEXTS ?= $(COREMARK_DEFAULT_RISCV_ZEXTS)
COREMARK_GCC_MD ?= $(COREMARK_DEFAULT_GCC_MD)
COREMARK_XEXTS ?= $(if $(filter 1,$(COREMARK_GCC_MD)),$(COREMARK_DEFAULT_XEXTS),)
COREMARK_MD_FLAGS ?= $(if $(filter 1,$(COREMARK_GCC_MD)),$(call coremark_md_flags,$(COREMARK_XEXTS)),)

empty :=
space := $(empty) $(empty)
comma := ,
COREMARK_SUPPORTED_XEXTS := $(COREMARK_GCC_MD_SUPPORTED_XEXTS)
COREMARK_XEXT_WORDS := $(filter x%,$(subst _, ,$(strip $(COREMARK_XEXTS))))
COREMARK_UNKNOWN_XEXTS := $(filter-out $(COREMARK_SUPPORTED_XEXTS),$(COREMARK_XEXT_WORDS))
ifneq ($(strip $(COREMARK_UNKNOWN_XEXTS)),)
$(error Unsupported COREMARK_XEXTS: $(COREMARK_UNKNOWN_XEXTS))
endif
COREMARK_CRC_LTO_ENABLED := $(if $(and $(filter 1,$(COREMARK_ENABLE)),$(filter xcrcu8,$(COREMARK_XEXT_WORDS))),1,0)
COREMARK_CRC_LTO_FLAGS := $(if $(filter 1,$(COREMARK_CRC_LTO_ENABLED)),-flto -fcrc-semantic-lto,)
NANO_REPO := https://github.com/RT-Thread/rtthread-nano.git
NANO_COMMIT := $(shell tr -d '[:space:]' < $(ROOT_DIR)/SOURCE_COMMIT)
NANO_CHECKOUT := $(ROOT_DIR)/upstream
NANO_DIR := $(NANO_CHECKOUT)/rt-thread

ifeq ($(COREMARK_ENABLE),1)
COREMARK_BENCH_SRCS := $(addprefix $(COREMARK_BENCH_DIR)/, \
        core_list_join.c core_main.c core_matrix.c core_state.c core_util.c)
COREMARK_SUPPORT_SRCS := $(addprefix $(COREMARK_PORT_DIR)/, \
        core_portme.c ee_printf.c cvt.c coremark_softfloat.c)
COREMARK_O3_SRCS := $(addprefix $(COREMARK_BENCH_DIR)/, \
        core_list_join.c core_matrix.c core_state.c core_util.c)
COREMARK_CFLAGS := -DITERATIONS=$(COREMARK_ITERATIONS) \
                   -DTOTAL_DATA_SIZE=$(COREMARK_DATA_SIZE) \
                   -D__COREMARK_XEXTS=\"$(COREMARK_XEXTS)\"
LIBS += softfloat
endif

# The source list selects the local size-bounded formatter; the upstream
# formatter is not part of SRCS.
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
INC_PATH += $(ROOT_DIR)/include $(NANO_DIR)/include $(NANO_DIR)/components/finsh \
            $(COREMARK_BENCH_DIR) $(COREMARK_PORT_DIR) $(COREMARK_ACCEL_DIR)
RT_PATH_MAP_FLAGS := -ffile-prefix-map=$(JYD_HOME)=. -fdebug-prefix-map=$(JYD_HOME)=.
CFLAGS += -DHAVE_CCONFIG_H -D__RTTHREAD__ -DCOREMARK_ENABLE=$(COREMARK_ENABLE) \
          $(COREMARK_CFLAGS) -Wno-nonnull-compare -Wno-maybe-uninitialized
# Keep diagnostics, __FILE__ strings, and debug metadata independent of the
# checkout location so a relocated reproduction tree emits identical COE data.
CFLAGS += $(RT_PATH_MAP_FLAGS)
LIB_MAKE_ARGS_am = EXTRA_CFLAGS='$(RT_PATH_MAP_FLAGS)'
LIB_MAKE_ARGS_klib = EXTRA_CFLAGS='$(RT_PATH_MAP_FLAGS)'
ifeq ($(ARCH),riscv32-jyd)
LDFLAGS += -T $(ROOT_DIR)/extra.ld
else
LDFLAGS += -T $(ROOT_DIR)/extra-flat.ld
endif
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
COREMARK_O3_OBJS := $(addprefix $(DST_DIR)/,$(addsuffix .o,$(basename $(COREMARK_O3_SRCS))))
COREMARK_BUILD_CONFIG_STAMP := $(DST_DIR)/coremark-build-config-v2.stamp

# Make does not otherwise notice command-line flag changes. Include the full
# embedded-build configuration so an old non-LTO object cannot be reused after
# enabling semantic CRC lowering.
$(COREMARK_BUILD_CONFIG_STAMP): FORCE
	@mkdir -p $(dir $@)
	@{ \
		printf '%s\n' 'iterations=$(COREMARK_ITERATIONS)'; \
		printf '%s\n' 'data-size=$(COREMARK_DATA_SIZE)'; \
		printf '%s\n' 'bench-opt=$(COREMARK_OPT)'; \
		printf '%s\n' 'zexts=$(COREMARK_ZEXTS)'; \
		printf '%s\n' 'xexts=$(COREMARK_XEXTS)'; \
		printf '%s\n' 'md-flags=$(COREMARK_MD_FLAGS)'; \
		printf '%s\n' 'crc-lto=$(COREMARK_CRC_LTO_ENABLED) $(COREMARK_CRC_LTO_FLAGS)'; \
	} > $@.tmp
	@if ! cmp -s $@.tmp $@ 2>/dev/null; then mv $@.tmp $@; else rm -f $@.tmp; fi

$(COREMARK_BENCH_OBJS) $(COREMARK_SUPPORT_OBJS): $(COREMARK_BUILD_CONFIG_STAMP)
# Only the CoreMark benchmark translation units get the B extensions; the
# last -march on the command line wins, so this overrides the base ISA above.
$(COREMARK_BENCH_OBJS): CFLAGS += -march=rv32im$(COREMARK_ZEXTS)_zicsr
$(COREMARK_BENCH_OBJS): CFLAGS += $(COREMARK_OPT)
ifeq ($(COREMARK_CRC_LTO_ENABLED),1)
$(COREMARK_BENCH_OBJS): CFLAGS += $(COREMARK_CRC_LTO_FLAGS)
endif
$(COREMARK_SUPPORT_OBJS): CFLAGS += $(COREMARK_SUPPORT_OPT)
$(COREMARK_SUPPORT_OBJS): CFLAGS += -march=rv32im$(COREMARK_ZEXTS)_zicsr

# The embedded CoreMark uses the same raw 50 MHz counter as the standalone
# image and renames main() to avoid colliding with the RT-Thread entry point.
COREMARK_COMMON_FLAGS := $(strip $(CFLAGS))
COREMARK_REPORT_COMMON_FLAGS := $(filter -O% -mabi=% -flto \
	-fcrc-semantic-lto -fno-builtin -ffreestanding -fdata-sections -ffunction-sections, \
	$(COREMARK_COMMON_FLAGS)) -march=rv32im$(COREMARK_ZEXTS)_zicsr
COREMARK_REPORT_LTO_FLAGS := $(if $(filter 1,$(COREMARK_CRC_LTO_ENABLED)),$(COREMARK_CRC_LTO_FLAGS),)
COREMARK_FLAGS_HEADER := $(DST_DIR)/coremark-compiler-flags.h
COREMARK_BENCH_FLAGS = $(COREMARK_OPT) $(COREMARK_MD_FLAGS) \
	-include $(abspath $(COREMARK_FLAGS_HEADER))
COREMARK_O3_FLAGS = -include $(COREMARK_ACCEL_DIR)/force_o3.h

$(COREMARK_BENCH_OBJS): $(COREMARK_FLAGS_HEADER)
$(COREMARK_BENCH_OBJS): CFLAGS += $(COREMARK_BENCH_FLAGS)
$(COREMARK_O3_OBJS): CFLAGS += $(COREMARK_O3_FLAGS)
$(DST_DIR)/$(COREMARK_BENCH_DIR)/core_main.o: CFLAGS += -Dmain=coremark_main

$(COREMARK_FLAGS_HEADER): FORCE $(COREMARK_ACCEL_DIR)/gen_flags_header.py
	@mkdir -p $(dir $@)
	@python3 $(COREMARK_ACCEL_DIR)/gen_flags_header.py --output $@ -- \
		'$(COREMARK_OPT)' $(foreach flag,$(COREMARK_REPORT_COMMON_FLAGS),'$(flag)') \
		'GCC machine-description selection' $(foreach flag,$(COREMARK_MD_FLAGS),'$(flag)') \
		'CRC semantic LTO' $(foreach flag,$(COREMARK_REPORT_LTO_FLAGS),'$(flag)') \
		'software floating-point reporting'

.PHONY: FORCE
FORCE:

ifeq ($(COREMARK_CRC_LTO_ENABLED),1)
# LTO objects must be linked through the same GCC driver that produced them;
# the RTOS and AM archives remain ordinary non-LTO objects.
RTTHREAD_GCC_LDFLAGS = $(foreach flag,$(LDFLAGS),-Wl$(comma)$(flag))
RTTHREAD_COREMARK_LTO_LINK_FLAGS = \
	$(filter-out -march=%,$(COMMON_CFLAGS)) \
	-march=rv32im$(COREMARK_ZEXTS)_zicsr \
	$(COREMARK_MD_FLAGS) $(COREMARK_CRC_LTO_FLAGS)
$(IMAGE).elf: $(LINKAGE) $(LDSCRIPTS)
	@mkdir -p $(dir $@)
	@echo \# Creating CoreMark semantic-LTO image [$(ARCH)]
	@echo + LD "->" $(IMAGE_REL).elf
	@$(CC) $(RTTHREAD_COREMARK_LTO_LINK_FLAGS) -nostdlib -o $@ \
		$(RTTHREAD_GCC_LDFLAGS) -Wl,--start-group $(LINKAGE) $(LDLIBS) -Wl,--end-group
endif

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

$(NANO_CHECKOUT)/.pinned-$(NANO_COMMIT): $(ROOT_DIR)/SOURCE_COMMIT $(wildcard $(ROOT_DIR)/patches/*.patch)
	@if [ ! -d "$(NANO_CHECKOUT)/.git" ]; then \
		git clone --filter=blob:none "$(NANO_REPO)" "$(NANO_CHECKOUT)"; \
	fi
	@if ! git -C "$(NANO_CHECKOUT)" cat-file -e "$(NANO_COMMIT)^{commit}" 2>/dev/null; then \
		git -C "$(NANO_CHECKOUT)" fetch --depth 1 origin "$(NANO_COMMIT)"; \
	fi
	@git -C "$(NANO_CHECKOUT)" checkout -f --detach "$(NANO_COMMIT)"
	@test "$$(git -C "$(NANO_CHECKOUT)" rev-parse HEAD)" = "$(NANO_COMMIT)"
	@git -C "$(NANO_CHECKOUT)" apply $(ROOT_DIR)/patches/*.patch
	@touch "$@"

.PHONY: fetch

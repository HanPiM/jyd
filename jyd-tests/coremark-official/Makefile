NAME = coremark-official

ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
JYD_HOME := $(abspath $(ROOT_DIR)/../..)
include $(ROOT_DIR)/coremark-defaults.mk

ITERATIONS ?= 10000
TOTAL_DATA_SIZE ?= 2000
RISCV_ZEXTS ?= $(COREMARK_DEFAULT_RISCV_ZEXTS)
ENABLE_LTO ?= 0
COREMARK_OPT ?= -Os
COREMARK_GCC_MD ?= $(COREMARK_DEFAULT_GCC_MD)
COREMARK_XEXTS ?= $(if $(filter 1,$(COREMARK_GCC_MD)),$(COREMARK_DEFAULT_XEXTS),)
COREMARK_MD_FLAGS ?= $(if $(filter 1,$(COREMARK_GCC_MD)),$(call coremark_md_flags,$(COREMARK_XEXTS)),)

empty :=
space := $(empty) $(empty)
comma := ,
COREMARK_SUPPORTED_XEXTS := xbmul xcrcu8 xdot16 xlistfind xlistrev xmac16 xmacacc xmsum xdfacnt xdfa2 xdfa4 xdfa4h xdfa4p xmbm
COREMARK_XEXT_WORDS := $(filter x%,$(subst _, ,$(strip $(COREMARK_XEXTS))))
COREMARK_UNKNOWN_XEXTS := $(filter-out $(COREMARK_SUPPORTED_XEXTS),$(COREMARK_XEXT_WORDS))
ifneq ($(strip $(COREMARK_UNKNOWN_XEXTS)),)
$(error Unsupported COREMARK_XEXTS: $(COREMARK_UNKNOWN_XEXTS))
endif
COREMARK_ACCEL_WORDS := $(filter-out xcrcu8,$(COREMARK_XEXT_WORDS))
COREMARK_ACCELS := $(subst $(space),$(comma),$(strip $(COREMARK_ACCEL_WORDS)))
COREMARK_PLUGIN_ACCELS := $(if $(COREMARK_ACCELS),$(COREMARK_ACCELS),none)
COREMARK_USE_PLUGIN := $(if $(and $(filter-out 1,$(COREMARK_GCC_MD)),$(strip $(COREMARK_ACCEL_WORDS) $(COREMARK_XACCEL_EXPLORE))),1,0)
sanitize = $(subst $(space),_,$(subst /,_,$(subst :,_,$(subst =,_,$(subst ',_,$(1))))))
COREMARK_CONFIG_ID = iter$(ITERATIONS)-data$(TOTAL_DATA_SIZE)-z$(call sanitize,$(if $(strip $(RISCV_ZEXTS)),$(RISCV_ZEXTS),none))-x$(call sanitize,$(if $(strip $(COREMARK_XEXTS)),$(COREMARK_XEXTS),none))-e$(call sanitize,$(if $(strip $(COREMARK_XACCEL_EXPLORE)),$(COREMARK_XACCEL_EXPLORE),none))-c$(call sanitize,$(if $(strip $(EXTRA_CFLAGS)),$(EXTRA_CFLAGS),default))-lto$(ENABLE_LTO)-softfloat
BUILD_DIR ?= build/$(COREMARK_CONFIG_ID)
LIB_BUILD_DIR_softfloat = build/$(ARCH)-z$(call sanitize,$(if $(strip $(RISCV_ZEXTS)),$(RISCV_ZEXTS),none))

SRCS = $(shell find src/ -name "*.c")
LIBS += softfloat
COREMARK_BENCH_SRCS := src/core_list_join.c src/core_main.c src/core_matrix.c \
                       src/core_state.c src/core_util.c
COREMARK_CRC_CALLER_SRCS := $(filter-out src/core_util.c,$(COREMARK_BENCH_SRCS))
COREMARK_O3_SRCS := src/core_list_join.c src/core_matrix.c src/core_state.c src/core_util.c
INC_PATH += $(CURDIR)/src

ifeq ($(MAKECMDGOALS),check)
ARCH ?= riscv32-nemu
endif
include $(JYD_HOME)/abstract-machine/Makefile
ifeq ($(ARCH),riscv32-jyd)
# Keep multi-letter extensions in canonical order for current GCC/binutils.
COMMON_CFLAGS := $(filter-out -march=%,$(COMMON_CFLAGS)) -march=rv32im$(RISCV_ZEXTS)_zicsr
endif
LIB_MAKE_ARGS_softfloat = \
	SOFTFLOAT_MARCH=$(patsubst -march=%,%,$(filter -march=%,$(COMMON_CFLAGS))) \
	SOFTFLOAT_MABI=$(patsubst -mabi=%,%,$(filter -mabi=%,$(COMMON_CFLAGS)))

# Keep COREMARK_OPT at -Os for the formal image: the hot benchmark translation
# units below still receive force_o3.h, while core_main stays smaller and saves
# roughly 54 million instructions on the xlistrev+xdfa4 workload. Explicitly
# pass COREMARK_OPT=-O3 only for comparison experiments.
CFLAGS += -Os
CFLAGS += -DITERATIONS=$(ITERATIONS) -DTOTAL_DATA_SIZE=$(TOTAL_DATA_SIZE)
CFLAGS += -D__COREMARK_XEXTS=\"$(COREMARK_XEXTS)\"
CFLAGS += $(if $(filter xlistfind,$(subst $(comma),$(space),$(COREMARK_XACCEL_EXPLORE))),-D__COREMARK_XLISTFIND=1,)
CFLAGS += $(EXTRA_CFLAGS)
COREMARK_COMMON_FLAGS := $(strip $(CFLAGS))
COREMARK_REPORT_COMMON_FLAGS := $(filter -O% -march=% -mabi=% -flto \
	-fno-builtin -ffreestanding -fdata-sections -ffunction-sections, \
	$(COREMARK_COMMON_FLAGS))

COREMARK_BENCH_OBJS = $(addprefix $(DST_DIR)/, $(addsuffix .o, $(basename $(COREMARK_BENCH_SRCS))))
COREMARK_CRC_CALLER_OBJS = $(addprefix $(DST_DIR)/, $(addsuffix .o, $(basename $(COREMARK_CRC_CALLER_SRCS))))
COREMARK_O3_OBJS = $(addprefix $(DST_DIR)/, $(addsuffix .o, $(basename $(COREMARK_O3_SRCS))))

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
COREMARK_ACCEL_PLUGIN := $(CURDIR)/build/tools/xaccel_plugin.so

$(COREMARK_ACCEL_PLUGIN): accel/xaccel_plugin.cc
	@mkdir -p $(dir $@)
	$(HOST_CXX) -std=gnu++17 -fPIC -shared -fno-rtti -fno-exceptions \
		-I$(GCC_PLUGIN_DIR)/include \
		$(if $(GCC_PLUGIN_GMP_INCLUDE),-I$(GCC_PLUGIN_GMP_INCLUDE),) \
		-o $@ $<

COREMARK_FLAGS_HEADER := $(BUILD_DIR)/generated/compiler_flags.h
ifeq ($(COREMARK_USE_PLUGIN),1)
COREMARK_PLUGIN_FLAGS = -fplugin=$(COREMARK_ACCEL_PLUGIN) \
	-fplugin-arg-xaccel_plugin-accels=$(COREMARK_PLUGIN_ACCELS) \
	-include $(CURDIR)/accel/xaccel_wrappers.c
COREMARK_SELECTION_REPORT = '-fplugin=$(notdir $(COREMARK_ACCEL_PLUGIN))' \
	'-fplugin-arg-xaccel_plugin-accels=$(COREMARK_PLUGIN_ACCELS)'
else
COREMARK_PLUGIN_FLAGS =
COREMARK_SELECTION_REPORT = 'standard compiler selection' 'software floating-point reporting'
endif
ifeq ($(COREMARK_GCC_MD),1)
COREMARK_PLUGIN_FLAGS =
COREMARK_SELECTION_REPORT = 'GCC machine-description selection' \
	$(foreach flag,$(COREMARK_MD_FLAGS) $(filter -m%,$(EXTRA_CFLAGS)),'$(flag)')
ifneq ($(strip $(COREMARK_XACCEL_EXPLORE)),)
$(error COREMARK_XACCEL_EXPLORE is plugin-only; select GCC-generated instructions with EXTRA_CFLAGS)
endif
endif
COREMARK_BENCH_FLAGS = $(COREMARK_OPT) $(COREMARK_PLUGIN_FLAGS) \
	$(COREMARK_MD_FLAGS) \
	-include $(abspath $(COREMARK_FLAGS_HEADER))
COREMARK_O3_FLAGS = -include $(CURDIR)/accel/force_o3.h
COREMARK_CRC_FLAGS = -include $(CURDIR)/accel/xcrc_hw.h \
	-D__COREMARK_XCRCU8=1

ifeq ($(COREMARK_USE_PLUGIN),1)
$(COREMARK_BENCH_OBJS): $(COREMARK_ACCEL_PLUGIN) accel/xaccel_wrappers.c
endif
$(COREMARK_BENCH_OBJS): $(COREMARK_FLAGS_HEADER)
$(COREMARK_BENCH_OBJS): CFLAGS += $(COREMARK_BENCH_FLAGS)
$(COREMARK_O3_OBJS): CFLAGS += $(COREMARK_O3_FLAGS)

ifneq ($(filter xcrcu8,$(COREMARK_XEXT_WORDS)),)
$(COREMARK_CRC_CALLER_OBJS): CFLAGS += $(COREMARK_CRC_FLAGS)
endif

.PHONY: FORCE
FORCE:

$(COREMARK_FLAGS_HEADER): FORCE accel/gen_flags_header.py
	@python3 accel/gen_flags_header.py --output $@ -- \
		'$(COREMARK_OPT)' $(foreach flag,$(COREMARK_REPORT_COMMON_FLAGS),'$(flag)') \
		$(COREMARK_SELECTION_REPORT)

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
		--accels '$(subst $(space),$(comma),$(strip $(COREMARK_XEXT_WORDS)))'

check:
	cd src && md5sum -c ../coremark.md5

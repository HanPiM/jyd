NAME = coremark-official

ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
JYD_HOME := $(abspath $(ROOT_DIR)/../..)
include $(ROOT_DIR)/coremark-defaults.mk

ITERATIONS ?= 10000
TOTAL_DATA_SIZE ?= 2000
RISCV_ZEXTS ?= $(COREMARK_DEFAULT_RISCV_ZEXTS)
ENABLE_LTO ?= $(if $(filter xcrcu8,$(COREMARK_XEXT_WORDS)),1,0)
COREMARK_OPT ?= -Os
COREMARK_XEXTS ?= $(COREMARK_DEFAULT_XEXTS)
COREMARK_MD_FLAGS ?= $(call coremark_md_flags,$(COREMARK_XEXTS))

empty :=
space := $(empty) $(empty)
comma := ,
COREMARK_SUPPORTED_XEXTS := $(COREMARK_GCC_MD_SUPPORTED_XEXTS)
COREMARK_XEXT_WORDS := $(filter x%,$(subst _, ,$(strip $(COREMARK_XEXTS))))
COREMARK_UNKNOWN_XEXTS := $(filter-out $(COREMARK_SUPPORTED_XEXTS),$(COREMARK_XEXT_WORDS))
ifneq ($(strip $(COREMARK_UNKNOWN_XEXTS)),)
$(error Unsupported COREMARK_XEXTS: $(COREMARK_UNKNOWN_XEXTS))
endif
ifneq ($(filter xcrcu8,$(COREMARK_XEXT_WORDS)),)
ifneq ($(ENABLE_LTO),1)
$(error COREMARK_XEXTS includes xcrcu8 but ENABLE_LTO is not 1; semantic CRC lowering requires LTO)
endif
endif
COREMARK_CRC_SEMANTIC_LTO := $(if $(and $(filter 1,$(ENABLE_LTO)),$(filter xcrcu8,$(COREMARK_XEXT_WORDS))),1,0)
COREMARK_CRC_LTO_FLAGS := $(if $(filter 1,$(COREMARK_CRC_SEMANTIC_LTO)),-fcrc-semantic-lto,)
COREMARK_LTO_FLAGS := -flto $(COREMARK_CRC_LTO_FLAGS)
sanitize = $(subst $(space),_,$(subst /,_,$(subst :,_,$(subst =,_,$(subst ',_,$(1))))))
COREMARK_CONFIG_ID = iter$(ITERATIONS)-data$(TOTAL_DATA_SIZE)-z$(call sanitize,$(if $(strip $(RISCV_ZEXTS)),$(RISCV_ZEXTS),none))-x$(call sanitize,$(if $(strip $(COREMARK_XEXTS)),$(COREMARK_XEXTS),none))-c$(call sanitize,$(if $(strip $(EXTRA_CFLAGS)),$(EXTRA_CFLAGS),default))-lto$(ENABLE_LTO)-crcsem$(COREMARK_CRC_SEMANTIC_LTO)-softfloat
BUILD_DIR ?= build/$(COREMARK_CONFIG_ID)
LIB_BUILD_DIR_softfloat = build/$(ARCH)-z$(call sanitize,$(if $(strip $(RISCV_ZEXTS)),$(RISCV_ZEXTS),none))

SRCS = $(shell find src/ -name "*.c")
LIBS += softfloat
COREMARK_BENCH_SRCS := src/core_list_join.c src/core_main.c src/core_matrix.c \
                       src/core_state.c src/core_util.c
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
CFLAGS += $(EXTRA_CFLAGS)
ifeq ($(ENABLE_LTO),1)
CFLAGS += $(COREMARK_LTO_FLAGS)
endif
COREMARK_COMMON_FLAGS := $(strip $(CFLAGS))
COREMARK_REPORT_COMMON_FLAGS := $(filter -O% -march=% -mabi=% -flto \
	-fcrc-semantic-lto -fno-builtin -ffreestanding -fdata-sections -ffunction-sections, \
	$(COREMARK_COMMON_FLAGS))

COREMARK_BENCH_OBJS = $(addprefix $(DST_DIR)/, $(addsuffix .o, $(basename $(COREMARK_BENCH_SRCS))))
COREMARK_O3_OBJS = $(addprefix $(DST_DIR)/, $(addsuffix .o, $(basename $(COREMARK_O3_SRCS))))

COREMARK_FLAGS_HEADER := $(BUILD_DIR)/generated/compiler_flags.h
COREMARK_SELECTION_REPORT = 'GCC machine-description selection' \
	$(foreach flag,$(COREMARK_MD_FLAGS) $(filter -m%,$(EXTRA_CFLAGS)),'$(flag)')
COREMARK_BENCH_FLAGS = $(COREMARK_OPT) $(COREMARK_MD_FLAGS) \
	-include $(abspath $(COREMARK_FLAGS_HEADER))
COREMARK_O3_FLAGS = -include $(CURDIR)/accel/force_o3.h

$(COREMARK_BENCH_OBJS): $(COREMARK_FLAGS_HEADER)
$(COREMARK_BENCH_OBJS): CFLAGS += $(COREMARK_BENCH_FLAGS)
$(COREMARK_O3_OBJS): CFLAGS += $(COREMARK_O3_FLAGS)

.PHONY: FORCE
FORCE:

$(COREMARK_FLAGS_HEADER): FORCE accel/gen_flags_header.py
	@python3 accel/gen_flags_header.py --output $@ -- \
		'$(COREMARK_OPT)' $(foreach flag,$(COREMARK_REPORT_COMMON_FLAGS),'$(flag)') \
		$(COREMARK_SELECTION_REPORT)

ifeq ($(ENABLE_LTO),1)
# AM normally invokes ld directly.  LTO objects need to be linked through the
# compiler driver so GCC can load the matching LTO object handler.  Keeping the
# recipe local avoids changing the link behavior of other AM images.
COREMARK_GCC_LDFLAGS = $(foreach flag,$(LDFLAGS),-Wl$(comma)$(flag))
$(IMAGE).elf: $(LINKAGE) $(LDSCRIPTS)
	@mkdir -p $(dir $@)
	@echo \# Creating LTO image [$(ARCH)]
	@echo + LD "->" $(IMAGE_REL).elf
	@$(CC) $(COMMON_CFLAGS) $(COREMARK_MD_FLAGS) $(EXTRA_CFLAGS) \
		$(COREMARK_LTO_FLAGS) -nostdlib -o $@ \
		$(COREMARK_GCC_LDFLAGS) -Wl,--start-group $(LINKAGE) $(LDLIBS) -Wl,--end-group
endif

.PHONY: audit-accel check
audit-accel: $(IMAGE).elf
	python3 accel/audit_accel_elf.py --elf $< \
		--objdump '$(OBJDUMP)' \
		--accels '$(subst $(space),$(comma),$(strip $(COREMARK_XEXT_WORDS)))'

check:
	cd src && md5sum -c ../coremark.md5

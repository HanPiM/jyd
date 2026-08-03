NAME = coremark-official

ITERATIONS ?= 1000
TOTAL_DATA_SIZE ?= 2000
ENABLE_LTO ?= 0
PSEUDO_FLOAT ?= 1

empty :=
space := $(empty) $(empty)
comma := ,
sanitize = $(subst $(space),_,$(subst /,_,$(subst :,_,$(subst =,_,$(subst ',_,$(1))))))
COREMARK_CONFIG_ID = iter$(ITERATIONS)-data$(TOTAL_DATA_SIZE)-z$(call sanitize,$(if $(strip $(RISCV_ZEXTS)),$(RISCV_ZEXTS),none))-c$(call sanitize,$(if $(strip $(EXTRA_CFLAGS)),$(EXTRA_CFLAGS),default))-lto$(ENABLE_LTO)-pf$(PSEUDO_FLOAT)
BUILD_DIR ?= build/$(COREMARK_CONFIG_ID)
LIB_BUILD_DIR_softfloat = build/$(ARCH)-z$(call sanitize,$(if $(strip $(RISCV_ZEXTS)),$(RISCV_ZEXTS),none))

SRCS = $(shell find src/ -name "*.c")
ifeq ($(PSEUDO_FLOAT),1)
SRCS := $(filter-out src/cvt.c src/coremark_softfloat.c,$(SRCS))
else
LIBS += softfloat
endif

INC_PATH += $(CURDIR)/src

include $(JYD_AM_HOME)/Makefile
LIB_MAKE_ARGS_softfloat = \
	SOFTFLOAT_MARCH=$(patsubst -march=%,%,$(filter -march=%,$(COMMON_CFLAGS))) \
	SOFTFLOAT_MABI=$(patsubst -mabi=%,%,$(filter -mabi=%,$(COMMON_CFLAGS)))

# Optimize the image for IROM size by default.  The benchmark translation
# units override this with -O3 locally; setup, reporting and formatting stay
# optimized for size.
CFLAGS += -Os

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
COREMARK_COMPILER_FLAGS := $(strip $(filter -O%,$(CFLAGS)) $(filter -march=% -mabi=%,$(COMMON_CFLAGS)) $(EXTRA_CFLAGS))
endif
escape_spaces = $(subst $(space),\ ,$(1))

CFLAGS += -DITERATIONS=$(ITERATIONS) -DTOTAL_DATA_SIZE=$(TOTAL_DATA_SIZE)
CFLAGS += -DCOREMARK_PSEUDO_FLOAT=$(PSEUDO_FLOAT)
CFLAGS += -DCOMPILER_FLAGS=\"$(call escape_spaces,$(COREMARK_COMPILER_FLAGS))\"
CFLAGS += $(EXTRA_CFLAGS)

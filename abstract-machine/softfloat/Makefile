NAME := softfloat
.DEFAULT_GOAL := archive

REPO_URL := https://github.com/ucb-bar/berkeley-softfloat-3.git
REPO_COMMIT := a0c6494cdc11865811dec815d5c0049fba9d82a8
REPO_DIR := $(CURDIR)/repo
REPO_STAMP := $(REPO_DIR)/.am-commit-$(REPO_COMMIT)

BUILD_DIR ?= build
BUILD_ROOT := $(CURDIR)/$(BUILD_DIR)
UPSTREAM_BUILD := $(BUILD_ROOT)/upstream
UPSTREAM_MAKEFILE := $(REPO_DIR)/build/Linux-RISCV64-GCC/Makefile
UPSTREAM_LIB := $(UPSTREAM_BUILD)/softfloat.a
ABI_OBJ := $(BUILD_ROOT)/softfloat_abi.o
ARCHIVE := $(BUILD_ROOT)/$(NAME)-$(ARCH).a

CROSS_COMPILE ?= riscv64-linux-gnu-
CC := $(CROSS_COMPILE)gcc
AR := $(CROSS_COMPILE)ar

ifneq ($(MAKECMDGOALS),clean)
ifeq ($(strip $(SOFTFLOAT_MARCH)),)
$(error SOFTFLOAT_MARCH must be supplied by the AM target)
endif
ifeq ($(strip $(SOFTFLOAT_MABI)),)
$(error SOFTFLOAT_MABI must be supplied by the AM target)
endif
endif

SOFTFLOAT_OPT ?= -O2
SOFTFLOAT_OPTS := -DSOFTFLOAT_ROUND_ODD -DINLINE_LEVEL=5 \
	-DSOFTFLOAT_FAST_DIV32TO16 -DSOFTFLOAT_FAST_DIV64TO32 \
	$(SOFTFLOAT_OPT) \
	-ffreestanding -fno-builtin -ffunction-sections -fdata-sections \
	-I$(CURDIR)/src/compat
SOFTFLOAT_INCLUDES := -I$(UPSTREAM_BUILD) -I$(REPO_DIR)/source/RISCV \
	-I$(REPO_DIR)/source/include
TARGET_FLAGS := -march=$(SOFTFLOAT_MARCH) -mabi=$(SOFTFLOAT_MABI)

$(REPO_STAMP):
	@if [ ! -d $(REPO_DIR)/.git ]; then \
		git clone --no-checkout $(REPO_URL) $(REPO_DIR); \
	fi
	@git -C $(REPO_DIR) fetch --depth=1 origin $(REPO_COMMIT)
	@git -C $(REPO_DIR) checkout --detach $(REPO_COMMIT)
	@touch $@

$(UPSTREAM_BUILD)/platform.h: src/platform.h
	@mkdir -p $(UPSTREAM_BUILD)
	@cp $< $@

$(UPSTREAM_LIB): $(REPO_STAMP) $(UPSTREAM_BUILD)/platform.h Makefile
	@echo + SOFTFLOAT $(SOFTFLOAT_MARCH)/$(SOFTFLOAT_MABI)
	@$(MAKE) -s -C $(UPSTREAM_BUILD) -f $(UPSTREAM_MAKEFILE) \
		SOURCE_DIR=$(REPO_DIR)/source SPECIALIZE_TYPE=RISCV \
		CC=$(CC) MARCH=$(SOFTFLOAT_MARCH) MABI=$(SOFTFLOAT_MABI) \
		SOFTFLOAT_OPTS='$(SOFTFLOAT_OPTS)'
	@touch $(UPSTREAM_LIB)

$(ABI_OBJ): src/softfloat_abi.c $(REPO_STAMP) $(UPSTREAM_BUILD)/platform.h
	@mkdir -p $(dir $@)
	@echo + CC $<
	@$(CC) -std=gnu11 $(SOFTFLOAT_OPT) -Wall -Werror -ffreestanding -fno-builtin \
		-ffunction-sections -fdata-sections $(TARGET_FLAGS) \
		$(SOFTFLOAT_INCLUDES) -c -o $@ $<

$(ARCHIVE): $(UPSTREAM_LIB) $(ABI_OBJ)
	@mkdir -p $(dir $@)
	@cp $(UPSTREAM_LIB) $@
	@$(AR) rcs $@ $(ABI_OBJ)
	@echo + AR "->" $(ARCHIVE)

archive: $(ARCHIVE)

clean:
	rm -rf $(BUILD_ROOT)

.PHONY: archive clean

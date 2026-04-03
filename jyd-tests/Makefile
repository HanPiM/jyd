ROOT_DIR := $(abspath $(dir $(lastword $(MAKEFILE_LIST))))
YEAR ?= 2026

COE_ROOT := $(ROOT_DIR)/$(YEAR)/coe
BIN_ROOT := $(ROOT_DIR)/$(YEAR)/bin
PROC_COE := $(ROOT_DIR)/proc_coe.sh
NPC_DIR := $(ROOT_DIR)/../npc

KNOWN_TARGETS := run help list
TEST_GOALS := $(filter-out $(KNOWN_TARGETS),$(MAKECMDGOALS))
TEST_NAME := $(firstword $(TEST_GOALS))

IROM_COE := $(COE_ROOT)/$(TEST_NAME)/irom.coe
DRAM_COE := $(COE_ROOT)/$(TEST_NAME)/dram.coe
IMG_BIN := $(BIN_ROOT)/$(TEST_NAME).bin
DATA_BIN := $(BIN_ROOT)/$(TEST_NAME).data.bin
DISASM_TXT := $(BIN_ROOT)/$(TEST_NAME).txt

.PHONY: run help list $(TEST_GOALS)

run: $(IMG_BIN) $(DATA_BIN) $(DISASM_TXT)
	@if [ -z "$(TEST_NAME)" ]; then \
		echo "Usage: make run <sample>"; \
		exit 1; \
	fi
	@if [ ! -f "$(IROM_COE)" ] || [ ! -f "$(DRAM_COE)" ]; then \
		echo "Error: missing coe files for sample '$(TEST_NAME)' under $(COE_ROOT)/$(TEST_NAME)"; \
		exit 1; \
	fi
	@$(MAKE) -C "$(NPC_DIR)" ARCH=riscv32-jyd sim ARGS=-b IMG="$(IMG_BIN)"

$(IMG_BIN): $(IROM_COE) $(PROC_COE)
	@mkdir -p "$(BIN_ROOT)"
	@"$(PROC_COE)" "$(IROM_COE)" "$(BIN_ROOT)"

$(DISASM_TXT): $(IMG_BIN)
	@:

$(DATA_BIN): $(DRAM_COE) $(PROC_COE)
	@mkdir -p "$(BIN_ROOT)"
	@"$(PROC_COE)" -nodisasm "$(DRAM_COE)" "$(BIN_ROOT)"
	@mv -f "$(BIN_ROOT)/dram.bin" "$(DATA_BIN)"

list:
	@find "$(COE_ROOT)" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort

help:
	@echo "Usage: make run <sample> [YEAR=2026]"
	@echo "Example: make run src0"
	@echo "Samples:"
	@$(MAKE) -s list YEAR="$(YEAR)"

$(TEST_GOALS):
	@:

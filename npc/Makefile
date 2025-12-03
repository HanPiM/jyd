BUILD_DIR = ./build
TOP_NAME = Top # case sensitive

SIM_CSRC_DIR = ./csrc
SIM_BIN = $(BUILD_DIR)/$(TOP_NAME)

SIM_IMG ?=

SAN_FLAGS?= -fsanitize=address

default: $(SIM_BIN)
	

# Chisel
CHISEL_DESIGN = design
CHISEL_SRCS = $(shell find $(abspath $(CHISEL_DESIGN)) -name "*.scala")
CHISEL_SRCS += build.mill
CHISEL2V_EMIT_DIR = $(BUILD_DIR)/chisel2v
CHISEL2V_DONE = $(BUILD_DIR)/verilog.done
$(CHISEL2V_DONE): $(CHISEL_SRCS)
	$(call git_commit, "generate verilog")
	@rm -rf $(CHISEL2V_EMIT_DIR)
	mill -i $(CHISEL_DESIGN).runMain Elaborate --target-dir $(CHISEL2V_EMIT_DIR)
	touch $(CHISEL2V_DONE)

# Verilator
VERILATOR = verilator
VERILATOR_CFLAGS += -MMD --build -cc -O3 \
										--x-assign fast --x-initial fast --noassert \
										-I$(CHISEL2V_EMIT_DIR)/verification

V2CXX_EMIT_DIR = $(BUILD_DIR)/v2cxx

SIM_VSRCS = $(shell find $(abspath $(CHISEL2V_EMIT_DIR)) -name "*.v" -or -name "*.sv")
SIM_CSRCS = $(shell find $(abspath $(SIM_CSRC_DIR)) -name "*.c" -or -name "*.cc" -or -name "*.cpp")

# sdb
INC_PATH += $(abspath ../sdb/include)
LDFLAGS += $(SAN_FLAGS) -L$(abspath ../sdb/build) -lsdb
ARCHIVES += $(NVBOARD_ARCHIVE) $(abspath ../sdb/build/libsdb.a)

# dirs
$(shell mkdir -p $(BUILD_DIR))
$(shell mkdir -p $(V2CXX_EMIT_DIR))
$(shell mkdir -p $(CHISEL2V_EMIT_DIR))


# NVBOARD
NXDC_FILES = $(addprefix $(BUILD_DIR)/,$(shell find . -maxdepth 1 -name "*.nxdc"))
$(BUILD_DIR)/%.nxdc: %.nxdc
	@sed 's/top=.*/top=$(TOP_NAME)/g' $< > $@
NVB_AUTOBIND_SRC = $(abspath $(BUILD_DIR)/auto_bind.cpp)
$(NVB_AUTOBIND_SRC): $(NXDC_FILES)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@
SIM_CSRCS += $(NVB_AUTOBIND_SRC)

# rules for NVBoard
include $(NVBOARD_HOME)/scripts/nvboard.mk

# rules for verilator
INCFLAGS = $(addprefix -I, $(INC_PATH))
CXXFLAGS += $(INCFLAGS) -DTOP_NAME=V$(TOP_NAME)
CXXFLAGS += $(SAN_FLAGS) -std=c++20

$(SIM_BIN): $(SIM_VSRCS) $(SIM_CSRCS) $(ARCHIVES) verilog
	@rm -rf $(V2CXX_EMIT_DIR)
	$(VERILATOR) $(VERILATOR_CFLAGS) \
		$(SIM_CSRCS) $(NVBOARD_ARCHIVE) \
		--top-module $(TOP_NAME) $(SIM_VSRCS) \
		$(addprefix -CFLAGS , $(CXXFLAGS)) \
		$(addprefix -LDFLAGS , $(LDFLAGS)) \
		--Mdir $(V2CXX_EMIT_DIR) --exe -o $(abspath $(SIM_BIN))

all: default

test:
	mill -i $(CHISEL_DESIGN).test

verilog: $(CHISEL2V_DONE)

help:
	mill -i $(CHISEL_DESIGN).runMain Elaborate --help

reformat:
	mill -i __.reformat

checkformat:
	mill -i __.checkFormat

bsp:
	mill -i mill.bsp.BSP/install

idea:
	mill -i mill.idea.GenIdea/idea

clean:
	-rm -rf $(BUILD_DIR)

.PHONY: test help reformat checkformat clean

ARGS?=
sim: $(SIM_BIN)
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "Run the simulation"
	@$^ $(ARGS) $(SIM_IMG)

-include ../Makefile

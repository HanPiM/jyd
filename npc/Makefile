BUILD_DIR = ./build
TOP_NAME = Top # case sensitive

SIM_CSRC_DIR = ./csrc
SIM_BIN = $(BUILD_DIR)/$(TOP_NAME)

default: $(SIM_BIN)


SIM_IMG ?=

SAN_FLAGS?= -fsanitize=address

VERILATOR_CFLAGS ?=
ENABLE_VPI ?= 1
ifeq ($(ENABLE_VPI), 1)
VERILATOR_CFLAGS += --vpi
endif

# Chisel
CHISEL_DESIGN = design
CHISEL_SRCS = $(shell find $(abspath $(CHISEL_DESIGN)) -name "*.scala")
CHISEL_SRCS += build.mill
CHISEL2V_EMIT_DIR = $(BUILD_DIR)/gened/verilog
CHISEL2V_DONE = $(BUILD_DIR)/verilog.done

# Verilator
VERILATOR = verilator
VERILATOR_CFLAGS += -MMD --build -cc -O3 \
										--x-assign fast --x-initial fast --noassert \
										-I$(CHISEL2V_EMIT_DIR)/verification

V2CXX_EMIT_DIR = $(BUILD_DIR)/gened/cxx

SIM_VSRCS = $(shell find $(abspath $(CHISEL2V_EMIT_DIR)) -name "*.v" -or -name "*.sv")
SIM_CSRCS = $(shell find $(abspath $(SIM_CSRC_DIR)) -name "*.c" -or -name "*.cc" -or -name "*.cpp")

SIM_HEADERS = $(shell find $(abspath $(SIM_CSRC_DIR)) -name "*.h" -or -name "*.hpp")

# Insert verilog public modifier for VPI
#   see https://verilator.org/guide/latest/extensions.html#cmdoption-verilator-public_-flat-flat_rd-flat_rw-_on
# to enable VPI work (make signal accessible from C++ side)

VPI_DONE := $(BUILD_DIR)/vpi.done

# use VPI_DONE as a dependency to avoid repeated insertion
# since the sed command modifies the source files
# the real modification only needs when CHISEL2V_DONE is updated
$(VPI_DONE): $(CHISEL2V_DONE)
ifeq ($(ENABLE_VPI), 1)
	@echo "VPI enabled: inserting verilator public modifier"
	$(foreach src,$(SIM_VSRCS),\
	sed -i '1i /*verilator public_on*/' $(src); \
	sed -i -e '$$a /*verilator public_off*/' $(src);)
	touch $(VPI_DONE)
endif

# sdb
INC_PATH += $(abspath ../sdb/include)
LDFLAGS += $(SAN_FLAGS) -L$(abspath ../sdb/build) -lsdb
ARCHIVES += $(NVBOARD_ARCHIVE) $(abspath ../sdb/build/libsdb.a)

# dirs
$(shell mkdir -p $(BUILD_DIR))
$(shell mkdir -p $(V2CXX_EMIT_DIR))
$(shell mkdir -p $(CHISEL2V_EMIT_DIR))


# Chisel
$(CHISEL2V_DONE): $(CHISEL_SRCS)
	$(call git_commit, "generate verilog")
	@rm -rf $(CHISEL2V_EMIT_DIR)
	mill -i $(CHISEL_DESIGN).runMain Elaborate --target-dir $(CHISEL2V_EMIT_DIR)
	touch $(CHISEL2V_DONE)


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

$(SIM_BIN): $(SIM_VSRCS) $(SIM_CSRCS) $(ARCHIVES) $(CHISEL2V_DONE) $(VPI_DONE) $(SIM_HEADERS)
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

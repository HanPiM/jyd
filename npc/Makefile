BUILD_DIR = ./build
# case sensitive
TOP_NAME = ysyxSoCFull
#ysyx_25100261

SIM_CSRC_DIR = ./csrc
VSIM_OPT?= -O3
SIM_BIN_DIR = $(BUILD_DIR)/bin
SIM_BIN = $(SIM_BIN_DIR)/$(TOP_NAME)$(VSIM_OPT)

VERILATOR = verilator

default: $(SIM_BIN)

SIM_IMG ?=

SOC_HOME ?= ../ysyxSoC

# SAN_FLAGS?=
SAN_FLAGS?= -fsanitize=address

VERILATOR_FLAGS ?=

# Performance counter need VPI to fetch handshake signals
ENABLE_VPI ?= 1

# spdlog
SPDLOG_PATH ?= /home/wuser/gitclones/spdlog
SPDLOG_LIBPATH ?= $(SPDLOG_PATH)/build
INC_PATH += $(abspath $(SPDLOG_PATH)/include)
LDFLAGS += -L$(abspath $(SPDLOG_LIBPATH)) -lspdlog
CXXFLAGS += -DSPDLOG_COMPILED_LIB

# gdbstub
GDBSTUB_PATH ?= /home/wuser/gitclones/mini-gdbstub
GDBSTUB_LIBPATH ?= $(GDBSTUB_PATH)/build
INC_PATH += $(abspath $(GDBSTUB_PATH)/include)
LDFLAGS += -L$(abspath $(GDBSTUB_LIBPATH)) -lgdbstub

VERILATOR_FLAGS += --trace-fst --trace-structs

ifeq ($(ENABLE_VPI), 1)
VERILATOR_FLAGS += --vpi
endif

# Chisel
CHISEL_DESIGN = design
CHISEL_SRCS = $(shell find $(abspath $(CHISEL_DESIGN)) -name "*.scala")
CHISEL_SRCS += build.mill
CHISEL2V_EMIT_DIR = $(BUILD_DIR)/gened/verilog
CHISEL2V_DONE = $(BUILD_DIR)/verilog.done

V2CXX_EMIT_DIR = $(BUILD_DIR)/gened/cxx

MY_VSRCS = $(shell find $(abspath $(CHISEL2V_EMIT_DIR)) -maxdepth 1 -name "*.v" -or -name "*.sv")
SIM_VSRCS = $(MY_VSRCS)
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
	$(foreach src,$(MY_VSRCS),\
	sed -i '1i /*verilator public_on*/' $(src); \
	sed -i -e '$$a /*verilator public_off*/' $(src);)
	touch $(VPI_DONE)
	@echo "VPI insertion done"
endif

# sdb
INC_PATH += $(abspath ../sdb/include)
LDFLAGS += $(SAN_FLAGS) -L$(abspath ../sdb/build) -lsdb
ARCHIVES += $(NVBOARD_ARCHIVE) $(abspath ../sdb/build/libsdb.a)


# dirs
$(shell mkdir -p $(BUILD_DIR))
$(shell mkdir -p $(SIM_BIN_DIR))
$(shell mkdir -p $(V2CXX_EMIT_DIR))
$(shell mkdir -p $(CHISEL2V_EMIT_DIR))


# Chisel
$(CHISEL2V_DONE): $(CHISEL_SRCS)
	$(call git_commit, "generate verilog")
	# @rm -rf $(CHISEL2V_EMIT_DIR)
	mill -i $(CHISEL_DESIGN).runMain Elaborate --target-dir $(CHISEL2V_EMIT_DIR)
	touch $(CHISEL2V_DONE)


# NVBOARD
NXDC_FILES = $(addprefix $(BUILD_DIR)/,$(shell find . -maxdepth 1 -name "*.nxdc"))
$(BUILD_DIR)/%.nxdc: %.nxdc
	@sed 's/top=.*/top=$(TOP_NAME)/g' $< > $@
NVB_AUTOBIND_SRC = $(abspath $(BUILD_DIR)/auto_bind.cpp)
$(NVB_AUTOBIND_SRC): $(NXDC_FILES)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@

# rules for NVBoard
SIM_CSRCS += $(NVB_AUTOBIND_SRC)
include $(NVBOARD_HOME)/scripts/nvboard.mk

# ysyxSoC
SOC_PERIP_VSRCS = $(shell find $(abspath $(SOC_HOME)/perip) -name "*.v")
SOC_GENED_FILE = $(abspath $(SOC_HOME)/build/ysyxSoCFull.v)

SIM_VSRCS += $(SOC_GENED_FILE)
SIM_VSRCS += $(SOC_PERIP_VSRCS)

VERILATOR_INCDIRS += $(abspath $(SOC_HOME)/perip/uart16550/rtl)
VERILATOR_INCDIRS += $(abspath $(SOC_HOME)/perip/spi/rtl)

VERILATOR_FLAGS += --timescale "1ns/1ns" --no-timing 
VERILATOR_FLAGS += -Wno-SYMRSVDWORD # make uart verilog happy

VERILATOR_FLAGS += -Wno-WIDTHEXPAND # make chiplink happy
# VERILATOR_FLAGS += -Wno-UNOPTFLAT
# rules for verilator

VERILATOR_FLAGS += -MMD -cc \
										--x-assign fast --x-initial fast \
										--autoflush \
										--compiler clang \
										$(addprefix -I,$(VERILATOR_INCDIRS)) \

VERILATOR_FLAGS += --hierarchical
										# --output-split 20000 

VERILATOR_CONTROL_FILE = verilator.vlt

# --noassert -O3


INCFLAGS = $(addprefix -I, $(INC_PATH))
CXXFLAGS += $(INCFLAGS) -DTOP_NAME=V$(TOP_NAME) -DENABLE_VPI=$(ENABLE_VPI)# -DENABLE_WAVE=$(ENABLE_WAVE)
CXXFLAGS += $(SAN_FLAGS) -std=c++20 -g # -fno-inline -O0

# make link faster
LDFLAGS += -fuse-ld=mold


$(SIM_BIN): $(SIM_VSRCS) $(SIM_CSRCS) $(ARCHIVES) $(CHISEL2V_DONE) $(VPI_DONE) $(SIM_HEADERS) $(VERILATOR_CONTROL_FILE)
	@rm -rf $(V2CXX_EMIT_DIR)
	@$(VERILATOR) $(VERILATOR_FLAGS) \
		$(SIM_CSRCS) $(NVBOARD_ARCHIVE) \
		--top-module $(TOP_NAME) $(VERILATOR_CONTROL_FILE) $(SIM_VSRCS) \
		$(addprefix -CFLAGS , $(CXXFLAGS)) \
		$(addprefix -LDFLAGS , $(LDFLAGS)) \
		--Mdir $(V2CXX_EMIT_DIR) --exe -o $(abspath $(SIM_BIN))
	@make -C $(V2CXX_EMIT_DIR) -f V$(TOP_NAME).mk -j$(shell nproc) OPT_FAST=$(VSIM_OPT) CXX=clang++ LINK=clang++

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

clean-bin:
	-rm -rf $(SIM_BIN_DIR)

.PHONY: test help reformat checkformat clean

ARGS?=
sim: $(SIM_BIN)
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "=== ENV ==="
	@echo "VSIM_OPT=$(VSIM_OPT)"
	@echo "ENABLE_VPI=$(ENABLE_VPI)"
	@echo "=== SIM BEGIN ==="
	@LSAN_OPTIONS=suppressions=asan_suppress.txt $^ $(ARGS) $(SIM_IMG)

-include ../Makefile

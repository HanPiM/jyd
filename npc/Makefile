BUILD_DIR = ./build
# case sensitive
CPU_DESIGN_NAME = ysyx_25100261

SIM_CSRC_DIR = ./sim
VSIM_OPT?= -O3
SIM_BIN_DIR = $(BUILD_DIR)/bin

COMMIT_HASH = $(shell git rev-parse HEAD)
CXXFLAGS += -DGIT_COMMIT_HASH=\"$(COMMIT_HASH)\"

ifeq ($(ARCH), riscv32e-ysyxsoc)
TOP_NAME = ysyxSoCFull
SIM_ARCH_SUFFIX = _soc
CXXFLAGS += -DSIM_SOC
else
TOP_NAME = $(CPU_DESIGN_NAME)
SIM_ARCH_SUFFIX = _npc
SIM_VSRCS =
endif

ALL_IN_ONE_VFILE = $(BUILD_DIR)/$(CPU_DESIGN_NAME).v

SIM_BIN = $(SIM_BIN_DIR)/$(TOP_NAME)$(SIM_ARCH_SUFFIX)$(VSIM_OPT)

VERILATOR = verilator

MILL ?= 

default: $(SIM_BIN)

ifeq ($(ARCH), riscv32e-ysyxsoc)
include ./soc.mk
endif


SIM_IMG ?=

SOC_HOME ?= ../ysyxSoC

SAN_FLAGS?=
# SAN_FLAGS?= -fsanitize=address

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

# tabulate
TABULATE_PATH ?= /home/wuser/gitclones/tabulate
INC_PATH += $(abspath $(TABULATE_PATH)/include)

# json
JSON_PATH ?= /home/wuser/gitclones/json
INC_PATH += $(abspath $(JSON_PATH)/include)

VERILATOR_FLAGS += --trace-fst --trace-structs

ifeq ($(ENABLE_VPI), 1)
VERILATOR_FLAGS += --vpi
endif

# Chisel
CHISEL_DESIGN = design
CHISEL_SRCS = $(shell find $(abspath $(CHISEL_DESIGN)) -name "*.scala")
CHISEL_SRCS += build.mill
CHISEL2V_EMIT_DIR = $(BUILD_DIR)/gened/verilog$(SIM_ARCH_SUFFIX)
CHISEL2V_DONE = $(BUILD_DIR)/verilog$(SIM_ARCH_SUFFIX).done

V2CXX_EMIT_DIR = $(BUILD_DIR)/gened/cxx
MY_VSRCS = $(addprefix $(CHISEL2V_EMIT_DIR)/,$(shell cat $(CHISEL2V_EMIT_DIR)/filelist.f))

# un synithesizable sources
#
# Layer : Verification code for assert/assume/cover... and printf ...
# 				Chisel generates these code into separate files with name like `layers-*` to
# 				make core logic synthesizable and clean
#
LAYER_VSRCS = $(shell find $(abspath $(CHISEL2V_EMIT_DIR)) -name "layers-*")
# INIT_VSRCS = $(shell find $(abspath $(CHISEL2V_EMIT_DIR)) -name "*_init.sv")
MY_VSRCS += $(LAYER_VSRCS) # $(INIT_VSRCS)

MY_VSRCS_INCDIRS = $(shell find $(abspath $(CHISEL2V_EMIT_DIR)) -type d)
VERILATOR_FLAGS += $(addprefix -I,$(MY_VSRCS_INCDIRS))

SIM_VSRCS += $(MY_VSRCS)
SIM_CSRCS += $(shell find $(abspath $(SIM_CSRC_DIR)) -name "*.c" -or -name "*.cc" -or -name "*.cpp")

SIM_HEADERS = $(shell find $(abspath $(SIM_CSRC_DIR)) -name "*.h" -or -name "*.hpp")

# Insert verilog public modifier for VPI
#   see https://verilator.org/guide/latest/extensions.html#cmdoption-verilator-public_-flat-flat_rd-flat_rw-_on
# to enable VPI work (make signal accessible from C++ side)
#
# use VPI_DONE as a dependency to avoid repeated insertion
# since the sed command modifies the source files
# the real modification only needs when CHISEL2V_DONE is updated
VPI_DONE := $(BUILD_DIR)/vpi.done
$(VPI_DONE): $(CHISEL2V_DONE)
ifeq ($(ENABLE_VPI), 1)
	$(info # Inserting verilator public modifier)
	@$(foreach src,$(MY_VSRCS),\
	sed -i '1i /*verilator public_on*/' $(src); \
	sed -i -e '$$a /*verilator public_off*/' $(src);)
	touch $@
endif


# Chisel generates macro like `layer$xxx` to control inline layer
# renaming them to `$(CPU_DESIGN_NAME)_xxx` to avoid conflict and
# make them more standard as macro names (convinient to pass to verilator)

RENAME_LAYER_MACRO := $(BUILD_DIR)/rename_layer_macro.done
$(RENAME_LAYER_MACRO): $(CHISEL2V_DONE)
	$(info # Removing unstandard layer$$ style macro)
	@$(foreach src,$(MY_VSRCS), sed -i 's/layer\$$\(\w\+\)/$(CPU_DESIGN_NAME)_\1/g' $(src);)
	touch $@

# sdb
INC_PATH += $(abspath ../sdb/include)
LDFLAGS += $(SAN_FLAGS) -L$(abspath ../sdb/build) -lsdb
ARCHIVES += $(NVBOARD_ARCHIVE) $(abspath ../sdb/build/libsdb.a)

STA_OUT_DIR = $(abspath $(BUILD_DIR)/sta)

# dirs
$(shell mkdir -p $(BUILD_DIR))
$(shell mkdir -p $(STA_OUT_DIR))
$(shell mkdir -p $(SIM_BIN_DIR))
$(shell mkdir -p $(V2CXX_EMIT_DIR))
$(shell mkdir -p $(CHISEL2V_EMIT_DIR))


# Chisel
$(CHISEL2V_DONE): $(CHISEL_SRCS)
	$(call git_commit, "generate verilog")
	#@rm -rf $(CHISEL2V_EMIT_DIR)
	$(MILL) -i $(CHISEL_DESIGN).runMain Elaborate --target-dir $(CHISEL2V_EMIT_DIR)
	touch $(CHISEL2V_DONE)

# rules for verilator

VERILATOR_FLAGS += -MMD -cc \
										--x-assign fast --x-initial fast \
										--autoflush \
										--compiler clang \
										$(addprefix -I,$(VERILATOR_INCDIRS)) \

VERILATOR_FLAGS += --hierarchical
										# --output-split 20000 

# VERILATOR_FLAGS += -threads 2
# VERILATOR_FLAGS += +define+ysyx_25100261_InlinePrintfLayer


VERILATOR_CONTROL_FILE = verilator.vlt

# --noassert -O3


INCFLAGS = $(addprefix -I, $(INC_PATH))
CXXFLAGS += $(INCFLAGS) -DTOP_NAME=V$(TOP_NAME) -DENABLE_VPI=$(ENABLE_VPI)
CXXFLAGS += $(SAN_FLAGS) -std=c++20 -g # -fno-inline

# when use perf tool, frame pointer is needed
# CXXFLAGS += -fno-omit-frame-pointer

# LDFLAGS += -fuse-ld=mold

MOLD_EXISTS := $(shell command -v mold 2> /dev/null)
ifneq ($(MOLD_EXISTS),)
# make link faster
  LDFLAGS += -fuse-ld=mold
endif

GLOBAL_MILL_EXISTS := $(shell command -v mill 2> /dev/null)
LOCAL_MILL_EXISTS := $(shell test -f ./mill && echo y)
ifeq ($(MILL),)
ifneq ($(GLOBAL_MILL_EXISTS),)
  $(info Using global Mill: $(shell which mill))
  MILL = mill
else ifeq ($(LOCAL_MILL_EXISTS), y)
  $(info Using local Mill: $(abspath ./mill))
  MILL = ./mill
else
  $(error Mill build tool not found. Please install Mill or place a local copy in the project directory.)
endif
else
  $(info Using specified Mill: $(MILL))
endif


$(SIM_BIN): $(CHISEL2V_DONE) $(VPI_DONE) $(RENAME_LAYER_MACRO) $(SIM_VSRCS) $(SIM_CSRCS) $(ARCHIVES)  $(SIM_HEADERS) $(VERILATOR_CONTROL_FILE) 
	@rm -rf $(V2CXX_EMIT_DIR)
	@echo "# Building simulation binary: $@"
	@$(VERILATOR) $(VERILATOR_FLAGS) \
		$(SIM_CSRCS) $(NVBOARD_ARCHIVE) \
		--top-module $(TOP_NAME) $(VERILATOR_CONTROL_FILE) $(SIM_VSRCS) \
		$(addprefix -CFLAGS , $(CXXFLAGS)) \
		$(addprefix -LDFLAGS , $(LDFLAGS)) \
		--Mdir $(V2CXX_EMIT_DIR) --exe -o $(abspath $(SIM_BIN))
	@make -s -C $(V2CXX_EMIT_DIR) -f V$(TOP_NAME).mk -j$(shell nproc) OPT_FAST=$(VSIM_OPT) CXX=clang++ LINK=clang++ AR="ar -T"

iverilog: $(CHISEL2V_DONE) $(RENAME_LAYER_MACRO)
	$(info process verilog files with iverilog)
	iverilog -g2012 -o $(BUILD_DIR)/iverilog_sim $(SIM_VSRCS)
	@$(BUILD_DIR)/iverilog_sim

verilog-lint: $(MY_VSRCS) 
	$(info Lint verilog files with verilator)
	@$(VERILATOR) --lint-only $(VERILATOR_FLAGS) -Wall $(MY_VSRCS)

all: default

test:
	$(MILL) -i $(CHISEL_DESIGN).test

verilog: $(CHISEL2V_DONE) $(VPI_DONE) $(RENAME_LAYER_MACRO)
	$(info Merge verilog files into one file $(ALL_IN_ONE_VFILE))
	@cat $(MY_VSRCS) > $(ALL_IN_ONE_VFILE)

help:
	$(MILL) -i $(CHISEL_DESIGN).runMain Elaborate --help

reformat:
	$(MILL) -i __.reformat

checkformat:
	$(MILL) -i __.checkFormat

bsp:
	$(MILL) -i mill.bsp.BSP/install

idea:
	$(MILL) -i mill.idea.GenIdea/idea

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

YOSYS_STA_PATH ?= /home/wuser/gitclones/yosys-sta

STA_NODPI_PATH = $(BUILD_DIR)/sta_nodpi

$(shell mkdir -p $(STA_NODPI_PATH))

STA_VSRCS = $(shell find $(abspath $(STA_NODPI_PATH)) -maxdepth 1 -name "*.v" -or -name "*.sv")


STA_DEST ?= 
ifeq ($(STA_DEST),)
  STA_DEST_FULL_NAME = $(CPU_DESIGN_NAME)
else
  STA_DEST_FULL_NAME = $(CPU_DESIGN_NAME)_$(STA_DEST)
endif

sta: $(CHISEL2V_DONE) $(STA_VSRCS)
	@echo "Removing DPI-C void functions from verilog sources..."
	./scripts/remove_dpi.sh $(CHISEL2V_EMIT_DIR) $(STA_NODPI_PATH)
	make -C $(YOSYS_STA_PATH) sta \
		DESIGN=$(STA_DEST_FULL_NAME) \
		CLK_FREQ_MHZ=2000 \
		CLK_PORT_NAME=clock \
		O=$(STA_OUT_DIR) \
		RTL_FILES="$(STA_VSRCS)" 

sta-area: $(CHISEL2V_DONE) $(STA_VSRCS)
	@echo "Removing DPI-C void functions from verilog sources..."
	./scripts/remove_dpi.sh $(CHISEL2V_EMIT_DIR) $(STA_NODPI_PATH)
	make -C $(YOSYS_STA_PATH) stat_only \
		DESIGN=$(STA_DEST_FULL_NAME) \
		CLK_FREQ_MHZ=2000 \
		CLK_PORT_NAME=clock \
		O=$(STA_OUT_DIR) \
		RTL_FILES="$(STA_VSRCS)" \

sta-all-area: $(CHISEL2V_DONE)
	./scripts/sta_all.sh $(CHISEL2V_EMIT_DIR)/filelist.f $(STA_OUT_DIR)

perf: 
	./scripts/make_perf.sh

-include ../Makefile

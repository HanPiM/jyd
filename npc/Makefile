TOPNAME = top
NXDC_FILES = constr/top.nxdc
INC_PATH ?=

VERILATOR = verilator
VERILATOR_CFLAGS += -MMD --build -cc \
				-O3 \
			   	--x-assign fast --x-initial fast --noassert

BUILD_DIR = ./build
OBJ_DIR = $(BUILD_DIR)/obj_dir
BIN = $(BUILD_DIR)/$(TOPNAME)

ARGS?=

default: $(BIN)

$(shell mkdir -p $(BUILD_DIR))
$(shell mkdir -p $(BUILD_DIR)/syn)

# constraint file
SRC_AUTO_BIND = $(abspath $(BUILD_DIR)/auto_bind.cpp)
$(SRC_AUTO_BIND): $(NXDC_FILES)
	python3 $(NVBOARD_HOME)/scripts/auto_pin_bind.py $^ $@

# project source
VSRCS = $(shell find $(abspath ./vsrc) -name "*.v")
CSRCS = $(shell find $(abspath ./csrc) -name "*.c" -or -name "*.cc" -or -name "*.cpp")
CSRCS += $(SRC_AUTO_BIND)

INCHEADERS = $(shell find $(abspath ./csrc) -name "*.h" -or -name "*.hpp")

# rules for NVBoard
include $(NVBOARD_HOME)/scripts/nvboard.mk

# rules for verilator

INC_PATH += $(abspath ../sdb/include)

INCFLAGS = $(addprefix -I, $(INC_PATH))
CXXFLAGS += $(INCFLAGS) -DTOP_NAME="\"V$(TOPNAME)\""

SAN_FLAGS?=
#SAN_FLAGS+=-fsanitize=address

CXXFLAGS += $(SAN_FLAGS) -std=c++20
LDFLAGS += $(SAN_FLAGS) -L$(abspath ../sdb/build) -lsdb
ARCHIVES += $(NVBOARD_ARCHIVE) $(abspath ../sdb/build/libsdb.a)


$(BIN): $(VSRCS) $(INCHEADERS) $(CSRCS) $(ARCHIVES)
	@rm -rf $(OBJ_DIR)
	$(VERILATOR) $(VERILATOR_CFLAGS) \
		--top-module $(TOPNAME) $(VSRCS) $(CSRCS) $(NVBOARD_ARCHIVE) \
		$(addprefix -CFLAGS , $(CXXFLAGS)) $(addprefix -LDFLAGS , $(LDFLAGS)) \
		--Mdir $(OBJ_DIR) --exe -o $(abspath $(BIN))

all: default

SIM_IMG ?=

sim: $(BIN)
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "Run the simulation."
	@$^ $(ARGS) $(SIM_IMG)
#	./obj_dir/$(TOPNAME)

clean:
	rm -rf $(BUILD_DIR)

.PHONY: default all clean run

# for PROJ_PATH: yosys-sta makefile get scripts tool from PROJ_PATH, default is pwd, which is not correct here
mysyn:
	make -f $(HOME)/yosys-sta/Makefile syn \
		-C $(BUILD_DIR)/syn \
		PROJ_PATH=$(HOME)/yosys-sta \
	   	DESIGN=$(TOPNAME) \
	   	CLK_FREQ=100 CLK_PORT_NAME=clk \
	   	O=./output \
		RTL_FILES="$(VSRCS)"

include ../Makefile

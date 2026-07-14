JYD_NEMU_HOME := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../..)

#***************************************************************************************
# Copyright (c) 2014-2024 Zihao Yu, Nanjing University
#
# NEMU is licensed under Mulan PSL v2.
# You can use this software according to the terms and conditions of the Mulan PSL v2.
# You may obtain a copy of Mulan PSL v2 at:
#          http://license.coscl.org.cn/MulanPSL2
#
# THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
# EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
# MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
#
# See the Mulan PSL v2 for more details.
#**************************************************************************************/

REPO_PATH = repo
SPIKE_JOBS ?= 4
ifeq ($(wildcard repo/spike_main),)
  $(shell git clone --depth=1 git@github.com:NJU-ProjectN/riscv-isa-sim $(REPO_PATH))
endif

REPO_BUILD_PATH = $(REPO_PATH)/build
REPO_MAKEFILE = $(REPO_BUILD_PATH)/Makefile
REPO_BITMANIP_STAMP = $(REPO_BUILD_PATH)/.bitmanip-decode
$(REPO_MAKEFILE):
	@mkdir -p $(@D)
	cd $(@D) && $(abspath $(REPO_PATH))/configure
	sed -i -e 's/-g -O2/-O2/' $@

SPIKE = $(REPO_BUILD_PATH)/spike
$(REPO_BITMANIP_STAMP): $(REPO_MAKEFILE) Makefile
	sed -i -e '/riscv_insn_ext_b) \\/d' $(abspath $(REPO_PATH))/riscv/riscv.mk.in
	sed -i -e '/riscv_insn_ext_m) \\/a\\t$$(riscv_insn_ext_b) \\' $(abspath $(REPO_PATH))/riscv/riscv.mk.in
	sed -i -e '/riscv_insn_ext_b) \\/d' $(REPO_BUILD_PATH)/riscv.mk
	sed -i -e '/riscv_insn_ext_m) \\/a\\t$$(riscv_insn_ext_b) \\' $(REPO_BUILD_PATH)/riscv.mk
	rm -f $(REPO_BUILD_PATH)/insn_list.h
	touch $@

$(SPIKE): $(REPO_BITMANIP_STAMP) | $(REPO_MAKEFILE)
	CFLAGS="-fvisibility=hidden" CXXFLAGS="-fvisibility=hidden" $(MAKE) -j$(SPIKE_JOBS) -C $(REPO_BUILD_PATH)
	test -f $(SPIKE)
	touch $(REPO_BITMANIP_STAMP) $(SPIKE)

BUILD_DIR = ./build
$(shell mkdir -p $(BUILD_DIR))

inc_dependencies = fesvr riscv disasm customext fdt softfloat spike_main spike_dasm build
INC_PATH  = -I$(REPO_PATH) $(addprefix -I$(REPO_PATH)/, $(inc_dependencies))
INC_PATH += -I$(JYD_NEMU_HOME)/include
lib_dependencies = libspike_main.a libriscv.a libdisasm.a libsoftfloat.a libfesvr.a libfdt.a
INC_LIBS  = $(addprefix $(REPO_PATH)/build/, $(lib_dependencies))

NAME = $(GUEST_ISA)-spike-so
BINARY = $(BUILD_DIR)/$(NAME)
SRCS = difftest.cc

$(BINARY): $(SPIKE) $(SRCS)
	g++ -std=c++17 -O2 -shared -fPIC -fvisibility=hidden $(INC_PATH) $(SRCS) $(INC_LIBS) -o $@

clean:
	rm -rf $(BUILD_DIR)

all: $(BINARY)
.DEFAULT_GOAL = all

.PHONY: all clean

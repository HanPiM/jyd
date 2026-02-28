CLANG_VERSION_MAJOR := $(shell clang -dumpversion | cut -f1 -d.)
CLANG_VERSION_OLDER_THAN_15 := $(shell [ $(CLANG_VERSION_MAJOR) -lt 15 ] && echo 1 || echo 0)

LLVM21_INSTALLED = ./llvm21_installed.done
$(LLVM21_INSTALLED):
	$(info # Downloading LLVM script)
	wget 'https://apt.llvm.org/llvm.sh' && chmod +x llvm.sh
	$(info # Installing LLVM 21)
	sudo ./llvm.sh 21
	$(info # Setting LLVM 21 as default clang)
	sudo update-alternatives --install /usr/bin/clang clang /usr/bin/clang-21 100
	sudo update-alternatives --install /usr/bin/clang++ clang++ /usr/bin/clang++-21 100
	touch $@

foo.mk: $(LLVM21_INSTALLED)
	touch $@

include foo.mk

ifeq ($(CLANG_VERSION_OLDER_THAN_15), 1)
# older clang versions contains csr and fence.i extensions in the rv32i/e base
# RISCV_MARCH_EXT_CSRS_AND_FENCE_I :=
# COMMON_CFLAGS += -Wno-unused-command-line-argument
$(info fuck clang $(CLANG_VERSION_MAJOR))
$(info Try clang 21!)
else
endif

# sdb
INC_PATH += $(abspath ../sdb/include)
LDFLAGS += $(SAN_FLAGS) -L$(abspath ../sdb/build) -lsdb
ARCHIVES += $(NVBOARD_ARCHIVE) $(abspath ../sdb/build/libsdb.a)

SDB_BUILD_LIB = $(abspath ../sdb/build/libsdb.a)
$(SDB_BUILD_LIB):
	@make -C ../sdb

DEPS_DIR = ./deps

# spdlog
SPDLOG_PATH ?= $(DEPS_DIR)/spdlog
SPDLOG_LIBPATH ?= $(SPDLOG_PATH)/build
INC_PATH += $(abspath $(SPDLOG_PATH)/include)
LDFLAGS += -L$(abspath $(SPDLOG_LIBPATH)) -lspdlog
CXXFLAGS += -DSPDLOG_COMPILED_LIB

# gdbstub
GDBSTUB_PATH ?= $(DEPS_DIR)/mini-gdbstub
GDBSTUB_LIBPATH ?= $(GDBSTUB_PATH)/build
INC_PATH += $(abspath $(GDBSTUB_PATH)/include)
LDFLAGS += -L$(abspath $(GDBSTUB_LIBPATH)) -lgdbstub

# tabulate
TABULATE_PATH ?= $(DEPS_DIR)/tabulate
INC_PATH += $(abspath $(TABULATE_PATH)/include)

# json
JSON_PATH ?= $(DEPS_DIR)/json
INC_PATH += $(abspath $(JSON_PATH)/include)

SIM_DEP_LIBS_CLONE_DONE = $(DEPS_DIR)/clone.done
$(SIM_DEP_LIBS_CLONE_DONE):
	@mkdir -p $(DEPS_DIR)
	@./scripts/dev-init/clone_deps.sh $(DEPS_DIR)
	@touch $@

SIM_DEP_LIBS_BUILD_DONE = $(DEPS_DIR)/build.done
$(SIM_DEP_LIBS_BUILD_DONE): $(SIM_DEP_LIBS_CLONE_DONE) $(SDB_BUILD_LIB)
	@./scripts/dev-init/build_deps.sh $(DEPS_DIR)
	@touch $@

sim-bin-deps: $(SIM_DEP_LIBS_CLONE_DONE) $(SIM_DEP_LIBS_BUILD_DONE)



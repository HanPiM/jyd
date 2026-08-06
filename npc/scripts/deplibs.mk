# include $(JYD_HOME)/init-deps/always-install-llvm.mk

# sdb
LLVM_CONFIG ?= llvm-config
ifeq ($(shell command -v $(LLVM_CONFIG) 2>/dev/null),)
  $(error llvm-config not found. Please install llvm-devel or set LLVM_CONFIG=/path/to/llvm-config)
endif
INC_PATH += $(abspath ../sdb/include)
LDFLAGS += $(SAN_FLAGS) -L$(abspath ../sdb/build) -lsdb
LDFLAGS += $(shell $(LLVM_CONFIG) --ldflags --libs --system-libs mcdisassembler riscv)
ARCHIVES += $(NVBOARD_ARCHIVE) $(abspath ../sdb/build/libsdb.a)

SDB_BUILD_LIB = $(abspath ../sdb/build/libsdb.a)
$(SDB_BUILD_LIB):
	$(JYD_HOME)/init-deps/check-and-install-buildtools.sh
	@mkdir -p $(dir $@)
	@flock $@.lock -c '\
		if [ ! -f $(SDB_BUILD_LIB) ]; then \
			$(MAKE) -C ../sdb; \
		fi'

DEPS_DIR = ./deps

# spdlog
SPDLOG_PATH ?= $(DEPS_DIR)/spdlog
SPDLOG_LIBPATH ?= $(SPDLOG_PATH)/build
INC_PATH += $(abspath $(SPDLOG_PATH)/include)
LDFLAGS += -L$(abspath $(SPDLOG_LIBPATH)) -lspdlog
CXXFLAGS += -DSPDLOG_COMPILED_LIB
# KonataLog.o calls fmt directly, and the shared spdlog used here is the
# linuxbrew build whose fmt dependency is only a DT_NEEDED entry.  Newer
# binutils ld no longer copies dt-needed entries, so link libfmt explicitly
# from the same prefix as libspdlog.
SPDLOG_SO := $(realpath $(SPDLOG_LIBPATH)/libspdlog.so)
ifneq ($(SPDLOG_SO),)
FMT_LIBDIR := $(dir $(SPDLOG_SO))
ifeq ($(wildcard $(FMT_LIBDIR)libfmt.so*),)
# libspdlog here is a linuxbrew Cellar symlink; libfmt lives in the same
# linuxbrew prefix's top-level lib directory.
FMT_LIBDIR := $(abspath $(FMT_LIBDIR)/../../../..)/lib/
endif
ifneq ($(wildcard $(FMT_LIBDIR)libfmt.so*),)
LDFLAGS += -L$(FMT_LIBDIR) -lfmt
endif
endif

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
$(SIM_DEP_LIBS_BUILD_DONE): $(SDB_BUILD_LIB) $(SIM_DEP_LIBS_CLONE_DONE) 
	@+./scripts/dev-init/build_deps.sh $(DEPS_DIR)
	@touch $@

sim-bin-deps: $(SIM_DEP_LIBS_CLONE_DONE) $(SIM_DEP_LIBS_BUILD_DONE)

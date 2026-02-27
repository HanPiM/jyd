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

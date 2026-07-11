CXX_SRC = $(wildcard src/*.cpp)
C_SRC = $(wildcard src/*.c)

BUILD_DIR = ./build

CXX_OBJ = $(CXX_SRC:src/%.cpp=$(BUILD_DIR)/%.o)
C_OBJ = $(C_SRC:src/%.c=$(BUILD_DIR)/%.o)
OBJS = $(CXX_OBJ) $(C_OBJ)
DEPS = $(OBJS:.o=.d)

INC_PATH = ./include ./src

LLVM_CONFIG ?= llvm-config
LLVM_CONFIG_EXISTS := $(shell command -v $(LLVM_CONFIG) 2>/dev/null)
ifeq ($(LLVM_CONFIG_EXISTS),)
$(error llvm-config not found. Please install llvm-devel or set LLVM_CONFIG=/path/to/llvm-config)
endif
LLVM_CXXFLAGS := $(patsubst -I%,-isystem %,$(filter-out -std=% -fno-exceptions,$(shell $(LLVM_CONFIG) --cxxflags)))

INCLUDES = $(foreach dir,$(INC_PATH),-I$(dir))

CC = clang
CXX = clang++
AR = ar

COMMON_FLAGS = -fPIC -MMD -MP -Wall -Wextra -O3 -g $(INCLUDES) $(LLVM_CXXFLAGS)
CFLAGS = $(COMMON_FLAGS)
CXXFLAGS = $(COMMON_FLAGS) -std=c++20

TARGET = $(BUILD_DIR)/libsdb.a
all: $(BUILD_DIR) $(TARGET)

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

$(TARGET): $(OBJS)
	$(AR) rcs $@ $(OBJS)

$(BUILD_DIR)/%.o: src/%.c
	$(CC) $(CFLAGS) -c $< -o $@

$(BUILD_DIR)/%.o: src/%.cpp
	$(CXX) $(CXXFLAGS) -c $< -o $@

-include $(DEPS)

clean:
	rm -f $(BUILD_DIR)/*

.PHONY: all clean

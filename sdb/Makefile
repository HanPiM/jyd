CXX_SRC = $(wildcard src/*.cpp)
C_SRC = $(wildcard src/*.c)

BUILD_DIR = ./build

CXX_OBJ = $(CXX_SRC:src/%.cpp=$(BUILD_DIR)/%.o)
C_OBJ = $(C_SRC:src/%.c=$(BUILD_DIR)/%.o)
OBJS = $(CXX_OBJ) $(C_OBJ)

INC_PATH = ./include ./src

LIBCAPSTONE = tools/capstone/repo/libcapstone.so.5
INC_PATH += $(abspath tools/capstone/repo/include)

INCLUDES = $(foreach dir,$(INC_PATH),-I$(dir))

CC = gcc
CXX = g++
AR = ar rcs

CFLAGS = -Wall -Wextra -O2 -g
CXXFLAGS = -Wall -Wextra -O2 -g -std=c++20

CXXFLAGS += $(INCLUDES)
CFLAGS += $(INCLUDES)

TARGET = $(BUILD_DIR)/libsdb.a
all: $(BUILD_DIR) $(TARGET)

src/disasm.c: $(LIBCAPSTONE)

$(LIBCAPSTONE):
	$(MAKE) -C tools/capstone

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

$(TARGET): $(OBJS)
	$(AR) $@ $^

$(BUILD_DIR)/%.o: src/%.c
	$(CC) $(CFLAGS) -c $< -o $@

$(BUILD_DIR)/%.o: src/%.cpp
	$(CXX) $(CXXFLAGS) -c $< -o $@

clean:
	rm -f $(BUILD_DIR)/*

.PHONY: all clean

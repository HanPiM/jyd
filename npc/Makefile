all:
	@echo "Write this Makefile by your self."

CSRC_DIR := ./csrc
VSRC_DIR := ./vsrc

CPP_SRCS := $(shell find $(CSRC_DIR) -type f -name "*.cpp")
V_SRCS   := $(shell find $(VSRC_DIR) -type f -name "*.v")

sim:
	$(call git_commit, "sim RTL") # DO NOT REMOVE THIS LINE!!!
	@echo "Write this Makefile by your self."

	verilator --cc --exe --build --trace -j 0 -Wall $(CPP_SRCS) $(V_SRCS)
	./obj_dir/Vtop

include ../Makefile

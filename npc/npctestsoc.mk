# npctestsoc

TOP_NAME = NPCTestSoC

SOC_HOME = $(BUILD_DIR)/npctestsoc

SOC_LAYER_VSRCS = $(shell find $(abspath $(SOC_HOME)) -name "layers-*")
SOC_VSRCS = $(call rd_filelist_indir, $(SOC_HOME))

MY_VSRCS += $(SOC_VSRCS)
MY_VSRCS += $(SOC_LAYER_VSRCS)

VERILATOR_INCDIRS += $(shell find $(abspath $(SOC_HOME)) -type d)
RESET_PC = 80000000

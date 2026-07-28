REPO_URL := https://github.com/ucb-bar/berkeley-softfloat-3.git
REPO_COMMIT := a0c6494cdc11865811dec815d5c0049fba9d82a8
REPO_DIR := repo
BUILD_DIR := $(REPO_DIR)/build/Linux-x86_64-GCC
LIB := $(BUILD_DIR)/softfloat.a
STAMP := $(REPO_DIR)/.jyd-commit-$(REPO_COMMIT)
SOFTFLOAT_OPTS := -DSOFTFLOAT_ROUND_ODD -DINLINE_LEVEL=5 \
	-DSOFTFLOAT_FAST_DIV32TO16 -DSOFTFLOAT_FAST_DIV64TO32 -fPIC

all: $(LIB)

$(STAMP):
	@if [ ! -d $(REPO_DIR)/.git ]; then \
		git clone --no-checkout $(REPO_URL) $(REPO_DIR); \
	fi
	git -C $(REPO_DIR) fetch --depth=1 origin $(REPO_COMMIT)
	git -C $(REPO_DIR) checkout --detach $(REPO_COMMIT)
	touch $@

$(LIB): $(STAMP) Makefile
	$(MAKE) -C $(BUILD_DIR) clean
	$(MAKE) -C $(BUILD_DIR) SPECIALIZE_TYPE=RISCV \
		SOFTFLOAT_OPTS="$(SOFTFLOAT_OPTS)"

clean:
	@if [ -d $(BUILD_DIR) ]; then $(MAKE) -C $(BUILD_DIR) clean; fi

.PHONY: all clean

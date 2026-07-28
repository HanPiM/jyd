TEX_SRC := src/main.tex
BUILD_DIR := build
OUT_DIR := output
BUILD_PDF := $(BUILD_DIR)/main.pdf
OUT_PDF := $(OUT_DIR)/后续优化说明报告.pdf
LATEX := xelatex
LATEX_FLAGS := -interaction=nonstopmode -halt-on-error -file-line-error -output-directory=$(BUILD_DIR)

.PHONY: all pdf clean

all: pdf

pdf: $(OUT_PDF)

$(OUT_PDF): $(TEX_SRC)
	mkdir -p $(BUILD_DIR) $(OUT_DIR)
	$(LATEX) $(LATEX_FLAGS) $(TEX_SRC)
	$(LATEX) $(LATEX_FLAGS) $(TEX_SRC)
	cp $(BUILD_PDF) $(OUT_PDF)

clean:
	rm -rf $(BUILD_DIR) $(OUT_DIR)

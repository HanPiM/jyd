TEX_SRC := src/main.tex
SRC_DIR := src
ASSET_DIR := $(SRC_DIR)/assets
ASSET_FILES := $(shell find "$(ASSET_DIR)" -type f ! -name '*:Zone.Identifier' 2>/dev/null)
BUILD_DIR := build
OUT_DIR := output
OUT_PDF := $(OUT_DIR)/竞业达技术文档.pdf
OVERLEAF_ZIP := $(OUT_DIR)/overleaf.zip
OVERLEAF_STAGING := $(BUILD_DIR)/overleaf
BUILD_PDF := $(BUILD_DIR)/main.pdf
LATEX := xelatex
LATEX_FLAGS := -interaction=nonstopmode -halt-on-error -file-line-error -output-directory=$(BUILD_DIR)

.PHONY: all pdf overleaf clean regenerate

all: pdf

pdf: $(OUT_PDF)

overleaf: $(OVERLEAF_ZIP)

regenerate:
	python3 scripts/merge_reports.py

$(OUT_PDF): $(TEX_SRC) $(ASSET_FILES)
	mkdir -p $(BUILD_DIR) $(OUT_DIR)
	$(LATEX) $(LATEX_FLAGS) $(TEX_SRC)
	$(LATEX) $(LATEX_FLAGS) $(TEX_SRC)
	$(LATEX) $(LATEX_FLAGS) $(TEX_SRC)
	cp $(BUILD_PDF) $(OUT_PDF)

$(OVERLEAF_ZIP): $(TEX_SRC) $(ASSET_FILES)
	rm -rf "$(OVERLEAF_STAGING)"
	mkdir -p "$(OVERLEAF_STAGING)" "$(OUT_DIR)"
	cp "$(TEX_SRC)" "$(OVERLEAF_STAGING)/main.tex"
	cp -R "$(ASSET_DIR)" "$(OVERLEAF_STAGING)/assets"
	find "$(OVERLEAF_STAGING)" \( -name '*.aux' -o -name '*.log' -o -name '*.out' -o -name '*.toc' \) -delete
	rm -f "$(OVERLEAF_ZIP)"
	cd "$(OVERLEAF_STAGING)" && zip -r "../../$(OVERLEAF_ZIP)" .

clean:
	rm -rf $(BUILD_DIR) $(OUT_DIR)

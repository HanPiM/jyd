DOCX := 竞业达测评报告.docx
TEX_SRC := src/main.tex
SRC_DIR := src
ASSET_DIR := $(SRC_DIR)/assets
ASSET_FILES := $(shell find "$(ASSET_DIR)" -type f ! -name '*:Zone.Identifier' ! -name '*.TMP' 2>/dev/null)
BUILD_DIR := build
OUT_DIR := output
OUT_PDF := $(OUT_DIR)/竞业达测评报告.pdf
OVERLEAF_ZIP := $(OUT_DIR)/overleaf.zip
OVERLEAF_STAGING := $(BUILD_DIR)/overleaf
BUILD_PDF := $(BUILD_DIR)/main.pdf
BUILD_DOCX := $(BUILD_DIR)/main.docx
LATEX := xelatex
LATEX_FLAGS := -interaction=nonstopmode -halt-on-error -file-line-error -output-directory=$(BUILD_DIR)

.PHONY: all pdf docx overleaf clean assets tex

all: pdf

pdf: $(OUT_PDF)

docx: $(BUILD_DOCX)

overleaf: $(OVERLEAF_ZIP)

$(OUT_PDF): $(TEX_SRC)
	mkdir -p $(BUILD_DIR) $(OUT_DIR)
	$(LATEX) $(LATEX_FLAGS) $(TEX_SRC)
	$(LATEX) $(LATEX_FLAGS) $(TEX_SRC)
	cp $(BUILD_PDF) $(OUT_PDF)

$(BUILD_DOCX): $(TEX_SRC)
	mkdir -p $(BUILD_DIR)
	pandoc "$(TEX_SRC)" --resource-path="$(SRC_DIR):$(ASSET_DIR):." -o "$(BUILD_DOCX)"

$(OVERLEAF_ZIP): $(TEX_SRC) $(ASSET_FILES)
	rm -rf "$(OVERLEAF_STAGING)"
	mkdir -p "$(OVERLEAF_STAGING)" "$(OUT_DIR)"
	cp "$(TEX_SRC)" "$(OVERLEAF_STAGING)/main.tex"
	cp -R "$(ASSET_DIR)" "$(OVERLEAF_STAGING)/assets"
	find "$(OVERLEAF_STAGING)" \( -name '*.aux' -o -name '*.log' -o -name '*.out' -o -name '*.toc' -o -name '*:Zone.Identifier' -o -name '*.TMP' \) -delete
	rm -f "$(OVERLEAF_ZIP)"
	cd "$(OVERLEAF_STAGING)" && zip -r "../../$(OVERLEAF_ZIP)" .

assets:
	mkdir -p $(ASSET_DIR)
	unzip -j -o "$(DOCX)" "word/media/*" -d "$(ASSET_DIR)"
	find "$(ASSET_DIR)" -name '*:Zone.Identifier' -delete
	find "$(ASSET_DIR)" -name '*.TMP' -delete

tex: $(TEX_SRC)
	@echo "LaTeX source is ready at $(TEX_SRC)"

clean:
	rm -rf $(BUILD_DIR)/* $(OUT_DIR)/*

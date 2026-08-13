TEX_SRC := src/main.tex
CHAPTERS := $(wildcard src/chapters/*.tex)
TIKZ_SOURCES := $(wildcard src/tikz/*.tex)
ASSETS := $(shell find src/assets -type f 2>/dev/null)
DATA := $(shell find src/data -type f 2>/dev/null)
BUILD_DIR := build
OUTPUT_DIR := output
PDF := $(OUTPUT_DIR)/竞业达总决赛技术文档.pdf
OVERLEAF := $(OUTPUT_DIR)/overleaf.zip
LATEX := xelatex
FLAGS := -interaction=nonstopmode -halt-on-error -file-line-error -output-directory=$(BUILD_DIR)

.PHONY: all pdf overleaf audit clean

all: pdf audit

pdf: $(PDF)

$(PDF): $(TEX_SRC) $(CHAPTERS) $(TIKZ_SOURCES) $(ASSETS)
	mkdir -p $(BUILD_DIR) $(OUTPUT_DIR)
	$(LATEX) $(FLAGS) $(TEX_SRC)
	$(LATEX) $(FLAGS) $(TEX_SRC)
	$(LATEX) $(FLAGS) $(TEX_SRC)
	cp $(BUILD_DIR)/main.pdf "$@"

overleaf: $(OVERLEAF)

$(OVERLEAF): $(TEX_SRC) $(CHAPTERS) $(TIKZ_SOURCES) $(ASSETS) $(DATA)
	rm -rf $(BUILD_DIR)/overleaf
	mkdir -p $(BUILD_DIR)/overleaf/src $(OUTPUT_DIR)
	cp src/main.tex $(BUILD_DIR)/overleaf/main.tex
	cp -R src/chapters src/tikz src/assets src/data $(BUILD_DIR)/overleaf/src/
	cd $(BUILD_DIR)/overleaf && zip -qr ../../output/overleaf.zip .

audit:
	python3 scripts/audit_document.py src

clean:
	rm -rf $(BUILD_DIR) $(OUTPUT_DIR)

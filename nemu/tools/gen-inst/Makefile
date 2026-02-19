REPO_PATH = repo
ifeq ($(wildcard $(REPO_PATH)/Makefile),)
  $(shell git clone --depth=1 https://github.com/WangYiJie020/ysyx-workbench-nemu-inst-gen.git $(REPO_PATH))
endif

VENV_PATH = $(abspath $(REPO_PATH)/.venv)
VENV_CREATED = $(VENV_PATH)/bin/activate
VENV_DONE = ./venv.done

default: $(VENV_DONE)
	$(MAKE) -C $(REPO_PATH) VENV=$(VENV_PATH)

$(VENV_CREATED):
	$(shell python3 -m venv $(VENV_PATH))

$(VENV_DONE): $(VENV_CREATED)
	$(VENV_PATH)/bin/pip install -r $(REPO_PATH)/requirements.txt
	touch $@

clean:
	$(MAKE) -C $(REPO_PATH) clean

.PHONY: default clean

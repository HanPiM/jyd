AM_SRCS := riscv/jyd/start.S \
           riscv/jyd/trm.c \
           riscv/npc/ioe.c \
           riscv/jyd/timer.c \
           riscv/npc/input.c \
           riscv/npc/cte.c \
           riscv/npc/trap.S 
           # platform/dummy/vme.c \
           # platform/dummy/mpe.c

CFLAGS += -g

CFLAGS    += -fdata-sections -ffunction-sections
LDSCRIPTS += $(JYD_AM_HOME)/am/src/riscv/jyd/linker.ld

MAINARGS_MAX_LEN = 64
MAINARGS_PLACEHOLDER = the_insert-arg_rule_in_Makefile_will_insert_mainargs_here
CFLAGS += -DMAINARGS_MAX_LEN=$(MAINARGS_MAX_LEN) -DMAINARGS_PLACEHOLDER=$(MAINARGS_PLACEHOLDER)

insert-arg: image
	@python $(JYD_AM_HOME)/tools/insert-arg.py $(IMAGE).data.bin $(MAINARGS_MAX_LEN) $(MAINARGS_PLACEHOLDER) "$(mainargs)"

IMG_DATA_COE = $(IMAGE_REL).data.coe

image: image-dep
	@$(OBJDUMP) -d $(IMAGE).elf > $(IMAGE).txt
	@echo + OBJCOPY "->" $(IMAGE_REL).bin without .data section
	@$(OBJCOPY) -S -R .data -R .data.extra -R .rodata -O binary $(IMAGE).elf $(IMAGE).bin
	@echo + Extract .data section "->" $(IMAGE_REL).data.bin
	@$(OBJCOPY) -j .data -j .data.extra -j .rodata -O binary $(IMAGE).elf $(IMAGE_REL).data.bin
	@echo + Convert .data section "->" $(IMG_DATA_COE)
	@echo "memory_initialization_radix=16;" > $(IMG_DATA_COE)
	@echo "memory_initialization_vector=" >> $(IMG_DATA_COE)
	@# 处理二进制数据：
	@# -v: 显示所有数据（不压缩重复行）
	@# -e: 定义输出格式，每4个字节转为8位16进制，加逗号和换行
	@hexdump -v -e '1/4 "%08X" ",\n"' $(IMAGE_REL).data.bin >> $(IMG_DATA_COE)
	@# 注意：最后一行需要以分号结尾，手动或用 sed 修正最后一行
	@sed -i '$$s/,/;/' $(IMG_DATA_COE)
	@echo + Convert .text section "->" $(IMAGE_REL).text.coe
	@echo "memory_initialization_radix=16;" > $(IMAGE_REL).text.coe
	@echo "memory_initialization_vector=" >> $(IMAGE_REL).text.coe
	@hexdump -v -e '1/4 "%08X" ",\n"' $(IMAGE_REL).bin >> $(IMAGE_REL).text.coe
	@sed -i '$$s/,/;/' $(IMAGE_REL).text.coe


ifeq ($(VSIM_iverilog),1)
  SIM_TARGET = sim-iverilog
  ifeq ($(VSIM_netlist),1)
    SIM_TARGET = sim-iverilog-netlist
  endif
else
  SIM_TARGET = sim ARGS='-b'
endif

run: insert-arg
	@$(MAKE) -C $(JYD_NPC_HOME) $(SIM_TARGET) IMG=$(IMAGE).bin

.PHONY: insert-arg

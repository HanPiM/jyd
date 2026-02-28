CROSS_COMPILE := riscv64-linux-gnu-
COMMON_CFLAGS := -fno-pic -march=rv64g -mcmodel=medany -mstrict-align
CFLAGS        += $(COMMON_CFLAGS) -static
ASFLAGS       += $(COMMON_CFLAGS) -O0 -g
LDFLAGS       += -melf64lriscv

RISCV_MARCH_EXT_CSRS_AND_FENCE_I := _zicsr_zifencei
	
# overwrite ARCH_H defined in $(AM_HOME)/Makefile
ARCH_H := arch/riscv.h

CROSS_COMPILE := riscv64-linux-gnu-
COMMON_CFLAGS := -fno-pic -march=rv64g -mcmodel=medany -mstrict-align
CFLAGS        += $(COMMON_CFLAGS) -static
ASFLAGS       += $(COMMON_CFLAGS) -O0 -g
LDFLAGS       += -melf64lriscv

RISCV_MARCH_EXT_CSRS_AND_FENCE_I := _zicsr_zifencei

CLANG_VERSION_MAJOR := $(shell $(CC) -dumpversion | cut -f1 -d.)
CLANG_VERSION_OLDER_THAN_15 := $(shell [ $(CLANG_VERSION_MAJOR) -lt 15 ] && echo 1 || echo 0)

$(info Clang version $(CLANG_VERSION_MAJOR))
ifeq ($(CLANG_VERSION_OLDER_THAN_15),1)
$(error $(CC) version must be at least 15)
endif

# overwrite ARCH_H defined in $(AM_HOME)/Makefile
ARCH_H := arch/riscv.h

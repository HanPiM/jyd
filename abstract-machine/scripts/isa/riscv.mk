CROSS_COMPILE := riscv64-linux-gnu-
COMMON_CFLAGS := -fno-pic -march=rv64g -mcmodel=medany -mstrict-align
CFLAGS        += $(COMMON_CFLAGS) -static
ASFLAGS       += $(COMMON_CFLAGS) -O0 -g
LDFLAGS       += -melf64lriscv

CLANG_VERSION_MAJOR := $(shell clang -dumpversion | cut -f1 -d.)
CLANG_VERSION_OLDER_THAN_15 := $(shell [ $(CLANG_VERSION_MAJOR) -lt 15 ] && echo 1 || echo 0)

ifeq ($(CLANG_VERSION_OLDER_THAN_15), 1)
# older clang versions contains csr and fence.i extensions in the rv32i/e base
# RISCV_MARCH_EXT_CSRS_AND_FENCE_I :=
# COMMON_CFLAGS += -Wno-unused-command-line-argument
$(info fuck clang $(CLANG_VERSION_MAJOR))
$(info Try clang 21!)
$(shell wget 'https://apt.llvm.org/llvm.sh' && chmod +x llvm.sh)
$(shell sudo ./llvm.sh 21)
$(shell sudo update-alternatives --install /usr/bin/clang clang /usr/bin/clang-21 100 --slave /usr/bin/clang++ clang++ /usr/bin/clang++-21)
else
endif
RISCV_MARCH_EXT_CSRS_AND_FENCE_I := _zicsr_zifencei
	
# overwrite ARCH_H defined in $(AM_HOME)/Makefile
ARCH_H := arch/riscv.h

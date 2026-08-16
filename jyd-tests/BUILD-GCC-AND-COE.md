# 从全新环境构建 GCC 与生成 COE

本文说明如何从固定 GCC 16 基线构建 JYD 交叉编译器，并用同一套
CoreMark 默认选项生成独立 CoreMark 与 RT-Thread Nano 3.1.5 镜像。
RT-Thread 内核仍使用 `rv32im_zicsr`；共享选项只作用于其内嵌的
CoreMark 源文件。

## 0. 使用预编译 GCC 快速复核

提交材料同时提供独立的 `finals-coe-repro/` 目录。该目录携带预编译
GCC 16、目标 binutils、运行库、AM、SoftFloat 和两套程序源码，不要求
固定放置位置。在其根目录执行：

```sh
./build-coe.sh all
```

脚本先校验包内工具链，再从空 `out/` 生成两套 COE。已验证结果位于
`reference/`，可用其中的 `SHA256SUMS` 交叉核对。后续章节用于从 GCC
源码完整重建工具链，属于更严格但耗时更长的复核路径。

### 复核包导出约定

协作中提到“导出评委复核版本”时，固定指就地更新
`~/jyd/finals-coe-repro/`，而不是另建工作树或任意命名的临时目录。导出
以当前签出的提交分支为准，同步 AM、SoftFloat、独立 CoreMark、
RT-Thread Nano 3.1.5、预编译工具链和参考产物中发生变化的部分。

复核包必须可整体移动或重命名，脚本不得依赖仓库路径或用户主目录。
交付时 `out/` 保持为空，已验证产物只放在 `reference/`，并更新相应的
`SHA256SUMS`。导出完成前，需要在另一个目录名下以干净环境执行
`./build-coe.sh all`，通过后端审计，并逐字节核对两套镜像的四个 COE
文件与 `reference/` 一致。

## 1. 主机依赖

建议使用 64 位 Linux。Debian/Ubuntu 可安装：

```sh
sudo apt update
sudo apt install -y build-essential git python3 gawk bison flex texinfo \
  pkg-config libgmp-dev libmpfr-dev libmpc-dev zlib1g-dev libisl-dev \
  gcc-riscv64-linux-gnu binutils-riscv64-linux-gnu
```

主机需要 GNU Make、`hexdump`、`sed` 和 `md5sum`。构建过程需要约
20 GiB 临时空间；GCC 下载和首次获取 Berkeley SoftFloat 时需要网络。

## 2. 构建固定 GCC

从仓库根目录执行：

```sh
export JYD_ROOT=$PWD
export JYD_GCC=$PWD/out/jyd-gcc16
mkdir -p "$PWD/out"
./jyd-tests/coremark-official/accel/gcc-md/build-md-gcc.sh "$JYD_GCC"
```

脚本固定 GCC 基线提交
`ff20c357b3f62d4ffa76a74ce21fc49b640d61e6`，以严格 index 检查应用
仓库内的 `active-accel-gcc16.patch`，仅构建 C 编译器。补丁包含原先
只存在于本机提交的循环边界分析前置修复。补丁 SHA-256 应为：

```text
accc713e001e15a736172596863629c15ccc94bbd127b46f1593b3fbfa49b8c1
```

检查版本和后端完整性：

```sh
"$JYD_GCC/bin/riscv64-unknown-linux-gnu-gcc" --version
./jyd-tests/coremark-official/accel/gcc-md/check-backend-integrity.sh
./jyd-tests/coremark-official/accel/gcc-md/check-xdup8lo.sh \
  "$JYD_GCC/bin/riscv64-unknown-linux-gnu-gcc"
./jyd-tests/coremark-official/accel/gcc-md/check-xpaddh2.sh \
  "$JYD_GCC/bin/riscv64-unknown-linux-gnu-gcc"
./jyd-tests/coremark-official/accel/gcc-md/check-xdfascan.sh \
  "$JYD_GCC/bin/riscv64-unknown-linux-gnu-gcc"
./jyd-tests/coremark-official/accel/gcc-md/check-xlistfind-xmacacc.sh \
  "$JYD_GCC/bin/riscv64-unknown-linux-gnu-gcc"
./jyd-tests/coremark-official/accel/gcc-md/check-xmbm-xdfa4p.sh \
  "$JYD_GCC/bin/riscv64-unknown-linux-gnu-gcc"
```

这些形状检查包含重命名正例和近似形状负例；改名后仍须生成相同
指令，以验证选择依据是控制流和数据流形状，而不是函数符号。

## 3. 生成 CoreMark COE

```sh
export JYD_CROSS="$JYD_GCC/bin/riscv64-unknown-linux-gnu-"
make -C jyd-tests/coremark-official ARCH=riscv32-jyd \
  CROSS_COMPILE="$JYD_CROSS" \
  BUILD_DIR=../../out/coremark-official image audit-accel
```

主要产物为：

```text
out/coremark-official/coremark-official-riscv32-jyd.text.coe
out/coremark-official/coremark-official-riscv32-jyd.data.coe
out/coremark-official/coremark-official-riscv32-jyd.elf
```

`coremark-defaults.mk` 是唯一的默认 ISA/自定义指令配置来源。构建使用
官方算法源、EEMBC 浮点格式转换代码和 Berkeley SoftFloat；计时区间不
包含结果格式化与串口输出。

## 4. 生成 RT-Thread Nano COE

```sh
make -C jyd-tests/rtthread-nano fetch
make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd \
  CROSS_COMPILE="$JYD_CROSS" \
  BUILD_DIR=../../out/rtthread-nano image
```

`SOURCE_COMMIT` 固定官方 RT-Thread Nano 3.1.5。主要产物为：

```text
out/rtthread-nano/rtthread-nano-riscv32-jyd.text.coe
out/rtthread-nano/rtthread-nano-riscv32-jyd.data.coe
out/rtthread-nano/rtthread-nano-riscv32-jyd.elf
```

RT-Thread 源文件保持自身的 `rv32im_zicsr -Os` 选项；只有内嵌 CoreMark
对象读取 `coremark-defaults.mk`，因此两种 CoreMark 镜像不会出现默认
选项分叉。

#!/bin/bash
JYD_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

make -C "$JYD_ROOT" clean
make -C "$JYD_ROOT/fceux-am" clean
make -C "$JYD_ROOT/abstract-machine/am" clean
make -C "$JYD_ROOT/abstract-machine/klib" clean
make -C "$JYD_ROOT/abstract-machine/kasan" clean
find "$JYD_ROOT/am-kernels/kernels" -maxdepth 1 -type d -exec make -C {} clean \;
find "$JYD_ROOT/am-kernels/benchmarks" -maxdepth 1 -type d -exec make -C {} clean \;

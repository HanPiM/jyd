make clean
make -C $JYD_AM_HOME/../fceux-am clean
make -C $JYD_AM_HOME/am clean
make -C $JYD_AM_HOME/klib clean
make -C $JYD_AM_HOME/kasan clean
find $JYD_AM_HOME/../am-kernels/kernels -maxdepth 1 -type d -exec make -C {} clean \;
find $JYD_AM_HOME/../am-kernels/benchmarks -maxdepth 1 -type d -exec make -C {} clean \;

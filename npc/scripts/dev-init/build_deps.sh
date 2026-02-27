#!/bin/bash

cd $1

# spdlog
cd spdlog && mkdir -p build
cd build
cmake .. && cmake --build .
cd ../..

# build mini-gdbstub
cd mini-gdbstub && make all

cd ..

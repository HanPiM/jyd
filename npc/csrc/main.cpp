#include <stdio.h>
#include <stdlib.h>
#include <assert.h>

#include "Vtop.h"
#include "verilated.h"
#include <verilated_vcd_c.h>

#include <nvboard.h>

#ifndef TOP_NAME
#define TOP_NAME Vtop
#endif

static TOP_NAME dut;

void nvboard_bind_all_pins(TOP_NAME* top);

static void single_cycle() {
    //int a = rand() & 1;
    //int b = rand() & 1;
    //dut.a = a;
    //dut.b = b;
    dut.eval();
    //printf("a=%d, b=%d, f=%d\r", dut.a, dut.b, dut.f);
    assert(dut.f == (dut.a ^ dut.b));
}


int main(int argc, char **argv)
{

  nvboard_bind_all_pins(&dut);
  nvboard_init();


  while(1) {
    nvboard_update();
    single_cycle();
  }

  return 0;
}
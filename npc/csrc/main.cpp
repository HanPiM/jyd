#include <chrono>
#include <stdio.h>
#include <stdlib.h>
#include <assert.h>

#include "Vtop.h"
#include "verilated.h"
#include <thread>
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
    dut.clk=0;dut.eval();
	nvboard_update();
	//std::this_thread::sleep_for(std::chrono::milliseconds(10));
	if (dut.seg0!=3){
		//printf("seg0=%d\n", dut.seg0);
	}
	//printf("ps2_clk=%d\n", dut.ps2_clk);
    dut.clk=1;dut.eval();
	nvboard_update();
	//std::this_thread::sleep_for(std::chrono::milliseconds(10));
	//printf("ps2_clk=%d\n", dut.ps2_clk);

    //printf("a=%d, b=%d, f=%d\r", dut.a, dut.b, dut.f);

}

static void reset(int n) {
    dut.rst = 1;
    while (n -- > 0) single_cycle();
    dut.rst = 0;
}

int main(int argc, char **argv)
{

    nvboard_bind_all_pins(&dut);
    nvboard_init();

    reset(10);

    while(1) {
	single_cycle();
    }

    return 0;
}

#include <iostream>
#include <nvboard.h>
#include <VTop.h>
#include <VTop__Dpi.h>

#ifndef TOP_NAME
#endif
TOP_NAME dut;
void nvboard_bind_all_pins(TOP_NAME* top);

void fetch_inst(int pc, int *out_inst){

}
void pmem_read(int addr, int *out_data){

}
void pmem_write(int addr, int data, int mask){

}

int main(){
	nvboard_bind_all_pins(&dut);
	nvboard_init();

	while (1) {
		nvboard_update();
	
	}
	return 0;
}

#include <cstdint>
#include <stdio.h>
#include <stdlib.h>
#include <assert.h>

#include <verilated.h>
#include <verilated_vcd_c.h>

#include "Vtop.h"
#include "Vtop__Dpi.h"
#include <nvboard.h>

#ifndef TOP_NAME
#define TOP_NAME Vtop
#endif

static TOP_NAME dut;

#define MADDR_BASE 0x80000000u
typedef uint32_t word_t;
typedef uint32_t addr_t;

word_t guest_to_host(word_t addr){
	word_t res= addr - MADDR_BASE;
	//printf("raw addr %08X after trans: %08X\n",addr,res);
	return res;
}

void nvboard_bind_all_pins(TOP_NAME* top);
void nvboard_update();
void nvboard_init(int vga_clk_cycle);


word_t mem[512*1024/4]={
  0x00000297,  // auipc t0,0
  0x00028823,  // sb  zero,16(t0)
  0x0102c503,  // lbu a0,16(t0)
  0x00100073,  // ebreak (used as nemu_trap)
  0xdeadbeef,  // some data
};

bool is_running=true;

extern "C" void raise_break(){
	is_running=false;
	puts("\n--- raise_break called");
}

extern "C" int pmem_read(int raddr) {
	if(!is_running){
		printf("Warn: read addr %08X when not run, return 0xBAADCA11\n",raddr);
		return 0xBAADCA11;
	}
  	// 总是读取地址为`raddr & ~0x3u`的4字节返回
	uint32_t addr=guest_to_host(raddr);
  	addr&=~0x3u;
//	printf("  $pmem_read try read %08X\n",addr);
	return mem[addr>>2];
}
extern "C" void pmem_write(int waddr, int wdata, char wmask) {
	// 总是往地址为`waddr & ~0x3u`的4字节按写掩码`wmask`写入`wdata`
	// `wmask`中每比特表示`wdata`中1个字节的掩码,
	// 如`wmask = 0x3`代表只写入最低2个字节, 内存中的其它字节保持不变
	uint32_t addr=guest_to_host(waddr);
  	addr&=~0x3u;
//	printf("  $pmem_write try write %08X mask %d data:%08X\n",addr,(int)wmask,wdata);
	
	uint8_t* p=(uint8_t*)(&mem[addr>>2]);

	while (wmask) {
		if(wmask&1){
			*p=wdata&0xff;
		}
		p++;
		wmask>>=1;
		wdata>>=8;
	}
}

static void single_cycle() {
    dut.clk=0;dut.eval();
//	nvboard_update();

//	if(!dut.rst)printf("@ pc [%08X]:\n",dut.pc);

    dut.clk=1;dut.eval();
	nvboard_update();
}

static void reset(int n) {
    dut.rst = 1;
    while (n -- > 0) single_cycle();
    dut.rst = 0;
}

const char* img_file;

static long load_img() {
#define Log(fmt , ...) printf(fmt "\n",##__VA_ARGS__)
#define Assert(expr,...) do{if(!(expr)){fprintf(stderr,__VA_ARGS__);}}while(0)

  if (img_file == NULL) {
    Log("No image is given. Use the default build-in image.");
    return 4096; // built-in image size
  }



  FILE *fp = fopen(img_file, "rb");
  Assert(fp, "Can not open '%s'", img_file);

  fseek(fp, 0, SEEK_END);
  long size = ftell(fp);

  Log("The image is %s, size = %ld", img_file, size);

  fseek(fp, 0, SEEK_SET);
  int ret = fread(mem, size, 1, fp);
  assert(ret == 1);

  fclose(fp);

#define INST_EBREAK 0x00100073

  return size;
}


int main(int argc, char **argv)
{
//	pmem_write(0,0x12345678, 0x3);
//	int res=pmem_read(0);
//	printf("%X",res);
//	return 0;

	if(argc==2){
		img_file=argv[1];
	}

	load_img();

    nvboard_bind_all_pins(&dut);
    nvboard_init();

    reset(10);

	puts("\n--- Start ---\n");
	
    while(is_running) {
		single_cycle();
    }
	dut.final();

	puts("\n--- simulation end ---\n");

    return 0;
}

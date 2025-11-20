#include <array>

#include <cstdint>
#include <stdio.h>
#include <stdlib.h>
#include <assert.h>

#include <unistd.h>
#include <getopt.h>

#include <verilated.h>
#include <verilated_vcd_c.h>

#include "Vtop.h"
#include "Vtop__Dpi.h"
#include <nvboard.h>

#include "sdb.hpp"
#include "tracers.hpp"
#include "elf_tool.hpp"

#ifndef TOP_NAME
#define TOP_NAME Vtop
#endif

static TOP_NAME dut;

#define USE_NVBOARD 0

//#define TRACE_MEM 

#define NGPR 32

#define MADDR_BASE 0x80000000u
#define INITIAL_PC MADDR_BASE
#define NOP_INST 0x00000013u // addi x0, x0, 0
#define NOP_INST_ADDR (INITIAL_PC-4)

#define MMIO_SERIAL_PORT 0x10000000u
#define MMIO_RTC_ADDR 0x10000048u

typedef uint32_t word_t;
typedef uint32_t addr_t;



void nvboard_bind_all_pins(TOP_NAME* top);
void nvboard_update();
void nvboard_init(int vga_clk_cycle);



word_t mem[6*1024*1024/4]={
  0x00000297,  // auipc t0,0
  0x00028823,  // sb  zero,16(t0)
  0x0102c503,  // lbu a0,16(t0)
  0x00100073,  // ebreak (used as nemu_trap)
  0xdeadbeef,  // some data
};
uint8_t* mem_atguest(size_t addr){
	assert(addr>=MADDR_BASE);
	return ((uint8_t*)mem)+addr-MADDR_BASE;
}
word_t guest_to_host(word_t addr){
	//printf("raw addr %08X\n",addr);
	//assert(addr>=MADDR_BASE);
	word_t res= addr - MADDR_BASE;
	return res;
}

bool is_running=true;
bool is_good_trap=false;

word_t regs[NGPR];

extern "C" void reg_upadted(int idx,int val) {
//	printf("reg_upadted called %d %08X\n",idx,val);
	regs[idx]=val;
}
extern "C" int fetch_inst(int pc){
	if(pc<=INITIAL_PC-4)return NOP_INST;
	return mem[guest_to_host(pc)>>2];
}
static void single_cycle() {

#ifdef TRACE_SINGLE_CYCLE
	printf("----- single cycle -----\n");
#endif
	dut.clk=0;dut.eval();
#ifdef  TRACE_SINGLE_CYCLE
	printf("** Clock low eval done\n");
#endif

	dut.clk=1;dut.eval();

#ifdef  TRACE_SINGLE_CYCLE
	printf("** Clock high eval done\n");
#endif
#if USE_NVBOARD
	nvboard_update();
#endif
}

static void reset(int n) {
    dut.rst = 1;
    while (n -- > 0) single_cycle();
    dut.rst = 0;
}

static const char* img_file;
static size_t img_size;
static bool batch_mode=false;

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
  img_size = ftell(fp);

  Log("The image is %s, size = %ld", img_file, img_size);

  fseek(fp, 0, SEEK_SET);
  int ret = fread(mem, img_size, 1, fp);
  assert(ret == 1);

  fclose(fp);

#define INST_EBREAK 0x00100073

  return img_size;
}

sdb::paddr_t cpu_exec_once(){
	single_cycle();
	return dut.nxt_pc;
}

std::array<std::string_view,32> reg_names = {
  "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
  "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
  "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
  "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"
};

void sdb_shot_regsnap(sdb::reg_snapshot_t& snap){
	for(size_t i=0;i<NGPR;i++){
		snap[i]=regs[i];
	}
}

sdb::vlen_inst_code sdb_inst_fetcher(sdb::paddr_t pc){
		word_t raw = fetch_inst(pc);
		uint8_t* p = (uint8_t*)&raw;
		return sdb::vlen_inst_code(p, p + 4);
}
uint8_t* sdb_loadmem(sdb::paddr_t addr, size_t nbyte){
	return mem_atguest(addr);
}

sdb::debuger dbg(
	INITIAL_PC,	
	cpu_exec_once,
	sdb_loadmem,
	sdb_shot_regsnap,
	std::vector<std::string_view>(reg_names.begin(),reg_names.end()),
	sdb_inst_fetcher
);

extern "C" int pmem_read(int raddr) {
	// printf("pmem_read called %08X\n",raddr);
	if(raddr==NOP_INST_ADDR)return NOP_INST;
	if(raddr==MMIO_RTC_ADDR||raddr==MMIO_RTC_ADDR+4){
		dbg.difftest_ref_skip();
		static uint64_t time_in_us;
		if(raddr==MMIO_RTC_ADDR){
			struct timespec ts;
			clock_gettime(CLOCK_MONOTONIC_COARSE,&ts);
			time_in_us=ts.tv_sec*1000000+ts.tv_nsec/1000;
			return (uint32_t)(time_in_us&0xffffffffu);
		}
		else{
			return time_in_us>>32;
		}
	}

	if(!is_running){
		printf("Warn: read addr %08X when not run, return 0xBAADCA11\n",raddr);
		return 0xBAADCA11;
	}
  	// 总是读取地址为`raddr & ~0x3u`的4字节返回
	uint32_t addr=guest_to_host(raddr);
  	addr&=~0x3u;
#ifdef TRACE_MEM
		printf("  $pmem_read %08X\n",raddr);
#endif
	return mem[addr>>2];
}


extern "C" void pmem_write(int waddr, int wdata, char wmask) {
	if(waddr==MMIO_SERIAL_PORT){
		dbg.difftest_ref_skip();		
		putchar(wdata&0xff);
		return;
	}
	// printf("pmem_write called %08X\n",waddr);
	// 总是往地址为`waddr & ~0x3u`的4字节按写掩码`wmask`写入`wdata`
	// `wmask`中每比特表示`wdata`中1个字节的掩码,
	// 如`wmask = 0x3`代表只写入最低2个字节, 内存中的其它字节保持不变
	uint32_t addr=guest_to_host(waddr);
	addr&=~0x3u;

#ifdef TRACE_MEM
		printf("  $pmem_write %08X mask %d data:%08X\n",waddr,(int)wmask,wdata);
#endif
	
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


extern "C" void raise_break(int a0){
	dbg.state().halt(a0);

	is_running=false;
#define ANSI_FG_RED     "\33[1;31m"
#define ANSI_FG_GREEN   "\33[1;32m"
#define ANSI_NONE       "\33[0m"

	if(a0==0){
		printf(ANSI_FG_GREEN "HIT GOOD TRAP" ANSI_NONE);
		is_good_trap=true;
	}
	else{
		printf(ANSI_FG_RED "HIT BAD TRAP" ANSI_NONE);
	}
	printf(" at pc = 0x%08x\n",dut.pc);
}

extern "C" void sim_panic(){
	puts("SIM PANIC!");
	is_running=false;
	dbg.abort();
}

static void parse_args(int argc, char** argv){
	const struct option table[] = {
		{"batch", no_argument, NULL, 'b'},
		{0, 0, NULL, 0}
	};
	int o;
	while ((o = getopt_long(argc, argv, "-b", table, NULL)) != -1) {
		switch (o) {
		case 'b': batch_mode = true; break;
		case 1: img_file = optarg; break;
		default:
			printf("Bad option %c\n", o);
			exit(1);
		}
	}

}

int main(int argc, char **argv)
{
	parse_args(argc, argv);
	using std::string;
	using namespace std::views;
	using namespace std::ranges;

	load_img();

	dbg.enable_inst_trace=true;
	
	dbg.add_trace(sdb::make_disasm_trace_handler(sdb::default_inst_disasm,16));
	dbg.add_trace(sdb::make_etrace_handler());
	dbg.add_trace(sdb::make_iringbuf_trace_handler());

	if(img_file){
		auto elf_file=try_find_elf_file_of(img_file);

		if(!elf_file.empty()){
			printf("Found ELF file: %s\n",elf_file.c_str());
			//dbg.add_trace(sdb::make_ftrace_handler(elf_file));
		}
	}


// dbg.enable_difftest=true;
//	dbg.load_difftest_ref("../nemu/build/riscv32-nemu-interpreter-so",img_size,0);


#if USE_NVBOARD
    nvboard_bind_all_pins(&dut);
    nvboard_init();
#endif

  reset(10);

	if(batch_mode){
		dbg.exec_command("c");
		return dbg.state().is_badexit();
	}

	puts("\n--- Start ---\n");
	
	std::string cmd;
	while(true){
		std::cout<<"(sdb) ";
		std::getline(std::cin,cmd);
		dbg.exec_command(cmd);
		if(dbg.state().quited()){
			break;
		}
	}
	dut.final();
	return is_good_trap?0:1;
}

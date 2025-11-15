#include <array>
#include <unordered_map>

#include <cstdint>
#include <stdio.h>
#include <stdlib.h>
#include <assert.h>

#include <verilated.h>
#include <verilated_vcd_c.h>

#include "Vtop.h"
#include "Vtop__Dpi.h"
#include <nvboard.h>

#include "sdb/sdb.hpp"
#include "sdb/elf_tool.hpp"

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

typedef uint32_t word_t;
typedef uint32_t addr_t;

word_t guest_to_host(word_t addr){
	//printf("raw addr %08X\n",addr);
	//assert(addr>=MADDR_BASE);
	word_t res= addr - MADDR_BASE;
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
bool is_good_trap=false;

word_t regs[NGPR];

extern int read_reg(int idx);
extern "C" int reg_upadted(){
	for(int i=0;i<32;i++)
		regs[i]=read_reg(i);
	return 0;
}

extern "C" int pmem_read(int raddr) {
	// printf("pmem_read called %08X\n",raddr);
	if(raddr==NOP_INST_ADDR)return NOP_INST;

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

extern "C" int fetch_inst(int pc){
	if(pc<=INITIAL_PC-4)return NOP_INST;
	return mem[guest_to_host(pc)>>2];
}

extern "C" void pmem_write(int waddr, int wdata, char wmask) {
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

sdb::paddr_t cpu_exec_once(){
	single_cycle();
	return dut.nxt_pc;
}
uint8_t addr_readbyte(sdb::paddr_t addr){
	return pmem_read(addr)>>((addr&0x3)*8);
}
std::array<std::string_view,32> reg_names = {
  "$0", "ra", "sp", "gp", "tp", "t0", "t1", "t2",
  "s0", "s1", "a0", "a1", "a2", "a3", "a4", "a5",
  "a6", "a7", "s2", "s3", "s4", "s5", "s6", "s7",
  "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"
};
std::optional<sdb::word_t> get_reg(std::string_view name){
	static std::unordered_map<std::string_view,size_t> m;
	if(m.empty()){
		for (size_t i = 0; i < reg_names.size(); ++i) {
			m.emplace(reg_names[i], i);
		}
	}
	if(m.contains(name)){
		return regs[m.at(name)];
	}
	return std::nullopt;
}

sdb::vlen_inst_code sdb_inst_fetcher(sdb::paddr_t pc){
		word_t raw = fetch_inst(pc);
		uint8_t* p = (uint8_t*)&raw;
		return sdb::vlen_inst_code(p, p + 4);
}

std::string disasm(sdb::disasmable_inst inst){
	void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
	char buf[256];
	disassemble(buf,sizeof(buf),inst.pc,inst.code.data(),inst.code.size());
	return buf;
}
sdb::debuger dbg(
	INITIAL_PC,	
	cpu_exec_once,
	addr_readbyte,
	get_reg,
	std::vector<std::string>(reg_names.begin(),reg_names.end()),
	disasm,sdb_inst_fetcher
);

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

sdb::jump_type sdb_jump_recognizer(const sdb::disasmable_inst& inst){
	using namespace sdb;
	word_t instr=*(word_t*)inst.code.data();
	uint8_t opcode=instr&0x7f;
	uint8_t funct3=(instr>>12)&0x7;
	uint8_t rd=(instr>>7)&0x1f;
	uint8_t rs1=(instr>>15)&0x1f;
	bool is_jal=opcode==0x6f;
	bool is_jalr=opcode==0x67 && funct3==0x0;
	bool is_ret=is_jalr && rd==0 && rs1==1;
	bool is_call=(is_jal && rd==1) || (is_jalr && rd==1);
	if(is_call)return jump_type::call;
	if(is_ret)return jump_type::ret;
	return jump_type::normal;
}

std::string try_find_elf_file(std::string img_file){
	using namespace std;
	size_t pos=img_file.rfind('.');
	if(pos==string::npos)return "";
	string base=img_file.substr(0,pos);
	string elf_file=base+".elf";
	FILE* fp=fopen(elf_file.c_str(),"rb");
	if(fp){
		fclose(fp);
		return elf_file;
	}
	return "";
}

void init_disasm();
int main(int argc, char **argv)
{
	init_disasm();
	using std::string;
	using namespace std::views;
	using namespace std::ranges;

	if(argc==2){
		img_file=argv[1];
	}


	load_img();
	string elf_file=try_find_elf_file(img_file?img_file:"");
	if(!elf_file.empty()){
		printf("Found ELF file %s\n",elf_file.c_str());
		dbg.load_elf(elf_file.c_str());
	}

	dbg.set_jump_recognizer(sdb_jump_recognizer);


#if USE_NVBOARD
    nvboard_bind_all_pins(&dut);
    nvboard_init();
#endif

  reset(10);

	puts("\n--- Start ---\n");
	
	std::string cmd;
	while(true){
		std::cout<<"(sdb) ";
		std::getline(std::cin,cmd);
		dbg.exec_command(cmd);
		if(dbg.state().state==sdb::run_state::quit) {
			break;
		}
	}
	dut.final();
	return is_good_trap?0:1;
}

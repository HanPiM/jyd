#ifndef __SDBC_H__
#define __SDBC_H__
#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C"{
#endif

typedef struct sdb_debuger_imp* sdb_debuger;

typedef uint32_t sdb_paddr_t;

typedef struct{
	uint8_t* data;
	int len;
}sdb_vlen_inst_code;

typedef enum{
	SDB_JUMP_NORMAL,
	SDB_JUMP_CALL,
	SDB_JUMP_RET
}sdb_jump_type;

#define SDB_ENTRACE_INST 1
#define SDB_ENTRACE_FTRACE 2
#define SDB_ENTRACE_DIFFTEST 4

// should return the next pc
typedef sdb_paddr_t(*sdb_cpu_executor)();
// should return pointer to nbyte data at addr
typedef uint8_t*(*sdb_mem_loader)(sdb_paddr_t addr, size_t nbyte);
// should fill reg_snapshot with current register values
// reg_snapshot has enough size for all registers + extra
// never write out of register range!!!!
typedef void(*sdb_reg_snapshoter)(uint32_t* reg_snapshot);

typedef sdb_vlen_inst_code(*sdb_inst_fetcher)(sdb_paddr_t pc);
typedef void(*sdb_disasmsembler)(char* str, int size, uint64_t pc, uint8_t* code, int nbyte);
typedef sdb_jump_type(*sdb_jump_recognizer)(sdb_vlen_inst_code inst_code);

sdb_debuger sdb_create_debuger(
	sdb_paddr_t init_pc,
	sdb_cpu_executor exec,
	sdb_mem_loader loadmem,
	sdb_reg_snapshoter shotreg,
	const char** reg_names,
	size_t n_reg_names,
	sdb_disasmsembler disasm,
	sdb_inst_fetcher fetcher
);

void sdb_destroy_debuger(sdb_debuger dbg);

void sdb_enable_entrace(sdb_debuger dbg, int flags);

void sdb_load_elf(sdb_debuger dbg, const char* filename);
void sdb_load_difftest_ref(sdb_debuger dbg, const char* so_file, size_t img_size);
void sdb_set_jump_recognizer(sdb_debuger dbg, sdb_jump_recognizer recognizer);

void sdb_exec(sdb_debuger dbg, const char* cmdline);
void sdb_skip_difftest_ref(sdb_debuger dbg);

#ifdef __cplusplus
}
#endif

#endif

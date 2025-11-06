#ifndef __ELF_TOOL_H__
#define __ELF_TOOL_H__

#include <stdint.h>
void load_elf(const char* filename);
void free_elf();

typedef struct{
	uint32_t addr;
	uint32_t size;
	const char* name;
} func_sym;

// try to find func which satisify
//    inst_addr belong [addr,addr+size)
//
// if not find return -1 otherwise 0
int try_match_func(uint32_t inst_addr,func_sym* out);

#endif

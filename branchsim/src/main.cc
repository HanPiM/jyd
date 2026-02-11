#include "../public/btrace_pack.h"
#include <stdio.h>

int main() {
	btrace_pack_t pack = btrace_pack_open("../nemu/btrace_pack.bin");
	btrace_record_t record;

	while (true){
		if(btrace_pack_pick(pack, &record) == 0) break;
		printf("pc: %08x, code: %08x, nxt_pc: %08x\n", record.pc, record.code, record.nxt_pc);
	}
	btrace_pack_close(pack);
	return 0;
}

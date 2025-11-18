#include "sdb.hpp"
#include "sdbc.h"
#include <cassert>

using namespace std;

sdb_debuger sdb_create_debuger(sdb_paddr_t init_pc, sdb_cpu_executor exec, sdb_mem_loader loadmem, sdb_reg_snapshoter shotreg, const char **reg_names, size_t n_reg_names, sdb_disasmsembler disasm, sdb_inst_fetcher fetcher){
	return (sdb_debuger)(new sdb::debuger(
		init_pc,
		exec,
		loadmem,
		[shotreg](sdb::reg_snapshot_t& snap){
			shotreg(snap.data());
		},
		vector<string_view>(reg_names,reg_names+n_reg_names),	
		[fetcher](sdb::paddr_t pc){
			auto code=fetcher(pc);
			return sdb::vlen_inst_code(code.data,code.data+code.len);
		},
		disasm?[disasm](const sdb::disasmable_inst& inst){
			char buf[256];
			disasm(buf,sizeof(buf),
					inst.pc,(uint8_t*)inst.code.data(),inst.code.size());
			return string(buf);
		}:sdb::inst_disasmsembler(sdb::default_disasm)
	));
}

void sdb_destroy_debuger(sdb_debuger dbg){
	delete (sdb::debuger*)dbg;
}

#define _DBG (*((sdb::debuger*)dbg))

void sdb_enable_entrace(sdb_debuger dbg, int flags){
 _DBG.enable_difftest=(flags&SDB_ENTRACE_DIFFTEST);
 _DBG.enable_inst_trace=(flags&SDB_ENTRACE_INST);
 _DBG.enable_ftrace=(flags&SDB_ENTRACE_FTRACE);
 if(_DBG.enable_ftrace)assert(_DBG.enable_inst_trace);
 if(_DBG.enable_difftest)assert(_DBG.enable_inst_trace);
}

void sdb_load_elf(sdb_debuger dbg, const char* filename){
	_DBG.load_elf(filename);
}
void sdb_load_difftest_ref(sdb_debuger dbg, const char* so_file, size_t img_size){
	_DBG.load_difftest_ref(so_file,img_size);
}
void sdb_set_jump_recognizer(sdb_debuger dbg, sdb_jump_recognizer recognizer){
	_DBG.set_jump_recognizer([recognizer](const sdb::disasmable_inst& inst){
		sdb_vlen_inst_code code;
		code.data=(uint8_t*)inst.code.data();
		code.len=inst.code.size();
		return static_cast<sdb::jump_type>(recognizer(code));
	});
}
void sdb_exec(sdb_debuger dbg, const char* cmdline){
	_DBG.exec_command(cmdline);
}
void sdb_skip_difftest_ref(sdb_debuger dbg){
	_DBG.difftest_ref_skip();
}

#include "sdb.hpp"
#include "ansi_col.h"
#include "elf_tool.hpp"

using namespace std;
using namespace sdb;

struct sdb::_impl::ftrace_imp{
	elf_handler elf;
	jump_recognizer recog_jmp;
	int func_depth=0;
};
void _impl::_deleter_ftrace::operator()(ftrace_imp* ptr){
	if(ptr){delete ptr;}
}
void debuger::load_elf(const char* filename){
	_imp_ftrace=_impl::ftrace_imptr(new _impl::ftrace_imp());
	auto& imp=*_imp_ftrace;

	std::fstream fs(filename,std::ios::in|std::ios::binary);
	imp.elf.load(fs);
	_print("Loaded ELF file {}\n",filename);
	set_jump_recognizer(default_riscv_jump_recognizer);
}
void debuger::set_jump_recognizer(jump_recognizer r){
	if(_imp_ftrace){
		_imp_ftrace->recog_jmp=r;
	}
}

void debuger::_ftrace_handler(const disasmable_inst& inst){
	if(!_imp_ftrace)return;
	auto& imp=*_imp_ftrace;
	auto type=imp.recog_jmp(inst);
	if(type==jump_type::normal)return;
	auto hint_str=type==jump_type::call?"call fun":"ret from";
	if(type==jump_type::call)imp.func_depth++;

	auto f=imp.elf.get_fun_at(inst.pc);
	auto fname=f?f->name:"(unknown)";

	_print(
			"0x{:08X}: "
			"{}{} "
			ANSI_FG_GRAY "f`{:08X}"
		 	ANSI_NONE "{}{}\n",
		inst.pc,
		type==jump_type::call?ANSI_FG_YELLOW:ANSI_FG_BLUE,
		hint_str,
		f?f->addr:0,
		string(imp.func_depth,' '),
		fname
	);

	if(type==jump_type::ret){
		if(imp.func_depth>0)imp.func_depth--;
		else _error("ret but func depth is 0");
	}
}



#include "sdb.hpp"
#include <assert.h>

#include "ansi_col.h"

using namespace std;
using namespace sdb;
using namespace std::views;
using namespace clscmd;

template <typename T>
struct std::formatter<optional<T>> : formatter<T> {
    auto format(const optional<T>& opt, auto& ctx) const {
        if (opt)
            return formatter<T>::format(*opt, ctx);
        else
            return format_to(ctx.out(), "(nullopt)");
    }
};

template<>
struct std::formatter<errc> : formatter<string> {
    auto format(errc ec, auto& ctx) const {
        return formatter<string>::format(
            make_error_code(ec).message(), ctx);
    }
};

void debuger::_dump_inst(const disasmable_inst& inst,bool highlight_disasm){
	_print(ANSI_FG_GRAY "0x{:08X}: {}{:25} " ANSI_FG_GRAY "(",
			inst.pc,
			highlight_disasm?ANSI_FG_RED:ANSI_NONE,
			_disasm(inst));
	for(size_t j=0;j<inst.code.size();j++){
		if(j) _print(" ");
		_print("{:02X}",inst.code[j]);
	}
	auto as_u32code=*(uint32_t*)inst.code.data();
	_print(" `0x{:08X}",as_u32code);
	_print(")" ANSI_NONE "\n");
}

void debuger::_dump_iringbuf(){if (enable_inst_trace) {
	_print(ANSI_FG_YELLOW "==== recent instructions ====\n" ANSI_NONE);
	auto last=prev(end(_iringbuf));
	for(auto it=_iringbuf.begin();it!=_iringbuf.end();++it){
		_print("[{}{:02}" ANSI_NONE "] ",
			it==last?ANSI_FG_RED:ANSI_FG_CYAN,
			distance(it,end(_iringbuf))-1);
		_dump_inst(*it,it==last);
	}
}}

string sdb::_impl::expand_tabs(std::string_view in, int tabsize) {
    string out;
    out.reserve(in.size() * tabsize);
    int col = 0;
    for (char c : in) {
        if (c == '\t') {
            int spaces = tabsize - (col % tabsize);
            out.append(spaces, ' ');
            col += spaces;
        } else {
            out.push_back(c);
            col++;
        }
    }
    return out;
}
void debuger::_step_one(){
	if (enable_inst_trace){
		auto inst=_fetch_dinst(_state.pc);
		if(_enable_dump_inst)_dump_inst(inst);
		_iringbuf.push(std::move(inst));
		if (enable_ftrace){
			_ftrace_handler(inst);
		}
	}
	auto oldpc= _state.pc;
	_state.pc = _exec();	
	_difftest_step(oldpc, _state.pc);
}

uint64_t expr_t::eval()const{
	// only support 0x... now
	auto s=raw;
	assert(s.starts_with("0x"));
	s=s.substr(2);
	uint64_t v;
	auto ec=clscmd::parse(s,v,16);
	if(ec!=errc())cerr<<format("failed to parse {} : {}",s,ec)<<endl;
	return v;
}

void debuger::cmd_q(){
	if(_state.is_bad()){
		// cur pc has not executed yet
		_error("Program exited with bad state. nxt pc = 0x{:08x}", _state.pc);
	}
	_state.state=run_state::quit;
}

void debuger::dump_mem(paddr_t addr,paddr_t end){
	assert((end-addr)%4==0);
	while (addr!=end) {
		_print(ANSI_FG_GRAY"0x{:08x}: " ANSI_NONE,addr);	
		auto p=_loadmem(addr,4);
		for(int i=0;i<4;i++)_print("{:02x} ", p[i]);
		_print("\n");
		addr+=4;
	}
}
void debuger::dump_reg(){
	for(size_t i=0;i<_reg_names.size();i++){
		auto r=_reg_names[i];
		auto v=_reg_snap[i];
		_print("{}:\t0x{:08x}\t{}\n", r, v,v);
	}
}

void debuger::cmd_info(string_view s){
	if(s=="r"){
		_shot_reg(_reg_snap);
		dump_reg();
	}
	else return _error("Unknown info command {}", s);	
}
void debuger::cmd_x(size_t N,expr_t e_addr){
	paddr_t addr=e_addr.eval();
	_print("call x {} {:08x}", N,addr);
	cout<<endl;
	dump_mem(addr, addr+N*4);
}

void debuger::_init_cmd_table(){
#define _ITEM(name,desc,...) {name,command_t(desc,this,&debuger::__VA_ARGS__)}
	_cmd_table={
		_ITEM("c", "Continue the execution of the program", cmd_c),
		_ITEM("q", "Exit program",cmd_q),
		_ITEM("si","Step the program for N instructions",cmd_si,1),
		_ITEM("info", "Display information about registers or watchpoints",cmd_info),
		_ITEM("x", "Examine memory: x N EXPR",cmd_x),
		};
}

void debuger::exec_command(string_view cmdline){
	auto ec=exec(_cmd_table,cmdline);
	if(ec!=invoke_success){
		_error("{}", ec);
	}
}


extern "C" void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);
extern "C" void init_disasm();

string sdb::default_disasm(const disasmable_inst& inst){
	static bool has_init=false;
	if(!has_init){
		init_disasm();
		has_init=true;
	}
	char buf[256];
	disassemble(buf,sizeof(buf),inst.pc,(uint8_t*)inst.code.data(),inst.code.size());
	return buf;
}


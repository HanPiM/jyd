#include "sdb.hpp"
#include <assert.h>

#define ANSI_FG_BLACK   "\33[1;30m"
#define ANSI_FG_RED     "\33[1;31m"
#define ANSI_FG_GREEN   "\33[1;32m"
#define ANSI_FG_YELLOW  "\33[1;33m"
#define ANSI_FG_BLUE    "\33[1;34m"
#define ANSI_FG_MAGENTA "\33[1;35m"
#define ANSI_FG_CYAN    "\33[1;36m"
#define ANSI_FG_WHITE   "\33[1;37m"
#define ANSI_BG_BLACK   "\33[1;40m"
#define ANSI_BG_RED     "\33[1;41m"
#define ANSI_BG_GREEN   "\33[1;42m"
#define ANSI_BG_YELLOW  "\33[1;43m"
#define ANSI_BG_BLUE    "\33[1;44m"
#define ANSI_BG_MAGENTA "\33[1;45m"
#define ANSI_BG_CYAN    "\33[1;46m"
#define ANSI_BG_WHITE   "\33[1;47m"
#define ANSI_NONE       "\33[0m"

#define ANSI_FG_GRAY 		"\033[90m" // light black


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
struct std::formatter<errc> : formatter<std::string> {
    auto format(errc ec, auto& ctx) const {
        return formatter<std::string>::format(
            std::make_error_code(ec).message(), ctx);
    }
};

void debuger::_dump_iringbuf(){
	auto last=prev(end(_iringbuf));
	for(auto it=_iringbuf.begin();it!=_iringbuf.end();++it){
		auto inst=*it;
		_print("[" ANSI_FG_CYAN "{:02}" ANSI_NONE "] {:08X} ",
				distance(it,end(_iringbuf))-1,
				inst.pc);
		if(it==last)_print(ANSI_FG_YELLOW);
		_print("{:25} " ANSI_FG_GRAY "(",_disasm(inst));
		for(int j=0;j<inst.code.size();j++){
			if(j)_print(" ");
			_print("{:02X}",inst.code[j]);
		}
		_print(")" ANSI_NONE "\n");
	}
}

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

void debuger::_trace_handler_f(const disasmable_inst& inst){
	auto type=_recog_jmp(inst);
	if(type==jump_type::normal)return;
	auto hint_str=type==jump_type::call?"call fun":"ret from";
	if(type==jump_type::call)_func_depth++;

	auto f=_elf.get_fun_at(inst.pc);
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
		string(_func_depth,' '),
		fname
	);

	if(type==jump_type::ret){
		if(_func_depth>0)_func_depth--;
		else _error("ret but func depth is 0");
	}
}

void debuger::_step_one(){
	if constexpr (_ENABLE_ITRACE){
		auto inst=_fetch_dinst(_state.pc);
//		_print("{:08X}: {}\n",inst.pc,_disasm(inst));
		_iringbuf.push(std::move(inst));
		if constexpr (_ENABLE_FTRACE){
			_trace_handler_f(inst);
		}
	}
	_state.pc = _exec();	
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
	if(is_running()||!_state.is_bad()){
		_dump_iringbuf();
		_state.state=run_state::quit;
	}
}

void debuger::dump_mem(paddr_t addr,paddr_t end){
	assert((end-addr)%4==0);
	while (addr!=end) {
		_print("0x{:08x}: ",addr);	
		for(int i=0;i<4;i++)
			_print("{:02x} ", _paddr_read(addr+i));
		_print("\n");
		addr+=4;
	}
}
void debuger::dump_reg(){
	for(auto r:_reg_names){
		auto v=_reg_read(r);
		_print("{}:\t0x{:08x}\t{}\n", r, v,v);
	}
}

void debuger::cmd_info(string_view s){
	if(s=="r")dump_reg();
	else return _error("Unknown info command {}", s);	
}
void debuger::cmd_x(size_t N,expr_t e_addr){
	paddr_t addr=e_addr.eval();
	_print("call x {} {:08x}", N,addr);
	cout<<endl;
	dump_mem(addr, addr+N*4);
}

#define _ITEM(name,desc,...) {name,command_t(desc,this,&debuger::__VA_ARGS__)}

void debuger::_init_cmd_table(){
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

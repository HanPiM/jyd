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

string_view sdb::_impl::error_head_str(){
	return ANSI_FG_RED "[ERROR] " ANSI_NONE;
}

void debuger::_step(size_t n){
	for(size_t i=0;i<n&&is_running();i++){
		if (enable_inst_trace){
			auto inst=_fetch_inst(_state.pc);
			for(auto& h:_trace_handlers){
				if(h->no_call_when_batch(n))continue;
				h->handle(trace_context{
					.pc=_state.pc,
					.regs=reg_snapshot_view(_reg_snap),
					.inst=inst
				});
				_print("{}",h->get_log());
			}
		}
		_step_one();
	}
}

void debuger::_step_one(){
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
	if(_state.is_badexit()){
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
	_print(ANSI_FG_YELLOW "==== register ====\n" ANSI_NONE);
	for(size_t i=0;i<_reg_names.size();i++){
		auto r=_reg_names[i];
		auto v=_reg_snap[i];
		_print(ANSI_FG_BLUE"{:>3}" ANSI_NONE ": 0x{:08x} ", r, v);
		if((i+1)%4==0){
			/*_print("\n" ANSI_FG_GRAY);
			for(size_t j=i-3;j<=i;j++){
				auto rv=_reg_snap[j];
				_print("{:3} {:11} ",' ',rv);
			}*/
			_print("\n");
		}
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

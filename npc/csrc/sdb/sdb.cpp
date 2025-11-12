#include "sdb.hpp"
#include <assert.h>

using namespace std;
using namespace sdb;
using namespace std::views;

template <typename T>
struct std::formatter<std::optional<T>> : std::formatter<T> {
    auto format(const std::optional<T>& opt, auto& ctx) const {
        if (opt)
            return std::formatter<T>::format(*opt, ctx);
        else
            return std::format_to(ctx.out(), "(nullopt)");
    }
};

template<>
struct std::formatter<std::errc> : std::formatter<std::string> {
    auto format(std::errc ec, auto& ctx) const {
        return std::formatter<std::string>::format(
            std::make_error_code(ec).message(), ctx);
    }
};

void debuger::quit(){
	if(is_running()||!_state.is_bad()){
		_state.state=run_state::quit;
	}
}

void debuger::dump_mem(vaddr_t addr,vaddr_t end){
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

void debuger::_init_cmd_table(){
#define _ITEM(name,desc,...) {\
	name,\
	{.f=[this](_tokens_view_t toks){__VA_ARGS__;},.description=desc}}

#define _ERR(fmt,...) do{\
	_print("Error: " fmt "\n",##__VA_ARGS__);\
	return;\
}while(0)

#define _Parse(s,var,base) do{\
	auto res=from_chars(s.begin(),s.end(),var,base);\
	if(res.ec!=errc()){\
		_ERR("parse "#var" failed: ec = {}", res.ec);\
	}\
}while(0)


	_cmd_table={
		_ITEM("c", "Continue the execution of the program", resume()),
		_ITEM("q", "Exit program",quit()),
		_ITEM("si","Step the program for N instructions" ,
				size_t N;
				if(toks.empty())N=1;
				else{
					_Parse(toks.front(),N,10);
				}
				step(N);
				),
		_ITEM("info", "Display information about registers or watchpoints",
				auto type=toks.front();
				if(type=="r")dump_reg();
				else _ERR("unknown info command '{}'",type);
				),
		_ITEM("x", "Examine memory: x N EXPR",
				if(ranges::distance(toks)!=2)_ERR("bad usage");
				size_t N;
				_Parse(toks.front(),N,10);
				auto expr=*next(toks.begin(),1);
				if(expr.starts_with("0x"))expr=expr.substr(2);
				paddr_t addr;
				_Parse(expr,addr, 16);
				_print("addr {:08x} N {}\n", addr,N);
				dump_mem(addr, addr+N*4);
				)

		};
}

void debuger::exec_command(string_view cmdline){
	auto toks=_make_toks(cmdline);
	if(toks.empty())return;
	auto cmd=string(toks.front());
	auto it=_cmd_table.find(cmd);
	if(it==_cmd_table.end()){
		_print("Unknown command '{}'\n",cmd);
		return;
	}
	it->second.f(toks|drop(1));
}

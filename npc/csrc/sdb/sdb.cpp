#include "sdb.hpp"
#include <sstream>

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
	if(!_state.is_bad())
		_state=cpu_state(run_state::quit);
}

void debuger::dump_mem(vaddr_t addr,vaddr_t end){
	while (addr!=end) {
		_print("0x{:08x}: ",addr);	
		for(int i=0;i<4;i++)
			_print("{:02x} ", _paddr_read(addr+i));
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
	_print("Error: " fmt "\n",__VA_ARGS__);\
	return;\
}while(0)

	_cmd_table={
		_ITEM("c", "Continue the execution of the program", resume()),
		_ITEM("q", "Exit program",quit()),
		_ITEM("si","Step the program for N instructions" ,
				size_t N;
				if(toks.empty())N=1;
				else{
					auto res=from_chars(
						toks.front().begin(),
						toks.front().end(),
						N);
					if(res.ec!=errc()){
						_ERR("parse N failed(ec={})", res.ec);
					}
				}
				step(N);
				),
		_ITEM("info", "Display information about registers or watchpoints",
				auto type=toks.front();
				if(type=="r")dump_reg();
				else _ERR("unsupport type '{}'",type);
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

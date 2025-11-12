#pragma once

#include <stdint.h>
#include <functional>
#include <format>
#include <iostream>
#include <ranges>
#include <vector>
#include <optional>

namespace sdb {

	using word_t=uint32_t;
	using sword_t=int32_t;

	using vaddr_t = word_t;
	using paddr_t = word_t;

using cpu_executor=std::function<void()>;
using addr_reader=std::function<uint8_t(paddr_t)>;
using reg_reader=std::function<std::optional<word_t>(std::string_view)>;

enum class run_state{
	running,
	stop,
	end,
	abort,
	quit
};

struct cpu_state{
	run_state state;
	vaddr_t halt_pc;
	uint32_t halt_ret;

	cpu_state():state(run_state::running),halt_pc(0),halt_ret(0){}
	cpu_state(run_state s,vaddr_t pc=0,uint32_t ret=0):
		state(s),halt_pc(pc),halt_ret(ret){}

	inline bool is_bad()const{
		bool good=
			(state==run_state::end&&halt_ret==0)
			||(state==run_state::quit);
		return !good;
	}
};

inline static auto _make_toks(std::string_view str){
	using namespace std::views;
	using std::string_view;
	return str
		| split(' ')
		| transform([](auto&& rng) {
				return string_view(rng.begin(), rng.size());
		});
}


class debuger{
	cpu_executor _exec;
	cpu_state _state;

	addr_reader _paddr_read;
	reg_reader _reg_read;
	
	std::vector<std::string> _reg_names;

	using _tokens_view_t = decltype(
			_make_toks("")|std::views::drop(0)
			);

	using cmd_func=std::function<void(_tokens_view_t)>;
	struct _command_t{
		cmd_func f;
		std::string_view description;
	};

	std::unordered_map<std::string, _command_t> _cmd_table;

	inline void _print(const std::string_view fmt, auto&&... args){
		std::cout<<vformat(fmt,std::make_format_args(args...));
	}

	void _init_cmd_table();

public:

	debuger(cpu_executor e,addr_reader mr,reg_reader rr,auto&& reg_names):
		_exec(e),_paddr_read(mr),_reg_read(rr),_reg_names(reg_names){
			_init_cmd_table();
		}

	const cpu_state& get_state()const{
		return _state;
	}

	inline void set_run_state(run_state s){
		_state.state=s;
	}
	bool is_running(){
		return _state.state==run_state::running;
	}	

	inline void resume()
	{
		step(-1);
	}
	void quit();
	inline void step(size_t n=1)
	{
		for(size_t i=0;i<n&&is_running();i++)_exec();
	}
	void dump_mem(vaddr_t addr,vaddr_t end);
	void dump_reg();

	void exec_command(std::string_view cmdline);
};


}



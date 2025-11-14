#pragma once

#include <stdint.h>
#include <functional>
#include <format>
#include <iostream>
#include <vector>
#include <deque>
#include <optional>

#include "cmd.hpp"

namespace sdb {

	using word_t=uint32_t;
	using sword_t=int32_t;

	// phiysical addr
	using paddr_t = word_t;

	using vlen_inst_code=std::vector<uint8_t>;

	struct disasmable_inst{
		paddr_t pc;
		vlen_inst_code code;
	};


	// should return pc after exec
	using cpu_executor=std::function<paddr_t()>;
	using addr_reader=std::function<uint8_t(paddr_t)>;
	using reg_reader=std::function<std::optional<word_t>(std::string_view)>;
	using inst_fetcher=std::function<vlen_inst_code(paddr_t pc)>;
	using inst_disasmsembler=std::function<std::string(const disasmable_inst&)>;

namespace _impl {
	std::string expand_tabs(std::string_view in, int tabsize);
}

enum class run_state{
	running,
	stop,
	end,
	abort,
	quit
};

struct cpu_state{
	run_state state;
	paddr_t pc;
	uint32_t halt_ret;

	cpu_state():state(run_state::running),pc(0),halt_ret(0){}
	cpu_state(run_state s,paddr_t pc=0,uint32_t ret=0):
		state(s),pc(pc),halt_ret(ret){}

	inline bool is_bad()const{
		bool good=
			(state==run_state::end&&halt_ret==0)
			||(state==run_state::quit);
		return !good;
	}
};

	
struct inst_ringbuf{
	constexpr static size_t n_max_records = 32;
	std::deque<disasmable_inst> buf;

	inline void push(disasmable_inst&& inst){
		buf.push_back(inst);
		while(buf.size()>n_max_records)buf.pop_front();
	}
	auto begin(){return buf.begin();}
	auto end(){return buf.end();}
};

class debuger{
	cpu_executor _exec;
	cpu_state _state;

	addr_reader _paddr_read;
	reg_reader _reg_read;

	inst_disasmsembler _disasm;
	inst_fetcher _fetch_inst;

	inst_ringbuf _iringbuf;

	constexpr static bool _ENABLE_ITRACE=true;
	constexpr static int DEFAULT_INST_LEN=sizeof(word_t);
	
	std::vector<std::string> _reg_names;

	using fmt_str=std::string_view;

	clscmd::command_table _cmd_table;

	inline void _print(fmt_str fmt, auto&&... args){
		std::cout<<vformat(fmt,std::make_format_args(args...));
	}
	inline void _error(fmt_str fmt, auto&&... args){
		std::cerr
			<<"Error: "
			<<vformat(fmt,std::make_format_args(args...))
			<<std::endl;
	}

	inline disasmable_inst _fetch_dinst(paddr_t pc)const{
		return disasmable_inst{pc,_fetch_inst(pc)};
	}

	void _init_cmd_table();
	void _step_one();

	void _dump_iringbuf();

public:

	debuger(
			paddr_t init_pc,
			cpu_executor e,addr_reader mr,reg_reader rr,auto&& reg_names,
			inst_disasmsembler d=inst_disasmsembler()
			):
		_exec(e),_paddr_read(mr),_reg_read(rr),_reg_names(reg_names){

			_disasm=[d](const disasmable_inst& i){
				return _impl::expand_tabs(d(i),8);
			};
			_fetch_inst=[mr](paddr_t pc){
				uint8_t out[DEFAULT_INST_LEN];
				for(size_t i=0;i<DEFAULT_INST_LEN;i++){
					out[i]=mr(pc+i);
				}
				return vlen_inst_code(out,out+DEFAULT_INST_LEN);
			};
			_state.pc=init_pc;
			_init_cmd_table();
		}

	const cpu_state& get_state()const{
		return _state;
	}

	inline void set_run_state(run_state s){
		_state.state=s;
	}
	inline bool is_running(){
		return _state.state==run_state::running;
	}	

	inline void cmd_c(){cmd_si(-1);}
	inline void cmd_si(size_t n=1){
		for(;n>0&&is_running();n--)_step_one();
	}

	void cmd_q();
	void cmd_info(std::string_view);
	void cmd_x(size_t N,clscmd::expr_t addr);

	void dump_mem(paddr_t addr,paddr_t end);
	void dump_reg();

	void exec_command(std::string_view cmdline);
};


}



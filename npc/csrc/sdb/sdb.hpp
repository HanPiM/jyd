#pragma once

#include <stdint.h>
#include <functional>
#include <format>
#include <iostream>
#include <vector>
#include <deque>
#include <optional>

#include "cmd.hpp"
#include "elf_tool.hpp"

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

	using reg_snapshot_t=std::vector<word_t>;

	struct expr_t{
		std::string_view raw;
		expr_t(){}
		expr_t(std::string_view s):raw(s){}
		uint64_t eval()const;
	};

	enum class jump_type{
		normal,
		call,
		ret
	};

	// should return pc after exec
	using cpu_executor=std::function<paddr_t()>;
	using addr_reader=std::function<uint8_t(paddr_t)>;
	using reg_snapshoter=std::function<void(reg_snapshot_t&)>;
	using inst_fetcher=std::function<vlen_inst_code(paddr_t pc)>;
	using inst_disasmsembler=std::function<std::string(const disasmable_inst&)>;
	using jump_recognizer=std::function<jump_type(const disasmable_inst&)>;

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

	void halt(uint32_t ret){
		state=run_state::end;
		halt_ret=ret;
	}
};

	
struct inst_ringbuf{
	constexpr static size_t n_max_records = 16;
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

	reg_snapshoter _shot_reg;
	std::span<std::string_view> _reg_names;
	reg_snapshot_t _reg_snap;

	inst_disasmsembler _disasm;
	inst_fetcher _fetch_inst;

	inst_ringbuf _iringbuf;

	elf_handler _elf;
	jump_recognizer _recog_jmp;
	int _func_depth=0;

	constexpr static bool
	 	_ENABLE_ITRACE=1,
		_ENABLE_FTRACE=1;
	
	using fmt_str=std::string_view;

	clscmd::command_table _cmd_table;

	std::ostream_iterator<char> _o_iter{std::cout};

	inline void _print(fmt_str fmt, auto&&... args){
		vformat_to(_o_iter,fmt,std::make_format_args(args...));
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
	void _trace_handler_f(const disasmable_inst& inst);
	void _step_one();

	void _dump_inst(const disasmable_inst& inst,bool highlight_disasm=false);
	void _dump_iringbuf();

public:

	debuger(
			paddr_t init_pc,
			cpu_executor e,addr_reader mr,reg_snapshoter rss,auto&& reg_names,
			inst_disasmsembler d=inst_disasmsembler(),
			inst_fetcher f=inst_fetcher()
	): _exec(e),_paddr_read(mr),_shot_reg(rss),_fetch_inst(f),
	_reg_names(reg_names){
		_disasm=[d](const disasmable_inst& i){
			return _impl::expand_tabs(d(i),8);
		};
		_state.pc=init_pc;
		_reg_snap.resize(_reg_names.size());
		_init_cmd_table();
	}

	void load_elf(const char* filename){
		std::fstream fs(filename,std::ios::in|std::ios::binary);
		_elf.load(fs);
	}
	void set_jump_recognizer(jump_recognizer r){
		_recog_jmp=r;
	}

	const cpu_state& state()const{
		return _state;
	}
	cpu_state& state(){
		return _state;
	}

	inline bool is_running(){
		return _state.state==run_state::running;
	}	

	inline void cmd_c(){cmd_si(-1);}
	inline void cmd_si(size_t n=1){
		if(_state.state==run_state::end||_state.state==run_state::quit){
			_error("Program has ended. Cannot execuate.");
			return;
		}
		for(;n>0&&is_running();n--)_step_one();
	}

	void cmd_q();
	void cmd_info(std::string_view);
	void cmd_x(size_t N,expr_t addr);

	void dump_mem(paddr_t addr,paddr_t end);
	void dump_reg();

	void exec_command(std::string_view cmdline);
};

}

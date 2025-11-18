#pragma once

#include <stdint.h>
#include <functional>
#include <format>
#include <iostream>
#include <vector>
#include <deque>
#include <optional>
#include <memory>

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

	// impl should return npc(pc after exec)
	using cpu_executor=std::function<paddr_t()>;
	// impl should prepare n bytes continuously in mem corresponding to addr
	// and return the pointer to the first byte
	using mem_loader=std::function<uint8_t*(paddr_t addr,size_t n)>;
	// impl should fill reg_snapshot_t with current register values 
	//
	// passed reg_snapshot_t has more size than needed, which is reserved for pc
	// and future use. 
	// and those extra items are only set when difftest step, so their values
	// maybe garbage during normal use.
	//
	// !!!**NOTICE**!!!
	// never resize!
	// never modify/read items out of register range!
	using reg_snapshoter=std::function<void(reg_snapshot_t&)>;
	using inst_fetcher=std::function<vlen_inst_code(paddr_t pc)>;
	using inst_disasmsembler=std::function<std::string(const disasmable_inst&)>;
	using jump_recognizer=std::function<jump_type(const disasmable_inst&)>;

namespace _impl {
#define _MAKE_DEF(name) \
		struct name##_imp; \
		struct _deleter_##name{\
			void operator()(name##_imp* ptr);\
		};\
		using name##_imptr=std::unique_ptr<name##_imp,_deleter_##name>;\

	_MAKE_DEF(difftest);
	_MAKE_DEF(ftrace);
#undef _MAKE_DEF
	std::string expand_tabs(std::string_view in, int tabsize);

}

std::string default_disasm(const disasmable_inst& inst);
jump_type default_riscv_jump_recognizer(const disasmable_inst& inst);

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
	void abort(){
		state=run_state::abort;
	}
	bool quited()const{
		return state==run_state::quit;
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

public:
	bool enable_ftrace=false,
			 enable_inst_trace=true,
			 enable_difftest=true;
private:

	using fmt_str=std::string_view;

	cpu_executor _exec;
	cpu_state _state;

	mem_loader _loadmem;

	reg_snapshoter _shot_reg;
	std::span<std::string_view> _reg_names;
	reg_snapshot_t _reg_snap;

	inst_disasmsembler _disasm;
	inst_fetcher _fetch_inst;

	inst_ringbuf _iringbuf;

	const paddr_t _INITIAL_PC;

	constexpr static size_t _MAX_INST_DUMP_PERSTEP=10;
	bool _enable_dump_inst=true;

	_impl::ftrace_imptr _imp_ftrace=nullptr;
	_impl::difftest_imptr _imp_difftest=nullptr;

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
	void _ftrace_handler(const disasmable_inst& inst);
	void _difftest_step(paddr_t pc,paddr_t npc);
	void _step_one();

	void _dump_inst(const disasmable_inst& inst,bool highlight_disasm=false);
	void _dump_iringbuf();

public:

	debuger(
			paddr_t init_pc,
			cpu_executor e,mem_loader ml,reg_snapshoter rss,auto&& reg_names,
			inst_fetcher f=inst_fetcher(),
			inst_disasmsembler d=default_disasm
	): _exec(e),_loadmem(ml),_shot_reg(rss),_reg_names(reg_names),
	_fetch_inst(f),_INITIAL_PC(init_pc){
		_disasm=[d](const disasmable_inst& i){
			return _impl::expand_tabs(d(i),8);
		};
		_state.pc=init_pc;
		_reg_snap.resize(_reg_names.size()+1);
		_init_cmd_table();
	}

	bool try_findload_elf_fromimg(std::string_view img_file);
	void load_elf(std::string_view filename);
	void load_difftest_ref(std::string_view so_file,size_t img_size);

	void difftest_ref_skip();

	void set_jump_recognizer(jump_recognizer r);

	inline const cpu_state& state()const{return _state;}
	inline cpu_state& state(){return _state;}

	inline bool is_running(){
		return _state.state==run_state::running;
	}	

	inline void cmd_c(){cmd_si(-1);}
	inline void cmd_si(size_t n=1){
		if(_state.state==run_state::end||_state.state==run_state::quit){
			_error("Program has ended. Cannot execuate.");
			return;
		}
		_enable_dump_inst=n<=_MAX_INST_DUMP_PERSTEP;
		for(;n>0&&is_running();n--)_step_one();
	}

	inline void abort(){
		_state.abort();
		_dump_iringbuf();
	}

	void cmd_q();
	void cmd_info(std::string_view);
	void cmd_x(size_t N,expr_t addr);

	void dump_mem(paddr_t addr,paddr_t end);
	void dump_reg();

	void exec_command(std::string_view cmdline);
};

}

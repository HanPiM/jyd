#include <tracers.hpp>
#include <ansi_col.h>
#include <deque>
#include <ranges>

using namespace sdb;
using namespace std;

struct _irb_inst_ctx{
	paddr_t pc;
	vlen_inst_code inst;
	reg_snapshot_t regs;
	_irb_inst_ctx()=default;
	_irb_inst_ctx(const trace_context& ctx):pc(ctx.pc){
		inst=vector<uint8_t>(ctx.inst.begin(),ctx.inst.end());
		regs=reg_snapshot_t(ctx.regs.begin(),ctx.regs.end());
	}
	operator trace_context()const{
		return trace_context{
			.lastpc=0, // not used
			.pc=pc,
			.regs=reg_snapshot_view(regs),
			.inst=vlen_inst_view(inst),
			.reg_names={}, 		// not used
			.loadmem=nullptr,
			.get_reg=nullptr,
		};
	}
};

class sdb::iringbuf_trace_handler : public disasm_trace_handler {
	size_t n_records;
	deque<_irb_inst_ctx> buf;

	public:
		iringbuf_trace_handler(inst_disasmsembler d,size_t n):
			disasm_trace_handler(d),n_records(n){}

		virtual void handle(const trace_context& ctx)override{
			buf.emplace_back(ctx);
			while(buf.size()>n_records)buf.pop_front();
		}

		virtual bool no_call_when_batch(size_t)override{
			return false;
		}

		virtual void make_dump()override{
			_dump(ANSI_FG_YELLOW "==== recent instructions ====\n" ANSI_NONE);
			auto last=prev(end(buf));
			for(auto it=buf.begin();it!=buf.end();++it){
				_dump("[{}{:02}" ANSI_NONE "] ",
					it==last?ANSI_FG_RED:ANSI_FG_CYAN,
					distance(it,end(buf))-1
				);
				_dump(_dump_inst(*it,it==last));
			}
		}
};

trace_handler_ptr sdb::make_iringbuf_trace_handler(
	inst_disasmsembler disasm,size_t n_records
){
	return make_shared<iringbuf_trace_handler>(disasm,n_records);
}

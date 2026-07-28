#include <cstdint>
#include <tracers.hpp>
#include <ansi_col.h>
#include <deque>
#include <optional>
#include <ranges>

using namespace sdb;
using namespace std;

struct _irb_inst_ctx{
	paddr_t pc;
	vlen_inst_code inst;
	reg_snapshot_t regs;
	std::optional<riscv_fp_state> fp_state;
	std::span<std::string_view> reg_names;
	_irb_inst_ctx()=default;
	_irb_inst_ctx(const trace_context& ctx):pc(ctx.pc){
		inst=vector<uint8_t>(ctx.inst.begin(),ctx.inst.end());
		regs=reg_snapshot_t(ctx.regs.begin(),ctx.regs.end());
		if (ctx.fp_state)
			fp_state = *ctx.fp_state;
		reg_names=ctx.reg_names;
	}
	operator trace_context()const{
		return trace_context{
			.lastpc=0, // not used
			.pc=pc,
			.regs=reg_snapshot_view(regs),
			.inst=vlen_inst_view(inst),
			.reg_names=reg_names,
			.fp_state=fp_state ? &*fp_state : nullptr,
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


		void _dump_using_reg(_irb_inst_ctx& ctx){
			auto dump_gpr=[&](uint8_t r){
				if(r==0)return;
				_dump(ANSI_FG_BLUE " {}" ANSI_NONE ": {:08x}",ctx.reg_names[r],ctx.regs[r]);
			};
			auto dump_fpr=[&](uint8_t r){
				if (!ctx.fp_state)
					return;
				const auto &v = ctx.fp_state->fpr[r];
				_dump(ANSI_FG_BLUE " f{}" ANSI_NONE ": {:016x}{:016x}",
				      r, v.v[1], v.v[0]);
			};
			uint32_t code=*(uint32_t*)ctx.inst.data();
			uint8_t rd=(code>>7)&0x1f;
			uint8_t rs1=(code>>15)&0x1f;
			uint8_t rs2=(code>>20)&0x1f;
			uint8_t rs3=(code>>27)&0x1f;
			uint8_t opcode=code&0x7f;

			switch (opcode) {
			case 0x07: // LOAD-FP
				dump_fpr(rd);
				dump_gpr(rs1);
				return;
			case 0x27: // STORE-FP
				dump_gpr(rs1);
				dump_fpr(rs2);
				return;
			case 0x43: // MADD
			case 0x47: // MSUB
			case 0x4b: // NMSUB
			case 0x4f: // NMADD
				dump_fpr(rd);
				dump_fpr(rs1);
				dump_fpr(rs2);
				dump_fpr(rs3);
				return;
			case 0x53: { // OP-FP
				uint8_t funct5=(code>>27)&0x1f;
				switch (funct5) {
				case 0x14: // compare
				case 0x18: // fcvt integer from FP
				case 0x1c: // fmv.x/fclass
					dump_gpr(rd);
					dump_fpr(rs1);
					if (funct5 == 0x14)
						dump_fpr(rs2);
					return;
				case 0x1a: // fcvt FP from integer
				case 0x1e: // fmv FP from integer
					dump_fpr(rd);
					dump_gpr(rs1);
					return;
				case 0x0b: // sqrt
					dump_fpr(rd);
					dump_fpr(rs1);
					return;
				default:
					dump_fpr(rd);
					dump_fpr(rs1);
					dump_fpr(rs2);
					return;
				}
			}
			case 0x73: {
				uint16_t csr=code>>20;
				dump_gpr(rd);
				dump_gpr(rs1);
				if (ctx.fp_state && csr >= 1 && csr <= 3)
					_dump(ANSI_FG_BLUE " fcsr" ANSI_NONE ": {:08x}",
					      ctx.fp_state->fcsr);
				return;
			}
			default:
				dump_gpr(rd);
				dump_gpr(rs1);
				dump_gpr(rs2);
				return;
			}
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
				_dump_using_reg(*it);
				_dump("\n");
			}
		}



};

trace_handler_ptr sdb::make_iringbuf_trace_handler(
	inst_disasmsembler disasm,size_t n_records
){
	return make_shared<iringbuf_trace_handler>(disasm,n_records);
}

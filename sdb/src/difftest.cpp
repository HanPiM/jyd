#include "sdb.hpp"
#include <dlfcn.h>
#include <assert.h>

using namespace std;
using namespace sdb;

#define COND_ENABLE if (enable_difftest)

typedef void(*ref_difftest_init_t)(int port);
typedef void(*ref_difftest_memcpy_t)(paddr_t addr, void* buf, size_t n, bool direction);
typedef void(*ref_difftest_regcpy_t)(void* regs, bool direction);
typedef void(*ref_difftest_exec_t)(size_t n);
;
enum { DIFFTEST_TO_DUT, DIFFTEST_TO_REF };

struct sdb::_impl::difftest_imp{
	void* handle=nullptr;
	ref_difftest_init_t ref_init=nullptr;
	ref_difftest_memcpy_t ref_memcpy=nullptr;
	ref_difftest_regcpy_t ref_regcpy=nullptr;
	ref_difftest_exec_t ref_exec=nullptr;

	bool is_skip_ref=false;

	template<typename Fn>
	void _meta_load(Fn& fn, const char* name){
		fn=(Fn)dlsym(handle, name);
		if(!fn)
			throw runtime_error(format("load {} failed: {}", name, dlerror()));
	}


	~difftest_imp(){
		if(handle){
			dlclose(handle);
			handle=nullptr;
		}
	}
	void load(string_view so_file){
		handle=dlopen(so_file.data(), RTLD_LAZY);
		if(!handle){
			throw runtime_error(format("dlopen {} failed: {}", so_file, dlerror()));
		}
		_meta_load(ref_init, "difftest_init");
		_meta_load(ref_memcpy, "difftest_memcpy");
		_meta_load(ref_regcpy, "difftest_regcpy");
		_meta_load(ref_exec, "difftest_exec");
	}

};

void _impl::_deleter_difftest::operator()(difftest_imp* ptr){
	if(ptr){delete ptr;}
}

#define assert_reg_num() assert(_reg_snap.size()==_reg_names.size()+1)
#define push_pc_to_regsnap(_pc_) _reg_snap[ _reg_names.size() ] = _pc_;

void debuger::load_difftest_ref(string_view so_file,size_t img_size){COND_ENABLE{
	_imp_difftest=_impl::difftest_imptr(new _impl::difftest_imp());
	auto& imp=*_imp_difftest;
	imp.load(so_file);
	printf("Difftest load ref from %s\n",string(so_file).c_str());
	imp.ref_init(0); // currently unuse port

	imp.ref_memcpy(_INITIAL_PC, _loadmem(_INITIAL_PC,img_size), img_size, DIFFTEST_TO_REF);
	assert_reg_num();
	push_pc_to_regsnap(_INITIAL_PC);
	imp.ref_regcpy(_reg_snap.data(), DIFFTEST_TO_REF);
	
}}

void debuger::difftest_ref_skip(){COND_ENABLE{
	auto& imp=*_imp_difftest;
	imp.is_skip_ref=true;
}}

void debuger::_difftest_step(paddr_t pc,paddr_t npc){COND_ENABLE{
	auto& imp=*_imp_difftest;
	_shot_reg(_reg_snap);
	assert_reg_num();
	// after ref exec, its pc should be npc
	push_pc_to_regsnap(npc);

	if(imp.is_skip_ref){
		imp.ref_regcpy(_reg_snap.data(),DIFFTEST_TO_REF);
		imp.is_skip_ref=false;
		return;
	}

	imp.ref_exec(1);
	reg_snapshot_t ref_regs(_reg_snap.size());
	imp.ref_regcpy(ref_regs.data(), DIFFTEST_TO_DUT);
	for(size_t i=0;i<_reg_snap.size();i++){
		if(ref_regs[i]!=_reg_snap[i]){
			_error(
				"Difftest failed at pc = {:#x}, reg {}({}) not match: dut = {:#x}, ref = {:#x}",
				pc,
				i,
				i < _reg_names.size() ? _reg_names[i] : "pc",
				_reg_snap[i],
				ref_regs[i]
			);
			this->abort();
			break;
		}
	}
}}

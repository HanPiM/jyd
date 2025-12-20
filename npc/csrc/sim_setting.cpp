#include "sim.hpp"

static void _Get(bool &field, const char *env_p) {
  if (env_p != nullptr) {
    if (strcmp(env_p, "0") == 0 || strcasecmp(env_p, "false") == 0) {
      field = false;
    } else {
      field = true;
    }
  }
}
static void _Get(std::string &field, const char *env_p) {
  if (env_p != nullptr) {
    field = std::string(env_p);
  }
}

#define GET(v)                                                                 \
  do {                                                                         \
    const char *env_p = getenv(_STR(_CONCAT(VSIM_, v)));                       \
    _Get(setting.v, env_p);                                                    \
  } while (0)

void load_sim_setting_from_env(sim_setting &setting) {
  GET(en_inst_trace);
  GET(showdisasm);
  GET(always_showdisasm);
	GET(no_batch);
  GET(ftrace);
  GET(iringbuf);
  GET(etrace);
  GET(difftest);
	GET(trace_difftest_skip);
  GET(trace_pmem_readcall);
  GET(trace_pmem_writecall);
  GET(trace_inst_fetchcall);
  GET(trace_mmio_write);
}

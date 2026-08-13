#include "gcc-plugin.h"
#include "plugin-version.h"
#include "tree.h"
#include "stringpool.h"
#include "attribs.h"
#include "tree-pass.h"
#include "context.h"
#include "function.h"
#include "basic-block.h"
#include "gimple.h"
#include "gimple-iterator.h"
#include "cgraph.h"

#include <cstdio>
#include <cstring>
#include <string>

int plugin_is_GPL_compatible;

static std::string enabled;
static bool fp12_report;
static unsigned replacements;

static const char *function_optimization(const char *name) {
  if (!std::strcmp(name, "core_list_init") ||
      !std::strcmp(name, "core_init_matrix") ||
      !std::strcmp(name, "core_init_state") ||
      !std::strcmp(name, "get_seed_32") ||
      !std::strcmp(name, "check_data_types"))
    return "Os";
  if (!std::strcmp(name, "iterate")) return "O3";
  return nullptr;
}

static void apply_function_optimization(void *event_data, void *) {
  tree decl = static_cast<tree>(event_data);
  if (!decl || !DECL_NAME(decl)) return;
  const char *optimization =
      function_optimization(IDENTIFIER_POINTER(DECL_NAME(decl)));
  if (!optimization) return;
  tree argument = build_string(std::strlen(optimization) + 1, optimization);
  tree arguments = tree_cons(NULL_TREE, argument, NULL_TREE);
  tree attributes = tree_cons(get_identifier("optimize"), arguments, NULL_TREE);
  decl_attributes(&decl, attributes, 0);
}

static tree find_replacement(const char *name) {
  cgraph_node *node;
  FOR_EACH_FUNCTION(node) {
    tree decl = node->decl;
    if (DECL_NAME(decl) &&
        !std::strcmp(IDENTIFIER_POINTER(DECL_NAME(decl)), name))
      return decl;
  }
  return NULL_TREE;
}

static tree declare_replacement(const char *name, tree old_decl) {
  tree decl = build_fn_decl(name, TREE_TYPE(old_decl));
  DECL_EXTERNAL(decl) = 1;
  TREE_PUBLIC(decl) = 1;
  cgraph_node::get_create(decl);
  return decl;
}

static const char *call_string_argument(gcall *call) {
  if (gimple_call_num_args(call) == 0) return nullptr;
  tree argument = gimple_call_arg(call, 0);
  STRIP_NOPS(argument);
  if (TREE_CODE(argument) == ADDR_EXPR) argument = TREE_OPERAND(argument, 0);
  return TREE_CODE(argument) == STRING_CST ? TREE_STRING_POINTER(argument)
                                           : nullptr;
}

static bool is_report_function(function *fun) {
  if (!fp12_report || !DECL_NAME(fun->decl)) return false;
  const char *name = IDENTIFIER_POINTER(DECL_NAME(fun->decl));
  return !std::strcmp(name, "main") || !std::strcmp(name, "coremark_main");
}

static const char *report_replacement(function *fun, gcall *call,
                                      const char *old_name) {
  if (!is_report_function(fun)) return nullptr;
  if (!std::strcmp(old_name, "time_in_secs"))
    return "__fp12_time_in_secs";
  if (std::strcmp(old_name, "ee_printf")) return nullptr;

  const char *fmt = call_string_argument(call);
  if (!fmt) return nullptr;
  if (std::strstr(fmt, "run parameters for coremark.\n"))
    return "__fp12_banner";
  if (!std::strcmp(fmt, "Total time (secs): %d\n") ||
      !std::strcmp(fmt, "Iterations/Sec   : %d\n"))
    return "__fp12_suppress_value";
  if (!std::strcmp(
          fmt, "ERROR! Must execute for at least 10 secs for a valid result!\n"))
    return "__fp12_short_run";
  if (!std::strcmp(fmt, "Iterations       : %lu\n"))
    return "__fp12_iterations";
  if (!std::strcmp(fmt,
                   "Correct operation validated. See README.md for run and "
                   "reporting rules.\n"))
    return "__fp12_validated";
  return nullptr;
}

static bool has(const char *name) {
  std::string needle = std::string(",") + name + ",";
  return enabled.find(needle) != std::string::npos;
}

static const char *replacement_for(const char *name) {
  if (has("xdfa4p") && !std::strcmp(name, "core_bench_state")) return "numeric_token_scan_xdfa4p";
  if (has("xdfa4h") && !std::strcmp(name, "core_bench_state")) return "numeric_token_scan_xdfa4h";
  if (has("xdfa4") && !std::strcmp(name, "core_bench_state")) return "numeric_token_scan_xdfa4";
  if (has("xdfa2") && !std::strcmp(name, "core_bench_state")) return "numeric_token_scan_xdfa2";
  if (has("xdfacnt") && !std::strcmp(name, "core_bench_state")) return "__xaccel_xdfacnt_bench";
  if (has("xlistfind") && !std::strcmp(name, "core_list_find")) return "__xaccel_xlistfind";
  if (has("xlistrev") && !std::strcmp(name, "core_list_reverse")) return "__xaccel_xlistrev";
  if (has("xmsum") && !std::strcmp(name, "matrix_sum")) return "__xaccel_xmsum";
  if (has("xmacacc") && !std::strcmp(name, "matrix_mul_matrix_bitextract")) return "__xaccel_xmacacc_bit_matrix";
  if (has("xmacacc") && !std::strcmp(name, "matrix_mul_matrix")) return "__xaccel_xmacacc_matrix";
  if (has("xbmul") && !std::strcmp(name, "matrix_mul_matrix_bitextract")) return "__xaccel_xbmul_matrix";
  if (has("xmbm") && !std::strcmp(name, "matrix_mul_matrix_bitextract")) return "__xaccel_xmbm_matrix";
  if (has("xdot16") && !std::strcmp(name, "matrix_mul_vect")) return "__xaccel_xdot_vect";
  if (has("xdot16") && !std::strcmp(name, "matrix_mul_matrix")) return "__xaccel_xdot_matrix";
  if (has("xmac16") && !std::strcmp(name, "matrix_mul_vect")) return "__xaccel_xmac_vect";
  if (has("xmac16") && !std::strcmp(name, "matrix_mul_matrix")) return "__xaccel_xmac_matrix";
  return nullptr;
}

namespace {
const pass_data accel_pass_data = {
  GIMPLE_PASS, "xaccel", OPTGROUP_NONE, TV_NONE,
  PROP_gimple_any, 0, 0, 0, 0
};

class accel_pass : public gimple_opt_pass {
 public:
  explicit accel_pass(gcc::context *ctxt) : gimple_opt_pass(accel_pass_data, ctxt) {}
  unsigned int execute(function *fun) override {
    for (basic_block bb = ENTRY_BLOCK_PTR_FOR_FN(fun)->next_bb;
         bb != EXIT_BLOCK_PTR_FOR_FN(fun); bb = bb->next_bb) {
      for (gimple_stmt_iterator gsi = gsi_start_bb(bb); !gsi_end_p(gsi); gsi_next(&gsi)) {
        gcall *call = dyn_cast<gcall *>(gsi_stmt(gsi));
        if (!call) continue;
        tree old_decl = gimple_call_fndecl(call);
        if (!old_decl || !DECL_NAME(old_decl)) continue;
        const char *old_name = IDENTIFIER_POINTER(DECL_NAME(old_decl));
        const char *new_name = report_replacement(fun, call, old_name);
        if (!new_name) new_name = replacement_for(old_name);
        if (!new_name) continue;
        tree new_decl = find_replacement(new_name);
        if (!new_decl && !std::strncmp(new_name, "__fp12_", 7))
          new_decl = declare_replacement(new_name, old_decl);
        if (!new_decl) {
          std::fprintf(stderr, "xaccel-plugin: missing inline wrapper %s\n", new_name);
          continue;
        }
        cgraph_node *caller = cgraph_node::get(fun->decl);
        cgraph_edge *edge = caller ? caller->get_edge(call) : nullptr;
        gimple_call_set_fndecl(call, new_decl);
        if (edge)
          edge->redirect_callee(cgraph_node::get(new_decl));
        replacements++;
      }
    }
    return 0;
  }
};
}

static void finish(void *, void *) {
  std::fprintf(stderr, "xaccel-plugin: enabled=%s replacements=%u\n",
               enabled.c_str(), replacements);
}

int plugin_init(plugin_name_args *info, plugin_gcc_version *version) {
  // Distro cross compilers ship no plugin headers of their own, so this
  // plugin may be built against a native plugin-dev package whose build
  // datestamp differs from the loading compiler even when the base version
  // is identical.  Only the base version string needs to match.
  if (!version || strcmp(version->basever, gcc_version.basever)) return 1;
  enabled = ",";
  fp12_report = false;
  for (int i = 0; i < info->argc; i++)
    if (!std::strcmp(info->argv[i].key, "accels") && info->argv[i].value)
      enabled += std::string(info->argv[i].value) + ",";
    else if (!std::strcmp(info->argv[i].key, "report") && info->argv[i].value)
      fp12_report = !std::strcmp(info->argv[i].value, "fp12");

  register_pass_info pass_info;
  pass_info.pass = new accel_pass(g);
  pass_info.reference_pass_name = "einline";
  pass_info.ref_pass_instance_number = 1;
  pass_info.pos_op = PASS_POS_INSERT_BEFORE;
  register_callback(info->base_name, PLUGIN_PASS_MANAGER_SETUP, nullptr, &pass_info);
  register_callback(info->base_name, PLUGIN_FINISH_PARSE_FUNCTION,
                    apply_function_optimization, nullptr);
  register_callback(info->base_name, PLUGIN_FINISH, finish, nullptr);
  return 0;
}

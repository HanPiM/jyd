#include "gcc-plugin.h"
#include "plugin-version.h"
#include "tree.h"
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
static unsigned replacements;

static tree find_replacement(const char *name) {
  cgraph_node *node;
  FOR_EACH_DEFINED_FUNCTION(node) {
    tree decl = node->decl;
    if (DECL_NAME(decl) &&
        !std::strcmp(IDENTIFIER_POINTER(DECL_NAME(decl)), name))
      return decl;
  }
  return NULL_TREE;
}

static bool has(const char *name) {
  std::string needle = std::string(",") + name + ",";
  return enabled.find(needle) != std::string::npos;
}

static const char *replacement_for(const char *name) {
  if (has("xstatec") && !std::strcmp(name, "core_bench_state")) return "__cm_xstatec_bench";
  if (has("xlrev") && !std::strcmp(name, "core_list_reverse")) return "__cm_xlrev";
  if (has("xstate") && !std::strcmp(name, "core_state_transition")) return "__cm_xstate";
  if (has("xmsum") && !std::strcmp(name, "matrix_sum")) return "__cm_xmsum";
  if (has("xbmul") && !std::strcmp(name, "matrix_mul_matrix_bitextract")) return "__cm_xbmul_matrix";
  if (has("xdot16") && !std::strcmp(name, "matrix_mul_vect")) return "__cm_xdot_vect";
  if (has("xdot16") && !std::strcmp(name, "matrix_mul_matrix")) return "__cm_xdot_matrix";
  if (has("xmac16") && !std::strcmp(name, "matrix_mul_vect")) return "__cm_xmac_vect";
  if (has("xmac16") && !std::strcmp(name, "matrix_mul_matrix")) return "__cm_xmac_matrix";
  return nullptr;
}

namespace {
const pass_data accel_pass_data = {
  GIMPLE_PASS, "coremark_accel", OPTGROUP_NONE, TV_NONE,
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
        const char *new_name = replacement_for(old_name);
        if (!new_name) continue;
        tree new_decl = find_replacement(new_name);
        if (!new_decl) {
          std::fprintf(stderr, "coremark-accel-plugin: missing inline wrapper %s\n", new_name);
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
  std::fprintf(stderr, "coremark-accel-plugin: enabled=%s replacements=%u\n",
               enabled.c_str(), replacements);
}

int plugin_init(plugin_name_args *info, plugin_gcc_version *version) {
  if (!plugin_default_version_check(version, &gcc_version)) return 1;
  enabled = ",";
  for (int i = 0; i < info->argc; i++)
    if (!std::strcmp(info->argv[i].key, "accels") && info->argv[i].value)
      enabled += std::string(info->argv[i].value) + ",";

  register_pass_info pass_info;
  pass_info.pass = new accel_pass(g);
  pass_info.reference_pass_name = "einline";
  pass_info.ref_pass_instance_number = 1;
  pass_info.pos_op = PASS_POS_INSERT_BEFORE;
  register_callback(info->base_name, PLUGIN_PASS_MANAGER_SETUP, nullptr, &pass_info);
  register_callback(info->base_name, PLUGIN_FINISH, finish, nullptr);
  return 0;
}

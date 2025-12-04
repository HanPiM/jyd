#include <iostream>
#include <verilated.h>
#include <verilated_vpi.h>

#include "sim.hpp"

void read_and_check(const char *sig_name) {
  vpiHandle vh1 = vpi_handle_by_name((PLI_BYTE8 *)(sig_name), NULL);
  if (!vh1) {
    printf("No handle found for %s\n", sig_name);
    return;
    // vl_fatal(__FILE__, __LINE__, "sim_main", "No handle found");
  }
  const char *name = vpi_get_str(vpiName, vh1);
  const char *type = vpi_get_str(vpiType, vh1);
  const int size = vpi_get(vpiSize, vh1);
  printf("name: %s, type: %s, size: %d\n", name, type, size);

  return;
  s_vpi_value v;
  v.format = vpiIntVal;
  vpi_get_value(vh1, &v);
  printf("Value of %s: %d\n", name,
         v.value.integer); // Prints "Value of readme: 0"
}

int main(int argc, char **argv) {
  sim_init(argc, argv);

  get_dut()->contextp()->internalsDump(); // See scopes to help debug

  vpiHandle top = vpi_handle_by_name((PLI_BYTE8 *)"TOP.Top", NULL);
  assert(top);
  vpiHandle iter;
  vpiHandle it;

  for (int type = 0; type < 150; type++) {
    iter = vpi_iterate(type, top);
    if (iter != NULL) {
      vpi_printf("TYPE %d success\n", type);
      while ((it = vpi_scan(iter)) != NULL) {
        const char *name = vpi_get_str(vpiName, it);
        vpi_printf("- : %s\n", name);
      }
    }
  }
  std::string cmd;
  bool quit = false;
  while (!sim_halted() && !quit) {
    std::cout << "(sdb) ";
    std::getline(std::cin, cmd);
    read_and_check(("TOP." + cmd).c_str());
    //		sim_exec_sdbcmd(cmd, quit);
  }

  return sim_hit_good_trap() ? 0 : 1;
}

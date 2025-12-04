#include <iostream>
#include <string_view>
#include <verilated.h>
#include <verilated_vpi.h>

#include "sim.hpp"

#include "sprobe.hpp"


void read_and_check(std::string sig_name) {
  vpiHandle vh1 = vpi_handle_by_name((PLI_BYTE8 *)(sig_name.c_str()), NULL);
  if (!vh1) {
    printf("No handle found for %s\n", sig_name.c_str());
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

	SProbe sprobe;
	sprobe.load_inside(top);

	std::cout<<"===== All Signal Probed ====="<<std::endl;
	for(auto& n:sprobe._fullnames){
		std::cout<<n<<std::endl;
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

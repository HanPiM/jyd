#include <iostream>
#include <string_view>
#include <verilated.h>
#include <verilated_vpi.h>

#include "sim.hpp"

#include "sprobe.hpp"

SProbe sprobe;
void cyc_callback() {
  if (sim_halted())
    return;
  sprobe.dump_watched();
}

int main(int argc, char **argv) {
	sim_setting setting;
	load_sim_setting_from_env(setting);
  setting.cycle_finish_cb = cyc_callback;
  if(sim_init(argc, argv, setting)!=0){
		get_dut()->final();
		return 1;
	}

  // get_dut()->contextp()->internalsDump(); // See scopes to help debug
  //
  std::string top_vpi_name =
      std::string("TOP.") + std::string(_STR(TOP_NAME)).substr(1);

  std::string cmd;
  bool quit = false;
  while (!sim_halted() && !quit) {
    std::cout << "(sdb) ";
    std::getline(std::cin, cmd);
    if (cmd == "sc") {
      sim_step_cycle();
      continue;
    }
    if (cmd.size() > 3 && cmd.substr(0, 2) == "ps") {
      std::string sig_name = cmd.substr(3);
      if (sig_name == "*") {
        sprobe.add_watch(top_vpi_name);
        continue;
      }
      if (sig_name.starts_with("`c.")) {
        sig_name = "asic.cpu.cpu." + sig_name.substr(3);
      }

      auto fullname = top_vpi_name + '.' + sig_name;
      if (sprobe.add_watch(fullname))
        printf("Added watch for '%s'\n", fullname.c_str());
      continue;
    }
    sim_exec_sdbcmd(cmd, quit);
  }

  return sim_hit_good_trap() ? 0 : 1;
}

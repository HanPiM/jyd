#pragma once
#include "vpi_user.h"
#include <cstdint>
#include <format>
#include <iostream>
#include <string>
#include <vector>
#include <verilated.h>
#include <verilated_vpi.h>

class SProbe {
public:
  std::vector<std::string> _fullnames;

  std::vector<vpiHandle> _watched_handles;

  ~SProbe() {
    for (auto &h : _watched_handles) {
      vpi_release_handle(h);
    }
  }

  void load_inside(vpiHandle top) {
    // std::cout<<"SProbe load inside
    // "<<vpi_get_str(vpiFullName,top)<<std::endl;

    vpiHandle iter;
    vpiHandle it;
    int type_begin = 0, type_end = 136;
    for (int type = type_begin; type < type_end; type++) {
      iter = vpi_iterate(type, top);
      if (iter != NULL) {
        // printf("SProbe scanning type %d\n",type);
        while ((it = vpi_scan(iter)) != NULL) {
          _fullnames.push_back(std::string(vpi_get_str(vpiFullName, it)));
          // printf("SProbe found %d  %s\n", type, _fullnames.back().c_str());
          if (type == vpiModule)
            load_inside(it);
          vpi_release_handle(it);
          // printf("SProbe back to %s\n",vpi_get_str(vpiFullName,top));
        }
      }
    }
  }
  void load_inside(std::string_view top) {
    load_inside(vpi_handle_by_name((PLI_BYTE8 *)top.data(), NULL));
  }

  bool add_watch(std::string_view sig_name) {
    vpiHandle vh1 = vpi_handle_by_name((PLI_BYTE8 *)(sig_name.data()), NULL);
    if (!vh1) {
      std::cout << "SProbe add_watch no handle found for " << sig_name
                << std::endl;
      return false;
    }
    _watched_handles.push_back(vh1);
    return true;
  }

  void dump_watched() {
    if (_watched_handles.empty())
      return;
    // std::cout << "===== SProbe Watched Signals =====" << std::endl;
    for (auto &h : _watched_handles) {
      s_vpi_value v;
      v.format = vpiIntVal;
      vpi_get_value(h, &v);
      std::string fullname = vpi_get_str(vpiFullName, h);
      std::cout << std::format("{} ` {} [{}] = {:08X}\n",
					vpi_get_str(vpiType, h), fullname,
					vpi_get(vpiSize, h)
					, (uint32_t)v.value.integer);
    }
  }
};

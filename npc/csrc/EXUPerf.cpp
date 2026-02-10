// deprecated
#include "PerfCounter.hpp"
#include "sim.hpp"
#include "spdlog/fmt/bundled/format.h"
#include <fstream>
#include <vector>

void EXUPerfCounter::dumpStatistics(std::ostream &os) {
  os << "instruction type counts:\n";
  os << "By type:\n";
  _dump(instCountOfTyp, totalCycleOfTyp, TYPE_NUM, nameOfTyp, os);
  os << "By format:\n";
  _dump(instCountOfFmt, totalCycleOfFmt, FMT_NUM, nameOfFmt, os);
}
const char *EXUPerfCounter::nameOfTyp(int type) {
  static const char *type_names[] = {
      "branch", "arithmetic", "load",  "store",  "jalr",
      "jal",    "lui",        "auipc", "system", "fence.i",
  };
  if (type < sizeof(type_names) / sizeof(type_names[0])) {
    return type_names[type];
  } else {
    return "unknown";
  }
}
const char *EXUPerfCounter::nameOfFmt(int fmt) {
  static const char *fmt_names[] = {
      "I_TYPE", "R_TYPE", "S_TYPE", "U_TYPE", "J_TYPE", "B_TYPE",
  };
  if (fmt < sizeof(fmt_names) / sizeof(fmt_names[0])) {
    return fmt_names[fmt];
  } else {
    return "unknown";
  }
}
void EXUPerfCounter::bind() {
  logger = spdlog::stdout_color_mt("InstTypeCounter");
  set_logger_pattern_with_simtime(logger);
  logger->set_level(spdlog::level::info);
  // hInstType = &_GetIDU()->io_out_bits_info_typ;
  // hInstFmt = &_GetIDU()->io_out_bits_info_fmt;
  // hOutValid = &_GetIDU()->io_out_valid;
  // hOutReady = &_GetIDU()->io_out_ready;
}
EXUPerfCounter::InstFmt EXUPerfCounter::OneHotToFmt(uint32_t onehot) {
  return static_cast<InstFmt>(std::countr_zero(onehot));
}
EXUPerfCounter::InstType EXUPerfCounter::OneHotToType(uint32_t onehot) {
  return static_cast<InstType>(std::countr_zero(onehot));
}
void EXUPerfCounter::update() {

  bool isOutValidRasingEdge = (!lastCycOutValid && hOutValid.get());
  lastCycOutValid = hOutValid.get();

  //
  // For history reason the timing is wired
  //
  // the exu cost time is calculated from idu
  //
  // a inst start execution approximately when out_valid rises
  // (TODO: figure out the exact timing)
  // and ends when out_ready highs
  //

  if (isOutValidRasingEdge) {
    instStartCycle = sim_get_cycle();
  }

  if (hOutReady.get() && hOutValid.get()) {
		// idu think inst invaild
    if (hInstType.get() == 0)
      return;
    if (hInstFmt.get() == 0)
      return;

    // instruction finished execution
    InstType type = OneHotToType(hInstType.get());
    InstFmt fmt = OneHotToFmt(hInstFmt.get());

    if (!isValidType(type) || !isValidFmt(fmt)) {
      logger->warn("Invalid instruction type {} or fmt {}", (int)type,
                   (int)fmt);
      return;
    }
    // assert(isValidType(type));
    // assert(isValidFmt(fmt));

    instCountOfTyp[type]++;
    instCountOfFmt[fmt]++;

    auto instEndCycle = sim_get_cycle();
    // fmt::println("Instruction executed: type {} fmt {} cycles {}",
    // 						 nameOfTyp(type),
    // nameOfFmt(fmt), 						 instEndCycle -
    // instStartCycle);
    auto instCycles = instEndCycle - instStartCycle;
    totalCycleOfTyp[type] += instCycles;
    totalCycleOfFmt[fmt] += instCycles;

    // logger->trace("inst executed: type {} fmt {} cycles {}", nameOfTyp(type),
    //               nameOfFmt(fmt), instCycles);
  }
}

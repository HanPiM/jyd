#include "sig_event.hpp"
#include <spdlog/sinks/stdout_color_sinks.h>

auto _FullPath(const std::string &pathWithoutValidOrReady,
               const std::string &suffix = "") {
  return cpu_vpi_path_prefix() + pathWithoutValidOrReady + suffix;
}
auto _DebugPath(const std::string &pathWithoutValidOrReady,
                const std::string &suffix = "") {
  return "`cpu." + pathWithoutValidOrReady + suffix;
}

void set_logger_pattern_with_simtime(std::shared_ptr<spdlog::logger> logger);
HandShakeDetector::HandShakeDetector() {
  logger = spdlog::stdout_color_mt("HandShakeDetector");
}
void HandShakeDetector::init() {
  set_logger_pattern_with_simtime(logger);
  logger->set_level(spdlog::level::info);
}

SignalHandle::SignalHandle(std::string barePath) {
  handle = vpi_handle_by_name(
      const_cast<PLI_BYTE8 *>(_FullPath(barePath).c_str()), nullptr);
  if (!handle) {
    spdlog::error("cannot find signal at path {}", _DebugPath(barePath));
  }
}

void HandShakeDetector::add(std::string barePath, std::string description,
                            callback_t onShake) {
  auto pathValid = _FullPath(barePath, "valid");
  auto pathReady = _FullPath(barePath, "ready");
  spdlog::debug("adding valid/ready pair: {}/{}", pathValid, pathReady);
  auto hValid = SignalHandle(barePath + "valid");
  auto hReady = SignalHandle(barePath + "ready");

  spdlog::info("added watch for channel {} ({})", _DebugPath(barePath),
               description);
  bus_list.emplace_back(ValidReadyBus{
      .hValid = std::move(hValid),
      .hReady = std::move(hReady),
      .description = description,
      .onShakeCallback = onShake,
  });
}

bool HandShakeDetector::ValidReadyBus::shakeHappened() {
  return hValid.getUint32Value() == 1 && hReady.getUint32Value() == 1;
}

void HandShakeDetector::checkAndCountAll() {
  for (auto &bus : bus_list) {
    if (bus.shakeHappened()) {
      bus.shake_count++;
      logger->trace("Handshake happened on {} (total count {})",
                    _DebugPath(bus.description), bus.shake_count);
      if (bus.onShakeCallback) {
        bus.onShakeCallback();
      }
    }
  }
}

const char *InstTypeCounter::name_of_type(InstType type) {
  static const char *type_names[] = {
      "branch", "arithmetic", "load",  "store",  "jalr",
      "jal",    "lui",        "auipc", "system",
  };
  if (type < sizeof(type_names) / sizeof(type_names[0])) {
    return type_names[type];
  } else {
    return "unknown";
  }
}
const char *InstTypeCounter::name_of_fmt(InstFmt fmt) {
  static const char *fmt_names[] = {
      "I_TYPE", "R_TYPE", "S_TYPE", "U_TYPE", "J_TYPE", "B_TYPE",
  };
  if (fmt < sizeof(fmt_names) / sizeof(fmt_names[0])) {
    return fmt_names[fmt];
  } else {
    return "unknown";
  }
}
void InstTypeCounter::init() {
  logger = spdlog::stdout_color_mt("InstTypeCounter");
  set_logger_pattern_with_simtime(logger);
  logger->set_level(spdlog::level::info);
  hInstType = SignalHandle("idu.iinfo_dec.io_out_typ");
  hInstFmt = SignalHandle("idu.iinfo_dec.io_out_fmt");
}
void InstTypeCounter::newInstFetched(uint64_t cyc) {

	if(isValidType(lastInstType)){
		auto cycles = cyc - lastInstFetchCyc;
		tot_cycle_of_type[lastInstType] += cycles;
	}
	if(isValidFmt(lastInstFmt)){
		auto cycles = cyc - lastInstFetchCyc;
		tot_cycle_of_fmt[lastInstFmt] += cycles;
	}

  uint32_t inst_type = hInstType.getUint32Value();
  uint32_t inst_fmt = hInstFmt.getUint32Value();

  type_count[inst_type]++;
  fmt_count[inst_fmt]++;

  logger->trace("new inst fetched: type {} fmt {}", inst_type, inst_fmt);

	lastInstType = (InstType)inst_type;
	lastInstFmt = (InstFmt)inst_fmt;
	lastInstFetchCyc = cyc;
}

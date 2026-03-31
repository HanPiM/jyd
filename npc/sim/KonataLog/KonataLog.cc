#include "KonataLog.hpp"

#include "../memory/mem.hpp"
#include "../sim.hpp"

#include <algorithm>
#include <array>
#include <memory>
#include <tracers.hpp>

namespace {

constexpr std::array<std::string_view, 5> kStageNames = {"IF", "DE", "EX", "LS", "WB"};

std::shared_ptr<KonataLogger> g_konata_logger;

std::string_view getStageName(uint32_t stageID) {
  if (stageID < kStageNames.size()) {
    return kStageNames[stageID];
  }
  return "UNKNOWN";
}

} // namespace

void KonataLogger::_output(const std::string &str) {
  constexpr size_t kMaxLogFileSize = 64 * 1024 * 1024;
  if (_fileStream.tellp() >= static_cast<std::streampos>(kMaxLogFileSize)) {
    _fileStream.close();
    spdlog::warn("Log file size exceeded {} MB. KonataLogger stopped logging",
                 kMaxLogFileSize / (1024 * 1024));
  } else {
    _fileStream << str << std::endl;
  }
}

void KonataLogger::handleEvent(uint32_t eventKind, uint32_t stageID,
                               InstFileIDType iid, uint32_t data,
                               uint32_t /*flags*/) {
  auto stageName = getStageName(stageID);

  switch (eventKind) {
  case EventKind::Stage:
    if (stageID == StageID::IF) {
      declare(iid, iid);
      sdb::vlen_inst_code code(4);
      auto pc = data;
      read_guest_mem(pc, (uint32_t *)code.data());
      auto disasm = sdb::default_inst_disasm(pc, code);
      std::ranges::replace(disasm, '\t', ' ');
      addLabel(iid, std::format("{:08x}: {}", pc, disasm));
      addLabel(iid, std::format("{}ps", sim_get_time()), true);
    }
    stageStart(iid, stageName);
    break;
  case EventKind::Retire:
    retire(iid, _GenNextRetireID());
    break;
  case EventKind::Flush:
    retire(iid, 0, true);
    break;
  default:
    spdlog::warn("Unknown konata event kind {} stage {} iid {}", eventKind,
                 stageID, iid);
    break;
  }
}

void init_konata_logger(std::string_view filePath) {
  g_konata_logger = std::make_shared<KonataLogger>(filePath);
}

void start_konata_logger(KonataLogger::CycleType startSimCycle) {
  if (g_konata_logger) {
    g_konata_logger->start(startSimCycle);
  }
}

bool has_konata_logger() { return static_cast<bool>(g_konata_logger); }

extern "C" void konata_event(int eventKind, int stageID, int iid, int data, int flags) {
  if (g_konata_logger) {
    g_konata_logger->handleEvent(static_cast<uint32_t>(eventKind),
                                 static_cast<uint32_t>(stageID),
                                 static_cast<KonataLogger::InstFileIDType>(static_cast<uint32_t>(iid)),
                                 static_cast<uint32_t>(data),
                                 static_cast<uint32_t>(flags));
  }
}

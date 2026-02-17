#pragma once
#include <cstdint>
#include <format>
#include <fstream>
#include <iostream>
#include <string>
#include <string_view>

uint64_t sim_get_cycle();

class KonataLogger {
public:
  using CycleType = uint64_t;
  using InstFileIDType = uint64_t;
  using InstUserIDType = uint64_t;
  using InstRetireIDType = uint64_t;
  using LaneIDType = uint32_t;

private:
  static constexpr std::string_view _Header = "Kanata\t0004";

  CycleType _LastOutputCycle = 0;
  std::ofstream _fileStream;

  void _output(const std::string &str) { _fileStream << str << '\n'; }
  void _Log(const std::string &cmd, auto &&...params) {
    std::string res = cmd;
    static_assert(sizeof...(params) > 0, "At least one parameter is required");
    ((res += std::format("\t{}", params)), ...);
    _output(res);
  }

public:
  KonataLogger(std::string_view filePath) {
    _fileStream.open(filePath.data(), std::ios::out);
  }
  void log(const std::string &cmd, auto &&...params) {
    CycleType cyc = sim_get_cycle();

    if (cyc < _LastOutputCycle) {
      throw std::runtime_error("Cycle must be non-decreasing");
    }
    if (cyc > _LastOutputCycle) {
      _Log("C", cyc - _LastOutputCycle);
    }
    _LastOutputCycle = cyc;
    _Log(cmd, params...);
  }

  void start(CycleType startSimCycle) {
    _output(std::string(_Header));
    _Log("C=", startSimCycle);
    _LastOutputCycle = startSimCycle;
  }

  void declare(InstFileIDType fileID, InstUserIDType simID = 0,
               InstUserIDType threadID = 0) {
    log("I", fileID, simID, threadID);
  }
  void addLabel(InstFileIDType id, std::string_view label,
                bool isHover = false) {
    log("L", id, isHover ? '1' : '0', label);
  }
  void stageStart(InstFileIDType id, std::string_view stageName,
                  LaneIDType laneID = 0) {
    log("S", id, laneID, stageName);
  }
  void stageEnd(InstFileIDType id, std::string_view stageName,
                LaneIDType laneID = 0) {
    log("E", id, laneID, stageName);
  }
  void retire(InstFileIDType id, InstRetireIDType retireID,
              bool isFlushed = false) {
    log("R", id, retireID, isFlushed ? '1' : '0');
  }

	void readSignalsAndLog();
};

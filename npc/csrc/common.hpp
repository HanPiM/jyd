#pragma once
#include <spdlog/sinks/basic_file_sink.h>
#include <spdlog/sinks/stdout_color_sinks.h>
#include <spdlog/spdlog.h>

#define FILELOG_OUTPUT_DIR "build/logs"

inline auto newFileLoggerSink(const std::string &name) {
  auto file_sink = std::make_shared<spdlog::sinks::basic_file_sink_mt>(
      fmt::format("{}/{}.log", FILELOG_OUTPUT_DIR, name), true);
  file_sink->set_level(spdlog::level::debug);
  return file_sink;
}

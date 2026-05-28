#include "aios/event_bus.h"

#include <chrono>
#include <cstdio>
#include <nlohmann/json.hpp>
#include <sstream>
#include <string>

namespace aios {

void EventBus::publish(EventType type, const std::string& source, const std::string& message) {
    auto now = std::chrono::system_clock::now();
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        now.time_since_epoch()).count();

    nlohmann::json entry;
    entry["ts"] = ms;
    entry["type"] = event_type_str(type);
    entry["source"] = source;
    entry["message"] = message;
    std::string json_str = entry.dump();

    const char* color = "";
    switch (type) {
        case EventType::LLM_REQ_START:   color = "\033[36m"; break;
        case EventType::LLM_REQ_END:     color = "\033[32m"; break;
        case EventType::WASM_EXEC_START: color = "\033[33m"; break;
        case EventType::WASM_TRAP:       color = "\033[31m"; break;
        case EventType::AGENT_SPAWN:     color = "\033[35m"; break;
        case EventType::VFS_WRITE:       color = "\033[34m"; break;
    }
    const char* reset = "\033[0m";

    std::printf("%s[Event | %s]%s %s: %s\n",
                color, event_type_str(type), reset,
                source.c_str(), message.c_str());

    std::lock_guard<std::mutex> lock(mutex_);
    event_logs_.push_back(std::move(json_str));
    while (event_logs_.size() > MAX_LOG_SIZE) {
        event_logs_.pop_front();
    }
}

std::string EventBus::dump_events() {
    std::lock_guard<std::mutex> lock(mutex_);
    nlohmann::json arr = nlohmann::json::array();
    for (const auto& log : event_logs_) {
        auto parsed = nlohmann::json::parse(log, nullptr, false);
        if (!parsed.is_discarded()) {
            arr.push_back(parsed);
        }
    }
    event_logs_.clear();
    return arr.dump();
}

} // namespace aios

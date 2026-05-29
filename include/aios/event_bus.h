#pragma once

#include <deque>
#include <mutex>
#include <string>

namespace aios {

enum class EventType {
    AGENT_SPAWN,
    AGENT_EXIT,
    LLM_REQ_START,
    LLM_REQ_END,
    WASM_EXEC_START,
    WASM_TRAP,
    VFS_WRITE
};

inline const char* event_type_str(EventType t) {
    switch (t) {
        case EventType::AGENT_SPAWN:    return "AGENT_SPAWN";
        case EventType::AGENT_EXIT:     return "AGENT_EXIT";
        case EventType::LLM_REQ_START:  return "LLM_REQ_START";
        case EventType::LLM_REQ_END:    return "LLM_REQ_END";
        case EventType::WASM_EXEC_START:return "WASM_EXEC_START";
        case EventType::WASM_TRAP:      return "WASM_TRAP";
        case EventType::VFS_WRITE:      return "VFS_WRITE";
    }
    return "UNKNOWN";
}

class EventBus {
public:
    static EventBus& instance() {
        static EventBus bus;
        return bus;
    }

    void publish(EventType type, const std::string& source, const std::string& message);
    std::string dump_events();

    EventBus(const EventBus&) = delete;
    EventBus& operator=(const EventBus&) = delete;

private:
    EventBus() = default;

    static constexpr size_t MAX_LOG_SIZE = 1000;

    std::deque<std::string> event_logs_;
    std::mutex mutex_;
};

} // namespace aios

#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>

namespace aios {

enum class TraceMode {
    DISABLED,
    RECORD,
    REPLAY
};

struct TraceEvent {
    uint64_t seq;
    int agent_id;
    std::string event_type;
    std::string payload;
    uint64_t timestamp_ns;
};

class TraceManager {
public:
    static TraceManager& instance();

    void set_mode(TraceMode mode);
    TraceMode mode() const;

    void record_event(int agent_id,
                      const std::string& event_type,
                      const std::string& payload);

    std::string replay_event(int agent_id,
                             const std::string& event_type);

    static void set_thread_agent_id(int agent_id);
    static int get_thread_agent_id();

    uint64_t current_seq() const;

    void reset_agent(int agent_id);
    void reset_all();

    int recorded_count() const;
    int replayed_count() const;

    TraceManager(const TraceManager&) = delete;
    TraceManager& operator=(const TraceManager&) = delete;

private:
    TraceManager() = default;

    bool ensure_trace_dir();
    bool write_tape(int agent_id, const TraceEvent& ev);
    std::string read_tape_next(int agent_id, const std::string& expected_type);
    std::string tape_path(int agent_id) const;

    TraceMode mode_ = TraceMode::DISABLED;
    uint64_t global_seq_ = 0;
    int recorded_count_ = 0;
    int replayed_count_ = 0;

    struct ReplayCursor {
        std::string tape_content;
        size_t offset = 0;
        uint64_t last_seq = 0;
    };

    std::mutex mutex_;
    std::unordered_map<int, ReplayCursor> replay_cursors_;
};

} // namespace aios

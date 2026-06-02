#include "aios/trace_manager.h"
#include "aios/kernel_logger.h"
#include "aios/vfs_manager.h"

#include <chrono>
#include <cstdio>
#include <nlohmann/json.hpp>

namespace aios {

TraceManager& TraceManager::instance() {
    static TraceManager inst;
    return inst;
}

static thread_local int g_thread_agent_id = 0;

void TraceManager::set_thread_agent_id(int agent_id) {
    g_thread_agent_id = agent_id;
}

int TraceManager::get_thread_agent_id() {
    return g_thread_agent_id;
}

void TraceManager::set_mode(TraceMode mode) {
    std::lock_guard<std::mutex> lock(mutex_);
    mode_ = mode;
    const char* names[] = {"DISABLED", "RECORD", "REPLAY"};
    std::printf("[TraceManager] Mode set to %s\n", names[static_cast<int>(mode)]);

    if (mode == TraceMode::RECORD) {
        ensure_trace_dir();
    }

    if (mode == TraceMode::REPLAY) {
        replay_cursors_.clear();
        std::printf("[TraceManager] Replay cursors reset — all agents will start from seq=0\n");
    }
}

TraceMode TraceManager::mode() const {
    return mode_;
}

uint64_t TraceManager::current_seq() const {
    return global_seq_;
}

void TraceManager::record_event(int agent_id,
                                const std::string& event_type,
                                const std::string& payload) {
    if (mode_ != TraceMode::RECORD) return;

    TraceEvent ev;
    ev.seq = 0;

    {
        std::lock_guard<std::mutex> lock(mutex_);
        ev.seq = ++global_seq_;
        recorded_count_++;
    }

    ev.agent_id = agent_id;
    ev.event_type = event_type;
    ev.payload = payload;
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    ev.timestamp_ns = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(now).count());

    nlohmann::json j;
    j["seq"] = ev.seq;
    j["agent_id"] = ev.agent_id;
    j["event_type"] = ev.event_type;
    j["payload"] = ev.payload;
    j["timestamp_ns"] = ev.timestamp_ns;

    std::string line = j.dump() + "\n";

    if (write_tape(agent_id, ev)) {
        std::printf("[TraceManager] 🎙️  REC seq=%llu agent=%d type=%s payload=%zu bytes\n",
                    (unsigned long long)ev.seq, agent_id,
                    event_type.c_str(), payload.size());
    }
}

std::string TraceManager::replay_event(int agent_id,
                                       const std::string& event_type) {
    if (mode_ != TraceMode::REPLAY) return "";

    std::string result = read_tape_next(agent_id, event_type);

    if (!result.empty()) {
        std::lock_guard<std::mutex> lock(mutex_);
        replayed_count_++;
        std::printf("[TraceManager] ⏪ REPLAY agent=%d type=%s → %zu bytes (from tape)\n",
                    agent_id, event_type.c_str(), result.size());
    } else {
        std::printf("[TraceManager] ⚠️  REPLAY MISS agent=%d type=%s → no matching event in tape\n",
                    agent_id, event_type.c_str());
    }

    return result;
}

void TraceManager::reset_agent(int agent_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    replay_cursors_.erase(agent_id);
    std::printf("[TraceManager] Agent %d replay cursor reset\n", agent_id);
}

void TraceManager::reset_all() {
    std::lock_guard<std::mutex> lock(mutex_);
    global_seq_ = 0;
    recorded_count_ = 0;
    replayed_count_ = 0;
    replay_cursors_.clear();
    std::printf("[TraceManager] All state reset\n");
}

int TraceManager::recorded_count() const {
    return recorded_count_;
}

int TraceManager::replayed_count() const {
    return replayed_count_;
}

bool TraceManager::ensure_trace_dir() {
    auto& vfs = VfsManager::instance();

    auto var_node = vfs.resolve_path("/var", 0);
    if (!var_node) {
        std::printf("[TraceManager] /var not found in VFS!\n");
        return false;
    }

    auto var_dir = std::dynamic_pointer_cast<DirectoryNode>(var_node);
    if (!var_dir) return false;

    auto trace_node = var_dir->get_child("trace");
    if (!trace_node) {
        auto trace_dir = std::make_shared<DirectoryNode>("/var/trace");
        var_dir->add_child("trace", trace_dir);
        std::printf("[TraceManager] Created /var/trace directory in VFS\n");
    }

    return true;
}

std::string TraceManager::tape_path(int agent_id) const {
    return "/var/trace/agent_" + std::to_string(agent_id) + ".tape";
}

bool TraceManager::write_tape(int agent_id, const TraceEvent& ev) {
    auto& vfs = VfsManager::instance();

    nlohmann::json j;
    j["seq"] = ev.seq;
    j["agent_id"] = ev.agent_id;
    j["event_type"] = ev.event_type;
    j["payload"] = ev.payload;
    j["timestamp_ns"] = ev.timestamp_ns;

    std::string json_line = j.dump();

    std::string path = tape_path(agent_id);
    auto existing = vfs.resolve_path(path, 0);

    if (existing) {
        std::string current = existing->read();
        if (!current.empty() && current.back() != '\n') {
            current += '\n';
        }
        current += json_line + '\n';
        existing->write(current);
    } else {
        auto trace_dir_node = vfs.resolve_path("/var/trace", 0);
        if (!trace_dir_node) {
            if (!ensure_trace_dir()) {
                std::printf("[TraceManager] ❌ Cannot create /var/trace for tape write\n");
                return false;
            }
            trace_dir_node = vfs.resolve_path("/var/trace", 0);
        }

        auto dir = std::dynamic_pointer_cast<DirectoryNode>(trace_dir_node);
        if (!dir) {
            std::printf("[TraceManager] ❌ /var/trace is not a directory\n");
            return false;
        }

        std::string tape_name = "agent_" + std::to_string(agent_id) + ".tape";
        auto tape_file = std::make_shared<FileNode>(path, json_line + '\n');
        dir->add_child(tape_name, tape_file);
    }

    return true;
}

std::string TraceManager::read_tape_next(int agent_id, const std::string& expected_type) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto& cursor = replay_cursors_[agent_id];

    if (cursor.tape_content.empty()) {
        auto& vfs = VfsManager::instance();
        std::string path = tape_path(agent_id);
        auto node = vfs.resolve_path(path, 0);
        if (!node) {
            std::printf("[TraceManager] ❌ Tape not found: %s\n", path.c_str());
            return "";
        }
        cursor.tape_content = node->read();
        cursor.offset = 0;
        cursor.last_seq = 0;
        std::printf("[TraceManager] 📼 Loaded tape: %s (%zu bytes)\n",
                    path.c_str(), cursor.tape_content.size());
    }

    while (cursor.offset < cursor.tape_content.size()) {
        size_t newline = cursor.tape_content.find('\n', cursor.offset);
        if (newline == std::string::npos) {
            newline = cursor.tape_content.size();
        }

        std::string line = cursor.tape_content.substr(cursor.offset, newline - cursor.offset);
        cursor.offset = (newline < cursor.tape_content.size()) ? newline + 1 : cursor.tape_content.size();

        if (line.empty()) continue;

        nlohmann::json j;
        try {
            j = nlohmann::json::parse(line);
        } catch (const nlohmann::json::parse_error& e) {
            std::printf("[TraceManager] ⚠️  Tape parse error at offset %zu: %s\n",
                        cursor.offset, e.what());
            continue;
        }

        std::string ev_type = j.value("event_type", "");
        uint64_t ev_seq = j.value("seq", uint64_t(0));

        if (ev_type == expected_type) {
            if (ev_seq <= cursor.last_seq) {
                continue;
            }
            cursor.last_seq = ev_seq;
            return j.value("payload", "");
        }
    }

    return "";
}

} // namespace aios

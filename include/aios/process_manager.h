#pragma once

#include <chrono>
#include <cstdio>
#include <mutex>
#include <string>
#include <unordered_map>

namespace aios {

enum class ProcessState {
    RUNNING,
    SLEEPING,
    ZOMBIE
};

struct AgentProcess {
    int pid;
    ProcessState state;
    int ring_level;
    size_t memory_used;
    int syscall_count;
    std::chrono::system_clock::time_point created_at;
    std::chrono::system_clock::time_point last_active_time;
};

class ProcessManager {
public:
    static ProcessManager& instance() {
        static ProcessManager inst;
        return inst;
    }

    void register_process(int pid, int ring_level = 3) {
        std::lock_guard<std::mutex> lock(mu_);
        if (ptable_.find(pid) == ptable_.end()) {
            auto now = std::chrono::system_clock::now();
            ptable_[pid] = {pid, ProcessState::SLEEPING, ring_level, 0, 0, now, now};
        }
    }

    void record_syscall(int pid) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = ptable_.find(pid);
        if (it != ptable_.end()) {
            it->second.syscall_count++;
            it->second.state = ProcessState::RUNNING;
            it->second.last_active_time = std::chrono::system_clock::now();
        }
    }

    void touch_active_time(int pid) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = ptable_.find(pid);
        if (it != ptable_.end()) {
            it->second.last_active_time = std::chrono::system_clock::now();
        }
    }

    void set_sleeping(int pid) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = ptable_.find(pid);
        if (it != ptable_.end()) {
            it->second.state = ProcessState::SLEEPING;
        }
    }

    void set_zombie(int pid) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = ptable_.find(pid);
        if (it != ptable_.end()) {
            it->second.state = ProcessState::ZOMBIE;
        }
    }

    void add_memory(int pid, size_t bytes) {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = ptable_.find(pid);
        if (it != ptable_.end()) {
            it->second.memory_used += bytes;
        }
    }

    std::string generate_proc_agents() {
        std::lock_guard<std::mutex> lock(mu_);
        std::string out = "PID\tRING\tSTATE\tSYSCALLS\tMEM(KB)\tUPTIME(s)\tIDLE(s)\n";
        auto now = std::chrono::system_clock::now();
        for (const auto& [pid, pcb] : ptable_) {
            auto uptime = std::chrono::duration_cast<std::chrono::seconds>(now - pcb.created_at).count();
            auto idle = std::chrono::duration_cast<std::chrono::seconds>(now - pcb.last_active_time).count();
            const char* state_str = "S";
            if (pcb.state == ProcessState::RUNNING) state_str = "R";
            else if (pcb.state == ProcessState::ZOMBIE) state_str = "Z";
            out += std::to_string(pid) + "\t" +
                   std::to_string(pcb.ring_level) + "\t" +
                   state_str + "\t" +
                   std::to_string(pcb.syscall_count) + "\t" +
                   std::to_string(pcb.memory_used / 1024) + "\t" +
                   std::to_string(uptime) + "\t" +
                   std::to_string(idle) + "\n";
        }
        return out;
    }

    std::unordered_map<int, AgentProcess> get_ptable() {
        std::lock_guard<std::mutex> lock(mu_);
        return ptable_;
    }

private:
    ProcessManager() = default;
    ProcessManager(const ProcessManager&) = delete;
    ProcessManager& operator=(const ProcessManager&) = delete;

    std::unordered_map<int, AgentProcess> ptable_;
    std::mutex mu_;
};

} // namespace aios

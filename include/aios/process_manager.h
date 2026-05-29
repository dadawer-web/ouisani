#pragma once

#include <atomic>
#include <chrono>
#include <cstdio>
#include <future>
#include <mutex>
#include <string>
#include <unordered_map>

namespace aios {

enum class AgentState {
    RUNNING,
    BLOCKED,
    ZOMBIE,
    TERMINATED
};

inline const char* agent_state_str(AgentState s) {
    switch (s) {
        case AgentState::RUNNING:     return "RUNNING";
        case AgentState::BLOCKED:     return "BLOCKED";
        case AgentState::ZOMBIE:      return "ZOMBIE";
        case AgentState::TERMINATED:  return "TERMINATED";
    }
    return "UNKNOWN";
}

struct ProcessControlBlock {
    int agent_id;
    int parent_id;
    AgentState state;
    std::string exit_result;
    std::string role;
    std::promise<std::string> exit_promise;
    std::shared_future<std::string> exit_future;

    int ring_level = 3;
    size_t memory_used = 0;
    int syscall_count = 0;
    std::chrono::system_clock::time_point created_at;
    std::chrono::system_clock::time_point last_active_time;

    ProcessControlBlock()
        : agent_id(0), parent_id(0), state(AgentState::RUNNING)
        , exit_future(exit_promise.get_future().share())
        , created_at(std::chrono::system_clock::now())
        , last_active_time(std::chrono::system_clock::now()) {}

    ProcessControlBlock(int aid, int pid, const std::string& r)
        : agent_id(aid), parent_id(pid), state(AgentState::RUNNING), role(r)
        , exit_future(exit_promise.get_future().share())
        , created_at(std::chrono::system_clock::now())
        , last_active_time(std::chrono::system_clock::now()) {}
};

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
    static ProcessManager& instance();

    int spawn(int parent_id, const std::string& role);
    std::string wait(int child_id);
    void exit(int agent_id, const std::string& result);

    void register_process(int pid, int ring_level = 3);
    void record_syscall(int pid);
    void touch_active_time(int pid);
    void set_sleeping(int pid);
    void set_zombie(int pid);
    void add_memory(int pid, size_t bytes);

    std::string generate_proc_agents();
    std::unordered_map<int, AgentProcess> get_ptable();

    std::shared_ptr<ProcessControlBlock> get_pcb(int agent_id);

    ProcessManager(const ProcessManager&) = delete;
    ProcessManager& operator=(const ProcessManager&) = delete;

private:
    ProcessManager() = default;

    void sync_legacy(int agent_id);

    std::unordered_map<int, std::shared_ptr<ProcessControlBlock>> process_table_;
    std::atomic<int> next_pid_{1000};
    std::mutex mu_;

    std::unordered_map<int, AgentProcess> ptable_;
};

} // namespace aios

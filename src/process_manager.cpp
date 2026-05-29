#include "aios/process_manager.h"
#include "aios/event_bus.h"

#include <cstdio>

namespace aios {

ProcessManager& ProcessManager::instance() {
    static ProcessManager inst;
    return inst;
}

void ProcessManager::sync_legacy(int agent_id) {
    auto it = process_table_.find(agent_id);
    if (it == process_table_.end()) return;
    auto& pcb = it->second;

    auto lit = ptable_.find(agent_id);
    if (lit == ptable_.end()) {
        auto now = std::chrono::system_clock::now();
        ptable_[agent_id] = {
            agent_id,
            ProcessState::RUNNING,
            pcb->ring_level,
            pcb->memory_used,
            pcb->syscall_count,
            pcb->created_at,
            now
        };
    } else {
        lit->second.ring_level = pcb->ring_level;
        lit->second.memory_used = pcb->memory_used;
        lit->second.syscall_count = pcb->syscall_count;
    }
}

int ProcessManager::spawn(int parent_id, const std::string& role) {
    int new_pid = next_pid_.fetch_add(1);

    auto pcb = std::make_shared<ProcessControlBlock>(new_pid, parent_id, role);
    pcb->ring_level = 3;

    {
        std::lock_guard<std::mutex> lock(mu_);
        process_table_[new_pid] = pcb;
        sync_legacy(new_pid);
    }

    std::printf("[ProcessManager] SPAWN | parent=%d -> child=%d | role=\"%s\"\n",
                parent_id, new_pid, role.c_str());

    EventBus::instance().publish(EventType::AGENT_SPAWN, "ProcessManager",
        "Agent " + std::to_string(parent_id) + " spawned child " + std::to_string(new_pid));

    return new_pid;
}

std::string ProcessManager::wait(int child_id) {
    std::shared_ptr<ProcessControlBlock> child_pcb;
    std::shared_ptr<ProcessControlBlock> parent_pcb;

    {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = process_table_.find(child_id);
        if (it == process_table_.end()) {
            std::printf("[ProcessManager] WAIT FAILED | child=%d not found\n", child_id);
            return "[error] child process " + std::to_string(child_id) + " not found";
        }
        child_pcb = it->second;

        if (child_pcb->state == AgentState::ZOMBIE || child_pcb->state == AgentState::TERMINATED) {
            std::printf("[ProcessManager] WAIT | child=%d already exited, returning cached result\n", child_id);
            return child_pcb->exit_result;
        }

        int parent_id = child_pcb->parent_id;
        auto pit = process_table_.find(parent_id);
        if (pit != process_table_.end()) {
            parent_pcb = pit->second;
            parent_pcb->state = AgentState::BLOCKED;
            auto lit = ptable_.find(parent_id);
            if (lit != ptable_.end()) {
                lit->second.state = ProcessState::SLEEPING;
            }
        }
    }

    std::printf("[ProcessManager] WAIT | parent=%d blocking on child=%d...\n",
                child_pcb->parent_id, child_id);

    std::string result = child_pcb->exit_future.get();

    {
        std::lock_guard<std::mutex> lock(mu_);
        if (parent_pcb) {
            parent_pcb->state = AgentState::RUNNING;
            auto lit = ptable_.find(parent_pcb->agent_id);
            if (lit != ptable_.end()) {
                lit->second.state = ProcessState::RUNNING;
            }
        }
    }

    std::printf("[ProcessManager] WAIT | parent=%d resumed, child=%d result=\"%s\"\n",
                child_pcb->parent_id, child_id,
                result.size() > 60 ? (result.substr(0, 60) + "...").c_str() : result.c_str());

    return result;
}

void ProcessManager::exit(int agent_id, const std::string& result) {
    std::shared_ptr<ProcessControlBlock> pcb;

    {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = process_table_.find(agent_id);
        if (it == process_table_.end()) {
            std::printf("[ProcessManager] EXIT FAILED | agent=%d not found\n", agent_id);
            return;
        }
        pcb = it->second;
        pcb->exit_result = result;
        pcb->state = AgentState::ZOMBIE;

        auto lit = ptable_.find(agent_id);
        if (lit != ptable_.end()) {
            lit->second.state = ProcessState::ZOMBIE;
        }
    }

    try {
        pcb->exit_promise.set_value(result);
    } catch (const std::future_error& e) {
        std::printf("[ProcessManager] EXIT | agent=%d promise already set: %s\n",
                    agent_id, e.what());
        pcb->state = AgentState::TERMINATED;
        return;
    }

    pcb->state = AgentState::TERMINATED;

    std::printf("[ProcessManager] EXIT | agent=%d | result=\"%s\"\n",
                agent_id,
                result.size() > 60 ? (result.substr(0, 60) + "...").c_str() : result.c_str());

    EventBus::instance().publish(EventType::AGENT_EXIT, "ProcessManager",
        "Agent " + std::to_string(agent_id) + " exited with result: " +
        (result.size() > 40 ? result.substr(0, 40) + "..." : result));
}

void ProcessManager::register_process(int pid, int ring_level) {
    std::lock_guard<std::mutex> lock(mu_);
    if (ptable_.find(pid) == ptable_.end()) {
        auto now = std::chrono::system_clock::now();
        ptable_[pid] = {pid, ProcessState::SLEEPING, ring_level, 0, 0, now, now};
    }

    if (process_table_.find(pid) == process_table_.end()) {
        auto pcb = std::make_shared<ProcessControlBlock>(pid, 0, "");
        pcb->ring_level = ring_level;
        process_table_[pid] = pcb;
    } else {
        process_table_[pid]->ring_level = ring_level;
    }
}

void ProcessManager::record_syscall(int pid) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = ptable_.find(pid);
    if (it != ptable_.end()) {
        it->second.syscall_count++;
        it->second.state = ProcessState::RUNNING;
        it->second.last_active_time = std::chrono::system_clock::now();
    }
    auto pit = process_table_.find(pid);
    if (pit != process_table_.end()) {
        pit->second->syscall_count++;
        pit->second->last_active_time = std::chrono::system_clock::now();
    }
}

void ProcessManager::touch_active_time(int pid) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = ptable_.find(pid);
    if (it != ptable_.end()) {
        it->second.last_active_time = std::chrono::system_clock::now();
    }
    auto pit = process_table_.find(pid);
    if (pit != process_table_.end()) {
        pit->second->last_active_time = std::chrono::system_clock::now();
    }
}

void ProcessManager::set_sleeping(int pid) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = ptable_.find(pid);
    if (it != ptable_.end()) {
        it->second.state = ProcessState::SLEEPING;
    }
    auto pit = process_table_.find(pid);
    if (pit != process_table_.end()) {
        pit->second->state = AgentState::BLOCKED;
    }
}

void ProcessManager::set_zombie(int pid) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = ptable_.find(pid);
    if (it != ptable_.end()) {
        it->second.state = ProcessState::ZOMBIE;
    }
    auto pit = process_table_.find(pid);
    if (pit != process_table_.end()) {
        pit->second->state = AgentState::ZOMBIE;
    }
}

void ProcessManager::add_memory(int pid, size_t bytes) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = ptable_.find(pid);
    if (it != ptable_.end()) {
        it->second.memory_used += bytes;
    }
    auto pit = process_table_.find(pid);
    if (pit != process_table_.end()) {
        pit->second->memory_used += bytes;
    }
}

std::string ProcessManager::generate_proc_agents() {
    std::lock_guard<std::mutex> lock(mu_);
    std::string out = "PID\tPPID\tRING\tSTATE\tSYSCALLS\tMEM(KB)\tUPTIME(s)\tIDLE(s)\n";
    auto now = std::chrono::system_clock::now();
    for (const auto& [pid, pcb] : process_table_) {
        auto uptime = std::chrono::duration_cast<std::chrono::seconds>(now - pcb->created_at).count();
        auto idle = std::chrono::duration_cast<std::chrono::seconds>(now - pcb->last_active_time).count();
        out += std::to_string(pcb->agent_id) + "\t" +
               std::to_string(pcb->parent_id) + "\t" +
               std::to_string(pcb->ring_level) + "\t" +
               agent_state_str(pcb->state) + "\t" +
               std::to_string(pcb->syscall_count) + "\t" +
               std::to_string(pcb->memory_used / 1024) + "\t" +
               std::to_string(uptime) + "\t" +
               std::to_string(idle) + "\n";
    }
    return out;
}

std::unordered_map<int, AgentProcess> ProcessManager::get_ptable() {
    std::lock_guard<std::mutex> lock(mu_);
    return ptable_;
}

std::shared_ptr<ProcessControlBlock> ProcessManager::get_pcb(int agent_id) {
    std::lock_guard<std::mutex> lock(mu_);
    auto it = process_table_.find(agent_id);
    if (it != process_table_.end()) {
        return it->second;
    }
    return nullptr;
}

} // namespace aios

#include "aios/process_manager.h"
#include "aios/event_bus.h"
#include "aios/memory_manager.h"
#include "aios/task_scheduler.h"
#include "aios/kernel_logger.h"
#include "aios/vfs_manager.h"
#include "aios/cgroup_manager.h"

#include <cstdio>
#include <nlohmann/json.hpp>

namespace aios {

constexpr int AIOS_CLONE_NEWNS = 0x00020000;

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

int ProcessManager::spawn(int parent_id, const std::string& role,
                           const std::string& stdin_path,
                           const std::string& stdout_path,
                           int clone_flags) {
    int new_pid = next_pid_.fetch_add(1);

    auto pcb = std::make_shared<ProcessControlBlock>(new_pid, parent_id, role);
    pcb->ring_level = 3;
    pcb->stdin_path = stdin_path;
    pcb->stdout_path = stdout_path;

    if (clone_flags & AIOS_CLONE_NEWNS) {
        VfsManager::instance().create_container_namespace(new_pid);
        pcb->root_dir = VfsManager::instance().get_agent_root(new_pid);
        pcb->has_own_namespace = true;
    }

    {
        std::lock_guard<std::mutex> lock(mu_);
        process_table_[new_pid] = pcb;
        sync_legacy(new_pid);
    }

    std::printf("[ProcessManager] SPAWN | parent=%d -> child=%d | role=\"%s\"",
                parent_id, new_pid, role.c_str());
    if (!stdin_path.empty() || !stdout_path.empty()) {
        std::printf(" | stdin=%s stdout=%s", stdin_path.c_str(), stdout_path.c_str());
    }
    if (clone_flags & AIOS_CLONE_NEWNS) {
        std::printf(" | CLONE_NEWNS root=%s", pcb->root_dir.c_str());
    }
    std::printf("\n");

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

void ProcessManager::set_scheduler(TaskScheduler* scheduler) {
    scheduler_ = scheduler;
}

void ProcessManager::set_memory_manager(std::shared_ptr<MemoryManager> mem_mgr) {
    mem_mgr_ = std::move(mem_mgr);
}

bool ProcessManager::create_cgroup(const std::string& name, int max_tokens_per_minute,
                                    double cpu_quota, const std::string& parent) {
    return CgroupManager::instance().create_cgroup(name, max_tokens_per_minute, cpu_quota, parent);
}

bool ProcessManager::attach_to_cgroup(int agent_id, const std::string& cgroup_name) {
    return CgroupManager::instance().attach_to_cgroup(agent_id, cgroup_name);
}

bool ProcessManager::detach_from_cgroup(int agent_id) {
    return CgroupManager::instance().detach_from_cgroup(agent_id);
}

std::string ProcessManager::export_snapshot(int agent_id) {
    std::shared_ptr<ProcessControlBlock> pcb;

    {
        std::lock_guard<std::mutex> lock(mu_);
        auto it = process_table_.find(agent_id);
        if (it == process_table_.end()) {
            std::printf("[LiveMigration] EXPORT FAILED | agent=%d not found\n", agent_id);
            return "";
        }
        pcb = it->second;

        if (pcb->state == AgentState::ZOMBIE || pcb->state == AgentState::TERMINATED) {
            std::printf("[LiveMigration] EXPORT FAILED | agent=%d is %s\n",
                        agent_id, agent_state_str(pcb->state));
            return "";
        }

        pcb->state = AgentState::BLOCKED;
        auto lit = ptable_.find(agent_id);
        if (lit != ptable_.end()) {
            lit->second.state = ProcessState::SLEEPING;
        }
    }

    std::printf("[LiveMigration] EXPORT | agent=%d SUSPENDED, collecting state...\n", agent_id);

    nlohmann::json snapshot;
    snapshot["version"] = "2.0";
    snapshot["migration_type"] = "live";

    nlohmann::json pcb_json;
    pcb_json["agent_id"] = pcb->agent_id;
    pcb_json["parent_id"] = pcb->parent_id;
    pcb_json["role"] = pcb->role;
    pcb_json["ring_level"] = pcb->ring_level;
    pcb_json["memory_used"] = pcb->memory_used;
    pcb_json["syscall_count"] = pcb->syscall_count;
    pcb_json["state"] = agent_state_str(pcb->state);

    auto uptime_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now() - pcb->created_at).count();
    pcb_json["uptime_ms"] = uptime_ms;
    pcb_json["stdin_path"] = pcb->stdin_path;
    pcb_json["stdout_path"] = pcb->stdout_path;
    snapshot["pcb"] = pcb_json;

    nlohmann::json pages_arr = nlohmann::json::array();
    size_t page_count = 0;
    if (mem_mgr_) {
        auto pages = mem_mgr_->read_pages(agent_id);
        page_count = pages.size();
        for (const auto& page : pages) {
            nlohmann::json pj;
            pj["page_id"] = page.page_id;
            pj["agent_id"] = page.agent_id;
            pj["timestamp"] = page.timestamp;
            pj["role"] = page.role;
            pj["content"] = page.content;
            if (!page.embedding.empty()) {
                pj["embedding"] = page.embedding;
            }
            pages_arr.push_back(std::move(pj));
        }

        std::string filepath = "./snapshots/agent_" + std::to_string(agent_id) + ".snapshot.json";
        mem_mgr_->create_snapshot(agent_id, filepath);
    }
    snapshot["memory_pages"] = pages_arr;
    snapshot["page_count"] = page_count;

    {
        std::lock_guard<std::mutex> lock(mu_);
        pcb->state = AgentState::MIGRATED;
        auto lit = ptable_.find(agent_id);
        if (lit != ptable_.end()) {
            lit->second.state = ProcessState::ZOMBIE;
        }

        process_table_.erase(agent_id);
        ptable_.erase(agent_id);
    }

    try {
        pcb->exit_promise.set_value("[MIGRATED] Agent " + std::to_string(agent_id) + " exported for live migration");
    } catch (const std::future_error&) {}

    std::string result = snapshot.dump();

    std::printf("[LiveMigration] EXPORT | agent=%d | pages=%zu | snapshot_size=%zu bytes | LOCAL PROCESS DESTROYED\n",
                agent_id, page_count, result.size());

    KernelLogger::instance().log("[Ring 0 | Migration] EXPORT agent=" + std::to_string(agent_id) +
        " pages=" + std::to_string(page_count) + " size=" + std::to_string(result.size()));

    EventBus::instance().publish(EventType::AGENT_EXIT, "LiveMigration",
        "Agent " + std::to_string(agent_id) + " exported (live migration)");

    return result;
}

bool ProcessManager::import_snapshot(int agent_id, const std::string& snapshot_data) {
    nlohmann::json snapshot;
    try {
        snapshot = nlohmann::json::parse(snapshot_data);
    } catch (const nlohmann::json::parse_error& e) {
        std::printf("[LiveMigration] IMPORT FAILED | agent=%d | JSON parse error: %s\n",
                    agent_id, e.what());
        return false;
    }

    if (!snapshot.contains("pcb") || !snapshot["pcb"].is_object()) {
        std::printf("[LiveMigration] IMPORT FAILED | agent=%d | missing pcb section\n", agent_id);
        return false;
    }

    auto pcb_json = snapshot["pcb"];
    int snap_agent = pcb_json.value("agent_id", -1);
    if (snap_agent != agent_id) {
        std::printf("[LiveMigration] IMPORT FAILED | agent_id mismatch: snapshot=%d, requested=%d\n",
                    snap_agent, agent_id);
        return false;
    }

    int parent_id = pcb_json.value("parent_id", 0);
    std::string role = pcb_json.value("role", "");
    int ring_level = pcb_json.value("ring_level", 3);
    size_t memory_used = pcb_json.value("memory_used", 0ULL);
    int syscall_count = pcb_json.value("syscall_count", 0);

    {
        std::lock_guard<std::mutex> lock(mu_);

        auto pcb = std::make_shared<ProcessControlBlock>(agent_id, parent_id, role);
        pcb->ring_level = ring_level;
        pcb->memory_used = memory_used;
        pcb->syscall_count = syscall_count;
        pcb->stdin_path = pcb_json.value("stdin_path", "");
        pcb->stdout_path = pcb_json.value("stdout_path", "");
        pcb->state = AgentState::RUNNING;

        process_table_[agent_id] = pcb;
        sync_legacy(agent_id);

        auto lit = ptable_.find(agent_id);
        if (lit != ptable_.end()) {
            lit->second.state = ProcessState::RUNNING;
        }
    }

    if (snapshot.contains("memory_pages") && snapshot["memory_pages"].is_array() && mem_mgr_) {
        for (const auto& pj : snapshot["memory_pages"]) {
            MemoryPage page;
            page.page_id = pj.value("page_id", "");
            page.agent_id = pj.value("agent_id", agent_id);
            page.timestamp = pj.value("timestamp", 0ULL);
            page.role = pj.value("role", "");
            page.content = pj.value("content", "");
            if (pj.contains("embedding") && pj["embedding"].is_array()) {
                page.embedding = pj["embedding"].get<std::vector<float>>();
            }

            if (page.page_id.empty()) {
                page.page_id = "migrated_" + std::to_string(agent_id) + "_" + std::to_string(page.timestamp);
            }

            mem_mgr_->write_page(page);
        }

        size_t page_count = snapshot["memory_pages"].size();
        std::printf("[LiveMigration] IMPORT | agent=%d | restored %zu memory pages\n", agent_id, page_count);
    }

    if (scheduler_) {
        auto task = std::make_shared<AgentTask>(
            agent_id, 0, TaskStatus::READY,
            "[MIGRATED] Resume execution after live migration",
            TaskType::LLM_CHAT, "", "", -1
        );
        scheduler_->submit(std::move(task));
        std::printf("[LiveMigration] IMPORT | agent=%d | task submitted to scheduler\n", agent_id);
    }

    std::printf("[LiveMigration] IMPORT | agent=%d | role=\"%s\" | ring=%d | mem=%zu | syscalls=%d | ALIVE\n",
                agent_id, role.c_str(), ring_level, memory_used, syscall_count);

    KernelLogger::instance().log("[Ring 0 | Migration] IMPORT agent=" + std::to_string(agent_id) +
        " role=" + role + " ring=" + std::to_string(ring_level));

    EventBus::instance().publish(EventType::AGENT_SPAWN, "LiveMigration",
        "Agent " + std::to_string(agent_id) + " imported (live migration)");

    return true;
}

} // namespace aios

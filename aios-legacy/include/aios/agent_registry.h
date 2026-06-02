#pragma once

#include <cstdio>
#include <mutex>
#include <string>
#include <unordered_map>

namespace aios {

enum class PrivilegeLevel {
    RING_0,
    RING_3
};

inline const char* privilege_str(PrivilegeLevel level) {
    return level == PrivilegeLevel::RING_0 ? "Ring 0 (Kernel)" : "Ring 3 (User)";
}

class AgentRegistry {
public:
    static AgentRegistry& instance() {
        static AgentRegistry reg;
        return reg;
    }

    void register_agent(int agent_id, PrivilegeLevel level) {
        std::lock_guard<std::mutex> lock(mutex_);
        agents_[agent_id] = level;
        std::printf("[Registry] Agent#%d registered as %s\n",
                    agent_id, privilege_str(level));
    }

    PrivilegeLevel get_level(int agent_id) const {
        std::lock_guard<std::mutex> lock(mutex_);
        auto it = agents_.find(agent_id);
        if (it != agents_.end()) return it->second;
        return PrivilegeLevel::RING_3;
    }

    bool is_ring0(int agent_id) const {
        return get_level(agent_id) == PrivilegeLevel::RING_0;
    }

    bool can_access(int caller_id, int target_id) const {
        if (caller_id == target_id) return true;
        return is_ring0(caller_id);
    }

    bool can_cancel(int caller_id, int target_id) const {
        if (caller_id == target_id) return true;
        return is_ring0(caller_id);
    }

    bool has_agent(int agent_id) const {
        std::lock_guard<std::mutex> lock(mutex_);
        return agents_.count(agent_id) > 0;
    }

    void ensure_registered(int agent_id) {
        if (!has_agent(agent_id)) {
            PrivilegeLevel level = (agent_id == 0) ? PrivilegeLevel::RING_0 : PrivilegeLevel::RING_3;
            register_agent(agent_id, level);
        }
    }

private:
    AgentRegistry() = default;
    AgentRegistry(const AgentRegistry&) = delete;
    AgentRegistry& operator=(const AgentRegistry&) = delete;

    mutable std::mutex mutex_;
    std::unordered_map<int, PrivilegeLevel> agents_;
};

} // namespace aios

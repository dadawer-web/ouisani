#include "aios/cgroup_manager.h"
#include "aios/process_manager.h"
#include "aios/event_bus.h"
#include "aios/kernel_logger.h"

#include <cstdio>
#include <nlohmann/json.hpp>
#include <sstream>

namespace aios {

CgroupManager::CgroupManager() {
    auto root = std::make_unique<CgroupNode>("/", 0, 100.0);
    root->parent_name = "";
    nodes_["/"] = std::move(root);
}

CgroupManager& CgroupManager::instance() {
    static CgroupManager inst;
    return inst;
}

CgroupNode* CgroupManager::find_node(const std::string& name) {
    auto it = nodes_.find(name);
    if (it != nodes_.end()) return it->second.get();
    return nullptr;
}

const CgroupNode* CgroupManager::find_node(const std::string& name) const {
    auto it = nodes_.find(name);
    if (it != nodes_.end()) return it->second.get();
    return nullptr;
}

bool CgroupManager::create_cgroup(const std::string& name, int max_tokens_per_minute,
                                   double cpu_quota, const std::string& parent) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (name.empty() || name == "/") {
        std::printf("[CgroupManager] CREATE FAILED | invalid name: \"%s\"\n", name.c_str());
        return false;
    }

    if (nodes_.count(name)) {
        std::printf("[CgroupManager] CREATE FAILED | cgroup \"%s\" already exists\n", name.c_str());
        return false;
    }

    std::string effective_parent = parent.empty() ? "/" : parent;
    if (!nodes_.count(effective_parent)) {
        std::printf("[CgroupManager] CREATE FAILED | parent \"%s\" not found\n", effective_parent.c_str());
        return false;
    }

    auto node = std::make_unique<CgroupNode>(name, max_tokens_per_minute, cpu_quota);
    node->parent_name = effective_parent;
    nodes_[effective_parent]->children_names.push_back(name);
    nodes_[name] = std::move(node);

    std::printf("[CgroupManager] CREATE | name=\"%s\" | max_tpm=%d | cpu_quota=%.1f%% | parent=\"%s\"\n",
                name.c_str(), max_tokens_per_minute, cpu_quota, effective_parent.c_str());

    EventBus::instance().publish(EventType::AGENT_SPAWN, "CgroupManager",
        "Cgroup \"" + name + "\" created with max_tpm=" + std::to_string(max_tokens_per_minute));

    return true;
}

bool CgroupManager::attach_to_cgroup(int agent_id, const std::string& cgroup_name) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto* node = find_node(cgroup_name);
    if (!node) {
        std::printf("[CgroupManager] ATTACH FAILED | cgroup \"%s\" not found\n", cgroup_name.c_str());
        return false;
    }

    auto it = agent_cgroup_map_.find(agent_id);
    if (it != agent_cgroup_map_.end()) {
        auto* old_node = find_node(it->second);
        if (old_node) {
            auto& ids = old_node->agent_ids;
            ids.erase(std::remove(ids.begin(), ids.end(), agent_id), ids.end());
        }
    }

    node->agent_ids.push_back(agent_id);
    agent_cgroup_map_[agent_id] = cgroup_name;

    if (node->oom_blocked) {
        auto pcb = ProcessManager::instance().get_pcb(agent_id);
        if (pcb) {
            pcb->state = AgentState::OOM_BLOCKED;
        }
    }

    std::printf("[CgroupManager] ATTACH | agent=%d -> cgroup=\"%s\" | agents_in_group=%zu\n",
                agent_id, cgroup_name.c_str(), node->agent_ids.size());

    return true;
}

bool CgroupManager::detach_from_cgroup(int agent_id) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = agent_cgroup_map_.find(agent_id);
    if (it == agent_cgroup_map_.end()) return false;

    auto* node = find_node(it->second);
    if (node) {
        auto& ids = node->agent_ids;
        ids.erase(std::remove(ids.begin(), ids.end(), agent_id), ids.end());
    }

    std::printf("[CgroupManager] DETACH | agent=%d <- cgroup=\"%s\"\n",
                agent_id, it->second.c_str());

    agent_cgroup_map_.erase(it);
    return true;
}

bool CgroupManager::recursive_check_limit(const std::string& cgroup_name) const {
    const auto* node = find_node(cgroup_name);
    if (!node) return false;

    if (node->max_tokens_per_minute > 0 && node->tokens_used >= node->max_tokens_per_minute) {
        return true;
    }

    if (!node->parent_name.empty()) {
        return recursive_check_limit(node->parent_name);
    }

    return false;
}

void CgroupManager::recursive_block_agents(const std::string& cgroup_name) {
    auto* node = find_node(cgroup_name);
    if (!node) return;

    if (!node->oom_blocked) {
        node->oom_blocked = true;
        std::printf("[CgroupManager] ⚠️ OOM_BLOCKED | cgroup=\"%s\" | tokens_used=%d/%d | agents_affected=%zu\n",
                    cgroup_name.c_str(), node->tokens_used, node->max_tokens_per_minute,
                    node->agent_ids.size());

        KernelLogger::instance().log_alert(
            "[CgroupManager] OOM_BLOCKED cgroup=\"" + cgroup_name +
            "\" tokens_used=" + std::to_string(node->tokens_used) +
            "/" + std::to_string(node->max_tokens_per_minute));
    }

    for (int aid : node->agent_ids) {
        auto pcb = ProcessManager::instance().get_pcb(aid);
        if (pcb && pcb->state == AgentState::RUNNING) {
            pcb->state = AgentState::OOM_BLOCKED;
            std::printf("[CgroupManager] OOM_BLOCKED agent=%d in cgroup=\"%s\"\n",
                        aid, cgroup_name.c_str());
        }
    }

    for (const auto& child_name : node->children_names) {
        recursive_block_agents(child_name);
    }
}

void CgroupManager::recursive_unblock_agents(const std::string& cgroup_name) {
    auto* node = find_node(cgroup_name);
    if (!node) return;

    if (node->oom_blocked) {
        node->oom_blocked = false;
        std::printf("[CgroupManager] ✅ UNBLOCKED | cgroup=\"%s\" | tokens_used=%d/%d\n",
                    cgroup_name.c_str(), node->tokens_used, node->max_tokens_per_minute);
    }

    for (int aid : node->agent_ids) {
        auto pcb = ProcessManager::instance().get_pcb(aid);
        if (pcb && pcb->state == AgentState::OOM_BLOCKED) {
            pcb->state = AgentState::RUNNING;
            std::printf("[CgroupManager] UNBLOCKED agent=%d in cgroup=\"%s\"\n",
                        aid, cgroup_name.c_str());
        }
    }

    for (const auto& child_name : node->children_names) {
        recursive_unblock_agents(child_name);
    }
}

bool CgroupManager::consume_tokens(int agent_id, int tokens) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = agent_cgroup_map_.find(agent_id);
    if (it == agent_cgroup_map_.end()) return true;

    std::string cgroup_name = it->second;
    auto* node = find_node(cgroup_name);
    if (!node) return true;

    if (node->oom_blocked) {
        std::printf("[CgroupManager] REJECT | agent=%d | cgroup=\"%s\" is OOM_BLOCKED\n",
                    agent_id, cgroup_name.c_str());
        return false;
    }

    std::string cur = cgroup_name;
    while (!cur.empty()) {
        auto* n = find_node(cur);
        if (!n) break;
        n->tokens_used += tokens;

        if (n->max_tokens_per_minute > 0 && n->tokens_used >= n->max_tokens_per_minute && !n->oom_blocked) {
            std::printf("[CgroupManager] OOM | cgroup=\"%s\" | tokens_used=%d >= max=%d → blocking subtree\n",
                        cur.c_str(), n->tokens_used, n->max_tokens_per_minute);
            recursive_block_agents(cur);

            EventBus::instance().publish(EventType::AGENT_EXIT, "CgroupManager",
                "Cgroup \"" + cur + "\" OOM! tokens_used=" + std::to_string(n->tokens_used) +
                " >= max=" + std::to_string(n->max_tokens_per_minute));

            return false;
        }

        cur = n->parent_name;
    }

    return true;
}

bool CgroupManager::is_oom_blocked(int agent_id) const {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = agent_cgroup_map_.find(agent_id);
    if (it == agent_cgroup_map_.end()) return false;

    const auto* node = find_node(it->second);
    if (!node) return false;

    if (node->oom_blocked) return true;

    std::string cur = node->parent_name;
    while (!cur.empty()) {
        const auto* n = find_node(cur);
        if (!n) break;
        if (n->oom_blocked) return true;
        cur = n->parent_name;
    }

    return false;
}

std::string CgroupManager::get_agent_cgroup(int agent_id) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = agent_cgroup_map_.find(agent_id);
    if (it != agent_cgroup_map_.end()) return it->second;
    return "";
}

void CgroupManager::reset_period() {
    std::lock_guard<std::mutex> lock(mutex_);

    for (auto& [name, node] : nodes_) {
        node->tokens_used = 0;
        if (node->oom_blocked) {
            node->oom_blocked = false;
            for (int aid : node->agent_ids) {
                auto pcb = ProcessManager::instance().get_pcb(aid);
                if (pcb && pcb->state == AgentState::OOM_BLOCKED) {
                    pcb->state = AgentState::RUNNING;
                }
            }
        }
    }

    std::printf("[CgroupManager] RESET | all cgroup token counters reset, OOM_BLOCKED agents unblocked\n");
}

std::string CgroupManager::dump_tree() const {
    std::lock_guard<std::mutex> lock(mutex_);

    std::function<void(const std::string&, int)> walk;
    std::string result;

    walk = [&](const std::string& name, int depth) {
        const auto* node = find_node(name);
        if (!node) return;

        std::string indent(depth * 2, ' ');
        std::string status = node->oom_blocked ? " [OOM_BLOCKED]" : "";
        result += indent + "├─ " + name +
                  " | tpm=" + std::to_string(node->tokens_used) + "/" + std::to_string(node->max_tokens_per_minute) +
                  " | cpu=" + std::to_string(static_cast<int>(node->cpu_quota)) + "%" +
                  " | agents=" + std::to_string(node->agent_ids.size()) +
                  status + "\n";

        for (int aid : node->agent_ids) {
            result += indent + "│  └─ agent_" + std::to_string(aid) + "\n";
        }

        for (const auto& child : node->children_names) {
            walk(child, depth + 1);
        }
    };

    result = "/sys/fs/cgroup\n";
    walk("/", 0);
    return result;
}

std::string CgroupManager::get_cgroup_info(const std::string& name) const {
    std::lock_guard<std::mutex> lock(mutex_);

    const auto* node = find_node(name);
    if (!node) return "{}";

    nlohmann::json info;
    info["group_name"] = node->group_name;
    info["max_tokens_per_minute"] = node->max_tokens_per_minute;
    info["tokens_used"] = node->tokens_used;
    info["cpu_quota"] = node->cpu_quota;
    info["oom_blocked"] = node->oom_blocked;
    info["parent"] = node->parent_name;
    info["agent_count"] = node->agent_ids.size();

    nlohmann::json agents_arr = nlohmann::json::array();
    for (int aid : node->agent_ids) {
        agents_arr.push_back(aid);
    }
    info["agents"] = agents_arr;

    nlohmann::json children_arr = nlohmann::json::array();
    for (const auto& child : node->children_names) {
        children_arr.push_back(child);
    }
    info["children"] = children_arr;

    return info.dump();
}

} // namespace aios

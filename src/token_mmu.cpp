#include "aios/token_mmu.h"
#include "aios/event_bus.h"
#include "aios/vector_node.h"
#include "aios/vfs_manager.h"
#include "aios/cgroup_manager.h"
#include "aios/process_manager.h"

#include <cstdio>
#include <nlohmann/json.hpp>
#include <string>

namespace aios {

TokenMmu& TokenMmu::instance() {
    static TokenMmu mmu;
    return mmu;
}

int TokenMmu::estimate_tokens(const std::string& text) const {
    return static_cast<int>(text.size()) / 4;
}

void TokenMmu::kswapd(int agent_id) {
    auto& ctx = agent_contexts_[agent_id];

    int swap_count = 0;
    while (ctx.token_count > RAM_LIMIT && !ctx.messages.empty()) {
        std::string popped = ctx.messages.front();
        ctx.messages.pop_front();
        int popped_tokens = estimate_tokens(popped);
        ctx.token_count -= popped_tokens;
        if (ctx.token_count < 0) ctx.token_count = 0;
        swap_count++;

        std::string vec_path = "/dev/vec_mem_" + std::to_string(agent_id);
        auto node = VfsManager::instance().resolve_path(vec_path, 0);
        if (node) {
            auto vec_node = std::dynamic_pointer_cast<VectorNode>(node);
            if (vec_node) {
                vec_node->write_as(popped, 0);
            }
        }

        std::printf("[kswapd] Agent %d | Swap Out #%d | tokens=%d→%d | evicted=\"%s\"\n",
                    agent_id, swap_count,
                    ctx.token_count + popped_tokens, ctx.token_count,
                    popped.size() > 40 ? (popped.substr(0, 40) + "...").c_str() : popped.c_str());
    }

    if (swap_count > 0) {
        EventBus::instance().publish(EventType::VFS_WRITE, "kswapd",
            "Agent " + std::to_string(agent_id) +
            " OOM! Swapped out " + std::to_string(swap_count) +
            " page(s) to Vector DB | active_tokens=" + std::to_string(ctx.token_count));
    }
}

void TokenMmu::add_context(int agent_id, const std::string& message) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto& cgroup_mgr = CgroupManager::instance();
    if (cgroup_mgr.is_oom_blocked(agent_id)) {
        std::printf("[TokenMMU] REJECT | Agent %d | OOM_BLOCKED by cgroup, cannot consume tokens\n",
                    agent_id);
        auto pcb = ProcessManager::instance().get_pcb(agent_id);
        if (pcb && pcb->state != AgentState::OOM_BLOCKED) {
            pcb->state = AgentState::OOM_BLOCKED;
        }
        return;
    }

    auto& ctx = agent_contexts_[agent_id];
    ctx.messages.push_back(message);
    ctx.token_count += estimate_tokens(message);

    std::printf("[TokenMMU] Agent %d | +\"%s\" | tokens=%d/%d\n",
                agent_id,
                message.size() > 30 ? (message.substr(0, 30) + "...").c_str() : message.c_str(),
                ctx.token_count, RAM_LIMIT);

    int tokens = estimate_tokens(message);
    bool cgroup_ok = cgroup_mgr.consume_tokens(agent_id, tokens);
    if (!cgroup_ok) {
        std::printf("[TokenMMU] Agent %d | CGROUP OOM! Token consumption rejected, agent OOM_BLOCKED\n",
                    agent_id);
        auto pcb = ProcessManager::instance().get_pcb(agent_id);
        if (pcb && pcb->state != AgentState::OOM_BLOCKED) {
            pcb->state = AgentState::OOM_BLOCKED;
        }
    }

    if (ctx.token_count > RAM_LIMIT) {
        std::printf("[TokenMMU] Agent %d | OOM! tokens=%d > RAM_LIMIT=%d → triggering kswapd\n",
                    agent_id, ctx.token_count, RAM_LIMIT);
        kswapd(agent_id);
    }
}

void TokenMmu::clear_context(int agent_id) {
    std::lock_guard<std::mutex> lock(mutex_);
    agent_contexts_.erase(agent_id);
    std::printf("[TokenMMU] Agent %d | Context cleared\n", agent_id);
}

std::string TokenMmu::get_active_context(int agent_id) const {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = agent_contexts_.find(agent_id);
    if (it == agent_contexts_.end() || it->second.messages.empty()) {
        return "";
    }

    std::string result;
    for (const auto& msg : it->second.messages) {
        if (!result.empty()) result += "\n";
        result += msg;
    }
    return result;
}

std::string TokenMmu::page_fault_recovery(int agent_id, const std::string& query, int top_k) const {
    std::string vec_path = "/dev/vec_mem_" + std::to_string(agent_id);
    auto node = VfsManager::instance().resolve_path(vec_path, 0);
    if (!node) return "";

    auto vec_node = std::dynamic_pointer_cast<VectorNode>(node);
    if (!vec_node) return "";

    std::string search_result = vec_node->search(query, top_k);

    try {
        auto arr = nlohmann::json::parse(search_result);
        if (!arr.is_array() || arr.empty()) return "";

        std::string recovered;
        for (const auto& item : arr) {
            std::string text = item.value("text", "");
            float score = item.value("score", 0.0f);
            if (score > 0.3f && !text.empty()) {
                recovered += "[Page Fault Recovery | score=" +
                             std::to_string(static_cast<int>(score * 100)) + "%] " +
                             text + "\n";
            }
        }
        if (!recovered.empty()) {
            std::printf("[TokenMMU] Agent %d | Page Fault Recovery | query=\"%s\" | recovered=%zu bytes\n",
                        agent_id,
                        query.size() > 30 ? (query.substr(0, 30) + "...").c_str() : query.c_str(),
                        recovered.size());
        }
        return recovered;
    } catch (...) {
        return "";
    }
}

int TokenMmu::total_tokens(int agent_id) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = agent_contexts_.find(agent_id);
    if (it == agent_contexts_.end()) return 0;
    return it->second.token_count;
}

int TokenMmu::message_count(int agent_id) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = agent_contexts_.find(agent_id);
    if (it == agent_contexts_.end()) return 0;
    return static_cast<int>(it->second.messages.size());
}

std::list<std::string> TokenMmu::get_messages(int agent_id) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = agent_contexts_.find(agent_id);
    if (it == agent_contexts_.end()) return {};
    return it->second.messages;
}

void TokenMmu::replace_messages(int agent_id, std::list<std::string> new_messages) {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = agent_contexts_.find(agent_id);
    if (it == agent_contexts_.end()) return;

    auto& ctx = it->second;
    int old_tokens = ctx.token_count;
    ctx.messages = std::move(new_messages);

    ctx.token_count = 0;
    for (const auto& msg : ctx.messages) {
        ctx.token_count += estimate_tokens(msg);
    }

    std::printf("[TokenMMU] Agent %d | Messages replaced | tokens=%d→%d | messages=%zu\n",
                agent_id, old_tokens, ctx.token_count, ctx.messages.size());
}

int TokenMmu::get_token_limit(int agent_id) const {
    std::string cgroup = CgroupManager::instance().get_agent_cgroup(agent_id);
    if (!cgroup.empty()) {
        std::string info_str = CgroupManager::instance().get_cgroup_info(cgroup);
        try {
            auto info = nlohmann::json::parse(info_str);
            int cgroup_tpm = info.value("max_tokens_per_minute", 0);
            if (cgroup_tpm > 0) return cgroup_tpm;
        } catch (...) {}
    }
    return DEFAULT_TOKEN_LIMIT;
}

bool TokenMmu::check_watermark(int agent_id) const {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = agent_contexts_.find(agent_id);
    if (it == agent_contexts_.end()) return false;

    int current_tokens = it->second.token_count;

    std::string cgroup = CgroupManager::instance().get_agent_cgroup(agent_id);
    int token_limit = DEFAULT_TOKEN_LIMIT;
    if (!cgroup.empty()) {
        std::string info_str = CgroupManager::instance().get_cgroup_info(cgroup);
        try {
            auto info = nlohmann::json::parse(info_str);
            int cgroup_tpm = info.value("max_tokens_per_minute", 0);
            if (cgroup_tpm > 0) token_limit = cgroup_tpm;
        } catch (...) {}
    }

    int watermark_high = static_cast<int>(token_limit * WATERMARK_HIGH_RATIO);

    if (current_tokens >= watermark_high) {
        std::printf("[Kernel MMU] ⚠️  Watermark HIGH hit! Agent %d | tokens=%d >= watermark=%d (limit=%d, %.0f%%)\n",
                    agent_id, current_tokens, watermark_high, token_limit, WATERMARK_HIGH_RATIO * 100);
        return true;
    }

    return false;
}

} // namespace aios

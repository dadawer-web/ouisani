#pragma once

#include <list>
#include <mutex>
#include <string>
#include <unordered_map>

namespace aios {

class TokenMmu {
public:
    static TokenMmu& instance();

    int estimate_tokens(const std::string& text) const;

    void add_context(int agent_id, const std::string& message);

    std::string get_active_context(int agent_id) const;

    std::string page_fault_recovery(int agent_id, const std::string& query, int top_k = 2) const;

    int total_tokens(int agent_id) const;

    TokenMmu(const TokenMmu&) = delete;
    TokenMmu& operator=(const TokenMmu&) = delete;

    static constexpr int RAM_LIMIT = 100;

private:
    TokenMmu() = default;

    void kswapd(int agent_id);

    struct AgentContext {
        std::list<std::string> messages;
        int token_count = 0;
    };

    std::unordered_map<int, AgentContext> agent_contexts_;
    mutable std::mutex mutex_;
};

} // namespace aios

#pragma once

#include <list>
#include <mutex>
#include <string>
#include <unordered_map>

namespace aios {

class TokenZram;

class TokenMmu {
public:
    friend class TokenZram;

    static TokenMmu& instance();

    int estimate_tokens(const std::string& text) const;

    void add_context(int agent_id, const std::string& message);

    void clear_context(int agent_id);

    std::string get_active_context(int agent_id) const;

    std::string page_fault_recovery(int agent_id, const std::string& query, int top_k = 2) const;

    int total_tokens(int agent_id) const;

    int message_count(int agent_id) const;

    std::list<std::string> get_messages(int agent_id) const;

    void replace_messages(int agent_id, std::list<std::string> new_messages);

    bool check_watermark(int agent_id) const;

    int get_token_limit(int agent_id) const;

    TokenMmu(const TokenMmu&) = delete;
    TokenMmu& operator=(const TokenMmu&) = delete;

    static constexpr int RAM_LIMIT = 100;
    static constexpr int DEFAULT_TOKEN_LIMIT = 8000;
    static constexpr double WATERMARK_HIGH_RATIO = 0.8;

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

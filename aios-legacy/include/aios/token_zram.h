#pragma once

#include <memory>
#include <string>

namespace aios {

struct AgentTask;

class TokenZram {
public:
    static TokenZram& instance();

    std::string compress_context(const std::string& cold_memory);

    void compress_agent_memory(std::shared_ptr<AgentTask> task);

    void compress_agent_memory_by_id(int agent_id);

    int total_compressed_blocks() const;
    int total_tokens_saved() const;

    TokenZram(const TokenZram&) = delete;
    TokenZram& operator=(const TokenZram&) = delete;

private:
    TokenZram() = default;

    int compressed_blocks_ = 0;
    int tokens_saved_ = 0;
};

} // namespace aios

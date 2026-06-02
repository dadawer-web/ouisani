#include "aios/token_zram.h"
#include "aios/token_mmu.h"
#include "aios/agent_task.h"
#include "aios/llm_router.h"
#include "aios/llm_adapter.h"
#include "aios/event_bus.h"
#include "aios/kernel_logger.h"

#include <cstdio>
#include <nlohmann/json.hpp>
#include <sstream>
#include <string>

namespace aios {

TokenZram& TokenZram::instance() {
    static TokenZram zram;
    return zram;
}

std::string TokenZram::compress_context(const std::string& cold_memory) {
    if (cold_memory.empty()) return "";

    std::string system_prompt =
        "You are a memory compression engine for an AI Operating System. "
        "Your task is to deeply summarize the following system context into a dense state representation. "
        "RULES: (1) Keep ALL factual information — names, numbers, decisions, conclusions. "
        "(2) Remove all conversational filler, pleasantries, and repetition. "
        "(3) Use compact notation: key=value pairs, bullet points, abbreviations. "
        "(4) Preserve the logical ordering and causal relationships. "
        "(5) Minimize token count while maximizing information density. "
        "Output ONLY the compressed representation, no meta-commentary.";

    std::string compressed;

    if (LlmRouter::instance().has_providers()) {
        try {
            compressed = LlmRouter::instance().route_and_execute(
                "zram_compress", system_prompt, cold_memory);
        } catch (const std::exception& e) {
            std::printf("[TokenZRAM] LLM compression failed: %s, falling back to extractive\n", e.what());
        }
    }

    if (compressed.empty()) {
        std::istringstream iss(cold_memory);
        std::string line;
        std::string extracted;
        int line_count = 0;

        while (std::getline(iss, line)) {
            line_count++;
            if (line.empty()) continue;
            if (line.size() > 20) {
                extracted += line.substr(0, static_cast<size_t>(line.size() * 0.6)) + "...";
            } else {
                extracted += line;
            }
            extracted += " | ";
        }

        if (extracted.size() >= 3) {
            extracted = extracted.substr(0, extracted.size() - 3);
        }

        size_t target_size = cold_memory.size() / 3;
        if (extracted.size() > target_size && target_size > 0) {
            extracted = extracted.substr(0, target_size) + "...[ZRAM_TRUNC]";
        }

        compressed = std::move(extracted);
    }

    if (compressed.empty()) {
        compressed = "[ZRAM:empty_cold_data]";
    }

    int original_tokens = TokenMmu::instance().estimate_tokens(cold_memory);
    int compressed_tokens = TokenMmu::instance().estimate_tokens(compressed);
    double ratio = original_tokens > 0
        ? 100.0 * compressed_tokens / original_tokens : 0.0;

    std::printf("[TokenZRAM] compress_context | original=%d tokens → compressed=%d tokens (%.0f%%)\n",
                original_tokens, compressed_tokens, ratio);

    return compressed;
}

void TokenZram::compress_agent_memory(std::shared_ptr<AgentTask> task) {
    if (!task) return;
    compress_agent_memory_by_id(task->agent_id);
}

void TokenZram::compress_agent_memory_by_id(int agent_id) {
    auto& mmu = TokenMmu::instance();

    int msg_count = mmu.message_count(agent_id);
    if (msg_count < 2) {
        std::printf("[TokenZRAM] Agent %d | Only %d messages, nothing to compress\n",
                    agent_id, msg_count);
        return;
    }

    int cold_count = msg_count / 2;
    if (cold_count < 1) cold_count = 1;

    std::printf("[TokenZRAM] Agent %d | Starting ZRAM compression | total_messages=%d | cold_messages=%d\n",
                agent_id, msg_count, cold_count);

    auto messages = mmu.get_messages(agent_id);
    if (static_cast<int>(messages.size()) < 2) return;

    std::string cold_data;
    auto it = messages.begin();
    for (int i = 0; i < cold_count && it != messages.end(); ++i, ++it) {
        if (!cold_data.empty()) cold_data += "\n";
        cold_data += *it;
    }

    int original_tokens = mmu.estimate_tokens(cold_data);
    std::printf("[TokenZRAM] Agent %d | Cold data extracted | %d messages | %d tokens | %zu bytes\n",
                agent_id, cold_count, original_tokens, cold_data.size());

    std::string compressed = compress_context(cold_data);

    std::string zram_block = "<ZRAM_COMPRESSED_BLOCK tokens_original="
        + std::to_string(original_tokens)
        + " tokens_compressed=" + std::to_string(mmu.estimate_tokens(compressed))
        + " messages_merged=" + std::to_string(cold_count)
        + ">\n" + compressed
        + "\n</ZRAM_COMPRESSED_BLOCK>";

    auto new_it = messages.begin();
    std::list<std::string> new_messages;
    new_messages.push_back(zram_block);

    for (int i = 0; i < cold_count && new_it != messages.end(); ++i) {
        ++new_it;
    }

    for (; new_it != messages.end(); ++new_it) {
        new_messages.push_back(*new_it);
    }

    int old_total = mmu.total_tokens(agent_id);
    mmu.replace_messages(agent_id, std::move(new_messages));
    int new_total = mmu.total_tokens(agent_id);

    int saved = old_total - new_total;
    if (saved < 0) saved = 0;

    compressed_blocks_++;
    tokens_saved_ += saved;

    std::printf("[TokenZRAM] Agent %d | ✅ ZRAM compression complete!\n", agent_id);
    std::printf("[TokenZRAM]            | messages: %d → %zu\n", msg_count, new_messages.size());
    std::printf("[TokenZRAM]            | tokens: %d → %d (saved %d)\n", old_total, new_total, saved);
    std::printf("[TokenZRAM]            | cold_data: %d tokens → ZRAM block: %d tokens\n",
                original_tokens, mmu.estimate_tokens(zram_block));

    EventBus::instance().publish(EventType::VFS_WRITE, "TokenZRAM",
        "Agent " + std::to_string(agent_id) +
        " ZRAM compressed: " + std::to_string(msg_count) + "→" +
        std::to_string(new_messages.size()) + " messages, saved " +
        std::to_string(saved) + " tokens");

    KernelLogger::instance().log(
        "[TokenZRAM] Agent=" + std::to_string(agent_id) +
        " compressed " + std::to_string(cold_count) +
        " cold messages into ZRAM block, saved " + std::to_string(saved) + " tokens");
}

int TokenZram::total_compressed_blocks() const {
    return compressed_blocks_;
}

int TokenZram::total_tokens_saved() const {
    return tokens_saved_;
}

} // namespace aios

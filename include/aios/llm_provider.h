#pragma once

#include "aios/llm_adapter.h"

#include <chrono>
#include <cstdio>
#include <memory>
#include <string>
#include <thread>
#include <vector>

namespace aios {

class ILlmProvider {
public:
    virtual ~ILlmProvider() = default;
    virtual std::string get_name() const = 0;
    virtual std::string generate(const std::string& prompt) = 0;
};

class LocalOllamaProvider : public ILlmProvider {
public:
    std::string get_name() const override { return "Local-Ollama-8B"; }

    std::string generate(const std::string& prompt) override {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
        std::printf("[LocalOllamaProvider] Fast response for: \"%s\"\n",
                    prompt.size() > 60 ? (prompt.substr(0, 60) + "...").c_str() : prompt.c_str());
        return "[Local-Ollama] 已极速处理日常任务: " + prompt;
    }
};

class CloudGptProvider : public ILlmProvider {
public:
    explicit CloudGptProvider(std::shared_ptr<LlmAdapter> llm) : llm_(std::move(llm)) {}

    std::string get_name() const override { return "Cloud-mimo-v2.5-pro"; }

    std::string generate(const std::string& prompt) override {
        if (llm_ && llm_->has_api_key()) {
            try {
                std::string result = llm_->generate("", prompt);
                std::printf("[CloudGptProvider] Deep inference via %s | result=%zu bytes\n",
                            llm_->model().c_str(), result.size());
                return result;
            } catch (const std::exception& e) {
                std::fprintf(stderr, "[CloudGptProvider] API error, fallback: %s\n", e.what());
                return "[Cloud-mimo] API Error: " + std::string(e.what());
            }
        }
        std::this_thread::sleep_for(std::chrono::seconds(2));
        return "[Cloud-GPT-4o] 💡 已深度思考并编写代码: " + prompt;
    }

private:
    std::shared_ptr<LlmAdapter> llm_;
};

} // namespace aios

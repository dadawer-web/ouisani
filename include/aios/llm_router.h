#pragma once

#include "aios/llm_provider.h"

#include <memory>
#include <string>
#include <vector>

namespace aios {

class LlmRouter {
public:
    static LlmRouter& instance();

    void register_provider(std::shared_ptr<ILlmProvider> provider);
    std::string route_and_execute(const std::string& prompt);
    bool has_providers() const { return !providers_.empty(); }

    LlmRouter(const LlmRouter&) = delete;
    LlmRouter& operator=(const LlmRouter&) = delete;

private:
    LlmRouter() = default;

    bool needs_deep_inference(const std::string& prompt) const;

    std::vector<std::shared_ptr<ILlmProvider>> providers_;
};

} // namespace aios

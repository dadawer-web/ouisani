#include "aios/llm_router.h"
#include "aios/event_bus.h"

#include <algorithm>
#include <cstdio>
#include <string>

namespace aios {

LlmRouter& LlmRouter::instance() {
    static LlmRouter router;
    return router;
}

void LlmRouter::register_provider(std::shared_ptr<ILlmProvider> provider) {
    std::printf("[LlmRouter] Registered provider: %s\n", provider->get_name().c_str());
    providers_.push_back(std::move(provider));
}

bool LlmRouter::needs_deep_inference(const std::string& prompt) const {
    static const std::vector<std::string> deep_keywords = {
        "代码", "C++", "编译", "复杂", "算法",
        "实现", "架构", "重构", "设计模式",
        "code", "compile", "algorithm", "implement",
        "debug", "optimize", "refactor"
    };

    for (const auto& kw : deep_keywords) {
        if (prompt.find(kw) != std::string::npos) {
            return true;
        }
    }
    return false;
}

std::string LlmRouter::route_and_execute(const std::string& prompt) {
    if (providers_.empty()) {
        std::fprintf(stderr, "[LlmRouter] No providers registered!\n");
        return "[LlmRouter Error] No providers available";
    }

    bool deep = needs_deep_inference(prompt);

    std::shared_ptr<ILlmProvider> selected;

    if (deep && providers_.size() > 1) {
        selected = providers_[1];
    } else {
        selected = providers_[0];
    }

    std::printf("[LlmRouter] %s → %s\n",
                deep ? "DEEP" : "FAST",
                selected->get_name().c_str());

    EventBus::instance().publish(EventType::LLM_REQ_START, "LlmRouter",
        "Routing to " + std::string(selected->get_name()) +
        " (mode=" + std::string(deep ? "DEEP" : "FAST") + ")");

    return selected->generate(prompt);
}

} // namespace aios

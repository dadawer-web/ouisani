#pragma once

#include <string>

namespace aios {

class LlmAdapter {
public:
    explicit LlmAdapter(const std::string& base_url = "http://localhost:11434",
                        const std::string& model = "qwen2.5:7b",
                        int timeout_seconds = 120);

    std::string generate(const std::string& prompt);

    bool is_available() const;

    const std::string& model() const { return model_; }
    const std::string& base_url() const { return base_url_; }

private:
    std::string base_url_;
    std::string model_;
    int timeout_seconds_;
};

} // namespace aios

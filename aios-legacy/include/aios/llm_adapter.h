#pragma once

#include <stdexcept>
#include <future>
#include <memory>
#include <string>
#include <vector>

namespace aios {

class AudioNode;

struct ChatMessage {
    std::string role;
    std::string content;
};

class SemanticPageFaultException : public std::runtime_error {
public:
    explicit SemanticPageFaultException(const std::string& keywords)
        : std::runtime_error("Semantic Page Fault: " + keywords)
        , keywords_(keywords) {}

    const std::string& keywords() const noexcept { return keywords_; }

private:
    std::string keywords_;
};

class LlmAdapter {
public:
    LlmAdapter(const std::string& api_key,
               const std::string& base_url = "https://api.deepseek.com",
               const std::string& model = "deepseek-chat",
               int timeout_seconds = 120);

    void set_embedding_config(const std::string& api_key,
                              const std::string& base_url,
                              const std::string& model);

    std::string generate(const std::string& system_prompt,
                         const std::vector<ChatMessage>& messages);

    std::string generate(const std::string& system_prompt,
                         const std::string& user_prompt);

    std::future<std::string> generate_async(const std::string& system_prompt,
                                            const std::vector<ChatMessage>& messages);

    std::future<std::string> generate_async(const std::string& system_prompt,
                                            const std::string& user_prompt);

    std::vector<float> get_embedding(const std::string& text);

    std::future<std::vector<float>> get_embedding_async(const std::string& text);

    void stream_tts(const std::string& text, std::shared_ptr<AudioNode> pcm_dev,
                    std::shared_ptr<AudioNode> viseme_dev);

    bool is_available() const;

    const std::string& model() const { return model_; }
    const std::string& base_url() const { return base_url_; }
    bool has_api_key() const { return !api_key_.empty(); }
    bool has_embedding_config() const { return !embedding_api_key_.empty(); }

private:
    std::string api_key_;
    std::string base_url_;
    std::string model_;
    int timeout_seconds_;

    std::string embedding_api_key_;
    std::string embedding_base_url_;
    std::string embedding_model_;
};

} // namespace aios

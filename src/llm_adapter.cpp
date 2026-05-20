#include "aios/llm_adapter.h"

#include <httplib.h>
#include <nlohmann/json.hpp>

#include <cstdio>

namespace aios {

LlmAdapter::LlmAdapter(const std::string& api_key,
                        const std::string& base_url,
                        const std::string& model,
                        int timeout_seconds)
    : api_key_(api_key)
    , base_url_(base_url)
    , model_(model)
    , timeout_seconds_(timeout_seconds)
{}

void LlmAdapter::set_embedding_config(const std::string& api_key,
                                       const std::string& base_url,
                                       const std::string& model) {
    embedding_api_key_ = api_key;
    embedding_base_url_ = base_url;
    embedding_model_ = model;
    std::printf("[LlmAdapter] Embedding config: %s model=%s\n",
                embedding_base_url_.c_str(), embedding_model_.c_str());
}

std::string LlmAdapter::generate(const std::string& system_prompt,
                                  const std::string& user_prompt) {
    std::vector<ChatMessage> msgs;
    msgs.push_back({"user", user_prompt});
    return generate(system_prompt, msgs);
}

std::future<std::string> LlmAdapter::generate_async(const std::string& system_prompt,
                                                     const std::vector<ChatMessage>& messages) {
    return std::async(std::launch::async, [this, system_prompt, messages]() {
        return generate(system_prompt, messages);
    });
}

std::future<std::string> LlmAdapter::generate_async(const std::string& system_prompt,
                                                     const std::string& user_prompt) {
    return std::async(std::launch::async, [this, system_prompt, user_prompt]() {
        return generate(system_prompt, user_prompt);
    });
}

std::future<std::vector<float>> LlmAdapter::get_embedding_async(const std::string& text) {
    return std::async(std::launch::async, [this, text]() {
        return get_embedding(text);
    });
}

std::vector<float> LlmAdapter::get_embedding(const std::string& text) {
    if (embedding_api_key_.empty()) {
        std::printf("[LlmAdapter] WARNING: No embedding API key, returning empty vector\n");
        return {};
    }

    std::string host = embedding_base_url_;
    int port = 443;
    bool use_ssl = false;

    auto proto_pos = embedding_base_url_.find("://");
    if (proto_pos != std::string::npos) {
        std::string proto = embedding_base_url_.substr(0, proto_pos);
        use_ssl = (proto == "https");
        host = embedding_base_url_.substr(proto_pos + 3);
    }

    auto port_pos = host.rfind(':');
    if (port_pos != std::string::npos) {
        port = std::stoi(host.substr(port_pos + 1));
        host = host.substr(0, port_pos);
    }

    std::string emb_path = "/v1/embeddings";
    if (embedding_base_url_.find("/v1") != std::string::npos &&
        embedding_base_url_.find("://") != std::string::npos) {
        auto after_proto = embedding_base_url_.substr(embedding_base_url_.find("://") + 3);
        auto slash_pos = after_proto.find('/');
        if (slash_pos != std::string::npos) {
            emb_path = after_proto.substr(slash_pos) + "/embeddings";
            host = after_proto.substr(0, slash_pos);
        }
    }

    nlohmann::json body;
    body["model"] = embedding_model_;
    body["input"] = text;

    std::string body_str = body.dump();

    std::printf("[Embedding] POST %s%s | model=%s | text_len=%zu\n",
                embedding_base_url_.c_str(), emb_path.c_str(),
                embedding_model_.c_str(), text.size());

    httplib::Result res = [&]() -> httplib::Result {
        if (use_ssl) {
            httplib::SSLClient cli(host, port);
            cli.set_connection_timeout(10);
            cli.set_read_timeout(30);
            cli.set_write_timeout(10);
            cli.enable_server_certificate_verification(false);

            httplib::Headers headers;
            headers.emplace("Authorization", "Bearer " + embedding_api_key_);
            headers.emplace("Content-Type", "application/json");

            return cli.Post(emb_path.c_str(), headers, body_str, "application/json");
        } else {
            httplib::Client cli(host, port);
            cli.set_connection_timeout(10);
            cli.set_read_timeout(30);
            cli.set_write_timeout(10);

            httplib::Headers headers;
            headers.emplace("Authorization", "Bearer " + embedding_api_key_);
            headers.emplace("Content-Type", "application/json");

            return cli.Post(emb_path.c_str(), headers, body_str, "application/json");
        }
    }();

    if (!res) {
        auto err = httplib::to_string(res.error());
        std::printf("[Embedding] HTTP request failed: %s\n", err.c_str());
        return {};
    }

    if (res->status != 200) {
        std::printf("[Embedding] HTTP %d: %s\n", res->status, res->body.substr(0, 200).c_str());
        return {};
    }

    try {
        auto resp_json = nlohmann::json::parse(res->body);
        auto embedding_arr = resp_json["data"][0]["embedding"];
        std::vector<float> result = embedding_arr.get<std::vector<float>>();
        std::printf("[Embedding] Response received (dim=%zu)\n", result.size());
        return result;
    } catch (const std::exception& e) {
        std::printf("[Embedding] JSON parse error: %s\n", e.what());
        return {};
    }
}

std::string LlmAdapter::generate(const std::string& system_prompt,
                                  const std::vector<ChatMessage>& messages) {
    if (api_key_.empty()) {
        std::printf("[LlmAdapter] ERROR: No API key configured\n");
        return "[LLM ERROR] No API key configured. Set DEEPSEEK_API_KEY in .env";
    }

    std::string host = base_url_;
    int port = 443;
    bool use_ssl = false;

    auto proto_pos = base_url_.find("://");
    if (proto_pos != std::string::npos) {
        std::string proto = base_url_.substr(0, proto_pos);
        use_ssl = (proto == "https");
        host = base_url_.substr(proto_pos + 3);
    }

    auto port_pos = host.rfind(':');
    if (port_pos != std::string::npos) {
        port = std::stoi(host.substr(port_pos + 1));
        host = host.substr(0, port_pos);
    }

    std::printf("[LlmAdapter] Sending request to %s/v1/chat/completions (model=%s, %zu messages, ssl=%d)\n",
                base_url_.c_str(), model_.c_str(), messages.size(), use_ssl);

    nlohmann::json body;
    body["model"] = model_;
    body["stream"] = false;

    nlohmann::json msg_arr = nlohmann::json::array();
    if (!system_prompt.empty()) {
        msg_arr.push_back({{"role", "system"}, {"content", system_prompt}});
    }
    for (const auto& m : messages) {
        msg_arr.push_back({{"role", m.role}, {"content", m.content}});
    }
    body["messages"] = msg_arr;

    std::string body_str = body.dump();

    httplib::Result res = [&]() -> httplib::Result {
        if (use_ssl) {
            httplib::SSLClient cli(host, port);
            cli.set_connection_timeout(10);
            cli.set_read_timeout(timeout_seconds_);
            cli.set_write_timeout(10);
            cli.enable_server_certificate_verification(false);

            httplib::Headers headers;
            headers.emplace("Authorization", "Bearer " + api_key_);
            headers.emplace("Content-Type", "application/json");

            return cli.Post("/v1/chat/completions", headers, body_str, "application/json");
        } else {
            httplib::Client cli(host, port);
            cli.set_connection_timeout(10);
            cli.set_read_timeout(timeout_seconds_);
            cli.set_write_timeout(10);

            httplib::Headers headers;
            headers.emplace("Authorization", "Bearer " + api_key_);
            headers.emplace("Content-Type", "application/json");

            return cli.Post("/v1/chat/completions", headers, body_str, "application/json");
        }
    }();

    if (!res) {
        auto err = httplib::to_string(res.error());
        std::printf("[LlmAdapter] HTTP request failed: %s\n", err.c_str());
        return "[LLM ERROR] Request failed: " + err;
    }

    if (res->status != 200) {
        std::printf("[LlmAdapter] HTTP %d: %s\n", res->status, res->body.c_str());
        return "[LLM ERROR] HTTP " + std::to_string(res->status) + ": " + res->body.substr(0, 200);
    }

    try {
        auto resp_json = nlohmann::json::parse(res->body);
        std::string response_text = resp_json["choices"][0]["message"]["content"].get<std::string>();
        std::printf("[LlmAdapter] Response received (%zu chars)\n", response_text.size());
        return response_text;
    } catch (const std::exception& e) {
        std::printf("[LlmAdapter] JSON parse error: %s\n", e.what());
        return "[LLM ERROR] Invalid JSON response";
    }
}

bool LlmAdapter::is_available() const {
    if (api_key_.empty()) return false;

    std::string host = base_url_;
    int port = 443;
    bool use_ssl = false;

    auto proto_pos = base_url_.find("://");
    if (proto_pos != std::string::npos) {
        std::string proto = base_url_.substr(0, proto_pos);
        use_ssl = (proto == "https");
        host = base_url_.substr(proto_pos + 3);
    }

    auto port_pos = host.rfind(':');
    if (port_pos != std::string::npos) {
        port = std::stoi(host.substr(port_pos + 1));
        host = host.substr(0, port_pos);
    }

    if (use_ssl) {
        httplib::SSLClient cli(host, port);
        cli.set_connection_timeout(5);
        cli.set_read_timeout(10);
        cli.enable_server_certificate_verification(false);
        auto res = cli.Get("/models");
        return res && (res->status == 200 || res->status == 401);
    } else {
        httplib::Client cli(host, port);
        cli.set_connection_timeout(5);
        cli.set_read_timeout(10);
        auto res = cli.Get("/models");
        return res && (res->status == 200 || res->status == 401);
    }
}

} // namespace aios

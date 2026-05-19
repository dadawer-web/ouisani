#include "aios/llm_adapter.h"

#include <httplib.h>
#include <nlohmann/json.hpp>

#include <cstdio>

namespace aios {

LlmAdapter::LlmAdapter(const std::string& base_url,
                        const std::string& model,
                        int timeout_seconds)
    : base_url_(base_url)
    , model_(model)
    , timeout_seconds_(timeout_seconds)
{}

std::string LlmAdapter::generate(const std::string& prompt) {
    std::printf("[LlmAdapter] Sending request to %s/api/generate (model=%s)...\n",
                base_url_.c_str(), model_.c_str());

    std::string host = base_url_;
    int port = 11434;

    auto colon_pos = base_url_.find("://");
    if (colon_pos != std::string::npos) {
        host = base_url_.substr(colon_pos + 3);
    }
    auto port_pos = host.rfind(':');
    if (port_pos != std::string::npos) {
        port = std::stoi(host.substr(port_pos + 1));
        host = host.substr(0, port_pos);
    }

    httplib::Client cli(host, port);
    cli.set_connection_timeout(10);
    cli.set_read_timeout(timeout_seconds_);
    cli.set_write_timeout(10);

    nlohmann::json body;
    body["model"] = model_;
    body["prompt"] = prompt;
    body["stream"] = false;

    auto res = cli.Post("/api/generate", body.dump(), "application/json");

    if (!res) {
        auto err = httplib::to_string(res.error());
        std::printf("[LlmAdapter] HTTP request failed: %s\n", err.c_str());
        return "[LLM ERROR] Request failed: " + err;
    }

    if (res->status != 200) {
        std::printf("[LlmAdapter] HTTP %d: %s\n", res->status, res->body.c_str());
        return "[LLM ERROR] HTTP " + std::to_string(res->status);
    }

    try {
        auto resp_json = nlohmann::json::parse(res->body);
        std::string response_text = resp_json.value("response", "");
        std::printf("[LlmAdapter] Response received (%zu chars)\n", response_text.size());
        return response_text;
    } catch (const nlohmann::json::parse_error& e) {
        std::printf("[LlmAdapter] JSON parse error: %s\n", e.what());
        return "[LLM ERROR] Invalid JSON response";
    }
}

bool LlmAdapter::is_available() const {
    std::string host = base_url_;
    int port = 11434;

    auto colon_pos = base_url_.find("://");
    if (colon_pos != std::string::npos) {
        host = base_url_.substr(colon_pos + 3);
    }
    auto port_pos = host.rfind(':');
    if (port_pos != std::string::npos) {
        port = std::stoi(host.substr(port_pos + 1));
        host = host.substr(0, port_pos);
    }

    httplib::Client cli(host, port);
    cli.set_connection_timeout(3);
    cli.set_read_timeout(5);

    auto res = cli.Get("/");
    return res && res->status == 200;
}

} // namespace aios

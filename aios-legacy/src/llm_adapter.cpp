#include "aios/llm_adapter.h"
#include "aios/audio_node.h"
#include "aios/trace_manager.h"

#include <httplib.h>
#include <nlohmann/json.hpp>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <thread>

namespace aios {

static const char* kPageFaultInstruction =
    "\n\n[SYSTEM DIRECTIVE - MANDATORY] If you are asked about factual knowledge or "
    "specific projects that you do not know, DO NOT apologize or guess. Instead, "
    "output exactly this string: <PAGE_FAULT: keywords> where keywords are the search "
    "terms needed. Example: <PAGE_FAULT: Rust async runtime tokio internals>. "
    "You MUST output ONLY this tag and nothing else when you lack knowledge.";

static std::string extract_page_fault_keywords(const std::string& response) {
    auto pos = response.find("<PAGE_FAULT:");
    if (pos == std::string::npos) return "";

    auto kw_start = response.find('[', pos);
    if (kw_start != std::string::npos) {
        auto kw_end = response.find(']', kw_start);
        if (kw_end != std::string::npos) {
            return response.substr(kw_start + 1, kw_end - kw_start - 1);
        }
    }

    auto content_start = pos + strlen("<PAGE_FAULT:");
    while (content_start < response.size() && response[content_start] == ' ') {
        content_start++;
    }
    auto content_end = response.find('>', content_start);
    if (content_end == std::string::npos) {
        content_end = response.size();
    }
    std::string keywords = response.substr(content_start, content_end - content_start);
    while (!keywords.empty() && keywords.back() == ' ') {
        keywords.pop_back();
    }
    return keywords;
}

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
    auto& tracer = TraceManager::instance();
    int agent_id = TraceManager::get_thread_agent_id();

    if (tracer.mode() == TraceMode::REPLAY) {
        std::string replayed = tracer.replay_event(agent_id, "LLM_INFERENCE");
        if (!replayed.empty()) {
            std::printf("[LlmAdapter] ⏪ REPLAY MODE — skipping real LLM call, returning recorded response (%zu bytes)\n",
                        replayed.size());
            return replayed;
        }
        std::printf("[LlmAdapter] ⏪ REPLAY MODE — tape miss, falling through to real inference\n");
    }

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
    std::string augmented_system = system_prompt + kPageFaultInstruction;
    msg_arr.push_back({{"role", "system"}, {"content", augmented_system}});
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

        std::string pf_keywords = extract_page_fault_keywords(response_text);
        if (!pf_keywords.empty()) {
            std::printf("[LlmAdapter] Semantic Page Fault detected! keywords='%s'\n",
                        pf_keywords.c_str());
            throw SemanticPageFaultException(pf_keywords);
        }

        if (tracer.mode() == TraceMode::RECORD) {
            tracer.record_event(agent_id, "LLM_INFERENCE", response_text);
        }

        return response_text;
    } catch (const SemanticPageFaultException&) {
        throw;
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

static std::vector<std::string> split_words(const std::string& text) {
    std::vector<std::string> words;
    std::string current;
    for (char c : text) {
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
            if (!current.empty()) {
                words.push_back(current);
                current.clear();
            }
        } else {
            current += c;
        }
    }
    if (!current.empty()) {
        words.push_back(current);
    }
    return words;
}

static const char* kVisemeTable[] = {
    "sil", "PP", "FF", "TH", "DD",
    "kk", "CH", "SS", "nn", "RR",
    "aa", "E", "I", "O", "OU",
    "LL", "MM", "PP"
};

static std::string char_to_viseme(char c) {
    if (c >= 'a' && c <= 'z') {
        return kVisemeTable[(c - 'a') % 18];
    }
    if (c >= 'A' && c <= 'Z') {
        return kVisemeTable[(c - 'A') % 18];
    }
    return "sil";
}

void LlmAdapter::stream_tts(const std::string& text,
                              std::shared_ptr<AudioNode> pcm_dev,
                              std::shared_ptr<AudioNode> viseme_dev) {
    if (text.empty()) return;

    std::printf("[LlmAdapter] stream_tts | text_len=%zu | simulating TTS pipeline\n",
                text.size());

    auto words = split_words(text);
    if (words.empty()) return;

    double time_offset = 0.0;
    const double word_duration = 0.5;
    const size_t pcm_chunk_size = 4096;

    for (size_t wi = 0; wi < words.size(); ++wi) {
        const auto& word = words[wi];

        std::vector<uint8_t> pcm_chunk(pcm_chunk_size);
        uint8_t pattern = static_cast<uint8_t>((wi * 37 + 0xA7) & 0xFF);
        for (size_t i = 0; i < pcm_chunk_size; ++i) {
            pcm_chunk[i] = static_cast<uint8_t>((pattern + i * 3) & 0xFF);
        }

        if (pcm_dev) {
            pcm_dev->write_stream(pcm_chunk, "");
        }

        if (viseme_dev) {
            for (size_t ci = 0; ci < word.size(); ++ci) {
                double viseme_time = time_offset + ci * 0.08;
                std::string viseme = char_to_viseme(word[ci]);

                nlohmann::json frame;
                frame["time"] = viseme_time;
                frame["viseme"] = viseme;
                frame["word"] = word;
                frame["duration"] = 0.08;
                frame["index"] = ci;

                viseme_dev->write_as(frame.dump(), 0);
            }
        }

        time_offset += word_duration;

        std::printf("[LlmAdapter] TTS chunk %zu/%zu | word='%s' | pcm=%zu bytes | t=%.2fs\n",
                    wi + 1, words.size(), word.c_str(), pcm_chunk_size, time_offset);

        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }

    nlohmann::json end_marker;
    end_marker["time"] = time_offset;
    end_marker["viseme"] = "sil";
    end_marker["word"] = "";
    end_marker["duration"] = 0.3;
    end_marker["index"] = -1;
    end_marker["end"] = true;

    if (viseme_dev) {
        viseme_dev->write_as(end_marker.dump(), 0);
    }

    std::printf("[LlmAdapter] stream_tts complete | %zu words | %.1fs total\n",
                words.size(), time_offset);
}

} // namespace aios

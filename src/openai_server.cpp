#include "aios/openai_server.h"
#include "aios/event_bus.h"
#include "aios/llm_router.h"

#include <arpa/inet.h>
#include <ctime>
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <nlohmann/json.hpp>
#include <string>

namespace aios {

static void send_all(int fd, const std::string& data) {
    size_t sent = 0;
    while (sent < data.size()) {
        ssize_t n = ::write(fd, data.data() + sent, data.size() - sent);
        if (n <= 0) break;
        sent += static_cast<size_t>(n);
    }
}

static std::string make_http_response(int status, const std::string& body) {
    const char* status_text = (status == 200) ? "OK"
                            : (status == 400) ? "Bad Request"
                            : (status == 404) ? "Not Found"
                            : (status == 413) ? "Payload Too Large"
                            : (status == 500) ? "Internal Server Error"
                            : "Unknown";
    std::string header = "HTTP/1.1 " + std::to_string(status) + " " + status_text + "\r\n";
    header += "Content-Type: application/json\r\n";
    header += "Access-Control-Allow-Origin: *\r\n";
    header += "Access-Control-Allow-Methods: POST, GET, OPTIONS\r\n";
    header += "Access-Control-Allow-Headers: Content-Type, Authorization\r\n";
    header += "Connection: close\r\n";
    header += "Content-Length: " + std::to_string(body.size()) + "\r\n";
    header += "\r\n";
    return header + body;
}

OpenAiServer::OpenAiServer(uint16_t port)
    : port_(port) {}

void OpenAiServer::start() {
    listen_fd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd_ < 0) {
        std::printf("[OpenAiServer] socket() failed: %s\n", std::strerror(errno));
        return;
    }

    int opt = 1;
    setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port_);
    inet_pton(AF_INET, "127.0.0.1", &addr.sin_addr);

    if (bind(listen_fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::printf("[OpenAiServer] bind() failed: %s\n", std::strerror(errno));
        close(listen_fd_);
        listen_fd_ = -1;
        return;
    }

    if (listen(listen_fd_, SOMAXCONN) < 0) {
        std::printf("[OpenAiServer] listen() failed: %s\n", std::strerror(errno));
        close(listen_fd_);
        listen_fd_ = -1;
        return;
    }

    running_.store(true);
    std::printf("[OpenAiServer] 🏰 OpenAI-compatible gateway listening on 127.0.0.1:%d\n", port_);

    accept_loop();
}

void OpenAiServer::shutdown() {
    running_.store(false);
    if (listen_fd_ >= 0) {
        ::shutdown(listen_fd_, SHUT_RDWR);
        close(listen_fd_);
        listen_fd_ = -1;
    }
    std::printf("[OpenAiServer] Shutdown complete\n");
}

void OpenAiServer::accept_loop() {
    while (running_.load()) {
        sockaddr_in client_addr{};
        socklen_t addr_len = sizeof(client_addr);
        int client_fd = accept(listen_fd_, reinterpret_cast<sockaddr*>(&client_addr), &addr_len);
        if (client_fd < 0) {
            if (errno == EINVAL || !running_.load()) break;
            if (errno == EINTR) continue;
            std::printf("[OpenAiServer] accept() error: %s\n", std::strerror(errno));
            continue;
        }

        int nodelay = 1;
        setsockopt(client_fd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

        struct timeval tv{};
        tv.tv_sec = 30;
        tv.tv_usec = 0;
        setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        setsockopt(client_fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));

        handle_client(client_fd);
        close(client_fd);
    }
}

void OpenAiServer::handle_client(int fd) {
    std::string raw;
    char buf[8192];
    static constexpr size_t MAX_REQUEST_SIZE = 1 * 1024 * 1024;

    while (true) {
        ssize_t n = ::read(fd, buf, sizeof(buf));
        if (n <= 0) break;
        raw.append(buf, static_cast<size_t>(n));

        if (raw.size() > MAX_REQUEST_SIZE) {
            std::string resp = make_http_response(413, "{\"error\":\"Payload too large\"}");
            send_all(fd, resp);
            return;
        }

        if (raw.find("\r\n\r\n") != std::string::npos) {
            auto header_end = raw.find("\r\n\r\n");
            std::string headers = raw.substr(0, header_end);

            size_t content_length = 0;
            std::string lower_headers = headers;
            std::transform(lower_headers.begin(), lower_headers.end(),
                           lower_headers.begin(), ::tolower);
            auto cl_pos = lower_headers.find("content-length:");
            if (cl_pos != std::string::npos) {
                auto val_start = cl_pos + 15;
                while (val_start < lower_headers.size() &&
                       (lower_headers[val_start] == ' ' || lower_headers[val_start] == '\t')) {
                    val_start++;
                }
                std::string cl_str;
                while (val_start < lower_headers.size() &&
                       std::isdigit(static_cast<unsigned char>(lower_headers[val_start]))) {
                    cl_str += lower_headers[val_start++];
                }
                if (!cl_str.empty()) {
                    try { content_length = std::stoul(cl_str); } catch (...) {}
                }
            }

            size_t body_start = header_end + 4;
            size_t body_received = raw.size() - body_start;

            if (content_length > 0 && body_received < content_length) {
                continue;
            }

            std::string body;
            if (body_start < raw.size()) {
                body = raw.substr(body_start, content_length > 0 ? content_length : std::string::npos);
            }

            std::string request_line = raw.substr(0, raw.find("\r\n"));
            std::string method;
            std::string path;
            {
                auto sp1 = request_line.find(' ');
                if (sp1 != std::string::npos) {
                    method = request_line.substr(0, sp1);
                    auto sp2 = request_line.find(' ', sp1 + 1);
                    if (sp2 != std::string::npos) {
                        path = request_line.substr(sp1 + 1, sp2 - sp1 - 1);
                    }
                }
            }

            std::printf("[OpenAiServer] %s %s | body=%zu bytes\n",
                        method.c_str(), path.c_str(), body.size());

            if (method == "OPTIONS") {
                std::string resp = make_http_response(200, "");
                send_all(fd, resp);
                return;
            }

            if (method != "POST") {
                std::string resp = make_http_response(400,
                    "{\"error\":{\"message\":\"Only POST is supported\",\"type\":\"invalid_request_error\"}}");
                send_all(fd, resp);
                return;
            }

            if (path == "/v1/chat/completions" || path == "/v1/chat/completions/") {
                std::string result = handle_chat_completion(body);
                std::string resp = make_http_response(200, result);
                send_all(fd, resp);
                return;
            }

            if (path == "/v1/models") {
                nlohmann::json models;
                models["object"] = "list";
                models["data"] = nlohmann::json::array({
                    {{"id", "ouisani-microkernel"}, {"object", "model"}, {"owned_by", "aios"}},
                    {{"id", "ouisani-fast"}, {"object", "model"}, {"owned_by", "aios"}},
                    {{"id", "ouisani-deep"}, {"object", "model"}, {"owned_by", "aios"}}
                });
                std::string resp = make_http_response(200, models.dump());
                send_all(fd, resp);
                return;
            }

            std::string resp = make_http_response(404,
                "{\"error\":{\"message\":\"Unknown endpoint: " + path + "\",\"type\":\"invalid_request_error\"}}");
            send_all(fd, resp);
            return;
        }
    }
}

std::string OpenAiServer::handle_chat_completion(const std::string& body) {
    nlohmann::json req;
    try {
        req = nlohmann::json::parse(body);
    } catch (const nlohmann::json::parse_error& e) {
        std::printf("[OpenAiServer] JSON parse error: %s\n", e.what());
        nlohmann::json err;
        err["error"]["message"] = "Invalid JSON in request body";
        err["error"]["type"] = "invalid_request_error";
        return err.dump();
    }

    std::string user_content;
    if (req.contains("messages") && req["messages"].is_array()) {
        for (auto it = req["messages"].rbegin(); it != req["messages"].rend(); ++it) {
            if (it->value("role", "") == "user") {
                user_content = it->value("content", "");
                break;
            }
        }
    }

    if (user_content.empty()) {
        user_content = req.value("prompt", req.value("content", ""));
    }

    if (user_content.empty()) {
        nlohmann::json err;
        err["error"]["message"] = "No user message found in request";
        err["error"]["type"] = "invalid_request_error";
        return err.dump();
    }

    std::printf("[OpenAiServer] 📨 Intercepted OpenAI request | content=\"%s\"\n",
                user_content.size() > 80 ? (user_content.substr(0, 80) + "...").c_str() : user_content.c_str());

    EventBus::instance().publish(EventType::LLM_REQ_START, "OpenAiServer",
        "OpenAI-compatible request intercepted: " +
        (user_content.size() > 40 ? user_content.substr(0, 40) + "..." : user_content));

    std::string kernel_result;
    try {
        if (LlmRouter::instance().has_providers()) {
            kernel_result = LlmRouter::instance().route_and_execute(user_content);
        } else {
            kernel_result = "[AIOS Kernel] No LLM providers registered. Echo: " + user_content;
        }
    } catch (const std::exception& e) {
        std::printf("[OpenAiServer] LLM execution exception: %s\n", e.what());
        kernel_result = "[AIOS Kernel Error] " + std::string(e.what());
    }

    EventBus::instance().publish(EventType::LLM_REQ_END, "OpenAiServer",
        "OpenAI-compatible response sent: " + std::to_string(kernel_result.size()) + " bytes");

    std::string model_name = req.value("model", "ouisani-microkernel");

    nlohmann::json response;
    response["id"] = "chatcmpl-ouisani-kernel";
    response["object"] = "chat.completion";
    response["created"] = static_cast<int64_t>(std::time(nullptr));
    response["model"] = model_name;

    nlohmann::json choice;
    choice["index"] = 0;
    choice["message"]["role"] = "assistant";
    choice["message"]["content"] = kernel_result;
    choice["finish_reason"] = "stop";
    response["choices"] = nlohmann::json::array({choice});

    int prompt_tokens = static_cast<int>(user_content.size()) / 4;
    int completion_tokens = static_cast<int>(kernel_result.size()) / 4;
    response["usage"] = {
        {"prompt_tokens", prompt_tokens},
        {"completion_tokens", completion_tokens},
        {"total_tokens", prompt_tokens + completion_tokens}
    };

    std::printf("[OpenAiServer] ✅ Response sent | model=%s | %d tokens\n",
                model_name.c_str(), prompt_tokens + completion_tokens);

    return response.dump();
}

} // namespace aios

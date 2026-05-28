#include "aios/mcp_server.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <future>
#include <nlohmann/json.hpp>
#include <sstream>
#include <string>
#include <vector>

namespace aios {

McpServer::McpServer(uint16_t port,
                     SubmitTaskFn submit_fn,
                     SubmitLlmFn submit_llm_fn)
    : port_(port)
    , submit_fn_(std::move(submit_fn))
    , submit_llm_fn_(std::move(submit_llm_fn))
{}

McpServer::~McpServer() {
    shutdown();
}

void McpServer::start() {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) return;

    listen_fd_ = socket(AF_INET, SOCK_STREAM, 0);
    if (listen_fd_ < 0) {
        std::printf("[MCP] socket() failed: %s\n", std::strerror(errno));
        running_.store(false);
        return;
    }

    int opt = 1;
    setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port_);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (bind(listen_fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::printf("[MCP] bind() failed on port %u: %s\n", port_, std::strerror(errno));
        close(listen_fd_); listen_fd_ = -1;
        running_.store(false);
        return;
    }

    if (listen(listen_fd_, 16) < 0) {
        std::printf("[MCP] listen() failed: %s\n", std::strerror(errno));
        close(listen_fd_); listen_fd_ = -1;
        running_.store(false);
        return;
    }

    accept_thread_ = std::thread(&McpServer::accept_loop, this);
    std::printf("[MCP] Server listening on 127.0.0.1:%u (JSON-RPC 2.0)\n", port_);
}

void McpServer::shutdown() {
    if (!running_.load()) return;
    running_.store(false);

    if (listen_fd_ >= 0) {
        ::shutdown(listen_fd_, SHUT_RDWR);
        close(listen_fd_); listen_fd_ = -1;
    }

    if (accept_thread_.joinable()) {
        accept_thread_.join();
    }

    std::printf("[MCP] Server shutdown complete\n");
}

void McpServer::accept_loop() {
    while (running_.load()) {
        sockaddr_in client_addr{};
        socklen_t addr_len = sizeof(client_addr);
        int client_fd = accept(listen_fd_,
                               reinterpret_cast<sockaddr*>(&client_addr),
                               &addr_len);
        if (client_fd < 0) {
            if (errno == EINVAL || errno == EBADF) break;
            if (errno == EINTR) continue;
            std::printf("[MCP] accept() failed: %s\n", std::strerror(errno));
            continue;
        }

        int nodelay = 1;
        setsockopt(client_fd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

        std::thread(&McpServer::handle_client, this, client_fd).detach();
    }
}

void McpServer::handle_client(int client_fd) {
    std::printf("[MCP] Client connected (fd=%d)\n", client_fd);

    char buf[8192];
    std::string recv_buf;

    while (running_.load()) {
        ssize_t n = read(client_fd, buf, sizeof(buf));
        if (n <= 0) break;

        recv_buf.append(buf, static_cast<size_t>(n));

        size_t pos = 0;
        while (true) {
            size_t newline = recv_buf.find('\n', pos);
            if (newline == std::string::npos) break;

            std::string line = recv_buf.substr(pos, newline - pos);
            pos = newline + 1;

            if (line.empty()) continue;

            std::string response = process_request(line);

            response.push_back('\n');
            ssize_t written = ::write(client_fd, response.data(), response.size());
            if (written < 0) {
                std::printf("[MCP] write() failed: %s\n", std::strerror(errno));
                break;
            }
        }

        recv_buf.erase(0, pos);
    }

    close(client_fd);
    std::printf("[MCP] Client disconnected (fd=%d)\n", client_fd);
}

std::string McpServer::process_request(const std::string& json_line) {
    nlohmann::json req;
    try {
        req = nlohmann::json::parse(json_line);
    } catch (const nlohmann::json::parse_error& e) {
        nlohmann::json err;
        err["jsonrpc"] = "2.0";
        err["id"] = nullptr;
        err["error"] = {{"code", -32700}, {"message", "Parse error"}};
        return err.dump();
    }

    int id = req.contains("id") && !req["id"].is_null()
             ? req.value("id", 0) : 0;
    std::string method = req.value("method", "");

    std::printf("[MCP] Request: method=%s | id=%d\n", method.c_str(), id);

    try {
        if (method == "initialize") {
            return handle_initialize(id);
        } else if (method == "initialized") {
            return "";
        } else if (method == "tools/list") {
            return handle_tools_list(id);
        } else if (method == "tools/call") {
            std::string params_str = req.contains("params") ? req["params"].dump() : "{}";
            return handle_tools_call(id, params_str);
        } else {
            nlohmann::json err;
            err["jsonrpc"] = "2.0";
            err["id"] = id;
            err["error"] = {{"code", -32601}, {"message", "Method not found: " + method}};
            return err.dump();
        }
    } catch (const std::exception& e) {
        nlohmann::json err;
        err["jsonrpc"] = "2.0";
        err["id"] = id;
        err["error"] = {{"code", -32603}, {"message", std::string("Internal error: ") + e.what()}};
        return err.dump();
    }
}

std::string McpServer::handle_initialize(int id) {
    nlohmann::json result;
    result["serverInfo"] = {{"name", "ouisani-mcp-kernel"}, {"version", "1.0"}};
    result["capabilities"] = {{"tools", nlohmann::json::object()}};
    result["protocolVersion"] = "2024-11-05";

    nlohmann::json resp;
    resp["jsonrpc"] = "2.0";
    resp["id"] = id;
    resp["result"] = result;

    std::printf("[MCP] initialize -> ouisani-mcp-kernel v1.0\n");
    return resp.dump();
}

std::string McpServer::handle_tools_list(int id) {
    nlohmann::json compile_tool;
    compile_tool["name"] = "compile_and_execute_c";
    compile_tool["description"] = "Compiles C code to WASM and executes it in a secure sandbox. Returns stdout output.";
    compile_tool["inputSchema"] = {
        {"type", "object"},
        {"properties", {{"code", {{"type", "string"}, {"description", "Complete C source code to compile and run"}}}}},
        {"required", nlohmann::json::array({"code"})}
    };

    nlohmann::json semantic_tool;
    semantic_tool["name"] = "semantic_vfs";
    semantic_tool["description"] = "Routes natural language intent through the semantic VFS. The kernel uses LLM to translate intent into VFS operations (READ/WRITE memory, etc).";
    semantic_tool["inputSchema"] = {
        {"type", "object"},
        {"properties", {{"intent", {{"type", "string"}, {"description", "Natural language intent (e.g. 'read agent 101 memory')"}}}}},
        {"required", nlohmann::json::array({"intent"})}
    };

    nlohmann::json result;
    result["tools"] = nlohmann::json::array({compile_tool, semantic_tool});

    nlohmann::json resp;
    resp["jsonrpc"] = "2.0";
    resp["id"] = id;
    resp["result"] = result;

    std::printf("[MCP] tools/list -> %zu tools\n", result["tools"].size());
    return resp.dump();
}

std::string McpServer::handle_tools_call(int id, const std::string& params_str) {
    nlohmann::json params;
    try {
        params = nlohmann::json::parse(params_str);
    } catch (...) {
        params = nlohmann::json::object();
    }
    std::string tool_name = params.value("name", "");
    auto arguments = params.value("arguments", nlohmann::json::object());

    std::printf("[MCP] tools/call | tool=%s | args=%s\n",
                tool_name.c_str(), arguments.dump().substr(0, 200).c_str());

    if (tool_name == "compile_and_execute_c") {
        std::string code = arguments.value("code", "");
        if (code.empty()) {
            nlohmann::json err_resp;
            err_resp["jsonrpc"] = "2.0";
            err_resp["id"] = id;
            err_resp["error"] = {{"code", -32602}, {"message", "Missing required argument: code"}};
            return err_resp.dump();
        }

        if (!submit_fn_) {
            nlohmann::json err_resp;
            err_resp["jsonrpc"] = "2.0";
            err_resp["id"] = id;
            err_resp["error"] = {{"code", -32603}, {"message", "No task scheduler bound"}};
            return err_resp.dump();
        }

        nlohmann::json payload_json;
        payload_json["code"] = code;
        payload_json["func"] = "_start";

        auto promise_ptr = std::make_shared<std::promise<std::string>>();
        auto future = promise_ptr->get_future();

        auto task = std::make_shared<AgentTask>(
            0, 0, TaskStatus::READY,
            payload_json.dump(), TaskType::VFS_CALL,
            "COMPILE_AND_EXECUTE", "", -1
        );

        task->set_response_callback([promise_ptr](int /*fd*/, const std::string& response) {
            promise_ptr->set_value(response);
        });

        submit_fn_(task);

        std::string compile_result;
        try {
            compile_result = future.get();
        } catch (const std::exception& e) {
            nlohmann::json err_resp;
            err_resp["jsonrpc"] = "2.0";
            err_resp["id"] = id;
            err_resp["error"] = {{"code", -32603}, {"message", std::string("Execution failed: ") + e.what()}};
            return err_resp.dump();
        }

        std::string output_text;
        try {
            auto parsed = nlohmann::json::parse(compile_result);
            if (parsed.contains("data")) {
                auto data = parsed["data"];
                if (data.is_string()) {
                    auto inner = nlohmann::json::parse(data.get<std::string>(), nullptr, false);
                    if (!inner.is_discarded() && inner.is_object()) {
                        output_text = inner.value("stdout", inner.value("output", ""));
                    } else {
                        output_text = data.get<std::string>();
                    }
                } else if (data.is_object()) {
                    output_text = data.value("stdout", data.value("output", ""));
                }
            }
            if (output_text.empty()) {
                output_text = parsed.value("message", compile_result);
            }
        } catch (...) {
            output_text = compile_result;
        }

        nlohmann::json content_item;
        content_item["type"] = "text";
        content_item["text"] = output_text;

        nlohmann::json result;
        result["content"] = nlohmann::json::array({content_item});

        nlohmann::json resp;
        resp["jsonrpc"] = "2.0";
        resp["id"] = id;
        resp["result"] = result;

        std::printf("[MCP] compile_and_execute_c -> output=%zu bytes\n", output_text.size());
        return resp.dump();

    } else if (tool_name == "semantic_vfs") {
        std::string intent = arguments.value("intent", "");
        if (intent.empty()) {
            nlohmann::json err_resp;
            err_resp["jsonrpc"] = "2.0";
            err_resp["id"] = id;
            err_resp["error"] = {{"code", -32602}, {"message", "Missing required argument: intent"}};
            return err_resp.dump();
        }

        if (!submit_fn_) {
            nlohmann::json err_resp;
            err_resp["jsonrpc"] = "2.0";
            err_resp["id"] = id;
            err_resp["error"] = {{"code", -32603}, {"message", "No task scheduler bound"}};
            return err_resp.dump();
        }

        auto promise_ptr = std::make_shared<std::promise<std::string>>();
        auto future = promise_ptr->get_future();

        auto task = std::make_shared<AgentTask>(
            0, 0, TaskStatus::READY,
            intent, TaskType::VFS_CALL, "WRITE", "/dev/semantic", -1
        );

        task->set_response_callback([promise_ptr](int /*fd*/, const std::string& response) {
            promise_ptr->set_value(response);
        });

        submit_fn_(task);

        std::string vfs_result;
        try {
            vfs_result = future.get();
        } catch (const std::exception& e) {
            nlohmann::json err_resp;
            err_resp["jsonrpc"] = "2.0";
            err_resp["id"] = id;
            err_resp["error"] = {{"code", -32603}, {"message", std::string("Semantic VFS failed: ") + e.what()}};
            return err_resp.dump();
        }

        std::string semantic_output;
        try {
            auto parsed = nlohmann::json::parse(vfs_result);
            if (parsed.contains("data")) {
                auto data = parsed["data"];
                if (data.is_string()) {
                    auto inner = nlohmann::json::parse(data.get<std::string>(), nullptr, false);
                    if (!inner.is_discarded() && inner.is_object()) {
                        semantic_output = inner.value("content", inner.value("message", ""));
                    } else {
                        semantic_output = data.get<std::string>();
                    }
                } else if (data.is_object()) {
                    semantic_output = data.value("content", data.value("message", ""));
                }
            }
            if (semantic_output.empty()) {
                semantic_output = parsed.value("message", vfs_result);
            }
        } catch (...) {
            semantic_output = vfs_result;
        }

        nlohmann::json content_item;
        content_item["type"] = "text";
        content_item["text"] = semantic_output;

        nlohmann::json result;
        result["content"] = nlohmann::json::array({content_item});

        nlohmann::json resp;
        resp["jsonrpc"] = "2.0";
        resp["id"] = id;
        resp["result"] = result;

        std::printf("[MCP] semantic_vfs -> output=%zu bytes\n", semantic_output.size());
        return resp.dump();

    } else {
        nlohmann::json err_resp;
        err_resp["jsonrpc"] = "2.0";
        err_resp["id"] = id;
        err_resp["error"] = {{"code", -32601}, {"message", "Unknown tool: " + tool_name}};
        return err_resp.dump();
    }
}

} // namespace aios

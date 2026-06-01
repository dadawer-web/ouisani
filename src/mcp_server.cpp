#include "aios/mcp_server.h"
#include "aios/cgroup_manager.h"
#include "aios/host_source_node.h"
#include "aios/token_zram.h"
#include "aios/process_manager.h"
#include "aios/vfs_manager.h"
#include "aios/bpf_manager.h"

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
    std::printf("[MCP] Server listening on 127.0.0.1:%u (JSON-RPC 2.0 / MCP 2024-11-05)\n", port_);
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

    {
        std::lock_guard<std::mutex> lock(sse_mutex_);
        for (auto& client : sse_clients_) {
            close(client.fd);
        }
        sse_clients_.clear();
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

            if (!response.empty()) {
                response.push_back('\n');
                ssize_t written = ::write(client_fd, response.data(), response.size());
                if (written < 0) {
                    std::printf("[MCP] write() failed: %s\n", std::strerror(errno));
                    break;
                }
            }
        }

        recv_buf.erase(0, pos);
    }

    close(client_fd);
    std::printf("[MCP] Client disconnected (fd=%d)\n", client_fd);
}

nlohmann::json McpServer::make_response(int id, const nlohmann::json& result) {
    nlohmann::json resp;
    resp["jsonrpc"] = "2.0";
    resp["id"] = id;
    resp["result"] = result;
    return resp;
}

nlohmann::json McpServer::make_error(int id, int code, const std::string& message) {
    nlohmann::json resp;
    resp["jsonrpc"] = "2.0";
    resp["id"] = id;
    resp["error"] = {{"code", code}, {"message", message}};
    return resp;
}

std::string McpServer::handle_message(const std::string& json_body) {
    return process_request(json_body);
}

std::string McpServer::process_request(const std::string& json_line) {
    nlohmann::json req;
    try {
        req = nlohmann::json::parse(json_line);
    } catch (const nlohmann::json::parse_error& e) {
        return make_error(0, -32700, "Parse error: " + std::string(e.what())).dump();
    }

    if (!req.contains("jsonrpc") || req["jsonrpc"] != "2.0") {
        int id = req.contains("id") && !req["id"].is_null() ? req.value("id", 0) : 0;
        return make_error(id, -32600, "Invalid Request: missing or wrong jsonrpc version").dump();
    }

    int id = req.contains("id") && !req["id"].is_null()
             ? req.value("id", 0) : 0;
    std::string method = req.value("method", "");

    if (method.empty()) {
        return make_error(id, -32600, "Invalid Request: missing method").dump();
    }

    std::printf("[MCP] ◄ Request: method=%s | id=%d\n", method.c_str(), id);

    try {
        std::string params_str = req.contains("params") ? req["params"].dump() : "{}";

        if (method == "initialize") {
            auto resp = handle_initialize(id);
            std::printf("[MCP] ► Response: initialize → capabilities sent\n");
            return resp;
        } else if (method == "initialized") {
            return handle_initialized(id);
        } else if (method == "ping") {
            return handle_ping(id);
        } else if (method == "tools/list") {
            return handle_tools_list(id);
        } else if (method == "tools/call") {
            return handle_tools_call(id, params_str);
        } else if (method == "resources/list") {
            return handle_resources_list(id);
        } else if (method == "resources/read") {
            return handle_resources_read(id, params_str);
        } else if (method == "resources/templates/list") {
            nlohmann::json result;
            result["resourceTemplates"] = nlohmann::json::array();
            return make_response(id, result).dump();
        } else if (method == "prompts/list") {
            return handle_prompts_list(id);
        } else if (method == "prompts/get") {
            return handle_prompts_get(id, params_str);
        } else if (method == "notifications/initialized") {
            return "";
        } else if (method == "logging/setLevel") {
            nlohmann::json result;
            result["status"] = "ok";
            return make_response(id, result).dump();
        } else if (method == "completion/complete") {
            nlohmann::json result;
            result["completion"] = {{"values", nlohmann::json::array()}, {"total", 0}, {"hasMore", false}};
            return make_response(id, result).dump();
        } else {
            return make_error(id, -32601, "Method not found: " + method).dump();
        }
    } catch (const std::exception& e) {
        return make_error(id, -32603, "Internal error: " + std::string(e.what())).dump();
    }
}

std::string McpServer::handle_initialize(int id) {
    nlohmann::json capabilities;

    nlohmann::json tools_cap;
    tools_cap["listChanged"] = true;
    capabilities["tools"] = tools_cap;

    nlohmann::json resources_cap;
    resources_cap["subscribe"] = true;
    resources_cap["listChanged"] = true;
    capabilities["resources"] = resources_cap;

    nlohmann::json prompts_cap;
    prompts_cap["listChanged"] = true;
    capabilities["prompts"] = prompts_cap;

    capabilities["logging"] = nlohmann::json::object();

    nlohmann::json result;
    result["serverInfo"] = {{"name", "ouisani-mcp-kernel"}, {"version", "1.0"}};
    result["capabilities"] = capabilities;
    result["protocolVersion"] = "2024-11-05";
    result["instructions"] = "AIOS MCP Kernel — Access system resources, tools, and prompts via Model Context Protocol. Use resources/list to discover system files, tools/list for available tools, and prompts/list for prompt templates.";

    return make_response(id, result).dump();
}

std::string McpServer::handle_initialized(int id) {
    broadcast_sse_event("notifications/initialized", "{}");
    return "";
}

std::string McpServer::handle_ping(int id) {
    return make_response(id, nlohmann::json::object()).dump();
}

std::string McpServer::handle_tools_list(int id) {
    nlohmann::json tools = nlohmann::json::array();

    nlohmann::json compile_tool;
    compile_tool["name"] = "execute_c_code_in_sandbox";
    compile_tool["description"] = "Compiles C code to WASM and executes it in the AIOS secure WasmEdge sandbox. The code is compiled with WASI SDK and runs in an isolated environment. Returns stdout output.";
    compile_tool["inputSchema"] = {
        {"type", "object"},
        {"properties", {
            {"code", {{"type", "string"}, {"description", "Complete C source code to compile and execute in the WASM sandbox"}}},
            {"function_name", {{"type", "string"}, {"description", "Entry function name (default: _start)", "default", "_start"}}}
        }},
        {"required", nlohmann::json::array({"code"})}
    };
    tools.push_back(compile_tool);

    nlohmann::json spawn_tool;
    spawn_tool["name"] = "agent_spawn";
    spawn_tool["description"] = "Spawns a new AIOS Agent process. Optionally creates an isolated VFS mount namespace (CLONE_NEWNS) and attaches the agent to a Cgroup for resource limits. Returns the new agent's ID and namespace info.";
    spawn_tool["inputSchema"] = {
        {"type", "object"},
        {"properties", {
            {"role", {{"type", "string"}, {"description", "Role description for the new agent"}}},
            {"clone_newns", {{"type", "boolean"}, {"description", "Whether to create an isolated VFS mount namespace (default: false)", "default", false}}},
            {"cgroup_name", {{"type", "string"}, {"description", "Cgroup to attach the agent to (optional)"}}},
            {"stdin_path", {{"type", "string"}, {"description", "Path to stdin pipe (optional)"}}},
            {"stdout_path", {{"type", "string"}, {"description", "Path to stdout pipe (optional)"}}}
        }},
        {"required", nlohmann::json::array({"role"})}
    };
    tools.push_back(spawn_tool);

    nlohmann::json semantic_tool;
    semantic_tool["name"] = "semantic_vfs";
    semantic_tool["description"] = "Routes natural language intent through the semantic VFS. The kernel uses LLM to translate intent into VFS operations (READ/WRITE memory, etc).";
    semantic_tool["inputSchema"] = {
        {"type", "object"},
        {"properties", {{"intent", {{"type", "string"}, {"description", "Natural language intent (e.g. 'read agent 101 memory')"}}}}},
        {"required", nlohmann::json::array({"intent"})}
    };
    tools.push_back(semantic_tool);

    nlohmann::json cgroup_tool;
    cgroup_tool["name"] = "cgroup_inspect";
    cgroup_tool["description"] = "Inspect Cgroup resource limits and usage for a given cgroup name. Returns token limits, CPU quota, OOM status, and attached agents.";
    cgroup_tool["inputSchema"] = {
        {"type", "object"},
        {"properties", {{"cgroup_name", {{"type", "string"}, {"description", "Cgroup path (e.g. /agent_my_agent)"}}}}},
        {"required", nlohmann::json::array({"cgroup_name"})}
    };
    tools.push_back(cgroup_tool);

    nlohmann::json zram_tool;
    zram_tool["name"] = "zram_compress";
    zram_tool["description"] = "Trigger Token ZRAM compression for an agent's memory context. Compresses cold data (older 50% of messages) into a dense <ZRAM_COMPRESSED_BLOCK>, freeing token space while preserving key information.";
    zram_tool["inputSchema"] = {
        {"type", "object"},
        {"properties", {{"agent_id", {{"type", "integer"}, {"description", "Agent ID to compress memory for"}}}}},
        {"required", nlohmann::json::array({"agent_id"})}
    };
    tools.push_back(zram_tool);

    nlohmann::json vfs_read_tool;
    vfs_read_tool["name"] = "vfs_read";
    vfs_read_tool["description"] = "Read data from a VFS node by path. Supports /proc/*, /dev/*, /containers/*, and any mounted VFS node.";
    vfs_read_tool["inputSchema"] = {
        {"type", "object"},
        {"properties", {{"path", {{"type", "string"}, {"description", "VFS path to read (e.g. /proc/agents, /dev/vec_mem_101)"}}}}},
        {"required", nlohmann::json::array({"path"})}
    };
    tools.push_back(vfs_read_tool);

    nlohmann::json compile_kernel_tool;
    compile_kernel_tool["name"] = "compile_kernel";
    compile_kernel_tool["description"] = "Compiles the AIOS kernel source code from /usr/src/aios. Runs cmake configure and build, producing a new aios_core binary. Requires Ring 0 (kernel) privilege. Can optionally trigger kexec to hot-swap the running kernel with the newly compiled one.";
    compile_kernel_tool["inputSchema"] = {
        {"type", "object"},
        {"properties", {
            {"kexec", {{"type", "boolean"}, {"description", "Whether to trigger kexec (hot-swap) after successful compilation (default: false)", "default", false}}},
            {"build_type", {{"type", "string"}, {"description", "CMake build type: Release or Debug (default: Release)", "default", "Release"}}}
        }},
        {"required", nlohmann::json::array()}
    };
    tools.push_back(compile_kernel_tool);

    nlohmann::json result;
    result["tools"] = tools;

    std::printf("[MCP] tools/list → %zu tools\n", tools.size());
    return make_response(id, result).dump();
}

std::string McpServer::handle_tools_call(int id, const std::string& params_str) {
    nlohmann::json params;
    try {
        params = nlohmann::json::parse(params_str);
    } catch (const nlohmann::json::parse_error&) {
        return make_error(id, -32602, "Invalid params JSON").dump();
    }
    std::string tool_name = params.value("name", "");
    auto arguments = params.value("arguments", nlohmann::json::object());

    std::printf("[MCP] tools/call | tool=%s | args=%s\n",
                tool_name.c_str(), arguments.dump().substr(0, 200).c_str());

    if (tool_name == "execute_c_code_in_sandbox" || tool_name == "compile_and_execute_c") {
        std::string code = arguments.value("code", "");
        if (code.empty()) {
            return make_error(id, -32602, "Missing required argument: code").dump();
        }

        if (!submit_fn_) {
            return make_error(id, -32603, "No task scheduler bound").dump();
        }

        std::string func = arguments.value("function_name", "_start");

        nlohmann::json payload_json;
        payload_json["code"] = code;
        payload_json["func"] = func;

        auto promise_ptr = std::make_shared<std::promise<std::string>>();
        auto future = promise_ptr->get_future();

        auto task = std::make_shared<AgentTask>(
            0, 0, TaskStatus::READY,
            payload_json.dump(), TaskType::VFS_CALL,
            "COMPILE_AND_EXECUTE", "", -1
        );

        task->set_response_callback([promise_ptr](int, const std::string& response) {
            promise_ptr->set_value(response);
        });

        submit_fn_(task);

        std::string compile_result;
        try {
            compile_result = future.get();
        } catch (const std::exception& e) {
            return make_error(id, -32603, "Sandbox execution failed: " + std::string(e.what())).dump();
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

        return make_response(id, result).dump();

    } else if (tool_name == "agent_spawn") {
        std::string role = arguments.value("role", "");
        if (role.empty()) {
            return make_error(id, -32602, "Missing required argument: role").dump();
        }

        bool clone_newns = arguments.value("clone_newns", false);
        std::string cgroup_name = arguments.value("cgroup_name", "");
        std::string stdin_path = arguments.value("stdin_path", "");
        std::string stdout_path = arguments.value("stdout_path", "");

        int clone_flags = 0;
        if (clone_newns) {
            clone_flags = 0x00020000;
        }

        auto& pm = ProcessManager::instance();
        int new_pid = pm.spawn(0, role, stdin_path, stdout_path, clone_flags);

        if (new_pid <= 0) {
            return make_error(id, -32603, "Failed to spawn agent").dump();
        }

        if (!cgroup_name.empty()) {
            CgroupManager::instance().attach_to_cgroup(new_pid, cgroup_name);
        }

        auto pcb = pm.get_pcb(new_pid);
        nlohmann::json spawn_info;
        spawn_info["agent_id"] = new_pid;
        spawn_info["role"] = role;
        spawn_info["state"] = pcb ? agent_state_str(pcb->state) : "RUNNING";
        spawn_info["mount_namespace"] = clone_newns;
        spawn_info["root_dir"] = pcb ? pcb->root_dir : "/";
        spawn_info["cgroup"] = cgroup_name.empty() ? "none" : cgroup_name;

        nlohmann::json content_item;
        content_item["type"] = "text";
        content_item["text"] = spawn_info.dump(2);

        nlohmann::json result;
        result["content"] = nlohmann::json::array({content_item});

        return make_response(id, result).dump();

    } else if (tool_name == "semantic_vfs") {
        std::string intent = arguments.value("intent", "");
        if (intent.empty()) {
            return make_error(id, -32602, "Missing required argument: intent").dump();
        }

        if (!submit_fn_) {
            return make_error(id, -32603, "No task scheduler bound").dump();
        }

        auto promise_ptr = std::make_shared<std::promise<std::string>>();
        auto future = promise_ptr->get_future();

        auto task = std::make_shared<AgentTask>(
            0, 0, TaskStatus::READY,
            intent, TaskType::VFS_CALL, "WRITE", "/dev/semantic", -1
        );

        task->set_response_callback([promise_ptr](int, const std::string& response) {
            promise_ptr->set_value(response);
        });

        submit_fn_(task);

        std::string vfs_result;
        try {
            vfs_result = future.get();
        } catch (const std::exception& e) {
            return make_error(id, -32603, "Semantic VFS failed: " + std::string(e.what())).dump();
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

        return make_response(id, result).dump();

    } else if (tool_name == "cgroup_inspect") {
        std::string cgroup_name = arguments.value("cgroup_name", "/");
        if (cgroup_name.empty()) cgroup_name = "/";

        std::string info_str = CgroupManager::instance().get_cgroup_info(cgroup_name);

        nlohmann::json content_item;
        content_item["type"] = "text";
        content_item["text"] = info_str;

        nlohmann::json result;
        result["content"] = nlohmann::json::array({content_item});

        return make_response(id, result).dump();

    } else if (tool_name == "zram_compress") {
        int agent_id = arguments.value("agent_id", -1);
        if (agent_id < 0) {
            return make_error(id, -32602, "Missing required argument: agent_id").dump();
        }

        TokenZram::instance().compress_agent_memory_by_id(agent_id);

        nlohmann::json content_item;
        content_item["type"] = "text";
        content_item["text"] = "ZRAM compression triggered for agent " + std::to_string(agent_id);

        nlohmann::json result;
        result["content"] = nlohmann::json::array({content_item});

        return make_response(id, result).dump();

    } else if (tool_name == "vfs_read") {
        std::string path = arguments.value("path", "");
        if (path.empty()) {
            return make_error(id, -32602, "Missing required argument: path").dump();
        }

        auto& vfs = VfsManager::instance();
        auto node = vfs.resolve_path(path, 0);

        if (!node) {
            return make_error(id, -32602, "VFS node not found: " + path).dump();
        }

        std::string content;
        if (node->node_type() == VfsNodeType::DIRECTORY) {
            content = vfs.list_dir(path);
        } else {
            content = node->read();
            if (content.empty()) {
                content = node->execute("");
            }
        }

        nlohmann::json content_item;
        content_item["type"] = "text";
        content_item["text"] = content;

        nlohmann::json result;
        result["content"] = nlohmann::json::array({content_item});

        return make_response(id, result).dump();

    } else if (tool_name == "compile_kernel") {
        std::string project_root = std::filesystem::current_path();
        std::string build_result = aios::compile_kernel(project_root);

        bool do_kexec = arguments.value("kexec", false);

        nlohmann::json compile_result;
        try {
            compile_result = nlohmann::json::parse(build_result);
        } catch (...) {
            compile_result["raw_output"] = build_result;
        }

        if (do_kexec && compile_result.value("status", "") == "ok") {
            std::string new_binary = compile_result.value("binary_path", "");
            if (!new_binary.empty()) {
                compile_result["kexec_triggered"] = true;
                compile_result["kexec_message"] = "Kernel hot-swap will be triggered after this response";
            }
        }

        nlohmann::json content_item;
        content_item["type"] = "text";
        content_item["text"] = compile_result.dump(2);

        nlohmann::json result;
        result["content"] = nlohmann::json::array({content_item});

        return make_response(id, result).dump();

    } else {
        return make_error(id, -32601, "Unknown tool: " + tool_name).dump();
    }
}

std::string McpServer::handle_resources_list(int id) {
    nlohmann::json resources = nlohmann::json::array();

    nlohmann::json res_version;
    res_version["uri"] = "vfs:///proc/version";
    res_version["name"] = "Kernel Version";
    res_version["description"] = "AIOS kernel version and build info";
    res_version["mimeType"] = "text/plain";
    resources.push_back(res_version);

    nlohmann::json res_agents;
    res_agents["uri"] = "vfs:///proc/agents";
    res_agents["name"] = "Agent List";
    res_agents["description"] = "List of all running agents and their states";
    res_agents["mimeType"] = "application/json";
    resources.push_back(res_agents);

    nlohmann::json res_cgroup;
    res_cgroup["uri"] = "vfs:///sys/fs/cgroup";
    res_cgroup["name"] = "Cgroup Hierarchy";
    res_cgroup["description"] = "Cgroup tree with resource limits and usage";
    res_cgroup["mimeType"] = "text/plain";
    resources.push_back(res_cgroup);

    nlohmann::json res_bpf;
    res_bpf["uri"] = "vfs:///sys/bpf/hooks";
    res_bpf["name"] = "BPF Hooks";
    res_bpf["description"] = "Active eBPF/WASM filter hooks";
    res_bpf["mimeType"] = "application/json";
    resources.push_back(res_bpf);

    auto& vfs = VfsManager::instance();

    auto dev_node = vfs.resolve_path("/dev", 0);
    if (dev_node && dev_node->node_type() == VfsNodeType::DIRECTORY) {
        auto dev_dir = std::static_pointer_cast<DirectoryNode>(dev_node);
        auto dev_children = dev_dir->list_children();
        for (const auto& child_name : dev_children) {
            auto child = dev_dir->get_child(child_name);
            if (!child) continue;

            std::string vfs_path = "/dev/" + child_name;
            std::string uri = "vfs://" + vfs_path;

            if (child->node_type() == VfsNodeType::DIRECTORY) {
                auto sub_dir = std::static_pointer_cast<DirectoryNode>(child);
                auto sub_children = sub_dir->list_children();
                for (const auto& sub_name : sub_children) {
                    auto sub_child = sub_dir->get_child(sub_name);
                    if (!sub_child) continue;

                    std::string sub_path = vfs_path + "/" + sub_name;
                    std::string sub_uri = "vfs://" + sub_path;
                    std::string sub_type = node_type_str(sub_child->node_type());

                    nlohmann::json res;
                    res["uri"] = sub_uri;
                    res["name"] = sub_path;
                    res["description"] = std::string("VFS device node [") + sub_type + "] at " + sub_path;
                    res["mimeType"] = (sub_type == "VEC" || sub_type == "GRAPH") ? "application/json" : "text/plain";
                    resources.push_back(res);
                }
            } else {
                std::string node_type = node_type_str(child->node_type());

                nlohmann::json res;
                res["uri"] = uri;
                res["name"] = vfs_path;
                res["description"] = std::string("VFS device node [") + node_type + "] at " + vfs_path;
                res["mimeType"] = (node_type == "VEC" || node_type == "GRAPH") ? "application/json" : "text/plain";
                resources.push_back(res);
            }
        }
    }

    auto containers_node = vfs.resolve_path("/containers", 0);
    if (containers_node && containers_node->node_type() == VfsNodeType::DIRECTORY) {
        auto containers_dir = std::static_pointer_cast<DirectoryNode>(containers_node);
        auto container_children = containers_dir->list_children();
        for (const auto& cname : container_children) {
            std::string container_uri = "vfs:///containers/" + cname;

            nlohmann::json res;
            res["uri"] = container_uri;
            res["name"] = "/containers/" + cname;
            res["description"] = "Container namespace for agent " + cname;
            res["mimeType"] = "text/plain";
            resources.push_back(res);
        }
    }

    nlohmann::json result;
    result["resources"] = resources;

    std::printf("[MCP] resources/list → %zu resources (dynamic VFS scan)\n", resources.size());
    return make_response(id, result).dump();
}

std::string McpServer::handle_resources_read(int id, const std::string& params_str) {
    nlohmann::json params;
    try {
        params = nlohmann::json::parse(params_str);
    } catch (...) {
        return make_error(id, -32602, "Invalid params").dump();
    }

    std::string uri = params.value("uri", "");
    if (uri.empty()) {
        return make_error(id, -32602, "Missing required argument: uri").dump();
    }

    std::string vfs_path;
    if (uri.substr(0, 6) == "vfs://") {
        vfs_path = uri.substr(5);
        if (!vfs_path.empty() && vfs_path[0] != '/') {
            vfs_path = "/" + vfs_path;
        }
    } else if (uri[0] == '/') {
        vfs_path = uri;
    } else {
        return make_error(id, -32602, "Unsupported URI scheme: " + uri).dump();
    }

    std::string content;
    std::string mime = "text/plain";

    if (vfs_path == "/proc/version") {
        content = "AIOS Kernel v1.0 (ouisani) | MCP Protocol 2024-11-05 | Build: " __DATE__ " " __TIME__;
    } else if (vfs_path == "/proc/agents") {
        auto& pm = ProcessManager::instance();
        nlohmann::json agents = nlohmann::json::array();
        for (int aid = 1; aid <= 100; ++aid) {
            auto pcb = pm.get_pcb(aid);
            if (pcb) {
                nlohmann::json agent;
                agent["id"] = aid;
                agent["state"] = agent_state_str(pcb->state);
                agent["role"] = pcb->role;
                agents.push_back(agent);
            }
        }
        content = agents.dump(2);
        mime = "application/json";
    } else if (vfs_path == "/sys/fs/cgroup") {
        content = CgroupManager::instance().dump_tree();
    } else if (vfs_path == "/sys/bpf/hooks") {
        auto hooks = BpfManager::instance().list_hooks();
        nlohmann::json arr = nlohmann::json::array();
        for (const auto& h : hooks) {
            nlohmann::json entry;
            entry["hook_point"] = h.hook_point;
            entry["wasm_path"] = h.wasm_path;
            entry["active"] = h.active;
            entry["invoke_count"] = h.invoke_count;
            arr.push_back(entry);
        }
        content = arr.dump(2);
        mime = "application/json";
    } else {
        auto& vfs = VfsManager::instance();
        auto node = vfs.resolve_path(vfs_path, 0);
        if (!node) {
            return make_error(id, -32602, "VFS node not found: " + vfs_path).dump();
        }

        if (node->node_type() == VfsNodeType::DIRECTORY) {
            content = vfs.list_dir(vfs_path);
        } else {
            content = node->read();
            if (content.empty()) {
                content = node->execute("");
            }
            if (content.empty()) {
                content = "[VFS] Node at " + vfs_path + " returned empty data";
            }
        }

        std::string ntype = node_type_str(node->node_type());
        if (ntype == "VEC" || ntype == "GRAPH") {
            mime = "application/json";
        }
    }

    nlohmann::json content_item;
    content_item["uri"] = uri;
    content_item["mimeType"] = mime;
    content_item["text"] = content;

    nlohmann::json result;
    result["contents"] = nlohmann::json::array({content_item});

    std::printf("[MCP] resources/read → uri=%s | %zu bytes\n", uri.c_str(), content.size());
    return make_response(id, result).dump();
}

std::string McpServer::handle_prompts_list(int id) {
    nlohmann::json prompts = nlohmann::json::array();

    nlohmann::json p1;
    p1["name"] = "system_status";
    p1["description"] = "Get a comprehensive overview of the AIOS kernel state including agents, cgroups, BPF hooks, and VFS tree";
    p1["arguments"] = nlohmann::json::array();
    prompts.push_back(p1);

    nlohmann::json p2;
    p2["name"] = "debug_agent";
    p2["description"] = "Debug an agent by inspecting its memory (TokenMMU), cgroup limits, VFS namespace, and OOM status";
    nlohmann::json arg_agent;
    arg_agent["name"] = "agent_id";
    arg_agent["description"] = "The agent ID to debug";
    arg_agent["required"] = true;
    p2["arguments"] = nlohmann::json::array({arg_agent});
    prompts.push_back(p2);

    nlohmann::json p3;
    p3["name"] = "security_audit";
    p3["description"] = "Run a security audit checking BPF hooks (os_default_guard), AppArmor policies, namespace isolation, and cgroup DoS vectors";
    p3["arguments"] = nlohmann::json::array();
    prompts.push_back(p3);

    nlohmann::json p4;
    p4["name"] = "spawn_system_daemon";
    p4["description"] = "Spawn a long-running system daemon agent with isolated VFS namespace and cgroup resource limits. Configures the daemon to run in a containerized environment with appropriate security constraints.";
    nlohmann::json arg_role;
    arg_role["name"] = "daemon_role";
    arg_role["description"] = "Role description for the daemon (e.g. 'log_collector', 'health_monitor')";
    arg_role["required"] = true;
    nlohmann::json arg_tpm;
    arg_tpm["name"] = "max_tokens_per_minute";
    arg_tpm["description"] = "Token rate limit for the daemon's cgroup (default: 10000)";
    arg_tpm["required"] = false;
    nlohmann::json arg_cpu;
    arg_cpu["name"] = "cpu_quota";
    arg_cpu["description"] = "CPU quota percentage for the daemon (default: 30.0)";
    arg_cpu["required"] = false;
    p4["arguments"] = nlohmann::json::array({arg_role, arg_tpm, arg_cpu});
    prompts.push_back(p4);

    nlohmann::json p5;
    p5["name"] = "memory_pressure_test";
    p5["description"] = "Simulate memory pressure on an agent to test ZRAM compression and Watermark soft page fault handling";
    nlohmann::json arg_target;
    arg_target["name"] = "agent_id";
    arg_target["description"] = "Target agent ID to pressure test";
    arg_target["required"] = true;
    nlohmann::json arg_tokens;
    arg_tokens["name"] = "inject_tokens";
    arg_tokens["description"] = "Number of tokens of noise to inject (default: 3000)";
    arg_tokens["required"] = false;
    p5["arguments"] = nlohmann::json::array({arg_target, arg_tokens});
    prompts.push_back(p5);

    nlohmann::json p6;
    p6["name"] = "container_deploy";
    p6["description"] = "Deploy an agent as a containerized service with full isolation: CLONE_NEWNS VFS namespace, Cgroup resource limits, and BPF security hooks";
    nlohmann::json arg_name;
    arg_name["name"] = "container_name";
    arg_name["description"] = "Name for the container";
    arg_name["required"] = true;
    nlohmann::json arg_image;
    arg_image["name"] = "base_image";
    arg_image["description"] = "Base image to use (default: aios/base_c_wasm)";
    arg_image["required"] = false;
    p6["arguments"] = nlohmann::json::array({arg_name, arg_image});
    prompts.push_back(p6);

    nlohmann::json result;
    result["prompts"] = prompts;

    std::printf("[MCP] prompts/list → %zu prompts\n", prompts.size());
    return make_response(id, result).dump();
}

std::string McpServer::handle_prompts_get(int id, const std::string& params_str) {
    nlohmann::json params;
    try {
        params = nlohmann::json::parse(params_str);
    } catch (const nlohmann::json::parse_error&) {
        return make_error(id, -32602, "Invalid params JSON").dump();
    }

    std::string prompt_name = params.value("name", "");
    auto arguments = params.value("arguments", nlohmann::json::object());

    nlohmann::json messages = nlohmann::json::array();

    if (prompt_name == "system_status") {
        nlohmann::json sys_msg;
        sys_msg["role"] = "system";
        sys_msg["content"] = "You are an AIOS kernel diagnostic assistant. Analyze the following system state and provide a comprehensive status report with any warnings or recommendations. Use the MCP tools available to you (cgroup_inspect, vfs_read) to gather real data.";
        messages.push_back(sys_msg);

        nlohmann::json user_msg;
        user_msg["role"] = "user";
        user_msg["content"] = "Please analyze the current AIOS kernel state: (1) Read /proc/agents to list all running agents, (2) Read /sys/fs/cgroup to check resource limits, (3) Read /sys/bpf/hooks to verify security hooks, (4) Provide a summary with any warnings.";
        messages.push_back(user_msg);

    } else if (prompt_name == "debug_agent") {
        int agent_id = arguments.value("agent_id", -1);
        if (agent_id < 0) {
            return make_error(id, -32602, "Missing required argument: agent_id").dump();
        }

        nlohmann::json sys_msg;
        sys_msg["role"] = "system";
        sys_msg["content"] = "You are an AIOS kernel debugger. Use MCP tools to inspect the specified agent's state. Check process state, memory context (TokenMMU), cgroup limits, VFS namespace isolation, and any OOM_BLOCKED status. Provide actionable debugging insights.";
        messages.push_back(sys_msg);

        nlohmann::json user_msg;
        user_msg["role"] = "user";
        user_msg["content"] = "Debug agent " + std::to_string(agent_id) + ": (1) Use cgroup_inspect to check its resource limits, (2) Use vfs_read on /proc/agents to check its state, (3) Use zram_compress if memory is high, (4) Provide a diagnosis.";
        messages.push_back(user_msg);

    } else if (prompt_name == "security_audit") {
        nlohmann::json sys_msg;
        sys_msg["role"] = "system";
        sys_msg["content"] = "You are an AIOS security auditor. Review the kernel's security posture using MCP tools and identify any vulnerabilities or misconfigurations. Check BPF hooks, namespace isolation, cgroup limits, and AppArmor policies.";
        messages.push_back(sys_msg);

        nlohmann::json user_msg;
        user_msg["role"] = "user";
        user_msg["content"] = "Perform a comprehensive security audit: (1) Read /sys/bpf/hooks — verify os_default_guard is active, (2) Read /proc/agents — check for unauthorized agents, (3) Read /sys/fs/cgroup — verify no agent has unlimited resources, (4) Use vfs_read on /containers/ to check namespace isolation, (5) Report findings with severity levels.";
        messages.push_back(user_msg);

    } else if (prompt_name == "spawn_system_daemon") {
        std::string daemon_role = arguments.value("daemon_role", "");
        if (daemon_role.empty()) {
            return make_error(id, -32602, "Missing required argument: daemon_role").dump();
        }

        int max_tpm = arguments.value("max_tokens_per_minute", 10000);
        double cpu_quota = arguments.value("cpu_quota", 30.0);

        nlohmann::json sys_msg;
        sys_msg["role"] = "system";
        sys_msg["content"] = "You are an AIOS system administrator. Your task is to spawn a daemon agent with proper isolation and resource limits. Use the MCP tools to create the daemon step by step.";
        messages.push_back(sys_msg);

        nlohmann::json user_msg;
        user_msg["role"] = "user";
        user_msg["content"] = "Spawn a system daemon with the following configuration:\n"
            "- Role: " + daemon_role + "\n"
            "- Isolation: CLONE_NEWNS (isolated VFS namespace)\n"
            "- Cgroup: /daemon_" + daemon_role + " (max_tokens_per_minute=" + std::to_string(max_tpm) + ", cpu_quota=" + std::to_string(static_cast<int>(cpu_quota)) + "%)\n\n"
            "Steps:\n"
            "1. Use agent_spawn with role='" + daemon_role + "' and clone_newns=true\n"
            "2. Create a cgroup for the daemon with the specified limits\n"
            "3. Attach the daemon to its cgroup\n"
            "4. Verify the daemon is running in its isolated namespace\n"
            "5. Report the daemon's agent_id and configuration";
        messages.push_back(user_msg);

    } else if (prompt_name == "memory_pressure_test") {
        int agent_id = arguments.value("agent_id", -1);
        if (agent_id < 0) {
            return make_error(id, -32602, "Missing required argument: agent_id").dump();
        }

        int inject_tokens = arguments.value("inject_tokens", 3000);

        nlohmann::json sys_msg;
        sys_msg["role"] = "system";
        sys_msg["content"] = "You are an AIOS memory management tester. Your task is to simulate memory pressure on a target agent to verify that the ZRAM compression and Watermark soft page fault mechanisms work correctly.";
        messages.push_back(sys_msg);

        nlohmann::json user_msg;
        user_msg["role"] = "user";
        user_msg["content"] = "Test memory pressure on agent " + std::to_string(agent_id) + ":\n"
            "1. Check current token usage via cgroup_inspect\n"
            "2. Inject ~" + std::to_string(inject_tokens) + " tokens of test data\n"
            "3. Verify Watermark soft page fault was triggered\n"
            "4. Use zram_compress to manually trigger compression if needed\n"
            "5. Verify the agent is still running (not OOM_KILLED)\n"
            "6. Report before/after token counts";
        messages.push_back(user_msg);

    } else if (prompt_name == "container_deploy") {
        std::string container_name = arguments.value("container_name", "");
        if (container_name.empty()) {
            return make_error(id, -32602, "Missing required argument: container_name").dump();
        }

        std::string base_image = arguments.value("base_image", "aios/base_c_wasm");

        nlohmann::json sys_msg;
        sys_msg["role"] = "system";
        sys_msg["content"] = "You are an AIOS container deployment orchestrator. Deploy a containerized agent service with full isolation using the available MCP tools.";
        messages.push_back(sys_msg);

        nlohmann::json user_msg;
        user_msg["role"] = "user";
        user_msg["content"] = "Deploy container '" + container_name + "' with base image '" + base_image + "':\n"
            "1. Create a cgroup /container_" + container_name + " with appropriate limits\n"
            "2. Spawn an agent with CLONE_NEWNS for VFS isolation\n"
            "3. Attach the agent to the cgroup\n"
            "4. Verify the container's VFS namespace is isolated\n"
            "5. Verify BPF security hooks are active\n"
            "6. Report deployment status and agent_id";
        messages.push_back(user_msg);

    } else {
        return make_error(id, -32602, "Unknown prompt: " + prompt_name).dump();
    }

    nlohmann::json result;
    result["description"] = "MCP prompt: " + prompt_name;
    result["messages"] = messages;

    return make_response(id, result).dump();
}

void McpServer::register_sse_client(int fd, const std::string& client_id) {
    std::lock_guard<std::mutex> lock(sse_mutex_);
    McpSseClient client;
    client.fd = fd;
    client.client_id = client_id;
    sse_clients_.push_back(std::move(client));
    std::printf("[MCP] SSE client registered: %s (fd=%d, total=%zu)\n",
                client_id.c_str(), fd, sse_clients_.size());
}

void McpServer::unregister_sse_client(const std::string& client_id) {
    std::lock_guard<std::mutex> lock(sse_mutex_);
    for (auto it = sse_clients_.begin(); it != sse_clients_.end(); ++it) {
        if (it->client_id == client_id) {
            close(it->fd);
            sse_clients_.erase(it);
            std::printf("[MCP] SSE client unregistered: %s (remaining=%zu)\n",
                        client_id.c_str(), sse_clients_.size());
            return;
        }
    }
}

void McpServer::broadcast_sse_event(const std::string& event_type, const std::string& data) {
    std::lock_guard<std::mutex> lock(sse_mutex_);
    if (sse_clients_.empty()) return;

    int eid = next_event_id_.fetch_add(1);
    std::string sse_msg = "id: " + std::to_string(eid) + "\n"
                          "event: " + event_type + "\n"
                          "data: " + data + "\n\n";

    for (auto it = sse_clients_.begin(); it != sse_clients_.end(); ) {
        ssize_t written = ::write(it->fd, sse_msg.data(), sse_msg.size());
        if (written < 0) {
            std::printf("[MCP] SSE write failed for client %s, removing\n", it->client_id.c_str());
            close(it->fd);
            it = sse_clients_.erase(it);
        } else {
            ++it;
        }
    }
}

size_t McpServer::sse_client_count() const {
    std::lock_guard<std::mutex> lock(sse_mutex_);
    return sse_clients_.size();
}

} // namespace aios

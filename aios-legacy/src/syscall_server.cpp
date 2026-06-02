#include "aios/syscall_server.h"
#include "aios/agent_registry.h"
#include "aios/event_bus.h"
#include "aios/instruction_decoder.h"
#include "aios/module_manager.h"
#include "aios/process_manager.h"
#include "aios/thread_pool.h"
#include "aios/vector_node.h"
#include "aios/graph_node.h"
#include "aios/audio_node.h"
#include "aios/security_guard.h"
#include "aios/kernel_logger.h"
#include "aios/bpf_manager.h"
#include "aios/trace_manager.h"
#include "aios/host_source_node.h"
#include "aios/kexec_manager.h"
#include "aios/vfs_manager.h"
#include "aios/vfs_node.h"
#include "aios/wasm_node.h"
#include "aios/webhook_node.h"
#include "aios/cgroup_manager.h"
#include "aios/mcp_server.h"
#include "aios/display_node.h"

#include <algorithm>
#include <arpa/inet.h>
#include <filesystem>
#include <fstream>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <nlohmann/json.hpp>
#include <sstream>
#include <string>

namespace aios {

static void notify_eventfd(int efd) {
    uint64_t val = 1;
    ssize_t ret = ::write(efd, &val, sizeof(val));
    (void)ret;
}

class VfsSyscallHandler : public ISyscallHandler {
public:
    void handle(int fd, int caller_id, const nlohmann::json& req, SyscallServer* server) override {
        auto& registry = AgentRegistry::instance();
        std::string action = req.value("action", "");
        std::string vfs_path;
        if (req.contains("path") && req["path"].is_string()) {
            vfs_path = req["path"].get<std::string>();
        }
        std::string payload;
        if (req.contains("payload") && req["payload"].is_string()) {
            payload = req["payload"].get<std::string>();
        }
        int agent_id = caller_id;
        if (req.contains("agent_id") && req["agent_id"].is_number()) {
            agent_id = req["agent_id"].get<int>();
        }

        std::string agent_root = VfsManager::instance().get_agent_root(caller_id);

        if (action == "READ" || action == "WRITE" || action == "TREE") {
            if (vfs_path.find("/dev/mem/") == 0) {
                int target_id = -1;
                try {
                    target_id = std::stoi(vfs_path.substr(9));
                } catch(...) {}

                auto caller_level = registry.get_level(caller_id);

                if (target_id != -1 && target_id != caller_id
                    && caller_level != PrivilegeLevel::RING_0) {
                    std::printf("[Kernel Security] 拦截! Agent %d 试图越权访问 Agent %d 的内存空间 (%s)!\n",
                                caller_id, target_id, vfs_path.c_str());
                    nlohmann::json err;
                    err["status"] = "error";
                    err["message"] = "[Segfault] Permission denied: Cross-agent VFS access forbidden by Kernel";
                    server->enqueue_response(fd, err.dump() + "\n");
                    return;
                }
            }
        }

        if (action.empty()) {
            server->enqueue_response(fd, "{\"status\":\"error\",\"message\":\"VFS_CALL requires 'action'\"}\n");
            return;
        }

        if (action != "COMPILE_AND_EXECUTE" && action != "PIPE_EXECUTE" && action != "CREATE_PIPE"
            && action != "BPF_LOAD" && action != "BPF_UNLOAD" && action != "BPF_LIST"
            && action != "AUDIO_STATUS" && action != "DEBUG_GRAPH" && action != "GRAPH_QUERY"
            && vfs_path.empty()) {
            server->enqueue_response(fd, "{\"status\":\"error\",\"message\":\"VFS_CALL requires 'path' for this action\"}\n");
            return;
        }

        if (action == "LIST") {
            auto& vfs = VfsManager::instance();
            std::string listing = vfs.list_dir(vfs_path, agent_root);
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["data"] = listing;
            server->enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "TREE") {
            auto& vfs = VfsManager::instance();
            std::string tree_str = vfs.tree(vfs_path, 0, agent_root);
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["data"] = tree_str;
            server->enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "SEARCH") {
            auto& vfs = VfsManager::instance();
            auto node = vfs.resolve_path(vfs_path, caller_id, agent_root);
            if (!node) {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "VFS path not found: " + vfs_path;
                server->enqueue_response(fd, err.dump() + "\n");
                return;
            }
            auto vec_node = std::dynamic_pointer_cast<aios::VectorNode>(node);
            if (!vec_node) {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "VFS node is not a VectorNode: " + vfs_path;
                server->enqueue_response(fd, err.dump() + "\n");
                return;
            }
            int top_k = req.value("top_k", 3);
            std::string result = vec_node->search(payload, top_k);
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["results"] = nlohmann::json::parse(result, nullptr, false);
            server->enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "GRAPH_QUERY") {
            auto& vfs = VfsManager::instance();
            auto node = vfs.resolve_path(vfs_path, 0);
            if (!node) {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "VFS path not found: " + vfs_path;
                server->enqueue_response(fd, err.dump() + "\n");
                return;
            }
            auto graph_node = std::dynamic_pointer_cast<aios::GraphNode>(node);
            if (!graph_node) {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "VFS node is not a GraphNode: " + vfs_path;
                server->enqueue_response(fd, err.dump() + "\n");
                return;
            }
            int depth = req.value("depth", 2);
            std::string entity = payload;
            if (req.contains("entity") && req["entity"].is_string()) {
                entity = req["entity"].get<std::string>();
            }
            std::string result = graph_node->query_subgraph(entity, depth);
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["result"] = nlohmann::json::parse(result, nullptr, false);
            server->enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "DEBUG_GRAPH") {
            auto& vfs = VfsManager::instance();
            auto node = vfs.resolve_path("/dev/graph0", 0);
            if (!node || node->node_type() != VfsNodeType::GRAPH) {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "GraphNode not found at /dev/graph0";
                server->enqueue_response(fd, err.dump() + "\n");
                return;
            }
            auto graph_node = std::dynamic_pointer_cast<aios::GraphNode>(node);
            std::string graph_json = graph_node->read();
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["graph"] = nlohmann::json::parse(graph_json, nullptr, false);
            server->enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "AUDIO_STATUS") {
            auto& vfs = VfsManager::instance();
            nlohmann::json audio_info;
            auto pcm_node = vfs.resolve_path("/dev/audio/pcm", 0);
            auto vis_node = vfs.resolve_path("/dev/audio/visemes", 0);
            if (pcm_node && pcm_node->node_type() == VfsNodeType::AUDIO) {
                auto pcm = std::dynamic_pointer_cast<aios::AudioNode>(pcm_node);
                audio_info["pcm"]["available"] = pcm->pcm_available();
                audio_info["pcm"]["total_written"] = pcm->total_pcm_written();
                audio_info["pcm"]["total_read"] = pcm->total_pcm_read();
            }
            if (vis_node && vis_node->node_type() == VfsNodeType::AUDIO) {
                auto vis = std::dynamic_pointer_cast<aios::AudioNode>(vis_node);
                audio_info["visemes"]["available"] = vis->viseme_available();
                audio_info["visemes"]["total_written"] = vis->total_visemes_written();
                audio_info["visemes"]["total_read"] = vis->total_visemes_read();
            }
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["audio"] = audio_info;
            server->enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "BPF_LOAD") {
            std::string hook_point = req.value("hook_point", "");
            std::string bpf_wasm_path = req.value("wasm_path", "");
            std::string export_func = req.value("export_func", "bpf_filter");

            if (hook_point.empty() || bpf_wasm_path.empty()) {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "BPF_LOAD requires 'hook_point' and 'wasm_path'";
                server->enqueue_response(fd, err.dump() + "\n");
                return;
            }

            auto& bpf = BpfManager::instance();
            bool ok = bpf.load_bpf_program(hook_point, bpf_wasm_path, export_func);
            nlohmann::json resp;
            resp["status"] = ok ? "ok" : "error";
            resp["hook_point"] = hook_point;
            resp["wasm_path"] = bpf_wasm_path;
            server->enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "BPF_UNLOAD") {
            std::string hook_point = req.value("hook_point", "");
            auto& bpf = BpfManager::instance();
            bool ok = bpf.unload_bpf_program(hook_point);
            nlohmann::json resp;
            resp["status"] = ok ? "ok" : "error";
            resp["hook_point"] = hook_point;
            server->enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "BPF_LIST") {
            auto& bpf = BpfManager::instance();
            auto hooks = bpf.list_hooks();
            nlohmann::json hooks_arr = nlohmann::json::array();
            for (const auto& h : hooks) {
                nlohmann::json entry;
                entry["hook_point"] = h.hook_point;
                entry["wasm_path"] = h.wasm_path;
                entry["export_func"] = h.export_func;
                entry["active"] = h.active;
                entry["invoke_count"] = h.invoke_count;
                entry["drop_count"] = h.drop_count;
                hooks_arr.push_back(entry);
            }
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["hooks"] = hooks_arr;
            resp["total_invokes"] = bpf.total_invokes();
            resp["total_drops"] = bpf.total_drops();
            server->enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "CREATE_PIPE") {
            if (vfs_path.empty()) {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "CREATE_PIPE requires 'path' (e.g. /tmp/pipes/pipe_A_B)";
                server->enqueue_response(fd, err.dump() + "\n");
                return;
            }
            auto& vfs = VfsManager::instance();
            auto existing = vfs.resolve_path(vfs_path, caller_id, agent_root);
            if (existing) {
                nlohmann::json resp;
                resp["status"] = "ok";
                resp["message"] = "Pipe already exists: " + vfs_path;
                resp["path"] = vfs_path;
                server->enqueue_response(fd, resp.dump() + "\n");
                return;
            }
            std::string dir_path;
            std::string name;
            auto last_slash = vfs_path.rfind('/');
            if (last_slash != std::string::npos && last_slash > 0) {
                dir_path = vfs_path.substr(0, last_slash);
                name = vfs_path.substr(last_slash + 1);
            }
            if (dir_path.empty() || name.empty()) {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "CREATE_PIPE: invalid path format: " + vfs_path;
                server->enqueue_response(fd, err.dump() + "\n");
                return;
            }
            auto pipe_node = std::make_shared<aios::PipeNode>(vfs_path);
            bool ok = vfs.mount(dir_path, name, pipe_node);
            if (ok) {
                std::printf("[VFS] CREATE_PIPE | path=%s | mounted by agent %d\n",
                            vfs_path.c_str(), caller_id);
                nlohmann::json resp;
                resp["status"] = "ok";
                resp["message"] = "Pipe created: " + vfs_path;
                resp["path"] = vfs_path;
                server->enqueue_response(fd, resp.dump() + "\n");
            } else {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "CREATE_PIPE failed: could not mount at " + vfs_path;
                server->enqueue_response(fd, err.dump() + "\n");
            }
            return;
        }

        if (action == "READ" && vfs_path == "/proc/events") {
            std::string events = aios::EventBus::instance().dump_events();
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["events"] = nlohmann::json::parse(events, nullptr, false);
            server->enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "READ") {
            auto& tracer = aios::TraceManager::instance();
            if (tracer.mode() == aios::TraceMode::REPLAY) {
                std::string replayed = tracer.replay_event(caller_id, "VFS_READ");
                if (!replayed.empty()) {
                    std::printf("[VFS_READ] ⏪ REPLAY — returning recorded VFS_READ for agent=%d path=%s\n",
                                caller_id, vfs_path.c_str());
                    nlohmann::json resp;
                    resp["status"] = "ok";
                    resp["data"] = nlohmann::json::parse(replayed, nullptr, false);
                    resp["replayed"] = true;
                    server->enqueue_response(fd, resp.dump() + "\n");
                    return;
                }
            }
        }

        std::shared_ptr<AgentTask> task;

        if (action == "COMPILE_ONLY") {
            if (vfs_path.empty()) {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "COMPILE_ONLY requires 'path' (target .wasm save path)";
                server->enqueue_response(fd, err.dump() + "\n");
                return;
            }
            task = std::make_shared<AgentTask>(
                agent_id, 0, TaskStatus::READY,
                payload, TaskType::VFS_CALL, "COMPILE_ONLY", vfs_path, fd
            );
        } else if (action == "EXECUTE_MODULE") {
            if (vfs_path.empty()) {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "EXECUTE_MODULE requires 'path' (.wasm file to execute)";
                server->enqueue_response(fd, err.dump() + "\n");
                return;
            }
            task = std::make_shared<AgentTask>(
                agent_id, 0, TaskStatus::READY,
                payload, TaskType::VFS_CALL, "EXECUTE_MODULE", vfs_path, fd
            );
        } else {
            task = std::make_shared<AgentTask>(
                agent_id, 0, TaskStatus::READY,
                payload, TaskType::VFS_CALL, "", payload, fd
            );
            task->tool_name = action;
            task->tool_code = vfs_path;
        }

        if (task) {
            task->root_dir = agent_root;
        }

        if (task && server->submit_fn()) {
            server->submit_fn()(std::move(task));
        }
    }
};

class LlmSyscallHandler : public ISyscallHandler {
public:
    void handle(int fd, int caller_id, const nlohmann::json& req, SyscallServer* server) override {
        int priority = 0;
        if (req.contains("priority") && req["priority"].is_number()) {
            priority = req["priority"].get<int>();
        }
        std::string payload;
        if (req.contains("payload") && req["payload"].is_string()) {
            payload = req["payload"].get<std::string>();
        } else if (req.contains("data") && req["data"].is_string()) {
            payload = req["data"].get<std::string>();
        }

        auto task = std::make_shared<AgentTask>(
            caller_id, priority, TaskStatus::READY,
            payload, TaskType::LLM_INFERENCE, "", "", fd
        );

        task->root_dir = VfsManager::instance().get_agent_root(caller_id);

        if (req.contains("stdin_path") && req["stdin_path"].is_string()) {
            task->stdin_path = req["stdin_path"].get<std::string>();
        }
        if (req.contains("stdout_path") && req["stdout_path"].is_string()) {
            task->stdout_path = req["stdout_path"].get<std::string>();
        }

        if (task->stdin_path.empty() || task->stdout_path.empty()) {
            auto pcb = ProcessManager::instance().get_pcb(caller_id);
            if (pcb) {
                if (task->stdin_path.empty() && !pcb->stdin_path.empty()) {
                    task->stdin_path = pcb->stdin_path;
                }
                if (task->stdout_path.empty() && !pcb->stdout_path.empty()) {
                    task->stdout_path = pcb->stdout_path;
                }
            }
        }

        task->set_response_callback([server](int cb_fd, const std::string& res) {
            server->enqueue_response(cb_fd, res);
        });

        std::printf("[Reactor] LLM_INFERENCE | agent=%d | priority=%d | payload=%zu bytes",
                    caller_id, priority, payload.size());
        if (!task->stdin_path.empty() || !task->stdout_path.empty()) {
            std::printf(" | stdin=%s stdout=%s",
                        task->stdin_path.c_str(), task->stdout_path.c_str());
        }
        std::printf("\n");

        if (server->submit_llm_fn()) {
            server->submit_llm_fn()(std::move(task));
        } else if (server->submit_fn()) {
            server->submit_fn()(std::move(task));
        }
    }
};

SyscallServer::SyscallServer(SubmitTaskFn submit_fn,
                             CancelTaskFn cancel_fn,
                             PingHeartbeatFn ping_fn,
                             const std::string& host,
                             uint16_t port)
    : submit_fn_(std::move(submit_fn))
    , cancel_fn_(std::move(cancel_fn))
    , ping_fn_(std::move(ping_fn))
    , host_(host)
    , port_(port)
    , decode_pool_(std::make_unique<ThreadPool>(2))
{
    register_handler("VFS_CALL", std::make_shared<VfsSyscallHandler>());
    register_handler("LLM_INFERENCE", std::make_shared<LlmSyscallHandler>());
}

SyscallServer::~SyscallServer() {
    shutdown();
}

void SyscallServer::set_submit_llm_fn(SubmitLlmFn fn) {
    submit_llm_fn_ = std::move(fn);
}

void SyscallServer::set_mcp_server(McpServer* mcp) {
    mcp_server_ = mcp;
    std::printf("[Reactor] MCP Server pointer set for HTTP transport\n");
}

void SyscallServer::register_handler(const std::string& name, std::shared_ptr<ISyscallHandler> handler) {
    handlers_[name] = std::move(handler);
    std::printf("[Reactor] Registered syscall handler: %s\n", name.c_str());
}

void SyscallServer::start() {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) {
        return;
    }
    decode_pool_->start();
    io_thread_ = std::thread(&SyscallServer::io_loop, this);
    std::printf("[Reactor] I/O thread started\n");

    webhook_http_ = std::make_unique<httplib::Server>();

    webhook_http_->Post("/webhook/trigger", [](const httplib::Request& req, httplib::Response& res) {
        std::string body = req.body;
        if (body.empty()) {
            res.status = 400;
            res.set_content("{\"status\":\"error\",\"message\":\"Empty payload\"}", "application/json");
            return;
        }

        auto& vfs = VfsManager::instance();
        auto node = vfs.resolve_path("/dev/irq/webhook0", 0);
        if (!node) {
            res.status = 500;
            res.set_content("{\"status\":\"error\",\"message\":\"/dev/irq/webhook0 not found\"}", "application/json");
            return;
        }

        auto webhook = std::dynamic_pointer_cast<WebhookNode>(node);
        if (!webhook) {
            res.status = 500;
            res.set_content("{\"status\":\"error\",\"message\":\"Node is not a WebhookNode\"}", "application/json");
            return;
        }

        webhook->write_event(body);

        std::printf("[WebhookHTTP] POST /webhook/trigger | payload=%zu bytes → IRQ triggered\n", body.size());

        res.status = 200;
        res.set_content("{\"status\":\"ok\"}", "application/json");
    });

    webhook_http_->Post("/cluster/migrate", [](const httplib::Request& req, httplib::Response& res) {
        std::string body = req.body;
        if (body.empty()) {
            res.status = 400;
            res.set_content("{\"status\":\"error\",\"message\":\"Empty snapshot body\"}", "application/json");
            return;
        }

        nlohmann::json snapshot;
        try {
            snapshot = nlohmann::json::parse(body);
        } catch (const nlohmann::json::parse_error& e) {
            res.status = 400;
            res.set_content("{\"status\":\"error\",\"message\":\"Invalid JSON: " + std::string(e.what()) + "\"}", "application/json");
            return;
        }

        if (!snapshot.contains("pcb") || !snapshot["pcb"].is_object()) {
            res.status = 400;
            res.set_content("{\"status\":\"error\",\"message\":\"Missing pcb section in snapshot\"}", "application/json");
            return;
        }

        int agent_id = snapshot["pcb"].value("agent_id", -1);
        if (agent_id < 0) {
            res.status = 400;
            res.set_content("{\"status\":\"error\",\"message\":\"Invalid agent_id in snapshot\"}", "application/json");
            return;
        }

        std::string snapshot_str = snapshot.dump();

        bool ok = ProcessManager::instance().import_snapshot(agent_id, snapshot_str);

        if (ok) {
            std::printf("[ClusterRPC] POST /cluster/migrate | agent=%d | IMPORT SUCCESS\n", agent_id);
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["agent_id"] = agent_id;
            resp["message"] = "Agent " + std::to_string(agent_id) + " imported successfully";
            res.status = 200;
            res.set_content(resp.dump(), "application/json");
        } else {
            std::printf("[ClusterRPC] POST /cluster/migrate | agent=%d | IMPORT FAILED\n", agent_id);
            res.status = 500;
            res.set_content("{\"status\":\"error\",\"message\":\"import_snapshot failed\"}", "application/json");
        }
    });

    webhook_http_->Get("/ui/stream", [](const httplib::Request&, httplib::Response& res) {
        res.set_chunked_content_provider(
            "text/event-stream",
            [](size_t /*offset*/, httplib::DataSink& sink) -> bool {
                auto& vfs = VfsManager::instance();
                auto node = vfs.resolve_path("/dev/fb0", 0);
                if (!node) {
                    std::string err = "data: {\"error\":\"/dev/fb0 not found\"}\n\n";
                    sink.write(err.data(), err.size());
                    return false;
                }

                auto fb = std::dynamic_pointer_cast<DisplayNode>(node);
                if (!fb) {
                    std::string err = "data: {\"error\":\"Node is not DisplayNode\"}\n\n";
                    sink.write(err.data(), err.size());
                    return false;
                }

                int last_index = -1;

                std::printf("[SSE] Client connected to /ui/stream\n");

                while (sink.is_writable()) {
                    try {
                        std::string frames_json = fb->read_stream(last_index);

                        auto arr = nlohmann::json::parse(frames_json, nullptr, false);
                        if (arr.is_array()) {
                            for (const auto& frame : arr) {
                                int idx = frame.value("index", -1);
                                std::string payload = frame.value("payload", "");

                                std::string sse_msg = "data: " + payload + "\n\n";
                                sink.write(sse_msg.data(), sse_msg.size());

                                if (idx > last_index) {
                                    last_index = idx;
                                }
                            }
                        }
                    } catch (const std::exception& e) {
                        std::printf("[SSE] Exception: %s\n", e.what());
                        break;
                    }
                }

                std::printf("[SSE] Client disconnected from /ui/stream\n");
                return true;
            });
    });

    webhook_http_->Get("/audio/stream", [](const httplib::Request&, httplib::Response& res) {
        res.set_chunked_content_provider(
            "application/octet-stream",
            [](size_t /*offset*/, httplib::DataSink& sink) -> bool {
                auto& vfs = VfsManager::instance();
                auto node = vfs.resolve_path("/dev/audio/pcm", 0);
                if (!node || node->node_type() != VfsNodeType::AUDIO) {
                    return false;
                }
                auto pcm = std::dynamic_pointer_cast<AudioNode>(node);
                if (!pcm) return false;

                std::printf("[AudioStream] Client connected to /audio/stream\n");

                while (sink.is_writable()) {
                    try {
                        auto chunk = pcm->read_pcm_blocking(0, 2000);
                        if (!chunk.empty()) {
                            sink.write(reinterpret_cast<const char*>(chunk.data()),
                                       chunk.size());
                        }
                    } catch (const std::exception& e) {
                        std::printf("[AudioStream] Exception: %s\n", e.what());
                        break;
                    }
                }

                std::printf("[AudioStream] Client disconnected from /audio/stream\n");
                return true;
            });
    });

    webhook_http_->Get("/audio/visemes", [](const httplib::Request&, httplib::Response& res) {
        res.set_chunked_content_provider(
            "text/event-stream",
            [](size_t /*offset*/, httplib::DataSink& sink) -> bool {
                auto& vfs = VfsManager::instance();
                auto node = vfs.resolve_path("/dev/audio/visemes", 0);
                if (!node || node->node_type() != VfsNodeType::AUDIO) {
                    std::string err = "data: {\"error\":\"/dev/audio/visemes not found\"}\n\n";
                    sink.write(err.data(), err.size());
                    return false;
                }
                auto vis = std::dynamic_pointer_cast<AudioNode>(node);
                if (!vis) return false;

                std::printf("[VisemeSSE] Client connected to /audio/visemes\n");

                while (sink.is_writable()) {
                    try {
                        std::string frame = vis->read_viseme_blocking(3000);
                        if (!frame.empty()) {
                            std::string sse_msg = "data: " + frame + "\n\n";
                            sink.write(sse_msg.data(), sse_msg.size());
                        }
                    } catch (const std::exception& e) {
                        std::printf("[VisemeSSE] Exception: %s\n", e.what());
                        break;
                    }
                }

                std::printf("[VisemeSSE] Client disconnected from /audio/visemes\n");
                return true;
            });
    });

    webhook_http_->Post("/bpf/load", [](const httplib::Request& req, httplib::Response& res) {
        std::string auth = req.get_header_value("X-AIOS-Ring");
        if (auth != "0" && auth != "RING_0") {
            res.status = 403;
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "EPERM: Only Ring 0 (kernel) can load BPF programs";
            res.set_content(err.dump(), "application/json");
            return;
        }

        std::string hook_point = req.get_file_value("hook_point").content;
        std::string export_func = req.get_file_value("export_func").content;
        if (export_func.empty()) export_func = "bpf_filter";

        if (hook_point.empty()) {
            res.status = 400;
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "Missing required field: hook_point";
            res.set_content(err.dump(), "application/json");
            return;
        }

        auto wasm_file = req.get_file_value("wasm");
        if (wasm_file.content.empty()) {
            res.status = 400;
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "Missing required file upload: wasm";
            res.set_content(err.dump(), "application/json");
            return;
        }

        std::string save_dir = "/tmp/aios_bpf";
        std::filesystem::create_directories(save_dir);

        std::string wasm_save_path = save_dir + "/bpf_" + hook_point + ".wasm";
        {
            std::ofstream ofs(wasm_save_path, std::ios::binary);
            ofs.write(wasm_file.content.data(), wasm_file.content.size());
        }

        auto& bpf = BpfManager::instance();
        bool ok = bpf.load_bpf_program(hook_point, wasm_save_path, export_func);

        nlohmann::json resp;
        resp["status"] = ok ? "ok" : "error";
        resp["hook_point"] = hook_point;
        resp["wasm_path"] = wasm_save_path;
        resp["wasm_size"] = wasm_file.content.size();
        resp["export_func"] = export_func;
        res.set_content(resp.dump(), "application/json");
    });

    webhook_http_->Get("/bpf/list", [](const httplib::Request& req, httplib::Response& res) {
        auto& bpf = BpfManager::instance();
        auto hooks = bpf.list_hooks();
        nlohmann::json hooks_arr = nlohmann::json::array();
        for (const auto& h : hooks) {
            nlohmann::json entry;
            entry["hook_point"] = h.hook_point;
            entry["wasm_path"] = h.wasm_path;
            entry["export_func"] = h.export_func;
            entry["active"] = h.active;
            entry["invoke_count"] = h.invoke_count;
            entry["drop_count"] = h.drop_count;
            hooks_arr.push_back(entry);
        }
        nlohmann::json resp;
        resp["status"] = "ok";
        resp["hooks"] = hooks_arr;
        resp["total_invokes"] = bpf.total_invokes();
        resp["total_drops"] = bpf.total_drops();
        res.set_content(resp.dump(), "application/json");
    });

    webhook_http_->Post("/trace/start_record", [](const httplib::Request& req, httplib::Response& res) {
        std::string auth = req.get_header_value("X-AIOS-Ring");
        if (auth != "0" && auth != "RING_0") {
            res.status = 403;
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "EPERM: Only Ring 0 (kernel) can start trace recording";
            res.set_content(err.dump(), "application/json");
            return;
        }

        nlohmann::json body;
        try {
            body = nlohmann::json::parse(req.body);
        } catch (...) {
            body = nlohmann::json::object();
        }

        int agent_id = body.value("agent_id", 0);
        bool reset = body.value("reset", true);

        auto& tracer = aios::TraceManager::instance();
        if (reset) {
            tracer.reset_all();
        }
        tracer.set_mode(aios::TraceMode::RECORD);

        if (agent_id > 0) {
            aios::TraceManager::set_thread_agent_id(agent_id);
        }

        nlohmann::json resp;
        resp["status"] = "ok";
        resp["mode"] = "RECORD";
        resp["agent_id"] = agent_id;
        resp["reset"] = reset;
        resp["message"] = "Time-travel recording started — all non-deterministic operations will be captured to tape";
        res.set_content(resp.dump(), "application/json");

        std::printf("[TraceAPI] 🎙️  RECORD mode activated via HTTP /trace/start_record | agent_id=%d | reset=%d\n",
                    agent_id, reset);
    });

    webhook_http_->Post("/trace/start_replay", [](const httplib::Request& req, httplib::Response& res) {
        std::string auth = req.get_header_value("X-AIOS-Ring");
        if (auth != "0" && auth != "RING_0") {
            res.status = 403;
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "EPERM: Only Ring 0 (kernel) can start trace replay";
            res.set_content(err.dump(), "application/json");
            return;
        }

        nlohmann::json body;
        try {
            body = nlohmann::json::parse(req.body);
        } catch (...) {
            body = nlohmann::json::object();
        }

        int agent_id = body.value("agent_id", 0);

        auto& tracer = aios::TraceManager::instance();
        tracer.set_mode(aios::TraceMode::REPLAY);

        if (agent_id > 0) {
            tracer.reset_agent(agent_id);
            aios::TraceManager::set_thread_agent_id(agent_id);
        }

        nlohmann::json resp;
        resp["status"] = "ok";
        resp["mode"] = "REPLAY";
        resp["agent_id"] = agent_id;
        resp["message"] = "Time-travel replay activated — all non-deterministic operations will be replayed from tape";
        res.set_content(resp.dump(), "application/json");

        std::printf("[TraceAPI] ⏪ REPLAY mode activated via HTTP /trace/start_replay | agent_id=%d\n",
                    agent_id);
    });

    webhook_http_->Get("/trace/status", [](const httplib::Request& req, httplib::Response& res) {
        auto& tracer = aios::TraceManager::instance();
        const char* mode_names[] = {"DISABLED", "RECORD", "REPLAY"};
        nlohmann::json resp;
        resp["status"] = "ok";
        resp["mode"] = mode_names[static_cast<int>(tracer.mode())];
        resp["current_seq"] = tracer.current_seq();
        resp["recorded_count"] = tracer.recorded_count();
        resp["replayed_count"] = tracer.replayed_count();
        res.set_content(resp.dump(), "application/json");
    });

    webhook_http_->Post("/kernel/compile", [](const httplib::Request& req, httplib::Response& res) {
        std::string auth = req.get_header_value("X-AIOS-Ring");
        if (auth != "0" && auth != "RING_0") {
            res.status = 403;
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "EPERM: Only Ring 0 (kernel) can compile the kernel";
            res.set_content(err.dump(), "application/json");
            return;
        }

        nlohmann::json body;
        try {
            body = nlohmann::json::parse(req.body);
        } catch (...) {
            body = nlohmann::json::object();
        }

        bool do_kexec = body.value("kexec", false);

        std::string project_root = std::filesystem::current_path();
        std::string build_result = aios::compile_kernel(project_root);

        nlohmann::json compile_result;
        try {
            compile_result = nlohmann::json::parse(build_result);
        } catch (...) {
            compile_result["raw_output"] = build_result;
            compile_result["status"] = "error";
        }

        if (do_kexec && compile_result.value("status", "") == "ok") {
            std::string new_binary = compile_result.value("binary_path", "");
            if (!new_binary.empty()) {
                compile_result["kexec_triggered"] = true;
                compile_result["kexec_message"] = "Kernel hot-swap initiated — new kernel will replace current process";
                res.set_content(compile_result.dump(), "application/json");

                std::printf("[KernelAPI] 🔥 compile_kernel + kexec triggered — replacing kernel with %s\n",
                            new_binary.c_str());

                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                aios::KexecManager::instance().trigger_kexec(new_binary);
                return;
            }
        }

        res.set_content(compile_result.dump(), "application/json");
    });

    webhook_http_->Get("/kernel/source_status", [](const httplib::Request& req, httplib::Response& res) {
        auto& vfs = VfsManager::instance();
        auto node = vfs.resolve_path("/usr/src/aios", 0);
        nlohmann::json resp;
        if (node) {
            resp["status"] = "ok";
            resp["mount_point"] = "/usr/src/aios";
            resp["mounted"] = true;
            auto dir = std::dynamic_pointer_cast<aios::HostSourceDirNode>(node);
            if (dir) {
                resp["host_path"] = dir->host_path();
            }
            resp["access_control"] = "Ring 0 write only";
        } else {
            resp["status"] = "ok";
            resp["mounted"] = false;
        }
        res.set_content(resp.dump(), "application/json");
    });

    webhook_http_->Post("/mcp/message", [this](const httplib::Request& req, httplib::Response& res) {
        if (!mcp_server_) {
            res.status = 503;
            res.set_content("{\"error\":\"MCP server not available\"}", "application/json");
            return;
        }

        std::string body = req.body;
        if (body.empty()) {
            res.status = 400;
            res.set_content("{\"error\":\"Empty body\"}", "application/json");
            return;
        }

        std::printf("[MCP-HTTP] POST /mcp/message | body=%zu bytes\n", body.size());

        std::string mcp_response = mcp_server_->handle_message(body);

        res.status = 200;
        res.set_header("Content-Type", "application/json");
        res.set_header("Access-Control-Allow-Origin", "*");
        res.set_content(mcp_response, "application/json");
    });

    webhook_http_->Get("/mcp/sse", [this](const httplib::Request& req, httplib::Response& res) {
        if (!mcp_server_) {
            res.status = 503;
            res.set_content("{\"error\":\"MCP server not available\"}", "application/json");
            return;
        }

        res.set_header("Content-Type", "text/event-stream");
        res.set_header("Cache-Control", "no-cache");
        res.set_header("Connection", "keep-alive");
        res.set_header("Access-Control-Allow-Origin", "*");
        res.set_header("X-Accel-Buffering", "no");

        std::string client_id = "sse_" + std::to_string(reinterpret_cast<uintptr_t>(&res));

        int sse_fd = socket(AF_INET, SOCK_STREAM, 0);
        if (sse_fd < 0) {
            res.status = 500;
            res.set_content("{\"error\":\"Failed to create SSE socket\"}", "application/json");
            return;
        }

        mcp_server_->register_sse_client(sse_fd, client_id);

        std::string endpoint_msg = "event: endpoint\ndata: /mcp/message?client_id=" + client_id + "\n\n";
        res.body = endpoint_msg;

        std::string connected_msg = "event: connected\ndata: {\"client_id\":\"" + client_id + "\"}\n\n";
        res.body += connected_msg;

        std::printf("[MCP-HTTP] SSE client connected: %s\n", client_id.c_str());
    });

    webhook_http_->Options("/mcp/message", [](const httplib::Request&, httplib::Response& res) {
        res.set_header("Access-Control-Allow-Origin", "*");
        res.set_header("Access-Control-Allow-Methods", "POST, OPTIONS");
        res.set_header("Access-Control-Allow-Headers", "Content-Type");
        res.status = 204;
    });

    webhook_http_->Options("/mcp/sse", [](const httplib::Request&, httplib::Response& res) {
        res.set_header("Access-Control-Allow-Origin", "*");
        res.set_header("Access-Control-Allow-Methods", "GET, OPTIONS");
        res.set_header("Access-Control-Allow-Headers", "Content-Type");
        res.status = 204;
    });

    webhook_http_thread_ = std::thread([this]() {
        std::printf("[WebhookHTTP] 🪝 Listening on 127.0.0.1:%u (POST /webhook/trigger, POST /cluster/migrate, GET /ui/stream, GET /audio/stream, GET /audio/visemes, POST /bpf/load, GET /bpf/list, GET /mcp/sse, POST /mcp/message)\n", webhook_port_);
        webhook_http_->listen("127.0.0.1", webhook_port_);
        std::printf("[WebhookHTTP] Server stopped\n");
    });
}

void SyscallServer::shutdown() {
    if (!running_.load()) return;
    running_.store(false);

    if (event_fd_ >= 0) {
        notify_eventfd(event_fd_);
    }

    if (listen_fd_ >= 0) {
        ::shutdown(listen_fd_, SHUT_RDWR);
    }

    if (io_thread_.joinable()) {
        io_thread_.join();
    }

    if (webhook_http_) {
        webhook_http_->stop();
    }
    if (webhook_http_thread_.joinable()) {
        webhook_http_thread_.join();
    }

    decode_pool_->shutdown();

    for (auto& [fd, conn] : clients_) {
        close(fd);
    }
    clients_.clear();

    if (event_fd_ >= 0) { close(event_fd_); event_fd_ = -1; }
    if (epoll_fd_ >= 0) { close(epoll_fd_); epoll_fd_ = -1; }
    if (listen_fd_ >= 0) { close(listen_fd_); listen_fd_ = -1; }

    std::printf("[Reactor] Shutdown complete\n");
}

void SyscallServer::io_loop() {
    listen_fd_ = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (listen_fd_ < 0) {
        std::printf("[Reactor] socket() failed: %s\n", std::strerror(errno));
        running_.store(false);
        return;
    }

    int opt = 1;
    setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    setsockopt(listen_fd_, SOL_SOCKET, SO_REUSEPORT, &opt, sizeof(opt));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port_);
    inet_pton(AF_INET, host_.c_str(), &addr.sin_addr);

    if (bind(listen_fd_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::printf("[Reactor] bind() failed: %s\n", std::strerror(errno));
        close(listen_fd_); listen_fd_ = -1;
        running_.store(false);
        return;
    }

    if (listen(listen_fd_, SOMAXCONN) < 0) {
        std::printf("[Reactor] listen() failed: %s\n", std::strerror(errno));
        close(listen_fd_); listen_fd_ = -1;
        running_.store(false);
        return;
    }

    epoll_fd_ = epoll_create1(0);
    if (epoll_fd_ < 0) {
        std::printf("[Reactor] epoll_create1() failed: %s\n", std::strerror(errno));
        close(listen_fd_); listen_fd_ = -1;
        running_.store(false);
        return;
    }

    epoll_event ev{};
    ev.events = EPOLLIN;
    ev.data.fd = listen_fd_;
    if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, listen_fd_, &ev) < 0) {
        std::printf("[Reactor] epoll_ctl(ADD listen_fd) failed: %s\n", std::strerror(errno));
        close(epoll_fd_); close(listen_fd_);
        epoll_fd_ = -1; listen_fd_ = -1;
        running_.store(false);
        return;
    }

    event_fd_ = eventfd(0, EFD_NONBLOCK);
    if (event_fd_ < 0) {
        std::printf("[Reactor] eventfd() failed: %s\n", std::strerror(errno));
        close(epoll_fd_); close(listen_fd_);
        epoll_fd_ = -1; listen_fd_ = -1;
        running_.store(false);
        return;
    }

    epoll_event eev{};
    eev.events = EPOLLIN;
    eev.data.fd = event_fd_;
    if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, event_fd_, &eev) < 0) {
        std::printf("[Reactor] epoll_ctl(ADD event_fd) failed: %s\n", std::strerror(errno));
        close(event_fd_); close(epoll_fd_); close(listen_fd_);
        event_fd_ = -1; epoll_fd_ = -1; listen_fd_ = -1;
        running_.store(false);
        return;
    }

    std::printf("[Reactor] Listening on %s:%u (epoll LT + eventfd)\n", host_.c_str(), port_);

    epoll_event events[MAX_EVENTS];

    while (running_.load()) {
        int nfds = epoll_wait(epoll_fd_, events, MAX_EVENTS, 500);
        if (nfds < 0) {
            if (errno == EINTR) continue;
            std::printf("[Reactor] epoll_wait error: %s\n", std::strerror(errno));
            break;
        }

        for (int i = 0; i < nfds; ++i) {
            int fd = events[i].data.fd;
            uint32_t evts = events[i].events;

            if (fd == event_fd_) {
                uint64_t val;
                while (::read(event_fd_, &val, sizeof(val)) == sizeof(val)) {}
                drain_response_queue();
                continue;
            }

            if (fd == listen_fd_) {
                handle_accept();
                continue;
            }

            if (evts & (EPOLLERR | EPOLLHUP | EPOLLRDHUP)) {
                if (remote_fwd_.count(fd)) {
                    close_remote(fd);
                } else {
                    close_client(fd);
                }
                continue;
            }

            if (remote_fwd_.count(fd)) {
                if (evts & EPOLLOUT) {
                    auto& fwd = remote_fwd_[fd];
                    if (!fwd.connected) {
                        fwd.connected = true;
                        std::printf("[RemoteFwd] fd=%d connected to %s:%u\n",
                                    fd, remote_host_.c_str(), remote_port_);
                        mod_fd(fd, EPOLLIN | EPOLLRDHUP);
                        if (!fwd.write_buf.empty()) {
                            ssize_t n = ::write(fd, fwd.write_buf.data(), fwd.write_buf.size());
                            if (n > 0) {
                                fwd.write_buf.erase(0, static_cast<size_t>(n));
                            }
                        }
                    }
                }
                if (evts & EPOLLIN) {
                    handle_remote_response(fd);
                }
                continue;
            }

            if (evts & EPOLLIN) {
                handle_read(fd);
            }

            if ((evts & EPOLLOUT) && clients_.count(fd)) {
                handle_write(fd);
            }
        }

        drain_response_queue();
        check_remote_timeouts();
    }

    running_.store(false);
}

void SyscallServer::handle_accept() {
    while (true) {
        sockaddr_in client_addr{};
        socklen_t addr_len = sizeof(client_addr);
        int client_fd = accept4(listen_fd_,
                                reinterpret_cast<sockaddr*>(&client_addr),
                                &addr_len,
                                SOCK_NONBLOCK);
        if (client_fd < 0) {
            if (errno != EAGAIN && errno != EWOULDBLOCK) {
                std::printf("[Reactor] accept() failed: %s\n", std::strerror(errno));
            }
            break;
        }

        int nodelay = 1;
        setsockopt(client_fd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));
        int keepalive = 1;
        setsockopt(client_fd, SOL_SOCKET, SO_KEEPALIVE, &keepalive, sizeof(keepalive));

        char ip_str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &client_addr.sin_addr, ip_str, sizeof(ip_str));
        std::printf("[Reactor] New connection from %s:%d (fd=%d)\n",
                    ip_str, ntohs(client_addr.sin_port), client_fd);

        epoll_event ev{};
        ev.events = EPOLLIN | EPOLLRDHUP;
        ev.data.fd = client_fd;
        if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, client_fd, &ev) < 0) {
            std::printf("[Reactor] epoll_ctl(ADD client) failed: %s\n", std::strerror(errno));
            close(client_fd);
            continue;
        }

        clients_[client_fd] = ClientConn{};
    }
}

void SyscallServer::handle_read(int fd) {
    char buf[READ_BUF_SIZE];
    while (true) {
        ssize_t n = ::read(fd, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                break;
            }
            close_client(fd);
            return;
        }
        if (n == 0) {
            close_client(fd);
            return;
        }

        auto it = clients_.find(fd);
        if (it == clients_.end()) {
            close_client(fd);
            return;
        }
        it->second.read_buf.append(buf, static_cast<size_t>(n));
    }

    auto it = clients_.find(fd);
    if (it == clients_.end()) return;

    std::string& data = it->second.read_buf;
    size_t pos = 0;
    while (true) {
        size_t newline = data.find('\n', pos);
        if (newline == std::string::npos) break;

        std::string line = data.substr(pos, newline - pos);
        pos = newline + 1;

        if (line.empty()) continue;

        std::printf("[Reactor] Recv fd=%d: %s\n", fd, line.c_str());
        parse_and_dispatch(fd, line);
    }

    data.erase(0, pos);
}

void SyscallServer::parse_and_dispatch(int fd, const std::string& line) {
    std::printf("[parse_and_dispatch] fd=%d | line_len=%zu | first20=%s\n",
                fd, line.size(), line.substr(0, 20).c_str());

    FlatCommand cmd = InstructionDecoder::ParseFlatCommand(line);
    if (cmd.valid) {
        std::printf("[网关] 扁平微指令直达: %s %s\n", cmd.action.c_str(), cmd.arg.c_str());
        dispatch_flat(fd, cmd, line);
        return;
    }

    nlohmann::json req;
    try {
        req = nlohmann::json::parse(line);
        std::printf("[parse_and_dispatch] JSON parsed OK, syscall check...\n");
    } catch (const nlohmann::json::exception& e) {
        std::printf("[parse_and_dispatch] JSON parse error: %s\n", e.what());
        auto& decoder = InstructionDecoder::GetInstance();
        if (decoder.is_ready()) {
            decode_pool_->submit([this, fd, line]() {
                decode_and_dispatch(fd, line);
            });
        } else {
            FlatCommand fallback = keyword_route(line);
            std::printf("[网关] Daemon不可达，关键词路由: %s %s\n",
                        fallback.action.c_str(), fallback.arg.c_str());
            dispatch_flat(fd, fallback, line);
        }
        return;
    }

    if (!req.contains("syscall") || !req["syscall"].is_string()) {
        enqueue_response(fd, "{\"status\":\"error\",\"message\":\"missing 'syscall' field\"}\n");
        return;
    }

    try {

    std::string syscall_name = req["syscall"].get<std::string>();
    int agent_id = -1;
    if (req.contains("agent_id") && req["agent_id"].is_number()) {
        agent_id = req["agent_id"].get<int>();
    }
    int caller_id = agent_id;
    if (req.contains("caller_id") && req["caller_id"].is_number()) {
        caller_id = req["caller_id"].get<int>();
    }

    auto& registry = AgentRegistry::instance();
    registry.ensure_registered(caller_id);

    if (caller_id >= 2000) {
        std::printf("[Reactor] Remote agent detected: caller_id=%d >= 2000 → forwarding\n", caller_id);
        forward_to_remote(fd, caller_id, line);
        return;
    }

    auto& proc_mgr = ProcessManager::instance();
    proc_mgr.register_process(caller_id,
        registry.get_level(caller_id) == PrivilegeLevel::RING_0 ? 0 : 3);
    proc_mgr.record_syscall(caller_id);

    if (ping_fn_ && caller_id > 0) {
        ping_fn_(caller_id);
    }

    std::string action = req.value("action", "");
    std::string path = req.value("path", "");
    std::string payload = req.value("payload", "");
    std::string security_payload = action + " " + path + " " + payload;

    if (caller_id > 0) {
        auto& guard = SecurityGuard::instance();
        if (!guard.check_intent(caller_id, syscall_name, security_payload)) {
            std::string block_reason = "EPERM: Operation not permitted by AIOS Security Guard";
            std::printf("[SecurityGuard] \u26a0\ufe0f BLOCKED syscall from agent=%d | %s | action=%s | KILLING AGENT\n",
                        caller_id, syscall_name.c_str(), action.c_str());

            KernelLogger::instance().log_alert(
                "[SecurityGuard] AGENT KILLED | agent=" + std::to_string(caller_id)
                + " | syscall=" + syscall_name + " | action=" + action
                + " | reason=" + block_reason);

            nlohmann::json err_resp;
            err_resp["status"] = "error";
            err_resp["code"] = 403;
            err_resp["errno_"] = 1;
            err_resp["message"] = block_reason;
            err_resp["agent_id"] = caller_id;
            err_resp["syscall"] = syscall_name;
            err_resp["action"] = action;
            enqueue_response(fd, err_resp.dump() + "\n");

            if (cancel_fn_) {
                cancel_fn_(caller_id);
                std::printf("[SecurityGuard] SIGKILL sent to Agent %d via cancel_fn_\n", caller_id);
            }

            return;
        }
    }

    auto it = handlers_.find(syscall_name);
    if (it != handlers_.end()) {
        it->second->handle(fd, caller_id, req, this);
        return;
    }

    std::shared_ptr<AgentTask> task;

    if (syscall_name == "WRITE_MEMORY") {
        task = std::make_shared<AgentTask>(
            agent_id, 0, TaskStatus::READY,
            req.value("content", req.value("data", "")),
            TaskType::WRITE_MEMORY, "", "", fd
        );
        task->role = req.value("role", "user");
        task->content = req.value("content", req.value("data", ""));
    } else if (syscall_name == "READ_MEMORY") {
        if (!registry.can_access(caller_id, agent_id)) {
            std::printf("[Security] BLOCKED | caller=%d (%s) -> READ_MEMORY agent=%d | Cross-agent access denied\n",
                        caller_id, privilege_str(registry.get_level(caller_id)), agent_id);
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "[Security Fault] Ring 3 Agent 无权越权访问";
            enqueue_response(fd, err.dump() + "\n");
            return;
        }
        task = std::make_shared<AgentTask>(
            agent_id, 0, TaskStatus::READY,
            req.value("keyword", ""),
            TaskType::READ_MEMORY, "", "", fd
        );
        task->keyword = req.value("keyword", "");
    } else if (syscall_name == "CANCEL_TASK") {
        int target_agent = req.value("target_agent_id", agent_id);
        if (target_agent < 0) {
            enqueue_response(fd, "{\"status\":\"error\",\"message\":\"missing agent_id\"}\n");
            return;
        }
        if (!registry.can_cancel(caller_id, target_agent)) {
            std::printf("[Security] BLOCKED | caller=%d (%s) -> CANCEL_TASK agent=%d | Cross-agent cancel denied\n",
                        caller_id, privilege_str(registry.get_level(caller_id)), target_agent);
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "[Security Fault] Ring 3 Agent 无权越权访问";
            enqueue_response(fd, err.dump() + "\n");
            return;
        }
        aios::WasmNode::SendSignal(target_agent, 15);
        nlohmann::json resp;
        resp["status"] = "ok";
        resp["message"] = "CANCEL_TASK sent for agent " + std::to_string(target_agent);
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "AGENT_SPAWN") {
        std::string role;
        if (req.contains("role") && req["role"].is_string()) {
            role = req["role"].get<std::string>();
        } else if (req.contains("payload") && req["payload"].is_string()) {
            role = req["payload"].get<std::string>();
        }
        std::string stdin_path;
        if (req.contains("stdin") && req["stdin"].is_string()) {
            stdin_path = req["stdin"].get<std::string>();
        }
        std::string stdout_path;
        if (req.contains("stdout") && req["stdout"].is_string()) {
            stdout_path = req["stdout"].get<std::string>();
        }
        int clone_flags = 0;
        if (req.contains("clone_flags")) {
            if (req["clone_flags"].is_number_integer()) {
                clone_flags = req["clone_flags"].get<int>();
            } else if (req["clone_flags"].is_number_unsigned()) {
                clone_flags = static_cast<int>(req["clone_flags"].get<unsigned int>());
            }
        }
        constexpr int AIOS_CLONE_NEWNS = 0x00020000;
        std::printf("[AGENT_SPAWN] clone_flags=%d (0x%x) | NEWNS=%s\n",
                    clone_flags, clone_flags,
                    (clone_flags & AIOS_CLONE_NEWNS) ? "YES" : "NO");
        int child_id = ProcessManager::instance().spawn(
            caller_id, role, stdin_path, stdout_path, clone_flags);
        nlohmann::json resp;
        resp["status"] = "ok";
        resp["child_id"] = child_id;
        resp["parent_id"] = caller_id;
        resp["role"] = role;
        if (!stdin_path.empty()) resp["stdin"] = stdin_path;
        if (!stdout_path.empty()) resp["stdout"] = stdout_path;
        if (clone_flags & AIOS_CLONE_NEWNS) {
            resp["mount_namespace"] = "/containers/agent_" + std::to_string(child_id);
            resp["root_dir"] = VfsManager::instance().get_agent_root(child_id);
            resp["clone_flags"] = clone_flags;
        }
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "AGENT_WAIT") {
        int child_id = -1;
        if (req.contains("child_id") && req["child_id"].is_number()) {
            child_id = req["child_id"].get<int>();
        } else if (req.contains("agent_id") && req["agent_id"].is_number()) {
            child_id = req["agent_id"].get<int>();
        }
        if (child_id < 0) {
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "AGENT_WAIT requires 'child_id'";
            enqueue_response(fd, err.dump() + "\n");
            return;
        }
        std::string result = ProcessManager::instance().wait(child_id);
        nlohmann::json resp;
        resp["status"] = "ok";
        resp["child_id"] = child_id;
        resp["data"] = result;
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "AGENT_EXIT") {
        std::string exit_result = req.value("payload", req.value("result", ""));
        ProcessManager::instance().exit(caller_id, exit_result);
        nlohmann::json resp;
        resp["status"] = "ok";
        resp["agent_id"] = caller_id;
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "AGENT_EXPORT") {
        int target_agent = req.value("agent_id", caller_id);
        std::string snapshot = ProcessManager::instance().export_snapshot(target_agent);
        if (snapshot.empty()) {
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "AGENT_EXPORT failed: agent " + std::to_string(target_agent) + " not found or already terminated";
            enqueue_response(fd, err.dump() + "\n");
        } else {
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["agent_id"] = target_agent;
            resp["snapshot"] = nlohmann::json::parse(snapshot, nullptr, false);
            resp["snapshot_size"] = snapshot.size();
            enqueue_response(fd, resp.dump() + "\n");
            std::printf("[Reactor] AGENT_EXPORT | agent=%d | snapshot_size=%zu bytes\n",
                        target_agent, snapshot.size());
        }
    } else if (syscall_name == "AGENT_IMPORT") {
        int target_agent = req.value("agent_id", -1);
        std::string snapshot_data = req.value("snapshot", "");
        if (target_agent < 0 || snapshot_data.empty()) {
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "AGENT_IMPORT requires 'agent_id' and 'snapshot'";
            enqueue_response(fd, err.dump() + "\n");
            return;
        }
        bool ok = ProcessManager::instance().import_snapshot(target_agent, snapshot_data);
        if (ok) {
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["agent_id"] = target_agent;
            resp["message"] = "Agent " + std::to_string(target_agent) + " imported successfully";
            enqueue_response(fd, resp.dump() + "\n");
            std::printf("[Reactor] AGENT_IMPORT | agent=%d | snapshot_size=%zu bytes\n",
                        target_agent, snapshot_data.size());
        } else {
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "AGENT_IMPORT failed for agent " + std::to_string(target_agent);
            enqueue_response(fd, err.dump() + "\n");
        }
    } else if (syscall_name == "CGROUP_CREATE") {
        std::string cgroup_name = req.value("name", "");
        int max_tpm = req.value("max_tokens_per_minute", 0);
        double cpu_quota = req.value("cpu_quota", 100.0);
        std::string parent = req.value("parent", "/");

        if (cgroup_name.empty()) {
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "CGROUP_CREATE requires 'name'";
            enqueue_response(fd, err.dump() + "\n");
            return;
        }

        bool ok = ProcessManager::instance().create_cgroup(cgroup_name, max_tpm, cpu_quota, parent);
        nlohmann::json resp;
        resp["status"] = ok ? "ok" : "error";
        resp["cgroup_name"] = cgroup_name;
        resp["max_tokens_per_minute"] = max_tpm;
        resp["cpu_quota"] = cpu_quota;
        resp["parent"] = parent;
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "CGROUP_ATTACH") {
        int agent_id = req.value("agent_id", -1);
        std::string cgroup_name = req.value("cgroup_name", "");

        if (agent_id < 0 || cgroup_name.empty()) {
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "CGROUP_ATTACH requires 'agent_id' and 'cgroup_name'";
            enqueue_response(fd, err.dump() + "\n");
            return;
        }

        bool ok = ProcessManager::instance().attach_to_cgroup(agent_id, cgroup_name);
        nlohmann::json resp;
        resp["status"] = ok ? "ok" : "error";
        resp["agent_id"] = agent_id;
        resp["cgroup_name"] = cgroup_name;
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "CGROUP_DETACH") {
        int agent_id = req.value("agent_id", -1);
        if (agent_id < 0) {
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "CGROUP_DETACH requires 'agent_id'";
            enqueue_response(fd, err.dump() + "\n");
            return;
        }
        bool ok = ProcessManager::instance().detach_from_cgroup(agent_id);
        nlohmann::json resp;
        resp["status"] = ok ? "ok" : "error";
        resp["agent_id"] = agent_id;
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "CGROUP_INFO") {
        std::string cgroup_name = req.value("cgroup_name", "/");
        std::string info = CgroupManager::instance().get_cgroup_info(cgroup_name);
        nlohmann::json resp;
        resp["status"] = "ok";
        resp["cgroup_name"] = cgroup_name;
        resp["info"] = nlohmann::json::parse(info, nullptr, false);
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "CGROUP_TREE") {
        std::string tree = CgroupManager::instance().dump_tree();
        nlohmann::json resp;
        resp["status"] = "ok";
        resp["tree"] = tree;
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "CGROUP_RESET") {
        CgroupManager::instance().reset_period();
        nlohmann::json resp;
        resp["status"] = "ok";
        resp["message"] = "All cgroup token counters reset";
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "TOOL_DISCOVER") {
        std::string tools_json = ModuleManager::instance().discover_tools();
        nlohmann::json resp;
        resp["status"] = "ok";
        resp["data"] = nlohmann::json::parse(tools_json, nullptr, false);
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "TOOL_CALL") {
        std::string tool_name = req.value("tool_name", req.value("name", ""));
        std::string args = req.value("args", req.value("payload", ""));
        if (tool_name.empty()) {
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "TOOL_CALL requires 'tool_name'";
            enqueue_response(fd, err.dump() + "\n");
            return;
        }
        std::string result = ModuleManager::instance().call_tool(tool_name, args);
        nlohmann::json resp = nlohmann::json::parse(result, nullptr, false);
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "TOOL_INSTALL") {
        std::string tool_name = req.value("tool_name", req.value("payload", ""));
        std::string reload_result = ModuleManager::instance().reload(tool_name);
        nlohmann::json resp = nlohmann::json::parse(reload_result, nullptr, false);
        enqueue_response(fd, resp.dump() + "\n");
    } else if (syscall_name == "PROCESS_CTRL") {
        std::string action = req.value("action", "");
        int target_agent = req.value("agent_id", -1);
        if (action.empty() || target_agent < 0) {
            enqueue_response(fd, "{\"status\":\"error\",\"message\":\"PROCESS_CTRL requires 'action' and 'agent_id'\"}\n");
            return;
        }
        task = std::make_shared<AgentTask>(
            target_agent, 0, TaskStatus::READY,
            action, TaskType::PROCESS_CTRL, action, "", fd
        );
        task->tool_name = action;
    } else if (syscall_name == "EXECUTE_TOOL" || syscall_name == "EXECUTE_TASK") {
        int priority = req.value("priority", 1);
        std::string tool_name = req.value("tool_name", "");
        std::string code = req.value("code", req.value("payload", ""));
        std::string data = req.value("data", req.value("payload", ""));

        if (!tool_name.empty()) {
            task = std::make_shared<AgentTask>(
                agent_id, priority, TaskStatus::READY,
                data, TaskType::TOOL_CALL, tool_name, code, fd
            );
        } else {
            task = std::make_shared<AgentTask>(
                agent_id, priority, TaskStatus::READY,
                data, TaskType::LLM_CHAT, "", "", fd
            );
        }
    } else {
        std::printf("[Reactor] Unknown syscall: %s\n", syscall_name.c_str());
        enqueue_response(fd, "{\"status\":\"error\",\"message\":\"unknown syscall: " + syscall_name + "\"}\n");
        return;
    }

    if (task && submit_fn_) {
        submit_fn_(std::move(task));
    }

    } catch (const nlohmann::json::exception& e) {
        std::printf("[parse_and_dispatch] JSON type error: %s\n", e.what());
        enqueue_response(fd, "{\"status\":\"error\",\"message\":\"JSON processing error\"}\n");
    } catch (const std::exception& e) {
        std::printf("[parse_and_dispatch] Exception: %s\n", e.what());
        enqueue_response(fd, "{\"status\":\"error\",\"message\":\"internal error\"}\n");
    }
}

void SyscallServer::handle_write(int fd) {
    auto it = clients_.find(fd);
    if (it == clients_.end()) {
        mod_fd(fd, EPOLLIN | EPOLLRDHUP);
        return;
    }

    std::string& wbuf = it->second.write_buf;
    if (wbuf.empty()) {
        mod_fd(fd, EPOLLIN | EPOLLRDHUP);
        return;
    }

    while (!wbuf.empty()) {
        ssize_t n = ::write(fd, wbuf.data(), wbuf.size());
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                return;
            }
            std::printf("[Reactor] write() error fd=%d: %s\n", fd, std::strerror(errno));
            close_client(fd);
            return;
        }
        wbuf.erase(0, static_cast<size_t>(n));
    }

    mod_fd(fd, EPOLLIN | EPOLLRDHUP);
}

void SyscallServer::close_client(int fd) {
    if (fd < 0) return;
    epoll_ctl(epoll_fd_, EPOLL_CTL_DEL, fd, nullptr);
    close(fd);
    clients_.erase(fd);
    std::printf("[Reactor] Connection closed (fd=%d)\n", fd);
}

void SyscallServer::enqueue_response(int fd, const std::string& response) {
    {
        std::lock_guard<std::mutex> lock(response_mutex_);
        response_queue_.push({fd, response});
    }

    if (event_fd_ >= 0) {
        notify_eventfd(event_fd_);
    }
}

void SyscallServer::drain_response_queue() {
    std::queue<PendingResponse> q;
    {
        std::lock_guard<std::mutex> lock(response_mutex_);
        q.swap(response_queue_);
    }

    while (!q.empty()) {
        auto& resp = q.front();
        auto it = clients_.find(resp.fd);
        if (it == clients_.end()) {
            q.pop();
            continue;
        }

        it->second.write_buf.append(resp.data);

        std::string& wbuf = it->second.write_buf;
        while (!wbuf.empty()) {
            ssize_t n = ::write(resp.fd, wbuf.data(), wbuf.size());
            if (n < 0) {
                if (errno == EAGAIN || errno == EWOULDBLOCK) break;
                break;
            }
            wbuf.erase(0, static_cast<size_t>(n));
        }

        if (clients_.count(resp.fd)) {
            if (!wbuf.empty()) {
                mod_fd(resp.fd, EPOLLIN | EPOLLOUT | EPOLLRDHUP);
            }
        }

        q.pop();
    }
}

void SyscallServer::mod_fd(int fd, uint32_t events) {
    epoll_event ev{};
    ev.events = events;
    ev.data.fd = fd;
    epoll_ctl(epoll_fd_, EPOLL_CTL_MOD, fd, &ev);
}

void SyscallServer::decode_and_dispatch(int fd, const std::string& natural_language) {
    std::printf("[网关] 收到自然语言意图: %s\n", natural_language.c_str());

    auto& decoder = InstructionDecoder::GetInstance();
    std::string decoded = decoder.Decode(natural_language);
    std::printf("[译码器] 编译结果: %s\n", decoded.empty() ? "(empty)" : decoded.c_str());

    FlatCommand cmd = InstructionDecoder::ParseFlatCommand(decoded);
    if (cmd.valid) {
        std::printf("[译码器] 扁平微指令解析成功: action=%s arg=%s\n",
                    cmd.action.c_str(), cmd.arg.c_str());
        FlatCommand kw = keyword_route(natural_language);
        if (kw.action == "COMPILE_AND_EXECUTE" && cmd.action != "COMPILE_AND_EXECUTE") {
            std::printf("[译码器] 关键词覆盖: %s -> COMPILE_AND_EXECUTE\n", cmd.action.c_str());
            cmd = kw;
        }
    } else {
        std::printf("[译码器] 扁平微指令解析失败，回退关键词路由\n");
        cmd = keyword_route(natural_language);
        std::printf("[译码器] 关键词路由: action=%s arg=%s\n",
                    cmd.action.c_str(), cmd.arg.c_str());
    }

    std::printf("[译码器] 自然语言 -> 系统调用: %s %s\n", cmd.action.c_str(), cmd.arg.c_str());
    dispatch_flat(fd, cmd, natural_language);
}

FlatCommand SyscallServer::keyword_route(const std::string& input) {
    FlatCommand cmd;
    cmd.arg = input;

    std::string lower = input;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);

    if (lower.find("cancel") != std::string::npos ||
        lower.find("stop") != std::string::npos ||
        lower.find("abort") != std::string::npos ||
        lower.find("停掉") != std::string::npos ||
        lower.find("取消") != std::string::npos) {
        cmd.action = "CANCEL_TASK";
    } else if (lower.find("remember") != std::string::npos ||
               lower.find("save") != std::string::npos ||
               lower.find("memorize") != std::string::npos ||
               lower.find("store") != std::string::npos ||
               lower.find("记住") != std::string::npos) {
        cmd.action = "EXECUTE_TASK";
    } else if (lower.find("snapshot") != std::string::npos ||
               lower.find("freeze") != std::string::npos ||
               lower.find("hibernate") != std::string::npos ||
               lower.find("快照") != std::string::npos ||
               lower.find("冻结") != std::string::npos) {
        cmd.action = "SNAPSHOT";
        cmd.arg = "0";
    } else if (lower.find("compile") != std::string::npos ||
               lower.find("编译") != std::string::npos ||
               lower.find("运行代码") != std::string::npos ||
               lower.find("执行代码") != std::string::npos ||
               lower.find("run code") != std::string::npos ||
               lower.find("compile and run") != std::string::npos ||
               lower.find("编译运行") != std::string::npos) {
        cmd.action = "COMPILE_AND_EXECUTE";
        cmd.arg = input;
    } else if (lower.find("restore") != std::string::npos ||
               lower.find("resurrect") != std::string::npos ||
               lower.find("恢复") != std::string::npos) {
        cmd.action = "RESTORE";
        cmd.arg = "0";
    } else if (lower.find("list file") != std::string::npos ||
               lower.find("show file") != std::string::npos ||
               lower.find("read") != std::string::npos ||
               lower.find("vfs") != std::string::npos) {
        cmd.action = "VFS_READ";
        cmd.arg = "/";
    } else {
        cmd.action = "EXECUTE_TASK";
    }

    cmd.valid = true;
    return cmd;
}

void SyscallServer::dispatch_flat(int fd, const FlatCommand& cmd, const std::string& original_text) {
    int agent_id = 0;
    try { agent_id = std::stoi(cmd.arg); } catch (...) { agent_id = 0; }

    std::shared_ptr<AgentTask> task;

    if (cmd.action == "EXECUTE_TASK") {
        std::string payload = (cmd.arg.find('_') != std::string::npos) ? cmd.arg : original_text;
        task = std::make_shared<AgentTask>(
            agent_id, 0, TaskStatus::READY,
            payload, TaskType::LLM_CHAT, "", payload, fd);
    } else if (cmd.action == "COMPILE_AND_EXECUTE") {
        std::string code_payload = cmd.arg.empty() ? original_text : cmd.arg;
        static const std::vector<std::string> code_markers = {
            "#include", "int ", "void ", "float ", "double ", "char ", "long ",
            "return ", "int\t", "void\t"
        };
        size_t code_start = std::string::npos;
        for (const auto& marker : code_markers) {
            auto pos = code_payload.find(marker);
            if (pos != std::string::npos) {
                if (code_start == std::string::npos || pos < code_start) {
                    code_start = pos;
                }
            }
        }
        if (code_start != std::string::npos && code_start > 0) {
            std::printf("[dispatch_flat] 代码提取: 跳过前缀 %zu 字节\n", code_start);
            code_payload = code_payload.substr(code_start);
        }
        nlohmann::json payload_json;
        payload_json["code"] = code_payload;
        if (code_payload.find("main") != std::string::npos ||
            code_payload.find("#include") != std::string::npos) {
            payload_json["func"] = "_start";
        } else {
            size_t paren_pos = code_payload.find('(');
            if (paren_pos != std::string::npos) {
                std::string before_paren = code_payload.substr(0, paren_pos);
                size_t last_space = before_paren.rfind(' ');
                size_t last_tab = before_paren.rfind('\t');
                size_t func_start = last_space;
                if (last_tab != std::string::npos && (func_start == std::string::npos || last_tab > func_start)) {
                    func_start = last_tab;
                }
                if (func_start != std::string::npos) {
                    payload_json["func"] = before_paren.substr(func_start + 1);
                } else {
                    payload_json["func"] = "_start";
                }
            } else {
                payload_json["func"] = "_start";
            }
        }
        std::printf("[dispatch_flat] COMPILE_AND_EXECUTE | func=%s | code_len=%zu\n",
                    payload_json["func"].get<std::string>().c_str(), code_payload.size());
        task = std::make_shared<AgentTask>(
            0, 0, TaskStatus::READY,
            payload_json.dump(), TaskType::VFS_CALL, "COMPILE_AND_EXECUTE", "", fd);
    } else if (cmd.action == "CANCEL_TASK") {
        aios::WasmNode::SendSignal(agent_id, 15);
        enqueue_response(fd, "{\"status\":\"ok\",\"message\":\"CANCEL_TASK sent for agent "
                         + std::to_string(agent_id) + "\"}\n");
    } else if (cmd.action == "VFS_READ") {
        std::string vfs_path = cmd.arg.empty() ? "/" : cmd.arg;
        task = std::make_shared<AgentTask>(
            0, 0, TaskStatus::READY,
            "", TaskType::VFS_CALL, "READ", vfs_path, fd);
    } else if (cmd.action == "SNAPSHOT") {
        task = std::make_shared<AgentTask>(
            agent_id, 0, TaskStatus::READY,
            "SNAPSHOT", TaskType::PROCESS_CTRL, "SNAPSHOT", "", fd);
        task->tool_name = "SNAPSHOT";
    } else if (cmd.action == "RESTORE") {
        task = std::make_shared<AgentTask>(
            agent_id, 0, TaskStatus::READY,
            "RESTORE", TaskType::PROCESS_CTRL, "RESTORE", "", fd);
        task->tool_name = "RESTORE";
    } else {
        task = std::make_shared<AgentTask>(
            0, 0, TaskStatus::READY,
            original_text, TaskType::LLM_CHAT, "", original_text, fd);
    }

    if (task) {
        submit_fn_(task);
    }
}

void SyscallServer::forward_to_remote(int original_fd, int caller_id, const std::string& payload) {
    std::printf("[RemoteFwd] Agent %d >= 2000, forwarding to remote %s:%u\n",
                caller_id, remote_host_.c_str(), remote_port_);

    int remote_fd = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (remote_fd < 0) {
        std::printf("[RemoteFwd] socket() failed: %s\n", std::strerror(errno));
        enqueue_response(original_fd,
            "{\"status\":\"error\",\"message\":\"Remote forward: socket creation failed\"}\n");
        return;
    }

    int nodelay = 1;
    setsockopt(remote_fd, IPPROTO_TCP, TCP_NODELAY, &nodelay, sizeof(nodelay));

    struct timeval tv{};
    tv.tv_sec = REMOTE_TIMEOUT_SEC;
    tv.tv_usec = 0;
    setsockopt(remote_fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
    setsockopt(remote_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    sockaddr_in remote_addr{};
    remote_addr.sin_family = AF_INET;
    remote_addr.sin_port = htons(remote_port_);
    inet_pton(AF_INET, remote_host_.c_str(), &remote_addr.sin_addr);

    int ret = connect(remote_fd, reinterpret_cast<sockaddr*>(&remote_addr), sizeof(remote_addr));
    if (ret < 0 && errno != EINPROGRESS) {
        std::printf("[RemoteFwd] connect() failed: %s\n", std::strerror(errno));
        close(remote_fd);
        enqueue_response(original_fd,
            "{\"status\":\"error\",\"message\":\"Remote forward: connect failed to " +
            remote_host_ + ":" + std::to_string(remote_port_) + "\"}\n");
        return;
    }

    RemoteForward fwd;
    fwd.original_fd = original_fd;
    fwd.caller_id = caller_id;
    fwd.connect_time = std::chrono::steady_clock::now();
    fwd.connected = (ret == 0);
    fwd.write_buf = payload + "\n";
    remote_fwd_[remote_fd] = std::move(fwd);

    epoll_event ev{};
    if (ret == 0) {
        ev.events = EPOLLIN | EPOLLRDHUP;
        ssize_t n = ::write(remote_fd, remote_fwd_[remote_fd].write_buf.data(),
                            remote_fwd_[remote_fd].write_buf.size());
        if (n > 0) {
            remote_fwd_[remote_fd].write_buf.erase(0, static_cast<size_t>(n));
        }
    } else {
        ev.events = EPOLLIN | EPOLLOUT | EPOLLRDHUP;
    }
    ev.data.fd = remote_fd;

    if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, remote_fd, &ev) < 0) {
        std::printf("[RemoteFwd] epoll_ctl(ADD remote_fd=%d) failed: %s\n",
                    remote_fd, std::strerror(errno));
        close(remote_fd);
        remote_fwd_.erase(remote_fd);
        enqueue_response(original_fd,
            "{\"status\":\"error\",\"message\":\"Remote forward: epoll register failed\"}\n");
        return;
    }

    std::printf("[RemoteFwd] Registered remote_fd=%d in epoll | caller=%d | target=%s:%u\n",
                remote_fd, caller_id, remote_host_.c_str(), remote_port_);
}

void SyscallServer::handle_remote_response(int remote_fd) {
    auto it = remote_fwd_.find(remote_fd);
    if (it == remote_fwd_.end()) {
        close_remote(remote_fd);
        return;
    }

    char buf[8192];
    std::string response;
    while (true) {
        ssize_t n = ::read(remote_fd, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) break;
            close_remote(remote_fd);
            return;
        }
        if (n == 0) {
            int original_fd = it->second.original_fd;
            int caller_id = it->second.caller_id;

            if (response.empty()) {
                response = "{\"status\":\"error\",\"message\":\"Remote node closed connection with empty response\"}";
            }

            std::printf("[RemoteFwd] Remote fd=%d closed | caller=%d | response=%zu bytes\n",
                        remote_fd, caller_id, response.size());

            close_remote(remote_fd);

            if (!response.empty() && response.back() != '\n') {
                response += '\n';
            }
            enqueue_response(original_fd, response);
            return;
        }
        response.append(buf, static_cast<size_t>(n));
    }

    if (!response.empty()) {
        size_t newline = response.find('\n');
        if (newline != std::string::npos) {
            std::string complete = response.substr(0, newline + 1);
            int original_fd = it->second.original_fd;
            int caller_id = it->second.caller_id;

            std::printf("[RemoteFwd] Complete response from remote | caller=%d | %zu bytes\n",
                        caller_id, complete.size());

            close_remote(remote_fd);
            enqueue_response(original_fd, complete);
        }
    }
}

void SyscallServer::check_remote_timeouts() {
    auto now = std::chrono::steady_clock::now();
    std::vector<int> timed_out;

    for (auto& [remote_fd, fwd] : remote_fwd_) {
        auto elapsed = std::chrono::duration_cast<std::chrono::seconds>(
            now - fwd.connect_time).count();
        if (elapsed > REMOTE_TIMEOUT_SEC) {
            std::printf("[RemoteFwd] TIMEOUT | remote_fd=%d | caller=%d | elapsed=%lds > %ds\n",
                        remote_fd, fwd.caller_id, elapsed, REMOTE_TIMEOUT_SEC);
            timed_out.push_back(remote_fd);
        }
    }

    for (int fd : timed_out) {
        auto it = remote_fwd_.find(fd);
        if (it != remote_fwd_.end()) {
            enqueue_response(it->second.original_fd,
                "{\"status\":\"error\",\"message\":\"Remote forward timed out (" +
                std::to_string(REMOTE_TIMEOUT_SEC) + "s)\"}\n");
        }
        close_remote(fd);
    }
}

void SyscallServer::close_remote(int remote_fd) {
    auto it = remote_fwd_.find(remote_fd);
    if (it != remote_fwd_.end()) {
        std::printf("[RemoteFwd] Closing remote_fd=%d | caller=%d\n",
                    remote_fd, it->second.caller_id);
        remote_fwd_.erase(it);
    }

    epoll_ctl(epoll_fd_, EPOLL_CTL_DEL, remote_fd, nullptr);
    close(remote_fd);
}

} // namespace aios

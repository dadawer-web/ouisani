#pragma once

#include "aios/agent_task.h"

#include <nlohmann/json.hpp>

#include <atomic>
#include <functional>
#include <list>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace aios {

struct McpSseClient {
    int fd;
    std::string client_id;
};

class McpServer {
public:
    using SubmitTaskFn = std::function<void(std::shared_ptr<AgentTask>)>;
    using SubmitLlmFn = std::function<void(std::shared_ptr<AgentTask>)>;

    McpServer(uint16_t port,
              SubmitTaskFn submit_fn,
              SubmitLlmFn submit_llm_fn);

    ~McpServer();

    McpServer(const McpServer&) = delete;
    McpServer& operator=(const McpServer&) = delete;

    void start();
    void shutdown();

    std::string handle_message(const std::string& json_body);

    void register_sse_client(int fd, const std::string& client_id);
    void unregister_sse_client(const std::string& client_id);
    void broadcast_sse_event(const std::string& event_type, const std::string& data);
    size_t sse_client_count() const;

private:
    void accept_loop();
    void handle_client(int client_fd);
    std::string process_request(const std::string& json_line);

    std::string handle_initialize(int id);
    std::string handle_initialized(int id);
    std::string handle_tools_list(int id);
    std::string handle_tools_call(int id, const std::string& params_str);
    std::string handle_resources_list(int id);
    std::string handle_resources_read(int id, const std::string& params_str);
    std::string handle_prompts_list(int id);
    std::string handle_prompts_get(int id, const std::string& params_str);
    std::string handle_ping(int id);

    nlohmann::json make_response(int id, const nlohmann::json& result);
    nlohmann::json make_error(int id, int code, const std::string& message);

    uint16_t port_;
    SubmitTaskFn submit_fn_;
    SubmitLlmFn submit_llm_fn_;
    int listen_fd_{-1};
    std::thread accept_thread_;
    std::atomic<bool> running_{false};

    std::list<McpSseClient> sse_clients_;
    mutable std::mutex sse_mutex_;
    std::atomic<int> next_event_id_{0};
};

} // namespace aios

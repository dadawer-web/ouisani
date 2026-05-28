#pragma once

#include "aios/agent_task.h"

#include <atomic>
#include <functional>
#include <memory>
#include <string>
#include <thread>

namespace aios {

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

private:
    void accept_loop();
    void handle_client(int client_fd);
    std::string process_request(const std::string& json_line);
    std::string handle_initialize(int id);
    std::string handle_tools_list(int id);
    std::string handle_tools_call(int id, const std::string& params_str);

    uint16_t port_;
    SubmitTaskFn submit_fn_;
    SubmitLlmFn submit_llm_fn_;
    int listen_fd_{-1};
    std::thread accept_thread_;
    std::atomic<bool> running_{false};
};

} // namespace aios

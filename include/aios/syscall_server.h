#pragma once

#include "aios/agent_task.h"
#include "aios/instruction_decoder.h"

#include <nlohmann/json.hpp>

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <queue>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace aios {

class ThreadPool;
class SyscallServer;

struct PendingResponse {
    int fd;
    std::string data;
};

class ISyscallHandler {
public:
    virtual ~ISyscallHandler() = default;
    virtual void handle(int fd, int caller_id, const nlohmann::json& req, SyscallServer* server) = 0;
};

class SyscallServer {
public:
    using SubmitTaskFn = std::function<void(std::shared_ptr<AgentTask>)>;
    using CancelTaskFn = std::function<void(int agent_id)>;
    using PingHeartbeatFn = std::function<void(int agent_id)>;
    using SubmitLlmFn = std::function<void(std::shared_ptr<AgentTask>)>;

    SyscallServer(SubmitTaskFn submit_fn,
                  CancelTaskFn cancel_fn,
                  PingHeartbeatFn ping_fn,
                  const std::string& host = "0.0.0.0",
                  uint16_t port = 8080);

    void set_submit_llm_fn(SubmitLlmFn fn);
    void register_handler(const std::string& name, std::shared_ptr<ISyscallHandler> handler);
    ~SyscallServer();

    SyscallServer(const SyscallServer&) = delete;
    SyscallServer& operator=(const SyscallServer&) = delete;

    void start();
    void shutdown();
    void enqueue_response(int fd, const std::string& response);

    uint16_t port() const { return port_; }

    SubmitTaskFn submit_fn() const { return submit_fn_; }
    SubmitLlmFn submit_llm_fn() const { return submit_llm_fn_; }
    CancelTaskFn cancel_fn() const { return cancel_fn_; }
    PingHeartbeatFn ping_fn() const { return ping_fn_; }

private:
    void io_loop();
    void handle_accept();
    void handle_read(int fd);
    void handle_write(int fd);
    void close_client(int fd);
    void drain_response_queue();
    void mod_fd(int fd, uint32_t events);
    void parse_and_dispatch(int fd, const std::string& line);
    void decode_and_dispatch(int fd, const std::string& natural_language);
    FlatCommand keyword_route(const std::string& input);
    void dispatch_flat(int fd, const FlatCommand& cmd, const std::string& original_text);

    SubmitTaskFn submit_fn_;
    SubmitLlmFn submit_llm_fn_;
    CancelTaskFn cancel_fn_;
    PingHeartbeatFn ping_fn_;
    std::string host_;
    uint16_t port_;

    std::unordered_map<std::string, std::shared_ptr<ISyscallHandler>> handlers_;

    int listen_fd_{-1};
    int epoll_fd_{-1};
    int event_fd_{-1};

    std::thread io_thread_;
    std::atomic<bool> running_{false};

    std::unique_ptr<ThreadPool> decode_pool_;

    struct ClientConn {
        std::string read_buf;
        std::string write_buf;
    };
    std::unordered_map<int, ClientConn> clients_;

    std::mutex response_mutex_;
    std::queue<PendingResponse> response_queue_;

    static constexpr int MAX_EVENTS = 64;
    static constexpr size_t READ_BUF_SIZE = 4096;
};

} // namespace aios

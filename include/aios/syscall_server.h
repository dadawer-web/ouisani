#pragma once

#include "aios/syscall_handler.h"

#include <atomic>
#include <memory>
#include <string>
#include <thread>
#include <unordered_map>

namespace aios {

class SyscallServer {
public:
    SyscallServer(std::shared_ptr<SyscallHandler> handler,
                  const std::string& host = "0.0.0.0",
                  uint16_t port = 8080);
    ~SyscallServer();

    SyscallServer(const SyscallServer&) = delete;
    SyscallServer& operator=(const SyscallServer&) = delete;

    void start();
    void shutdown();

    uint16_t port() const { return port_; }

private:
    void server_loop();
    void handle_accept();
    void handle_client_data(int fd);
    void close_client(int fd);
    void send_response(int fd, const std::string& response);

    std::shared_ptr<SyscallHandler> handler_;
    std::string host_;
    uint16_t port_;

    int listen_fd_{-1};
    int epoll_fd_{-1};

    std::thread server_thread_;
    std::atomic<bool> running_{false};

    struct ClientConn {
        std::string read_buf;
    };
    std::unordered_map<int, ClientConn> clients_;

    static constexpr int MAX_EVENTS = 64;
    static constexpr size_t READ_BUF_SIZE = 4096;
};

} // namespace aios

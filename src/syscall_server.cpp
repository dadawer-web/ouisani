#include "aios/syscall_server.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <sys/epoll.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <string>

namespace aios {

SyscallServer::SyscallServer(std::shared_ptr<SyscallHandler> handler,
                             const std::string& host,
                             uint16_t port)
    : handler_(std::move(handler))
    , host_(host)
    , port_(port)
{}

SyscallServer::~SyscallServer() {
    shutdown();
}

void SyscallServer::start() {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) {
        return;
    }

    server_thread_ = std::thread(&SyscallServer::server_loop, this);

    std::printf("[SyscallServer] Thread started\n");
}

void SyscallServer::shutdown() {
    if (!running_.load()) return;
    running_.store(false);

    if (listen_fd_ >= 0) {
        ::shutdown(listen_fd_, SHUT_RDWR);
        close(listen_fd_);
        listen_fd_ = -1;
    }

    if (server_thread_.joinable()) {
        server_thread_.join();
    }

    for (auto& [fd, conn] : clients_) {
        close(fd);
    }
    clients_.clear();

    if (epoll_fd_ >= 0) {
        close(epoll_fd_);
        epoll_fd_ = -1;
    }

    std::printf("[SyscallServer] Shutdown complete\n");
}

void SyscallServer::server_loop() {
    listen_fd_ = socket(AF_INET, SOCK_STREAM | SOCK_NONBLOCK, 0);
    if (listen_fd_ < 0) {
        std::printf("[SyscallServer] socket() failed: %s\n", std::strerror(errno));
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
        std::printf("[SyscallServer] bind() failed: %s\n", std::strerror(errno));
        close(listen_fd_);
        listen_fd_ = -1;
        running_.store(false);
        return;
    }

    if (listen(listen_fd_, SOMAXCONN) < 0) {
        std::printf("[SyscallServer] listen() failed: %s\n", std::strerror(errno));
        close(listen_fd_);
        listen_fd_ = -1;
        running_.store(false);
        return;
    }

    epoll_fd_ = epoll_create1(0);
    if (epoll_fd_ < 0) {
        std::printf("[SyscallServer] epoll_create1() failed: %s\n", std::strerror(errno));
        close(listen_fd_);
        listen_fd_ = -1;
        running_.store(false);
        return;
    }

    epoll_event ev{};
    ev.events = EPOLLIN;
    ev.data.fd = listen_fd_;
    if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, listen_fd_, &ev) < 0) {
        std::printf("[SyscallServer] epoll_ctl(ADD listen_fd) failed: %s\n", std::strerror(errno));
        close(epoll_fd_);
        close(listen_fd_);
        epoll_fd_ = -1;
        listen_fd_ = -1;
        running_.store(false);
        return;
    }

    std::printf("[SyscallServer] Listening on %s:%u (epoll)\n", host_.c_str(), port_);

    epoll_event events[MAX_EVENTS];

    while (running_.load()) {
        int nfds = epoll_wait(epoll_fd_, events, MAX_EVENTS, 500);
        if (nfds < 0) {
            if (errno == EINTR) continue;
            std::printf("[SyscallServer] epoll_wait error: %s\n", std::strerror(errno));
            break;
        }

        for (int i = 0; i < nfds; ++i) {
            if (events[i].data.fd == listen_fd_) {
                handle_accept();
            } else {
                int fd = events[i].data.fd;
                if (events[i].events & (EPOLLERR | EPOLLHUP)) {
                    close_client(fd);
                } else if (events[i].events & EPOLLIN) {
                    handle_client_data(fd);
                }
            }
        }
    }

    running_.store(false);
}

void SyscallServer::handle_accept() {
    sockaddr_in client_addr{};
    socklen_t addr_len = sizeof(client_addr);
    int client_fd = accept4(listen_fd_,
                            reinterpret_cast<sockaddr*>(&client_addr),
                            &addr_len,
                            SOCK_NONBLOCK);
    if (client_fd < 0) {
        if (errno != EAGAIN && errno != EWOULDBLOCK) {
            std::printf("[SyscallServer] accept() failed: %s\n", std::strerror(errno));
        }
        return;
    }

    char ip_str[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &client_addr.sin_addr, ip_str, sizeof(ip_str));
    std::printf("[SyscallServer] New connection from %s:%d (fd=%d)\n",
                ip_str, ntohs(client_addr.sin_port), client_fd);

    epoll_event ev{};
    ev.events = EPOLLIN | EPOLLET;
    ev.data.fd = client_fd;
    if (epoll_ctl(epoll_fd_, EPOLL_CTL_ADD, client_fd, &ev) < 0) {
        std::printf("[SyscallServer] epoll_ctl(ADD client) failed: %s\n", std::strerror(errno));
        close(client_fd);
        return;
    }

    clients_[client_fd] = ClientConn{};
}

void SyscallServer::handle_client_data(int fd) {
    char buf[READ_BUF_SIZE];
    while (true) {
        ssize_t n = read(fd, buf, sizeof(buf));
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

        std::printf("[SyscallServer] Received on fd=%d: %s\n", fd, line.c_str());

        SyscallResponse resp = handler_->handle(line);
        send_response(fd, resp.to_json() + "\n");
    }

    data.erase(0, pos);
}

void SyscallServer::close_client(int fd) {
    epoll_ctl(epoll_fd_, EPOLL_CTL_DEL, fd, nullptr);
    close(fd);
    clients_.erase(fd);
    std::printf("[SyscallServer] Connection closed (fd=%d)\n", fd);
}

void SyscallServer::send_response(int fd, const std::string& response) {
    size_t total = response.size();
    size_t sent = 0;
    while (sent < total) {
        ssize_t n = write(fd, response.data() + sent, total - sent);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                std::this_thread::sleep_for(std::chrono::milliseconds(1));
                continue;
            }
            std::printf("[SyscallServer] write() failed on fd=%d: %s\n", fd, std::strerror(errno));
            close_client(fd);
            return;
        }
        sent += static_cast<size_t>(n);
    }
}

} // namespace aios

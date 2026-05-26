#include "aios/syscall_server.h"
#include "aios/agent_registry.h"
#include "aios/instruction_decoder.h"
#include "aios/process_manager.h"
#include "aios/thread_pool.h"
#include "aios/vfs_manager.h"
#include "aios/wasm_node.h"

#include <algorithm>
#include <arpa/inet.h>
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
{}

SyscallServer::~SyscallServer() {
    shutdown();
}

void SyscallServer::set_submit_llm_fn(SubmitLlmFn fn) {
    submit_llm_fn_ = std::move(fn);
}

void SyscallServer::start() {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) {
        return;
    }
    decode_pool_->start();
    io_thread_ = std::thread(&SyscallServer::io_loop, this);
    std::printf("[Reactor] I/O thread started\n");
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
                close_client(fd);
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
    } catch (const nlohmann::json::parse_error&) {
        std::printf("[parse_and_dispatch] NOT JSON, routing to decode_and_dispatch\n");
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

    std::string syscall_name = req["syscall"].get<std::string>();
    int agent_id = req.value("agent_id", -1);
    int caller_id = req.value("caller_id", agent_id);

    auto& registry = AgentRegistry::instance();
    registry.ensure_registered(caller_id);

    auto& proc_mgr = ProcessManager::instance();
    proc_mgr.register_process(caller_id,
        registry.get_level(caller_id) == PrivilegeLevel::RING_0 ? 0 : 3);
    proc_mgr.record_syscall(caller_id);

    if (ping_fn_ && caller_id > 0) {
        ping_fn_(caller_id);
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
    } else if (syscall_name == "VFS_CALL") {
        std::string action = req.value("action", "");
        std::string vfs_path = req.value("path", "");
        std::string payload = req.value("payload", "");

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
                    enqueue_response(fd, err.dump() + "\n");
                    return;
                }
            }
        }

        if (action.empty()) {
            enqueue_response(fd, "{\"status\":\"error\",\"message\":\"VFS_CALL requires 'action'\"}\n");
            return;
        }

        if (action != "COMPILE_AND_EXECUTE" && action != "PIPE_EXECUTE" && vfs_path.empty()) {
            enqueue_response(fd, "{\"status\":\"error\",\"message\":\"VFS_CALL requires 'path' for this action\"}\n");
            return;
        }

        if (action == "LIST") {
            auto& vfs = VfsManager::instance();
            std::string listing = vfs.list_dir(vfs_path);
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["data"] = listing;
            enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "TREE") {
            auto& vfs = VfsManager::instance();
            std::string tree_str = vfs.tree(vfs_path);
            nlohmann::json resp;
            resp["status"] = "ok";
            resp["data"] = tree_str;
            enqueue_response(fd, resp.dump() + "\n");
            return;
        }

        if (action == "COMPILE_ONLY") {
            if (vfs_path.empty()) {
                nlohmann::json err;
                err["status"] = "error";
                err["message"] = "COMPILE_ONLY requires 'path' (target .wasm save path)";
                enqueue_response(fd, err.dump() + "\n");
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
                enqueue_response(fd, err.dump() + "\n");
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
    } else if (syscall_name == "LLM_INFERENCE") {
        int priority = req.value("priority", 0);
        std::string payload = req.value("payload", req.value("data", ""));

        auto task = std::make_shared<AgentTask>(
            caller_id, priority, TaskStatus::READY,
            payload, TaskType::LLM_INFERENCE, "", "", fd
        );

        task->set_response_callback([this](int cb_fd, const std::string& res) {
            enqueue_response(cb_fd, res);
        });

        std::printf("[Reactor] LLM_INFERENCE | agent=%d | priority=%d | payload=%zu bytes\n",
                    caller_id, priority, payload.size());

        if (submit_llm_fn_) {
            submit_llm_fn_(std::move(task));
        } else if (submit_fn_) {
            submit_fn_(std::move(task));
        }
        return;
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

} // namespace aios

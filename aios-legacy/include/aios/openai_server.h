#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>

namespace aios {

class OpenAiServer {
public:
    explicit OpenAiServer(uint16_t port = 8082);

    void start();
    void shutdown();

    OpenAiServer(const OpenAiServer&) = delete;
    OpenAiServer& operator=(const OpenAiServer&) = delete;

private:
    void accept_loop();
    void handle_client(int fd);
    std::string handle_chat_completion(const std::string& body);

    uint16_t port_;
    std::atomic<bool> running_{false};
    int listen_fd_ = -1;
};

} // namespace aios

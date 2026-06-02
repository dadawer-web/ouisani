#include "aios/instruction_decoder.h"

#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <string>

namespace aios {

bool InstructionDecoder::Initialize(const std::string& uds_path) {
    if (ready_) {
        std::printf("[Decoder] Already initialized, skipping.\n");
        return true;
    }

    uds_path_ = uds_path;

    int test_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (test_fd < 0) {
        std::printf("[Decoder] FATAL: Cannot create socket: %s\n", std::strerror(errno));
        return false;
    }

    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, uds_path_.c_str(), sizeof(addr.sun_path) - 1);

    if (connect(test_fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::printf("[Decoder] WARNING: aios_decoder daemon not reachable at %s\n", uds_path_.c_str());
        std::printf("[Decoder] Start it first: ./build/aios_decoder\n");
        close(test_fd);
        ready_ = false;
        return false;
    }

    close(test_fd);
    ready_ = true;
    std::printf("[Decoder] UDS client READY | daemon=%s\n", uds_path_.c_str());
    return true;
}

std::string InstructionDecoder::Decode(const std::string& user_intent) {
    if (!ready_) {
        std::printf("[Decoder] Not initialized, returning fallback\n");
        return "SYS_CMD EXECUTE_TASK fallback EOF";
    }

    std::lock_guard<std::mutex> lock(decode_mutex_);

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        std::printf("[Decoder] socket() failed: %s\n", std::strerror(errno));
        return "SYS_CMD EXECUTE_TASK fallback EOF";
    }

    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, uds_path_.c_str(), sizeof(addr.sun_path) - 1);

    if (connect(fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::printf("[Decoder] connect() failed: %s\n", std::strerror(errno));
        close(fd);
        return "SYS_CMD EXECUTE_TASK fallback EOF";
    }

    ssize_t sent = send(fd, user_intent.c_str(), user_intent.size(), 0);
    if (sent < 0) {
        std::printf("[Decoder] send() failed: %s\n", std::strerror(errno));
        close(fd);
        return "SYS_CMD EXECUTE_TASK fallback EOF";
    }

    shutdown(fd, SHUT_WR);

    char buf[4096];
    std::string result;
    result.reserve(256);

    while (true) {
        ssize_t n = recv(fd, buf, sizeof(buf), 0);
        if (n <= 0) break;
        result.append(buf, static_cast<size_t>(n));
    }

    close(fd);

    std::printf("[Decoder] UDS decode complete | output_len=%zu\n", result.size());
    return result;
}

FlatCommand InstructionDecoder::ParseFlatCommand(const std::string& flat) {
    FlatCommand cmd;

    if (flat.size() < 8 || flat.substr(0, 8) != "SYS_CMD ") {
        return cmd;
    }

    if (flat.size() < 4 || flat.substr(flat.size() - 3) != "EOF") {
        return cmd;
    }

    std::string inner = flat.substr(8, flat.size() - 8);

    auto eof_pos = inner.rfind(" EOF");
    if (eof_pos != std::string::npos) {
        inner = inner.substr(0, eof_pos);
    }

    std::stringstream ss(inner);
    if (!(ss >> cmd.action)) {
        return cmd;
    }

    std::string token;
    while (ss >> token) {
        if (!cmd.arg.empty()) cmd.arg += " ";
        cmd.arg += token;
    }

    cmd.valid = !cmd.action.empty();
    return cmd;
}

} // namespace aios

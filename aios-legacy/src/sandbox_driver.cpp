#include "aios/sandbox_driver.h"

#include <httplib.h>
#include <nlohmann/json.hpp>

#include <cstdio>
#include <string>

namespace aios {

SandboxDriver::SandboxDriver(const std::string& sandbox_url, int timeout_seconds)
    : sandbox_url_(sandbox_url)
    , timeout_seconds_(timeout_seconds)
{}

std::future<std::string> SandboxDriver::execute_async(const std::string& code) {
    return std::async(std::launch::async, [this, code]() {
        return execute(code);
    });
}

std::string SandboxDriver::execute(const std::string& code) {
    std::printf("[SandboxDriver] Sending code to %s/execute (%zu bytes)\n",
                sandbox_url_.c_str(), code.size());

    std::string host = sandbox_url_;
    int port = 5000;

    auto proto_pos = sandbox_url_.find("://");
    if (proto_pos != std::string::npos) {
        host = sandbox_url_.substr(proto_pos + 3);
    }
    auto port_pos = host.rfind(':');
    if (port_pos != std::string::npos) {
        port = std::stoi(host.substr(port_pos + 1));
        host = host.substr(0, port_pos);
    }

    httplib::Client cli(host, port);
    cli.set_connection_timeout(5);
    cli.set_read_timeout(timeout_seconds_);
    cli.set_write_timeout(5);

    nlohmann::json req_json;
    req_json["code"] = code;

    auto res = cli.Post("/execute", req_json.dump(), "application/json");

    if (!res) {
        auto err = httplib::to_string(res.error());
        std::printf("[SandboxDriver] HTTP request failed: %s\n", err.c_str());
        return "[SANDBOX ERROR] Request failed: " + err;
    }

    if (res->status != 200) {
        std::printf("[SandboxDriver] HTTP %d: %s\n", res->status, res->body.c_str());
        return "[SANDBOX ERROR] HTTP " + std::to_string(res->status);
    }

    try {
        auto resp_json = nlohmann::json::parse(res->body);
        std::string stdout_str = resp_json.value("stdout", "");
        std::string stderr_str = resp_json.value("stderr", "");

        if (!stdout_str.empty() && stdout_str.back() == '\n') {
            stdout_str.pop_back();
        }

        std::string result;
        if (!stdout_str.empty()) {
            result = stdout_str;
        }
        if (!stderr_str.empty()) {
            if (!result.empty()) result += "\n";
            result += "[STDERR] " + stderr_str;
        }

        std::printf("[SandboxDriver] Execution complete (stdout=%zuB, stderr=%zuB)\n",
                    stdout_str.size(), stderr_str.size());

        return result;
    } catch (const std::exception& e) {
        std::printf("[SandboxDriver] JSON parse error: %s\n", e.what());
        return "[SANDBOX ERROR] Invalid JSON response";
    }
}

} // namespace aios

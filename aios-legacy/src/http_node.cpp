#include "aios/http_node.h"
#include "aios/event_bus.h"

#include <algorithm>
#include <cstdio>
#include <nlohmann/json.hpp>
#include <regex>
#include <string>

namespace aios {

HttpNode::HttpNode(const std::string& path)
    : VfsNode(VfsNodeType::DEVICE, path) {}

bool HttpNode::is_ssrf_target(const std::string& url) const {
    static const std::vector<std::string> blocked = {
        "localhost",
        "127.0.0.1",
        "0.0.0.0",
        "0177.",
        "0x7f",
        "::1",
        "192.168.",
        "10.",
        "172.16.",
        "172.17.",
        "172.18.",
        "172.19.",
        "172.20.",
        "172.21.",
        "172.22.",
        "172.23.",
        "172.24.",
        "172.25.",
        "172.26.",
        "172.27.",
        "172.28.",
        "172.29.",
        "172.30.",
        "172.31.",
        "file://",
        "ftp://",
        "dict://",
        "gopher://",
    };

    std::string lower = url;
    std::transform(lower.begin(), lower.end(), lower.begin(),
                   [](unsigned char c) { return std::tolower(c); });

    for (const auto& pattern : blocked) {
        if (lower.find(pattern) != std::string::npos) {
            return true;
        }
    }

    std::regex ip_regex(R"((?:https?://)?(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}))",
                        std::regex::icase);
    std::smatch match;
    if (std::regex_search(lower, match, ip_regex)) {
        std::string ip = match[1].str();
        int octet0 = std::stoi(ip.substr(0, ip.find('.')));
        if (octet0 == 0 || octet0 == 127 || octet0 == 10 ||
            (octet0 == 192 && lower.find("192.168.") != std::string::npos) ||
            (octet0 == 172)) {
            return true;
        }
    }

    return false;
}

std::string HttpNode::escape_shell(const std::string& input) const {
    std::string safe;
    for (char c : input) {
        if (c == '\'') {
            safe += "'\\''";
        } else if (c == '`' || c == '$' || c == '!' || c == '"' ||
                   c == '\\' || c == ';' || c == '|' || c == '&' ||
                   c == '<' || c == '>' || c == '(' || c == ')') {
            continue;
        } else {
            safe += c;
        }
    }
    return safe;
}

std::string HttpNode::execute_curl(const std::string& method,
                                    const std::string& url,
                                    int timeout_sec) const {
    std::string safe_url = escape_shell(url);
    std::string upper_method = method;
    std::transform(upper_method.begin(), upper_method.end(),
                   upper_method.begin(), ::toupper);

    std::string cmd = "curl -s --max-time " + std::to_string(timeout_sec) +
                      " -X " + upper_method + " '" + safe_url + "'";

    std::printf("[HttpNode] Executing: %s\n", cmd.c_str());

    std::string output;
    char buffer[4096];
    FILE* pipe = popen(cmd.c_str(), "r");
    if (!pipe) {
        return nlohmann::json({{"status", "error"},
                               {"message", "popen() failed"}}).dump();
    }
    while (fgets(buffer, sizeof(buffer), pipe)) {
        output += buffer;
    }
    int status = pclose(pipe);

    if (status != 0) {
        return nlohmann::json({{"status", "error"},
                               {"message", "curl exited with code " + std::to_string(status)},
                               {"url", url}}).dump();
    }

    return output;
}

bool HttpNode::write(const std::string& data) {
    std::lock_guard<std::mutex> lock(mutex_);

    nlohmann::json req;
    try {
        req = nlohmann::json::parse(data);
    } catch (const nlohmann::json::parse_error& e) {
        last_response_ = nlohmann::json({{"status", "error"},
                                         {"message", std::string("Invalid JSON: ") + e.what()}}).dump();
        return false;
    }

    std::string method = req.value("method", "GET");
    std::string url = req.value("url", "");

    if (url.empty()) {
        last_response_ = nlohmann::json({{"status", "error"},
                                         {"message", "Missing 'url' field"}}).dump();
        return false;
    }

    if (is_ssrf_target(url)) {
        std::printf("[HttpNode] 🚫 SSRF BLOCKED: %s\n", url.c_str());
        last_response_ = nlohmann::json({
            {"status", "error"},
            {"message", "[Ring 0 Firewall] 触发 SSRF 拦截！禁止 Agent 访问宿主机内网或本地回环地址！"},
            {"url", url}
        }).dump();
        return false;
    }

    EventBus::instance().publish(EventType::VFS_WRITE, "HttpNode",
        "Agent 发起了外网请求: " + url);

    std::string response = execute_curl(method, url);

    last_response_ = nlohmann::json({
        {"status", "ok"},
        {"url", url},
        {"method", method},
        {"response", response}
    }).dump();

    std::printf("[HttpNode] ✅ %s %s -> %zu bytes\n",
                method.c_str(), url.c_str(), response.size());

    return true;
}

std::string HttpNode::read() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return last_response_;
}

} // namespace aios

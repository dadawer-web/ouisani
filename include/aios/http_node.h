#pragma once

#include "aios/vfs_node.h"

#include <mutex>
#include <string>

namespace aios {

class HttpNode : public VfsNode {
public:
    explicit HttpNode(const std::string& path);

    bool write(const std::string& data) override;
    std::string read() const override;

private:
    bool is_ssrf_target(const std::string& url) const;
    std::string escape_shell(const std::string& input) const;
    std::string execute_curl(const std::string& method, const std::string& url, int timeout_sec = 30) const;

    mutable std::mutex mutex_;
    std::string last_response_;
};

} // namespace aios

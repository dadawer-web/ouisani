#pragma once

#include "aios/vfs_node.h"

#include <condition_variable>
#include <deque>
#include <mutex>
#include <string>

namespace aios {

class WebhookNode : public VfsNode {
public:
    explicit WebhookNode(const std::string& path, int owner_uid = 0, int permissions = 0644);

    std::string read() const override;
    bool write(const std::string& data) override;

    void write_event(const std::string& payload);

    std::string read_nonblocking() const;
    size_t queue_size() const;

private:
    mutable std::mutex mu_;
    mutable std::condition_variable cv_;
    mutable std::deque<std::string> event_queue_;
};

} // namespace aios

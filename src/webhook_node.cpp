#include "aios/webhook_node.h"
#include "aios/event_bus.h"

#include <cstdio>

namespace aios {

WebhookNode::WebhookNode(const std::string& path, int owner_uid, int permissions)
    : VfsNode(VfsNodeType::WEBHOOK, path, owner_uid, permissions) {}

std::string WebhookNode::read() const {
    std::unique_lock<std::mutex> lock(mu_);
    cv_.wait(lock, [this]() { return !event_queue_.empty(); });

    std::string event = std::move(event_queue_.front());
    event_queue_.pop_front();

    std::printf("[WebhookNode] READ %s | dequeued | queue_size=%zu | event=\"%s\"\n",
                path_.c_str(), event_queue_.size(),
                event.size() > 60 ? (event.substr(0, 60) + "...").c_str() : event.c_str());

    return event;
}

bool WebhookNode::write(const std::string& data) {
    write_event(data);
    return true;
}

void WebhookNode::write_event(const std::string& payload) {
    {
        std::lock_guard<std::mutex> lock(mu_);
        event_queue_.push_back(payload);
    }

    std::printf("[WebhookNode] WRITE_EVENT %s | enqueued | queue_size=%zu | payload=\"%s\"\n",
                path_.c_str(), event_queue_.size(),
                payload.size() > 60 ? (payload.substr(0, 60) + "...").c_str() : payload.c_str());

    EventBus::instance().publish(EventType::VFS_WRITE, "WebhookNode",
        path_ + " | IRQ event queued | queue_size=" + std::to_string(event_queue_.size()));

    cv_.notify_one();
}

std::string WebhookNode::read_nonblocking() const {
    std::lock_guard<std::mutex> lock(mu_);
    if (event_queue_.empty()) return "";

    std::string event = std::move(event_queue_.front());
    event_queue_.pop_front();
    return event;
}

size_t WebhookNode::queue_size() const {
    std::lock_guard<std::mutex> lock(mu_);
    return event_queue_.size();
}

} // namespace aios

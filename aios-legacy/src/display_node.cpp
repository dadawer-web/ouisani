#include "aios/display_node.h"
#include "aios/event_bus.h"

#include <cstdio>
#include <nlohmann/json.hpp>

namespace aios {

DisplayNode::DisplayNode(const std::string& path, int owner_uid, int permissions)
    : VfsNode(VfsNodeType::DISPLAY, path, owner_uid, permissions) {}

std::string DisplayNode::read() const {
    std::lock_guard<std::mutex> lock(mu_);
    if (ring_.empty()) return "";

    nlohmann::json arr = nlohmann::json::array();
    for (const auto& frame : ring_) {
        arr.push_back({{"index", frame.index}, {"payload", frame.json_payload}});
    }
    return arr.dump();
}

bool DisplayNode::write(const std::string& data) {
    if (data.empty()) return false;

    {
        std::lock_guard<std::mutex> lock(mu_);
        int idx = write_index_++;
        ring_.push_back({idx, data});

        while (ring_.size() > RING_CAPACITY) {
            ring_.pop_front();
        }
    }

    std::printf("[DisplayNode] WRITE %s | frame #%d | ring=%zu/%zu | data=\"%s\"\n",
                path_.c_str(), write_index_ - 1, ring_.size(), RING_CAPACITY,
                data.size() > 60 ? (data.substr(0, 60) + "...").c_str() : data.c_str());

    EventBus::instance().publish(EventType::VFS_WRITE, "DisplayNode",
        path_ + " | frame #" + std::to_string(write_index_ - 1) +
        " | ring=" + std::to_string(ring_.size()));

    cv_.notify_all();
    return true;
}

std::string DisplayNode::read_stream(int last_index) {
    std::unique_lock<std::mutex> lock(mu_);

    cv_.wait(lock, [this, last_index]() {
        return !ring_.empty() && ring_.back().index > last_index;
    });

    nlohmann::json frames = nlohmann::json::array();
    for (const auto& frame : ring_) {
        if (frame.index > last_index) {
            frames.push_back({{"index", frame.index}, {"payload", frame.json_payload}});
        }
    }

    std::printf("[DisplayNode] READ_STREAM %s | since_index=%d | new_frames=%zu\n",
                path_.c_str(), last_index, frames.size());

    return frames.dump();
}

int DisplayNode::latest_index() const {
    std::lock_guard<std::mutex> lock(mu_);
    if (ring_.empty()) return -1;
    return ring_.back().index;
}

} // namespace aios

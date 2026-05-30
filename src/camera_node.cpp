#include "aios/camera_node.h"
#include "aios/event_bus.h"

#include <chrono>
#include <cstdio>

namespace aios {

CameraNode::CameraNode(const std::string& path, int owner_uid, int permissions)
    : VfsNode(VfsNodeType::CAMERA, path, owner_uid, permissions) {}

std::string CameraNode::read() const {
    std::lock_guard<std::mutex> lock(mutex_);
    capture_count_++;

    auto now = std::chrono::system_clock::now();
    auto epoch = std::chrono::duration_cast<std::chrono::seconds>(
        now.time_since_epoch()).count();

    std::string mock_frame =
        "{\n"
        "  \"device\": \"" + path_ + "\",\n"
        "  \"capture_id\": " + std::to_string(capture_count_) + ",\n"
        "  \"timestamp\": " + std::to_string(epoch) + ",\n"
        "  \"resolution\": \"1920x1080\",\n"
        "  \"format\": \"JPEG\",\n"
        "  \"mode\": \"mock_capture\",\n"
        "  \"description\": \"A mock camera frame showing a desk with a monitor displaying AIOS kernel logs, a keyboard, and a cup of coffee.\",\n"
        "  \"base64_preview\": \"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==\"\n"
        "}\n";

    std::printf("[CameraNode] READ %s | capture #%d | ts=%lld\n",
                path_.c_str(), capture_count_, static_cast<long long>(epoch));

    EventBus::instance().publish(EventType::VFS_WRITE, "CameraNode",
        path_ + " | capture #" + std::to_string(capture_count_));

    return mock_frame;
}

bool CameraNode::write(const std::string& data) {
    std::printf("[CameraNode] WRITE %s | ignored (camera is read-only device) | %zu bytes\n",
                path_.c_str(), data.size());
    return false;
}

} // namespace aios

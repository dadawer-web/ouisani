#pragma once

#include "aios/vfs_node.h"

#include <chrono>
#include <cstdio>
#include <mutex>
#include <string>

namespace aios {

class CameraNode : public VfsNode {
public:
    explicit CameraNode(const std::string& path, int owner_uid = 0, int permissions = 0444);

    std::string read() const override;
    bool write(const std::string& data) override;

private:
    mutable std::mutex mutex_;
    mutable int capture_count_ = 0;
};

} // namespace aios

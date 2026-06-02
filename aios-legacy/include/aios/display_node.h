#pragma once

#include "aios/vfs_node.h"

#include <condition_variable>
#include <deque>
#include <mutex>
#include <string>
#include <vector>

namespace aios {

class DisplayNode : public VfsNode {
public:
    explicit DisplayNode(const std::string& path, int owner_uid = 0, int permissions = 0666);

    std::string read() const override;
    bool write(const std::string& data) override;

    std::string read_stream(int last_index);
    int latest_index() const;

private:
    static constexpr size_t RING_CAPACITY = 50;

    struct UiFrame {
        int index;
        std::string json_payload;
    };

    mutable std::mutex mu_;
    mutable std::condition_variable cv_;
    std::deque<UiFrame> ring_;
    int write_index_ = 0;
};

} // namespace aios

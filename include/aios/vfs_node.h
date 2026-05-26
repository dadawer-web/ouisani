#pragma once

#include "aios/device_driver.h"
#include "aios/memory_manager.h"

#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <memory>
#include <mutex>
#include <queue>
#include <shared_mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace aios {

enum class VfsNodeType {
    FILE,
    DIRECTORY,
    EXECUTABLE,
    DEVICE,
    PIPE,
    WASM
};

inline const char* node_type_str(VfsNodeType t) {
    switch (t) {
        case VfsNodeType::FILE:       return "FILE";
        case VfsNodeType::DIRECTORY:  return "DIR";
        case VfsNodeType::EXECUTABLE: return "EXEC";
        case VfsNodeType::DEVICE:     return "DEV";
        case VfsNodeType::PIPE:       return "PIPE";
        case VfsNodeType::WASM:       return "WASM";
    }
    return "UNKNOWN";
}

class VfsNode {
public:
    VfsNode(VfsNodeType type, const std::string& path)
        : node_type_(type), path_(path) {}
    virtual ~VfsNode() = default;

    virtual std::string read() const { return ""; }
    virtual bool write(const std::string& /*data*/) { return false; }
    virtual std::string execute(const std::string& /*payload*/) { return ""; }

    VfsNodeType node_type() const { return node_type_; }
    const std::string& path() const { return path_; }

protected:
    VfsNodeType node_type_;
    std::string path_;
};

class FileNode : public VfsNode {
public:
    FileNode(const std::string& path, const std::string& content = "")
        : VfsNode(VfsNodeType::FILE, path), content_(content) {}

    std::string read() const override {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        return content_;
    }

    bool write(const std::string& data) override {
        std::unique_lock<std::shared_mutex> lock(mutex_);
        content_ = data;
        return true;
    }

private:
    std::string content_;
    mutable std::shared_mutex mutex_;
};

class DirectoryNode : public VfsNode {
public:
    explicit DirectoryNode(const std::string& path)
        : VfsNode(VfsNodeType::DIRECTORY, path) {}

    bool add_child(const std::string& name, std::shared_ptr<VfsNode> node) {
        std::unique_lock<std::shared_mutex> lock(mutex_);
        if (children_.count(name)) return false;
        children_[name] = std::move(node);
        std::printf("[VFS] Mounted: %s/%s [%s]\n",
                    path_.c_str(), name.c_str(), node_type_str(children_[name]->node_type()));
        return true;
    }

    std::shared_ptr<VfsNode> get_child(const std::string& name) const {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        auto it = children_.find(name);
        if (it != children_.end()) return it->second;
        return nullptr;
    }

    std::vector<std::string> list_children() const {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        std::vector<std::string> names;
        for (const auto& [name, _] : children_) {
            names.push_back(name);
        }
        return names;
    }

    bool remove_child(const std::string& name) {
        std::unique_lock<std::shared_mutex> lock(mutex_);
        return children_.erase(name) > 0;
    }

    size_t child_count() const {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        return children_.size();
    }

private:
    std::unordered_map<std::string, std::shared_ptr<VfsNode>> children_;
    mutable std::shared_mutex mutex_;
};

class ExecutableNode : public VfsNode {
public:
    ExecutableNode(const std::string& path, std::shared_ptr<DeviceDriver> driver)
        : VfsNode(VfsNodeType::EXECUTABLE, path), driver_(std::move(driver)) {}

    std::string execute(const std::string& payload) override {
        if (!driver_) return "[VFS ERROR] No driver bound to executable node";
        return driver_->execute(payload);
    }

    const std::shared_ptr<DeviceDriver>& driver() const { return driver_; }

private:
    std::shared_ptr<DeviceDriver> driver_;
};

class MemoryDeviceNode : public VfsNode {
public:
    MemoryDeviceNode(const std::string& path, int agent_id,
                     std::shared_ptr<MemoryManager> mmgr)
        : VfsNode(VfsNodeType::DEVICE, path)
        , agent_id_(agent_id)
        , mmgr_(std::move(mmgr)) {}

    std::string read() const override {
        if (!mmgr_) return "[VFS ERROR] No MemoryManager bound";
        auto pages = mmgr_->read_pages(agent_id_);
        std::string result;
        for (const auto& p : pages) {
            result += "[" + p.role + "] " + p.content + "\n";
        }
        if (result.empty()) {
            result = "[VFS] Agent#" + std::to_string(agent_id_) + " has no memory pages\n";
        }
        return result;
    }

    bool write(const std::string& data) override {
        if (!mmgr_) return false;
        MemoryPage page;
        page.agent_id = agent_id_;
        page.role = "user";
        page.content = data;
        page.timestamp = static_cast<size_t>(
            std::chrono::system_clock::now().time_since_epoch().count());
        mmgr_->write_page(page);
        return true;
    }

    int agent_id() const { return agent_id_; }

private:
    int agent_id_;
    std::shared_ptr<MemoryManager> mmgr_;
};

class PipeNode : public VfsNode {
public:
    explicit PipeNode(const std::string& path)
        : VfsNode(VfsNodeType::PIPE, path) {}

    bool write(const std::string& data) override {
        {
            std::lock_guard<std::mutex> lock(mtx_);
            message_queue_.push(data);
            std::printf("[Pipe] WRITE %s | queue_size=%zu | data=\"%s\"\n",
                        path_.c_str(), message_queue_.size(),
                        data.size() > 60 ? (data.substr(0, 60) + "...").c_str() : data.c_str());
        }
        cv_.notify_one();
        return true;
    }

    std::string read_blocking() {
        std::unique_lock<std::mutex> lock(mtx_);
        std::printf("[Pipe] READ %s | blocking... (queue_size=%zu)\n",
                    path_.c_str(), message_queue_.size());
        cv_.wait(lock, [this]() { return !message_queue_.empty(); });
        std::string data = std::move(message_queue_.front());
        message_queue_.pop();
        std::printf("[Pipe] READ %s | received | queue_remaining=%zu | data=\"%s\"\n",
                    path_.c_str(), message_queue_.size(),
                    data.size() > 60 ? (data.substr(0, 60) + "...").c_str() : data.c_str());
        return data;
    }

    std::string read_nonblocking() {
        std::lock_guard<std::mutex> lock(mtx_);
        if (message_queue_.empty()) {
            return "";
        }
        std::string data = std::move(message_queue_.front());
        message_queue_.pop();
        return data;
    }

    std::string read() const override {
        return "[Pipe] Use read_blocking() or read_nonblocking() for pipe nodes";
    }

    size_t queue_size() const {
        std::lock_guard<std::mutex> lock(mtx_);
        return message_queue_.size();
    }

private:
    std::queue<std::string> message_queue_;
    mutable std::mutex mtx_;
    std::condition_variable cv_;
};

} // namespace aios

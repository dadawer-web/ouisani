#pragma once

#include "aios/device_driver.h"
#include "aios/memory_manager.h"

#include <cstdio>
#include <memory>
#include <shared_mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace aios {

enum class VfsNodeType {
    FILE,
    DIRECTORY,
    EXECUTABLE,
    DEVICE
};

inline const char* node_type_str(VfsNodeType t) {
    switch (t) {
        case VfsNodeType::FILE:       return "FILE";
        case VfsNodeType::DIRECTORY:  return "DIR";
        case VfsNodeType::EXECUTABLE: return "EXEC";
        case VfsNodeType::DEVICE:     return "DEV";
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

    int agent_id() const { return agent_id_; }

private:
    int agent_id_;
    std::shared_ptr<MemoryManager> mmgr_;
};

} // namespace aios

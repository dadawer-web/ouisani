#pragma once

#include "aios/vfs_node.h"
#include "aios/wasm_node.h"
#include "aios/memory_manager.h"

#include <cstdio>
#include <memory>
#include <shared_mutex>
#include <string>
#include <vector>

namespace aios {

class VfsManager {
public:
    static VfsManager& instance() {
        static VfsManager mgr;
        return mgr;
    }

    void init() {
        std::unique_lock<std::shared_mutex> lock(global_mutex_);
        if (initialized_) return;

        root_ = std::make_shared<DirectoryNode>("/");

        auto bin = std::make_shared<DirectoryNode>("/bin");
        root_->add_child("bin", bin);

        auto dev = std::make_shared<DirectoryNode>("/dev");
        root_->add_child("dev", dev);

        auto mem = std::make_shared<DirectoryNode>("/mem");
        root_->add_child("mem", mem);

        auto proc = std::make_shared<DirectoryNode>("/proc");
        root_->add_child("proc", proc);

        auto tmp = std::make_shared<DirectoryNode>("/tmp");
        root_->add_child("tmp", tmp);

        auto wasm_sandbox = std::make_shared<WasmNode>(
            "/bin/wasm_sandbox", "./wasm/test.wasm");
        bin->add_child("wasm_sandbox", wasm_sandbox);

        initialized_ = true;
        std::printf("[VFS] Root filesystem initialized: /, /bin, /dev, /mem, /proc, /tmp\n");
        std::printf("[VFS] WasmEdge sandbox mounted at /bin/wasm_sandbox\n");
    }

    bool mount(const std::string& dir_path, const std::string& name,
               std::shared_ptr<VfsNode> node, int caller_uid = 0) {
        auto parent = resolve_path(dir_path, caller_uid);
        if (!parent) {
            std::printf("[VFS] Mount failed: parent path '%s' not found\n", dir_path.c_str());
            return false;
        }
        if (parent->node_type() != VfsNodeType::DIRECTORY) {
            std::printf("[VFS] Mount failed: '%s' is not a directory\n", dir_path.c_str());
            return false;
        }
        if (!parent->check_write(caller_uid)) {
            std::printf("[VFS] Mount DENIED: uid=%d no write permission on '%s'\n",
                        caller_uid, dir_path.c_str());
            return false;
        }
        auto dir = std::static_pointer_cast<DirectoryNode>(parent);
        return dir->add_child(name, std::move(node));
    }

    std::shared_ptr<VfsNode> resolve_path(const std::string& path, int caller_uid = 0) {
        std::shared_lock<std::shared_mutex> lock(global_mutex_);
        if (!initialized_) return nullptr;
        if (path == "/") return root_;

        std::vector<std::string> parts = split_path(path);
        if (parts.empty()) return nullptr;

        std::shared_ptr<VfsNode> current = root_;
        for (const auto& part : parts) {
            if (current->node_type() != VfsNodeType::DIRECTORY) return nullptr;
            if (!current->check_execute(caller_uid)) {
                std::printf("[VFS] Permission DENIED: uid=%d cannot traverse '%s'\n",
                            caller_uid, current->path().c_str());
                return nullptr;
            }
            auto dir = std::static_pointer_cast<DirectoryNode>(current);
            current = dir->get_child(part);
            if (!current) return nullptr;
        }
        return current;
    }

    std::shared_ptr<VfsNode> resolve_or_create_mem(const std::string& path,
                                                     std::shared_ptr<MemoryManager> mmgr) {
        auto node = resolve_path(path);
        if (node) return node;

        if (path.find("/dev/mem/") != 0) return nullptr;

        std::string agent_str = path.substr(9);
        int agent_id = 0;
        try {
            agent_id = std::stoi(agent_str);
        } catch (...) {
            return nullptr;
        }

        auto mem_node = std::make_shared<MemoryDeviceNode>(path, agent_id, std::move(mmgr));

        {
            std::unique_lock<std::shared_mutex> lock(global_mutex_);
            auto dev_mem = resolve_path_unlocked("/dev/mem");
            if (!dev_mem || dev_mem->node_type() != VfsNodeType::DIRECTORY) {
                return nullptr;
            }
            auto dir = std::static_pointer_cast<DirectoryNode>(dev_mem);
            dir->add_child(agent_str, mem_node);
        }

        std::printf("[VFS] Dynamic mount: /dev/mem/%s [DEV] -> Agent#%d memory pool\n",
                    agent_str.c_str(), agent_id);
        return mem_node;
    }

    std::string list_dir(const std::string& path) {
        auto node = resolve_path(path);
        if (!node || node->node_type() != VfsNodeType::DIRECTORY) {
            return "[VFS ERROR] Not a directory: " + path;
        }
        auto dir = std::static_pointer_cast<DirectoryNode>(node);
        auto children = dir->list_children();
        std::string result;
        for (const auto& name : children) {
            auto child = dir->get_child(name);
            result += name;
            if (child) {
                result += " [" + std::string(node_type_str(child->node_type())) + "]";
            }
            result += "\n";
        }
        return result;
    }

    std::string tree(const std::string& path = "/", int depth = 0) {
        auto node = resolve_path(path);
        if (!node) return "";

        std::string result;
        std::string indent(depth * 2, ' ');
        std::string name = (path == "/") ? "/" : path.substr(path.rfind('/') + 1);
        result += indent + name + " [" + node_type_str(node->node_type()) + "]\n";

        if (node->node_type() == VfsNodeType::DIRECTORY) {
            auto dir = std::static_pointer_cast<DirectoryNode>(node);
            auto children = dir->list_children();
            for (const auto& child_name : children) {
                std::string child_path = (path == "/") ? "/" + child_name : path + "/" + child_name;
                result += tree(child_path, depth + 1);
            }
        }
        return result;
    }

private:
    VfsManager() = default;
    VfsManager(const VfsManager&) = delete;
    VfsManager& operator=(const VfsManager&) = delete;

    std::vector<std::string> split_path(const std::string& path) {
        std::vector<std::string> parts;
        size_t start = 0;
        while (start < path.size()) {
            while (start < path.size() && path[start] == '/') ++start;
            if (start >= path.size()) break;
            size_t end = path.find('/', start);
            if (end == std::string::npos) end = path.size();
            parts.push_back(path.substr(start, end - start));
            start = end;
        }
        return parts;
    }

    std::shared_ptr<VfsNode> resolve_path_unlocked(const std::string& path) {
        if (!initialized_) return nullptr;
        if (path == "/") return root_;

        std::vector<std::string> parts = split_path(path);
        if (parts.empty()) return nullptr;

        std::shared_ptr<VfsNode> current = root_;
        for (const auto& part : parts) {
            if (current->node_type() != VfsNodeType::DIRECTORY) return nullptr;
            auto dir = std::static_pointer_cast<DirectoryNode>(current);
            current = dir->get_child(part);
            if (!current) return nullptr;
        }
        return current;
    }

    std::shared_ptr<DirectoryNode> root_;
    bool initialized_ = false;
    mutable std::shared_mutex global_mutex_;
};

} // namespace aios

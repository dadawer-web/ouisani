#pragma once

#include "aios/vfs_node.h"
#include "aios/wasm_node.h"
#include "aios/memory_manager.h"

#include <cstdio>
#include <memory>
#include <shared_mutex>
#include <string>
#include <unordered_map>
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

        auto containers = std::make_shared<DirectoryNode>("/containers");
        root_->add_child("containers", containers);

        auto var_dir = std::make_shared<DirectoryNode>("/var");
        root_->add_child("var", var_dir);
        auto crash_dir = std::make_shared<DirectoryNode>("/var/crash");
        var_dir->add_child("crash", crash_dir);

        auto wasm_sandbox = std::make_shared<WasmNode>(
            "/bin/wasm_sandbox", "./wasm/test.wasm");
        bin->add_child("wasm_sandbox", wasm_sandbox);

        initialized_ = true;
        std::printf("[VFS] Root filesystem initialized: /, /bin, /dev, /mem, /proc, /tmp, /containers, /var/crash\n");
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

    std::shared_ptr<VfsNode> resolve_path(const std::string& path, int caller_uid = 0,
                                           const std::string& agent_root = "/") {
        std::shared_lock<std::shared_mutex> lock(global_mutex_);
        if (!initialized_) return nullptr;

        std::string resolved = translate_path(path, agent_root);
        if (resolved.empty()) return nullptr;

        if (resolved == "/") return root_;

        std::vector<std::string> parts = split_path(resolved);
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

    std::string translate_path(const std::string& path, const std::string& agent_root = "/") {
        if (agent_root == "/" || agent_root.empty()) {
            return sanitize_path(path);
        }

        std::string clean = sanitize_path(path);
        if (clean.empty()) return "";

        if (clean[0] != '/') return clean;

        std::string combined = agent_root;
        if (combined.back() != '/') combined += '/';
        combined += clean.substr(1);

        std::string canonicalized = canonicalize(combined);

        if (!is_within_root(canonicalized, agent_root)) {
            std::printf("[VFS] PATH ESCAPE BLOCKED: '%s' escapes root '%s' (resolved: '%s')\n",
                        path.c_str(), agent_root.c_str(), canonicalized.c_str());
            return "";
        }

        return canonicalized;
    }

    bool create_container_namespace(int agent_id) {
        std::unique_lock<std::shared_mutex> lock(global_mutex_);
        if (!initialized_) return false;

        auto containers = resolve_path_unlocked("/containers");
        if (!containers || containers->node_type() != VfsNodeType::DIRECTORY) {
            std::printf("[VFS] /containers directory not found\n");
            return false;
        }
        auto containers_dir = std::static_pointer_cast<DirectoryNode>(containers);

        std::string agent_dir_name = "agent_" + std::to_string(agent_id);
        if (containers_dir->get_child(agent_dir_name)) {
            std::printf("[VFS] Container namespace already exists: /containers/%s\n",
                        agent_dir_name.c_str());
            return true;
        }

        std::string agent_root = "/containers/" + agent_dir_name;
        auto agent_root_node = std::make_shared<DirectoryNode>(agent_root);
        containers_dir->add_child(agent_dir_name, agent_root_node);

        auto agent_bin = std::make_shared<DirectoryNode>(agent_root + "/bin");
        agent_root_node->add_child("bin", agent_bin);

        auto agent_dev = std::make_shared<DirectoryNode>(agent_root + "/dev");
        agent_root_node->add_child("dev", agent_dev);

        auto agent_proc = std::make_shared<DirectoryNode>(agent_root + "/proc");
        agent_root_node->add_child("proc", agent_proc);

        auto agent_tmp = std::make_shared<DirectoryNode>(agent_root + "/tmp");
        agent_root_node->add_child("tmp", agent_tmp);

        auto agent_mem = std::make_shared<DirectoryNode>(agent_root + "/dev/mem");
        agent_dev->add_child("mem", agent_mem);

        agent_namespaces_[agent_id] = agent_root;

        std::printf("[VFS] Mount Namespace created: /containers/%s (CLONE_NEWNS)\n",
                    agent_dir_name.c_str());
        std::printf("[VFS]   ├── /bin  [DIR]\n");
        std::printf("[VFS]   ├── /dev  [DIR]\n");
        std::printf("[VFS]   │   └── /mem [DIR]\n");
        std::printf("[VFS]   ├── /proc [DIR]\n");
        std::printf("[VFS]   └── /tmp  [DIR]\n");

        return true;
    }

    std::string get_agent_root(int agent_id) const {
        auto it = agent_namespaces_.find(agent_id);
        if (it != agent_namespaces_.end()) return it->second;
        return "/";
    }

    bool has_namespace(int agent_id) const {
        return agent_namespaces_.find(agent_id) != agent_namespaces_.end();
    }

    bool destroy_container_namespace(int agent_id) {
        std::unique_lock<std::shared_mutex> lock(global_mutex_);
        auto it = agent_namespaces_.find(agent_id);
        if (it == agent_namespaces_.end()) return false;

        auto containers = resolve_path_unlocked("/containers");
        if (!containers || containers->node_type() != VfsNodeType::DIRECTORY) return false;

        std::string agent_dir_name = "agent_" + std::to_string(agent_id);
        auto containers_dir = std::static_pointer_cast<DirectoryNode>(containers);
        containers_dir->remove_child(agent_dir_name);

        agent_namespaces_.erase(it);
        std::printf("[VFS] Mount Namespace destroyed: /containers/%s\n", agent_dir_name.c_str());
        return true;
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

    std::string list_dir(const std::string& path, const std::string& agent_root = "/") {
        auto node = resolve_path(path, 0, agent_root);
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

    std::string tree(const std::string& path = "/", int depth = 0,
                     const std::string& agent_root = "/") {
        auto node = resolve_path(path, 0, agent_root);
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
                result += tree(child_path, depth + 1, agent_root);
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

    std::string sanitize_path(const std::string& path) {
        std::vector<std::string> parts = split_path(path);
        std::vector<std::string> result;
        for (const auto& part : parts) {
            if (part == ".") continue;
            if (part == "..") {
                if (!result.empty()) result.pop_back();
            } else {
                result.push_back(part);
            }
        }
        if (result.empty()) return "/";
        std::string out;
        for (const auto& p : result) {
            out += "/" + p;
        }
        return out;
    }

    std::string canonicalize(const std::string& path) {
        return sanitize_path(path);
    }

    bool is_within_root(const std::string& path, const std::string& root) {
        if (root == "/" || root.empty()) return true;
        if (path == root) return true;
        return path.size() > root.size()
               && path.substr(0, root.size()) == root
               && path[root.size()] == '/';
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
    std::unordered_map<int, std::string> agent_namespaces_;
};

} // namespace aios

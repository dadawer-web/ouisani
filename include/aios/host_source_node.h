#pragma once

#include "aios/agent_registry.h"
#include "aios/vfs_node.h"

#include <filesystem>
#include <fstream>
#include <nlohmann/json.hpp>
#include <sstream>

namespace aios {

class HostSourceNode : public VfsNode {
public:
    HostSourceNode(const std::string& vfs_path,
                   const std::string& host_path,
                   int owner_uid = 0,
                   int permissions = 0444)
        : VfsNode(VfsNodeType::FILE, vfs_path, owner_uid, permissions)
        , host_path_(host_path) {}

    std::string read() const override {
        try {
            std::ifstream ifs(host_path_);
            if (!ifs.is_open()) {
                return "[HostSource] Cannot read host file: " + host_path_;
            }
            std::string content((std::istreambuf_iterator<char>(ifs)),
                                 std::istreambuf_iterator<char>());
            return content;
        } catch (const std::exception& e) {
            return "[HostSource] Read error: " + std::string(e.what());
        }
    }

    bool write(const std::string& data) override {
        if (!AgentRegistry::instance().is_ring0(owner_uid_)) {
            std::printf("[HostSource] ❌ WRITE DENIED — only Ring 0 can modify kernel source: %s\n",
                        path_.c_str());
            return false;
        }

        try {
            std::ofstream ofs(host_path_, std::ios::trunc);
            if (!ofs.is_open()) {
                std::printf("[HostSource] ❌ Cannot open host file for writing: %s\n",
                            host_path_.c_str());
                return false;
            }
            ofs << data;
            std::printf("[HostSource] ✅ Kernel source modified: %s (%zu bytes written)\n",
                        path_.c_str(), data.size());
            return true;
        } catch (const std::exception& e) {
            std::printf("[HostSource] ❌ Write error: %s\n", e.what());
            return false;
        }
    }

    bool check_write(int caller_uid) const {
        if (!AgentRegistry::instance().is_ring0(caller_uid)) {
            return false;
        }
        return VfsNode::check_write(caller_uid);
    }

    const std::string& host_path() const { return host_path_; }

private:
    std::string host_path_;
};

class HostSourceDirNode : public DirectoryNode {
public:
    HostSourceDirNode(const std::string& vfs_path,
                      const std::string& host_path,
                      int owner_uid = 0,
                      int permissions = 0555)
        : DirectoryNode(vfs_path)
        , host_path_(host_path)
        , owner_uid_(owner_uid)
        , dir_permissions_(permissions) {}

    std::string read() const override {
        ensure_children();
        std::string listing;
        auto child_names = list_children();
        for (const auto& name : child_names) {
            auto child = get_child(name);
            listing += name;
            if (child && child->node_type() == VfsNodeType::DIRECTORY) {
                listing += "/";
            }
            listing += "\n";
        }
        return listing;
    }

    bool check_write(int caller_uid) const {
        return AgentRegistry::instance().is_ring0(caller_uid);
    }

    bool check_read(int) const {
        return true;
    }

    const std::string& host_path() const { return host_path_; }

private:
    void ensure_children() const {
        if (children_populated_) return;
        children_populated_ = true;

        namespace fs = std::filesystem;
        if (!fs::exists(host_path_) || !fs::is_directory(host_path_)) return;

        auto self = const_cast<HostSourceDirNode*>(this);

        for (const auto& entry : fs::directory_iterator(host_path_)) {
            std::string name = entry.path().filename().string();
            if (name.empty() || name[0] == '.') continue;

            if (self->get_child(name)) continue;

            std::string child_vfs = (path_ == "/") ? "/" + name : path_ + "/" + name;
            std::string child_host = entry.path().string();

            if (entry.is_directory()) {
                auto dir_node = std::make_shared<HostSourceDirNode>(
                    child_vfs, child_host, owner_uid_, dir_permissions_);
                self->add_child(name, dir_node);
            } else if (entry.is_regular_file()) {
                auto file_node = std::make_shared<HostSourceNode>(
                    child_vfs, child_host, owner_uid_, 0644);
                self->add_child(name, file_node);
            }
        }
    }

    std::string host_path_;
    int owner_uid_;
    int dir_permissions_;
    mutable bool children_populated_ = false;
};

inline std::string compile_kernel(const std::string& project_root) {
    namespace fs = std::filesystem;

    std::string build_dir = project_root + "/build";
    if (!fs::exists(build_dir)) {
        fs::create_directories(build_dir);
    }

    std::string cmake_cmd = "cd " + build_dir + " && cmake .. -DCMAKE_BUILD_TYPE=Release 2>&1";
    std::string build_cmd = "cd " + build_dir + " && cmake --build . -j$(nproc) 2>&1";

    std::printf("[compile_kernel] 🔨 Step 1: Running cmake configure...\n");

    FILE* cmake_pipe = popen(cmake_cmd.c_str(), "r");
    std::string cmake_output;
    if (cmake_pipe) {
        char buf[4096];
        while (fgets(buf, sizeof(buf), cmake_pipe)) {
            cmake_output += buf;
        }
        int cmake_ret = pclose(cmake_pipe);
        if (cmake_ret != 0) {
            std::printf("[compile_kernel] ❌ cmake configure FAILED (exit=%d)\n", cmake_ret);
            return "CMAKE CONFIGURE FAILED:\n" + cmake_output;
        }
    }

    std::printf("[compile_kernel] 🔨 Step 2: Building aios_core...\n");

    FILE* build_pipe = popen(build_cmd.c_str(), "r");
    std::string build_output;
    if (build_pipe) {
        char buf[4096];
        while (fgets(buf, sizeof(buf), build_pipe)) {
            build_output += buf;
        }
        int build_ret = pclose(build_pipe);
        if (build_ret != 0) {
            std::printf("[compile_kernel] ❌ Build FAILED (exit=%d)\n", build_ret);
            return "BUILD FAILED:\n" + build_output;
        }
    }

    std::string new_binary = build_dir + "/aios_core";
    if (fs::exists(new_binary)) {
        auto file_size = fs::file_size(new_binary);
        std::printf("[compile_kernel] ✅ Build SUCCESS! New kernel: %s (%zu bytes)\n",
                    new_binary.c_str(), file_size);

        nlohmann::json result;
        result["status"] = "ok";
        result["message"] = "Kernel compiled successfully";
        result["binary_path"] = new_binary;
        result["binary_size"] = file_size;
        result["cmake_output_last_line"] = "";
        result["build_output_last_lines"] = "";

        {
            std::istringstream iss(build_output);
            std::string line;
            std::vector<std::string> last_lines;
            while (std::getline(iss, line)) {
                last_lines.push_back(line);
            }
            int count = std::min((int)last_lines.size(), 5);
            for (int i = std::max(0, (int)last_lines.size() - count); i < (int)last_lines.size(); i++) {
                result["build_output_last_lines"] += last_lines[i] + "\n";
            }
        }

        return result.dump();
    }

    return "Build completed but binary not found at: " + new_binary;
}

} // namespace aios

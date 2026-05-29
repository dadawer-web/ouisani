#include "aios/module_manager.h"
#include "aios/event_bus.h"
#include "aios/wasm_node.h"

#include <cstdio>
#include <filesystem>
#include <nlohmann/json.hpp>

namespace aios {

ModuleManager& ModuleManager::instance() {
    static ModuleManager mgr;
    return mgr;
}

bool ModuleManager::is_path_safe(const std::filesystem::path& p) const {
    std::string canonical_str;
    try {
        if (!std::filesystem::exists(p)) return false;
        canonical_str = std::filesystem::canonical(p).string();
    } catch (...) {
        return false;
    }

    if (canonical_str.find("..") != std::string::npos) return false;

    std::string lib_canonical;
    try {
        lib_canonical = std::filesystem::canonical(lib_dir_).string();
    } catch (...) {
        return false;
    }

    if (canonical_str.size() < lib_canonical.size()) return false;
    if (canonical_str.substr(0, lib_canonical.size()) != lib_canonical) return false;

    return true;
}

void ModuleManager::init(const std::string& lib_dir) {
    std::lock_guard<std::mutex> lock(mu_);
    lib_dir_ = lib_dir;
    available_tools_.clear();

    try {
        if (!std::filesystem::exists(lib_dir_)) {
            std::filesystem::create_directories(lib_dir_);
            std::printf("[ModuleManager] Created library directory: %s\n", lib_dir_.c_str());
        }

        for (const auto& entry : std::filesystem::directory_iterator(lib_dir_)) {
            if (!entry.is_regular_file()) continue;
            std::string filename = entry.path().filename().string();

            if (filename.size() > 5 && filename.substr(filename.size() - 5) == ".wasm") {
                std::string tool_name = filename.substr(0, filename.size() - 5);
                std::string abs_path = std::filesystem::canonical(entry.path()).string();

                if (!is_path_safe(entry.path())) {
                    std::printf("[ModuleManager] SECURITY: Path traversal blocked for %s\n",
                                abs_path.c_str());
                    continue;
                }

                available_tools_[tool_name] = abs_path;
                std::printf("[ModuleManager] Registered tool: %s -> %s\n",
                            tool_name.c_str(), abs_path.c_str());
            }
        }
    } catch (const std::filesystem::filesystem_error& e) {
        std::printf("[ModuleManager] Filesystem error during init: %s\n", e.what());
    }

    initialized_ = true;
    std::printf("[ModuleManager] Initialized | lib_dir=%s | tools=%zu\n",
                lib_dir_.c_str(), available_tools_.size());
}

std::string ModuleManager::discover_tools() {
    std::lock_guard<std::mutex> lock(mu_);

    if (!initialized_) {
        const_cast<ModuleManager*>(this)->init();
    }

    nlohmann::json arr = nlohmann::json::array();
    for (const auto& [name, path] : available_tools_) {
        nlohmann::json tool_info;
        tool_info["name"] = name;
        tool_info["path"] = path;
        arr.push_back(tool_info);
    }

    std::string result = arr.dump();
    std::printf("[ModuleManager] discover_tools() -> %zu tools available\n",
                available_tools_.size());
    return result;
}

std::string ModuleManager::call_tool(const std::string& tool_name, const std::string& json_args) {
    std::string wasm_path;
    {
        std::lock_guard<std::mutex> lock(mu_);

        if (!initialized_) {
            const_cast<ModuleManager*>(this)->init();
        }

        auto it = available_tools_.find(tool_name);
        if (it == available_tools_.end()) {
            std::printf("[ModuleManager] call_tool FAILED | tool='%s' not found\n",
                        tool_name.c_str());
            nlohmann::json err;
            err["status"] = "error";
            err["message"] = "Tool not found: " + tool_name;
            return err.dump();
        }
        wasm_path = it->second;
    }

    std::filesystem::path p(wasm_path);
    if (!is_path_safe(p)) {
        std::printf("[ModuleManager] SECURITY: Path traversal blocked for tool '%s' -> %s\n",
                    tool_name.c_str(), wasm_path.c_str());
        nlohmann::json err;
        err["status"] = "error";
        err["message"] = "Path traversal detected for tool: " + tool_name;
        return err.dump();
    }

    if (!std::filesystem::exists(wasm_path)) {
        nlohmann::json err;
        err["status"] = "error";
        err["message"] = "WASM file not found: " + wasm_path;
        return err.dump();
    }

    EventBus::instance().publish(EventType::WASM_EXEC_START, "ModuleManager",
        "Dynamically linking and executing: " + tool_name);

    std::printf("[ModuleManager] call_tool | tool='%s' | wasm=%s | args=%zu bytes\n",
                tool_name.c_str(), wasm_path.c_str(), json_args.size());

    try {
        std::string node_name = "/usr/lib/" + tool_name;
        auto wasm_node = std::make_shared<WasmNode>(node_name, wasm_path);

        nlohmann::json exec_payload;
        exec_payload["file"] = wasm_path;
        exec_payload["func"] = "_start";
        if (!json_args.empty()) {
            exec_payload["stdin"] = json_args;
        }

        FILE* tmp_stdout = tmpfile();
        int stdout_fd = tmp_stdout ? fileno(tmp_stdout) : -1;

        std::string exec_result = wasm_node->execute_with_fds(
            exec_payload.dump(), -1, stdout_fd);

        std::string captured_stdout;
        if (tmp_stdout) {
            fflush(tmp_stdout);
            fseek(tmp_stdout, 0, SEEK_END);
            long fsize = ftell(tmp_stdout);
            if (fsize > 0) {
                fseek(tmp_stdout, 0, SEEK_SET);
                captured_stdout.resize(static_cast<size_t>(fsize));
                auto rd = fread(&captured_stdout[0], 1, static_cast<size_t>(fsize), tmp_stdout);
                (void)rd;
            }
            fclose(tmp_stdout);
        }

        nlohmann::json result;
        result["status"] = "ok";
        result["tool"] = tool_name;
        result["exit_code"] = exec_result;
        if (!captured_stdout.empty()) {
            result["stdout"] = captured_stdout;
        }
        return result.dump();

    } catch (const std::exception& e) {
        std::printf("[ModuleManager] call_tool EXCEPTION | tool='%s' | %s\n",
                    tool_name.c_str(), e.what());
        nlohmann::json err;
        err["status"] = "error";
        err["message"] = "WASM execution failed: " + std::string(e.what());
        return err.dump();
    }
}

} // namespace aios

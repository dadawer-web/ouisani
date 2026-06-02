#pragma once

#include <filesystem>
#include <mutex>
#include <string>
#include <unordered_map>

namespace aios {

class ModuleManager {
public:
    static ModuleManager& instance();

    void init(const std::string& lib_dir = "./usr_lib_wasm");

    std::string discover_tools();

    std::string call_tool(const std::string& tool_name, const std::string& json_args);

    std::string reload(const std::string& tool_name = "");

    ModuleManager(const ModuleManager&) = delete;
    ModuleManager& operator=(const ModuleManager&) = delete;

private:
    ModuleManager() = default;

    bool is_path_safe(const std::filesystem::path& p) const;

    std::string lib_dir_;
    std::unordered_map<std::string, std::string> available_tools_;
    std::mutex mu_;
    bool initialized_ = false;
};

} // namespace aios

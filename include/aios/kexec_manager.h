#pragma once

#include <nlohmann/json.hpp>
#include <string>

namespace aios {

class KexecManager {
public:
    static KexecManager& instance();

    void trigger_kexec(const std::string& new_kernel_binary_path);

    static bool has_kexec_state(int argc, char* argv[]);
    static std::string get_kexec_state_path(int argc, char* argv[]);
    void restore_from_kexec(const std::string& state_path);

    KexecManager(const KexecManager&) = delete;
    KexecManager& operator=(const KexecManager&) = delete;

private:
    KexecManager() = default;

    nlohmann::json collect_all_agent_snapshots();
    nlohmann::json serialize_vfs_state();
    nlohmann::json serialize_pipe_state();
    nlohmann::json serialize_cgroup_state();
    bool write_kexec_state(const std::string& path, const nlohmann::json& state);
    void suspend_all_agents();
    void graceful_shutdown();
    void do_execve(const std::string& binary_path, const std::string& state_path);
};

} // namespace aios

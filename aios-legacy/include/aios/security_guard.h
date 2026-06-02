#pragma once

#include "aios/agent_registry.h"

#include <cstdio>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>
#include <vector>

namespace aios {

class LlmAdapter;

class SecurityGuard {
public:
    static SecurityGuard& instance();

    void set_llm(std::shared_ptr<LlmAdapter> llm);

    bool check_intent(int agent_id,
                      const std::string& syscall_name,
                      const std::string& payload);

    bool is_code_safe(const std::string& code, PrivilegeLevel level);

    void add_sensitive_syscall(const std::string& name);
    void remove_sensitive_syscall(const std::string& name);

    void add_sensitive_path(const std::string& path);
    void remove_sensitive_path(const std::string& path);

    void set_enabled(bool enabled);
    bool is_enabled() const;

    size_t total_checks() const;
    size_t total_blocks() const;

    struct AuditLog {
        int agent_id;
        std::string syscall_name;
        std::string payload_preview;
        bool blocked;
        std::string reason;
    };

    std::vector<AuditLog> recent_logs(size_t max_count = 50) const;

private:
    SecurityGuard();

    bool is_sensitive_syscall(const std::string& syscall_name,
                              const std::string& payload) const;

    bool llm_verify(int agent_id,
                    const std::string& syscall_name,
                    const std::string& payload);

    std::shared_ptr<LlmAdapter> llm_;
    mutable std::mutex mutex_;

    std::unordered_set<std::string> sensitive_syscalls_;
    std::unordered_set<std::string> sensitive_paths_;

    bool enabled_ = true;
    size_t total_checks_ = 0;
    size_t total_blocks_ = 0;

    std::vector<AuditLog> audit_log_;
    static constexpr size_t kMaxAuditLog = 200;
};

} // namespace aios

#pragma once

#include "aios/agent_registry.h"

#include <cstdio>
#include <regex>
#include <string>
#include <vector>

namespace aios {

struct SecurityRule {
    std::string name;
    std::regex pattern;
    std::string description;
};

class SecurityGuard {
public:
    static SecurityGuard& instance() {
        static SecurityGuard guard;
        return guard;
    }

    bool is_code_safe(const std::string& code, PrivilegeLevel level) {
        if (level == PrivilegeLevel::RING_0) {
            std::printf("[SecurityGuard] RING_0 bypass | code=%zu bytes\n", code.size());
            return true;
        }

        auto violations = scan(code);
        if (violations.empty()) {
            std::printf("[SecurityGuard] PASS | Ring 3 code scan clean (%zu bytes)\n", code.size());
            return true;
        }

        std::printf("[SecurityGuard] BLOCKED | Ring 3 code violates %zu rule(s):\n", violations.size());
        for (const auto& v : violations) {
            std::printf("[SecurityGuard]   - %s\n", v.c_str());
        }
        return false;
    }

    std::vector<std::string> scan(const std::string& code) {
        std::vector<std::string> violations;
        for (const auto& rule : rules_) {
            if (std::regex_search(code, rule.pattern)) {
                violations.push_back(rule.name + ": " + rule.description);
            }
        }
        return violations;
    }

private:
    SecurityGuard() {
        rules_ = {
            {"OS_IMPORT",       std::regex(R"(import\s+os\b)",           std::regex::icase), "importing os module"},
            {"SUBPROCESS",      std::regex(R"(import\s+subprocess\b)",   std::regex::icase), "importing subprocess module"},
            {"SYS_IMPORT",      std::regex(R"(import\s+sys\b)",          std::regex::icase), "importing sys module"},
            {"FILE_OPEN",       std::regex(R"(\bopen\s*\()",             std::regex::icase), "file open() call"},
            {"EVAL_CALL",       std::regex(R"(\beval\s*\()",             std::regex::icase), "eval() call"},
            {"EXEC_CALL",       std::regex(R"(\bexec\s*\()",             std::regex::icase), "exec() call"},
            {"DUNDER_IMPORT",   std::regex(R"(__import__\s*\()",         std::regex::icase), "__import__() call"},
            {"SHUTIL_IMPORT",   std::regex(R"(import\s+shutil\b)",       std::regex::icase), "importing shutil module"},
            {"SOCKET_IMPORT",   std::regex(R"(import\s+socket\b)",       std::regex::icase), "importing socket module"},
            {"POPEN_CALL",      std::regex(R"(\bPopen\s*\()",            std::regex::icase), "subprocess.Popen() call"},
            {"OS_SYSTEM",       std::regex(R"(\bos\s*\.\s*system\s*\()", std::regex::icase), "os.system() call"},
            {"OS_POPEN",        std::regex(R"(\bos\s*\.\s*popen\s*\()",  std::regex::icase), "os.popen() call"},
        };
    }

    SecurityGuard(const SecurityGuard&) = delete;
    SecurityGuard& operator=(const SecurityGuard&) = delete;

    std::vector<SecurityRule> rules_;
};

} // namespace aios

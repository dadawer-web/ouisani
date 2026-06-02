#pragma once

#include <memory>
#include <string>

namespace aios {

struct AgentTask;

class CrashAnalyzer {
public:
    static CrashAnalyzer& instance();

    void handle_wasm_crash(int agent_id,
                           const std::string& wasm_file,
                           const std::string& func_name,
                           const std::string& trap_message,
                           uint64_t instr_count,
                           uint64_t gas_used);

    void handle_kernel_panic(int agent_id,
                             const std::string& reason);

    std::string get_core_dump(int agent_id) const;

    void analyze_panic(const std::string& dump_path);

    void analyze_panic_for_agent(int agent_id);

    int total_crashes() const;

    CrashAnalyzer(const CrashAnalyzer&) = delete;
    CrashAnalyzer& operator=(const CrashAnalyzer&) = delete;

private:
    CrashAnalyzer() = default;

    std::string collect_crash_context(int agent_id) const;
    std::string find_source_code(const std::string& wasm_file) const;
    bool write_core_dump(int agent_id, const std::string& json_data) const;

    int crash_count_ = 0;
};

} // namespace aios

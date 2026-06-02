#include "aios/crash_analyzer.h"
#include "aios/token_mmu.h"
#include "aios/process_manager.h"
#include "aios/vfs_manager.h"
#include "aios/cgroup_manager.h"
#include "aios/llm_router.h"
#include "aios/event_bus.h"
#include "aios/kernel_logger.h"

#include <chrono>
#include <cstdio>
#include <ctime>
#include <nlohmann/json.hpp>
#include <sstream>

namespace aios {

CrashAnalyzer& CrashAnalyzer::instance() {
    static CrashAnalyzer inst;
    return inst;
}

std::string CrashAnalyzer::collect_crash_context(int agent_id) const {
    auto messages = TokenMmu::instance().get_messages(agent_id);
    int total = static_cast<int>(messages.size());
    int start = total > 5 ? total - 5 : 0;

    nlohmann::json recent = nlohmann::json::array();
    auto it = messages.begin();
    std::advance(it, start);
    for (; it != messages.end(); ++it) {
        std::string msg = *it;
        if (msg.size() > 200) {
            msg = msg.substr(0, 200) + "...[truncated]";
        }
        recent.push_back(msg);
    }

    return recent.dump();
}

std::string CrashAnalyzer::find_source_code(const std::string& wasm_file) const {
    std::string c_path = wasm_file;
    auto pos = c_path.rfind(".wasm");
    if (pos != std::string::npos) {
        c_path.replace(pos, 5, ".c");
    }

    auto node = VfsManager::instance().resolve_path(c_path, 0);
    if (node) {
        std::string content = node->read();
        if (!content.empty()) return content;
    }

    pos = wasm_file.rfind('/');
    if (pos != std::string::npos) {
        std::string basename = wasm_file.substr(pos + 1);
        auto bpos = basename.rfind(".wasm");
        if (bpos != std::string::npos) {
            basename.replace(bpos, 5, ".c");
        }
        node = VfsManager::instance().resolve_path("/src/" + basename, 0);
        if (node) {
            std::string content = node->read();
            if (!content.empty()) return content;
        }
    }

    return "[source code not found in VFS]";
}

bool CrashAnalyzer::write_core_dump(int agent_id, const std::string& json_data) const {
    auto& vfs = VfsManager::instance();

    auto crash_dir = vfs.resolve_path("/var/crash", 0);
    if (!crash_dir) {
        std::printf("[CrashAnalyzer] /var/crash directory not found in VFS!\n");
        return false;
    }

    auto crash_dir_node = std::dynamic_pointer_cast<DirectoryNode>(crash_dir);
    if (!crash_dir_node) {
        std::printf("[CrashAnalyzer] /var/crash is not a directory!\n");
        return false;
    }

    std::string dump_name = "core_dump_" + std::to_string(agent_id) + ".json";
    std::string dump_path = "/var/crash/" + dump_name;

    auto existing = vfs.resolve_path(dump_path, 0);
    if (existing) {
        existing->write(json_data);
    } else {
        auto dump_node = std::make_shared<FileNode>(dump_path, json_data);
        crash_dir_node->add_child(dump_name, dump_node);
    }

    std::printf("[CrashAnalyzer] 💾 Core dump written: %s (%zu bytes)\n",
                dump_path.c_str(), json_data.size());
    return true;
}

void CrashAnalyzer::handle_wasm_crash(int agent_id,
                                       const std::string& wasm_file,
                                       const std::string& func_name,
                                       const std::string& trap_message,
                                       uint64_t instr_count,
                                       uint64_t gas_used) {
    crash_count_++;

    std::printf("[CrashAnalyzer] 💥 KERNEL PANIC! WASM Trap in Agent %d\n", agent_id);
    std::printf("[CrashAnalyzer]    WASM file:    %s\n", wasm_file.c_str());
    std::printf("[CrashAnalyzer]    Function:     %s\n", func_name.c_str());
    std::printf("[CrashAnalyzer]    Trap message: %s\n", trap_message.c_str());
    std::printf("[CrashAnalyzer]    Instructions: %llu\n", (unsigned long long)instr_count);
    std::printf("[CrashAnalyzer]    Gas used:     %llu\n", (unsigned long long)gas_used);

    KernelLogger::instance().log_alert(
        "[CrashAnalyzer] WASM TRAP! Agent=" + std::to_string(agent_id) +
        " func=" + func_name + " trap=" + trap_message);

    auto now = std::chrono::system_clock::now();
    auto time_t_now = std::chrono::system_clock::to_time_t(now);
    char time_buf[64];
    std::strftime(time_buf, sizeof(time_buf), "%Y-%m-%d %H:%M:%S", std::localtime(&time_t_now));

    std::string recent_context = collect_crash_context(agent_id);
    std::string source_code = find_source_code(wasm_file);

    auto pcb = ProcessManager::instance().get_pcb(agent_id);
    std::string agent_state = pcb ? agent_state_str(pcb->state) : "UNKNOWN";
    std::string agent_role = pcb ? pcb->role : "unknown";
    int token_count = TokenMmu::instance().total_tokens(agent_id);
    int msg_count = TokenMmu::instance().message_count(agent_id);

    std::string cgroup = "";
    bool oom_blocked = false;
    auto cgroup_name = CgroupManager::instance().get_agent_cgroup(agent_id);
    if (!cgroup_name.empty()) {
        cgroup = cgroup_name;
        oom_blocked = CgroupManager::instance().is_oom_blocked(agent_id);
    }

    nlohmann::json core_dump;
    core_dump["crash_type"] = "WASM_TRAP";
    core_dump["agent_id"] = agent_id;
    core_dump["agent_role"] = agent_role;
    core_dump["agent_state"] = agent_state;
    core_dump["timestamp"] = std::string(time_buf);
    core_dump["crash_sequence"] = crash_count_;

    nlohmann::json wasm_info;
    wasm_info["wasm_file"] = wasm_file;
    wasm_info["function"] = func_name;
    wasm_info["trap_message"] = trap_message;
    wasm_info["instruction_count"] = instr_count;
    wasm_info["gas_used"] = gas_used;
    core_dump["wasm_crash"] = wasm_info;

    nlohmann::json context_info;
    context_info["token_count"] = token_count;
    context_info["message_count"] = msg_count;
    context_info["cgroup"] = cgroup;
    context_info["oom_blocked"] = oom_blocked;
    context_info["recent_thoughts"] = nlohmann::json::parse(recent_context, nullptr, false);
    core_dump["agent_context"] = context_info;

    nlohmann::json source_info;
    std::string source_preview = source_code;
    if (source_preview.size() > 2000) {
        source_preview = source_preview.substr(0, 2000) + "\n...[truncated, total " +
                         std::to_string(source_code.size()) + " bytes]";
    }
    source_info["source_code"] = source_preview;
    source_info["source_found"] = (source_code != "[source code not found in VFS]");
    core_dump["source_code"] = source_info;

    core_dump["recovery_hint"] = "Agent was executing WASM sandbox code when a trap occurred. "
                                  "Check the trap_message for details. The source code that "
                                  "caused the crash has been preserved in this core dump.";

    std::string json_str = core_dump.dump(2);

    write_core_dump(agent_id, json_str);

    EventBus::instance().publish(EventType::AGENT_EXIT, "CrashAnalyzer",
        "WASM TRAP! Agent " + std::to_string(agent_id) +
        " crashed: " + trap_message);

    std::printf("[CrashAnalyzer] 💥 Semantic core dump complete for Agent %d\n", agent_id);
    std::printf("[CrashAnalyzer]    Total crashes this session: %d\n", crash_count_);
}

void CrashAnalyzer::handle_kernel_panic(int agent_id, const std::string& reason) {
    crash_count_++;

    std::printf("[CrashAnalyzer] 💥 KERNEL PANIC! Agent %d | Reason: %s\n",
                agent_id, reason.c_str());

    KernelLogger::instance().log_alert(
        "[CrashAnalyzer] KERNEL PANIC! Agent=" + std::to_string(agent_id) +
        " reason=" + reason);

    auto now = std::chrono::system_clock::now();
    auto time_t_now = std::chrono::system_clock::to_time_t(now);
    char time_buf[64];
    std::strftime(time_buf, sizeof(time_buf), "%Y-%m-%d %H:%M:%S", std::localtime(&time_t_now));

    std::string recent_context = collect_crash_context(agent_id);

    auto pcb = ProcessManager::instance().get_pcb(agent_id);
    std::string agent_state = pcb ? agent_state_str(pcb->state) : "UNKNOWN";
    std::string agent_role = pcb ? pcb->role : "unknown";
    int token_count = TokenMmu::instance().total_tokens(agent_id);

    nlohmann::json core_dump;
    core_dump["crash_type"] = "KERNEL_PANIC";
    core_dump["agent_id"] = agent_id;
    core_dump["agent_role"] = agent_role;
    core_dump["agent_state"] = agent_state;
    core_dump["timestamp"] = std::string(time_buf);
    core_dump["crash_sequence"] = crash_count_;
    core_dump["panic_reason"] = reason;

    nlohmann::json context_info;
    context_info["token_count"] = token_count;
    context_info["recent_thoughts"] = nlohmann::json::parse(recent_context, nullptr, false);
    core_dump["agent_context"] = context_info;

    core_dump["recovery_hint"] = "Agent experienced a kernel panic. Check the panic_reason "
                                  "for details. Recent agent thoughts have been preserved.";

    std::string json_str = core_dump.dump(2);
    write_core_dump(agent_id, json_str);

    EventBus::instance().publish(EventType::AGENT_EXIT, "CrashAnalyzer",
        "KERNEL PANIC! Agent " + std::to_string(agent_id) + ": " + reason);

    std::printf("[CrashAnalyzer] 💥 Kernel panic core dump complete for Agent %d\n", agent_id);
}

std::string CrashAnalyzer::get_core_dump(int agent_id) const {
    std::string dump_path = "/var/crash/core_dump_" + std::to_string(agent_id) + ".json";
    auto node = VfsManager::instance().resolve_path(dump_path, 0);
    if (node) {
        return node->read();
    }
    return "{}";
}

int CrashAnalyzer::total_crashes() const {
    return crash_count_;
}

void CrashAnalyzer::analyze_panic(const std::string& dump_path) {
    std::printf("[CrashAnalyzer] 🔍 Starting automatic panic analysis: %s\n", dump_path.c_str());

    auto node = VfsManager::instance().resolve_path(dump_path, 0);
    if (!node) {
        std::printf("[CrashAnalyzer] ❌ Core dump not found: %s\n", dump_path.c_str());
        KernelLogger::instance().log_alert(
            "[CrashAnalyzer] analyze_panic: core dump not found at " + dump_path);
        return;
    }

    std::string dump_json = node->read();
    if (dump_json.empty()) {
        std::printf("[CrashAnalyzer] ❌ Core dump is empty: %s\n", dump_path.c_str());
        return;
    }

    nlohmann::json dump_data;
    try {
        dump_data = nlohmann::json::parse(dump_json);
    } catch (const nlohmann::json::parse_error& e) {
        std::printf("[CrashAnalyzer] ❌ Core dump JSON parse error: %s\n", e.what());
        return;
    }

    int agent_id = dump_data.value("agent_id", -1);
    std::string crash_type = dump_data.value("crash_type", "UNKNOWN");

    std::printf("[CrashAnalyzer] 📋 Analyzing crash: type=%s | agent_id=%d\n",
                crash_type.c_str(), agent_id);

    std::string system_prompt =
        "You are an expert C/C++ kernel debugger for an AI Operating System (AIOS). "
        "You are analyzing a Semantic Core Dump from a WASM sandbox crash. "
        "Your task:\n"
        "1. Identify the ROOT CAUSE of the crash (e.g., out-of-bounds memory access, "
        "integer overflow, null pointer dereference, stack overflow, division by zero).\n"
        "2. Explain WHY the crash happened in terms of the specific code.\n"
        "3. Provide a CONCRETE PATCH or logic correction for the user's C code.\n"
        "4. Rate the severity: CRITICAL / HIGH / MEDIUM / LOW.\n\n"
        "Format your response as:\n"
        "## Root Cause Analysis\n"
        "[explanation]\n\n"
        "## Severity: [LEVEL]\n\n"
        "## Suggested Patch\n"
        "[corrected code or description of fix]\n\n"
        "Be precise, technical, and actionable. Do not give vague advice.";

    std::string truncated_dump = dump_json;
    if (truncated_dump.size() > 4000) {
        truncated_dump = truncated_dump.substr(0, 4000) + "\n...[truncated, total " +
                         std::to_string(dump_json.size()) + " bytes]";
    }

    std::string user_prompt = "Analyze the following Semantic Core Dump from the AIOS kernel:\n\n"
                               "```json\n" + truncated_dump + "\n```\n\n"
                               "Provide a root cause analysis and a patch for the crash.";

    std::string diagnosis;

    if (LlmRouter::instance().has_providers()) {
        std::printf("[CrashAnalyzer] 🧠 Invoking LLM for crash diagnosis (agent_id=%d)...\n", agent_id);

        try {
            diagnosis = LlmRouter::instance().route_and_execute(
                "crash_analyzer", system_prompt, user_prompt);
        } catch (const std::exception& e) {
            std::printf("[CrashAnalyzer] ⚠️  LLM diagnosis failed: %s\n", e.what());
            diagnosis = "";
        }
    }

    if (diagnosis.empty()) {
        std::string trap_msg;
        std::string source;
        if (dump_data.contains("wasm_crash")) {
            trap_msg = dump_data["wasm_crash"].value("trap_message", "unknown");
        }
        if (dump_data.contains("source_code")) {
            source = dump_data["source_code"].value("source_code", "");
        }

        diagnosis = "## Root Cause Analysis (Offline)\n";
        diagnosis += "Crash type: " + crash_type + "\n";
        diagnosis += "Trap message: " + trap_msg + "\n";

        if (trap_msg.find("out of bounds") != std::string::npos ||
            trap_msg.find("overflow") != std::string::npos) {
            diagnosis += "\nThe WASM sandbox detected an out-of-bounds memory access or overflow. "
                         "This typically occurs when array indices exceed allocated bounds, "
                         "or when pointer arithmetic goes past the WASM linear memory limit.\n";
        } else if (trap_msg.find("divide") != std::string::npos) {
            diagnosis += "\nDivision by zero detected. Check all division operations "
                         "and ensure denominators are non-zero.\n";
        } else if (trap_msg.find("unreachable") != std::string::npos) {
            diagnosis += "\nAn unreachable instruction was executed, indicating undefined behavior "
                         "in the C code (e.g., invalid enum switch case).\n";
        } else if (trap_msg.find("stack") != std::string::npos) {
            diagnosis += "\nStack overflow detected. The function call depth or local variable "
                         "size exceeds the WASM stack limit.\n";
        }

        if (!source.empty() && source != "[source code not found in VFS]") {
            diagnosis += "\n## Source Code (from core dump)\n```c\n" + source + "\n```\n";
        }

        diagnosis += "\n## Severity: HIGH\n";
        diagnosis += "\n## Suggested Patch\n";
        diagnosis += "Review the source code above for the issues identified. "
                     "Add bounds checking, null pointer guards, or input validation as appropriate.\n";
    }

    std::printf("\n");
    std::printf("\033[31m╔══════════════════════════════════════════════════════════════════════════════╗\033[0m\n");
    std::printf("\033[31m║  💀 KERNEL PANIC — AUTOMATIC CRASH DIAGNOSIS                               ║\033[0m\n");
    std::printf("\033[31m╠══════════════════════════════════════════════════════════════════════════════╣\033[0m\n");
    std::printf("\033[31m║  Core Dump: %-62s ║\033[0m\n", dump_path.c_str());
    std::printf("\033[31m║  Crash Type: %-61s ║\033[0m\n", crash_type.c_str());
    std::printf("\033[31m║  Agent ID: %-63d ║\033[0m\n", agent_id);
    std::printf("\033[31m╠══════════════════════════════════════════════════════════════════════════════╣\033[0m\n");

    std::istringstream iss(diagnosis);
    std::string line;
    while (std::getline(iss, line)) {
        std::printf("\033[31m║  %-76s ║\033[0m\n", line.c_str());
    }

    std::printf("\033[31m╚══════════════════════════════════════════════════════════════════════════════╝\033[0m\n");
    std::printf("\n");

    KernelLogger::instance().log_alert(
        "[CrashAnalyzer] PANIC DIAGNOSIS for Agent " + std::to_string(agent_id) +
        " (" + crash_type + "): " + diagnosis.substr(0, 200));

    nlohmann::json updated_dump;
    try {
        updated_dump = nlohmann::json::parse(dump_json);
    } catch (...) {
        updated_dump = nlohmann::json::object();
    }
    updated_dump["diagnosis"] = diagnosis;
    updated_dump["diagnosis_timestamp"] = []() -> std::string {
        auto now = std::chrono::system_clock::now();
        auto t = std::chrono::system_clock::to_time_t(now);
        char buf[64];
        std::strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", std::localtime(&t));
        return std::string(buf);
    }();

    node->write(updated_dump.dump(2));

    std::printf("[CrashAnalyzer] ✅ Diagnosis complete and appended to core dump: %s\n",
                dump_path.c_str());
}

void CrashAnalyzer::analyze_panic_for_agent(int agent_id) {
    std::string dump_path = "/var/crash/core_dump_" + std::to_string(agent_id) + ".json";
    analyze_panic(dump_path);
}

} // namespace aios

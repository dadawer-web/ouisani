#include "aios/bpf_manager.h"
#include "aios/event_bus.h"

#include <wasmedge/wasmedge.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>

namespace aios {

BpfManager& BpfManager::instance() {
    static BpfManager mgr;
    return mgr;
}

bool BpfManager::load_bpf_program(const std::string& hook_point,
                                    const std::string& wasm_path,
                                    const std::string& export_func) {
    std::lock_guard<std::mutex> lock(mutex_);

    std::ifstream test_file(wasm_path, std::ios::binary);
    if (!test_file.is_open()) {
        std::printf("[BpfManager] LOAD FAILED | hook=%s | file=%s not found\n",
                    hook_point.c_str(), wasm_path.c_str());
        return false;
    }
    test_file.close();

    BpfHookEntry entry;
    entry.hook_point = hook_point;
    entry.wasm_path = wasm_path;
    entry.export_func = export_func;
    entry.active = true;
    entry.invoke_count = 0;
    entry.drop_count = 0;

    hooks_[hook_point] = std::move(entry);

    std::printf("[BpfManager] LOADED | hook=%s | wasm=%s | func=%s\n",
                hook_point.c_str(), wasm_path.c_str(), export_func.c_str());

    EventBus::instance().publish(EventType::AGENT_SPAWN, "BpfManager",
        "BPF program loaded at " + hook_point + " from " + wasm_path);

    return true;
}

bool BpfManager::unload_bpf_program(const std::string& hook_point) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = hooks_.find(hook_point);
    if (it == hooks_.end()) {
        std::printf("[BpfManager] UNLOAD FAILED | hook=%s not found\n", hook_point.c_str());
        return false;
    }

    std::printf("[BpfManager] UNLOADED | hook=%s | invokes=%zu | drops=%zu\n",
                hook_point.c_str(), it->second.invoke_count, it->second.drop_count);

    hooks_.erase(it);
    return true;
}

std::string BpfManager::execute_bpf_filter(const std::string& wasm_path,
                                             const std::string& export_func,
                                             const std::string& payload) {
    WasmEdge_ConfigureContext* conf = WasmEdge_ConfigureCreate();
    WasmEdge_ConfigureAddHostRegistration(conf, WasmEdge_HostRegistration_Wasi);
    WasmEdge_ConfigureSetMaxMemoryPage(conf, 64);
    WasmEdge_ConfigureStatisticsSetInstructionCounting(conf, true);
    WasmEdge_ConfigureStatisticsSetCostMeasuring(conf, true);

    WasmEdge_VMContext* vm = WasmEdge_VMCreate(conf, nullptr);
    WasmEdge_ConfigureDelete(conf);

    if (!vm) {
        std::printf("[BpfManager] VM create failed for %s\n", wasm_path.c_str());
        return payload;
    }

    WasmEdge_StatisticsContext* stat = WasmEdge_VMGetStatisticsContext(vm);
    if (stat) {
        WasmEdge_StatisticsSetCostLimit(stat, 5000000);
    }

    WasmEdge_Result load_res = WasmEdge_VMLoadWasmFromFile(vm, wasm_path.c_str());
    if (!WasmEdge_ResultOK(load_res)) {
        std::printf("[BpfManager] WASM load failed: %s | %s\n",
                    wasm_path.c_str(), WasmEdge_ResultGetMessage(load_res));
        WasmEdge_VMDelete(vm);
        return payload;
    }

    WasmEdge_Result validate_res = WasmEdge_VMValidate(vm);
    if (!WasmEdge_ResultOK(validate_res)) {
        std::printf("[BpfManager] WASM validate failed: %s\n",
                    WasmEdge_ResultGetMessage(validate_res));
        WasmEdge_VMDelete(vm);
        return payload;
    }

    WasmEdge_Result inst_res = WasmEdge_VMInstantiate(vm);
    if (!WasmEdge_ResultOK(inst_res)) {
        std::printf("[BpfManager] WASM instantiate failed: %s\n",
                    WasmEdge_ResultGetMessage(inst_res));
        WasmEdge_VMDelete(vm);
        return payload;
    }

    WasmEdge_MemoryInstanceContext* mem = nullptr;
    const WasmEdge_ModuleInstanceContext* active_mod = WasmEdge_VMGetActiveModule(vm);
    if (active_mod) {
        uint32_t mem_count = WasmEdge_ModuleInstanceListMemoryLength(active_mod);
        if (mem_count > 0) {
            std::vector<WasmEdge_String> mem_names(mem_count);
            WasmEdge_ModuleInstanceListMemory(active_mod, mem_names.data(), mem_count);
            mem = WasmEdge_ModuleInstanceFindMemory(active_mod, mem_names[0]);
        }
    }

    if (!mem) {
        std::printf("[BpfManager] No memory export in %s, passing through\n", wasm_path.c_str());
        WasmEdge_VMDelete(vm);
        return payload;
    }

    uint32_t input_offset = 0;
    uint32_t input_len = static_cast<uint32_t>(payload.size());

    if (input_len > 0) {
        input_offset = 262144;
        WasmEdge_Result set_res = WasmEdge_MemoryInstanceSetData(
            mem, reinterpret_cast<const uint8_t*>(payload.data()),
            input_offset, input_len);
        if (!WasmEdge_ResultOK(set_res)) {
            std::printf("[BpfManager] Memory write failed\n");
            WasmEdge_VMDelete(vm);
            return payload;
        }
        uint8_t null_term = 0;
        WasmEdge_MemoryInstanceSetData(mem, &null_term, input_offset + input_len, 1);
    }

    WasmEdge_Value params[2] = {
        WasmEdge_ValueGenI32(static_cast<int32_t>(input_offset)),
        WasmEdge_ValueGenI32(static_cast<int32_t>(input_len)),
    };
    WasmEdge_Value returns[1] = {
        WasmEdge_ValueGenI32(0),
    };

    WasmEdge_String func_name = WasmEdge_StringCreateByCString(export_func.c_str());
    WasmEdge_Result exec_res = WasmEdge_VMExecute(vm, func_name, params, 2, returns, 1);
    WasmEdge_StringDelete(func_name);

    if (!WasmEdge_ResultOK(exec_res)) {
        std::printf("[BpfManager] Execute %s failed: %s\n",
                    export_func.c_str(), WasmEdge_ResultGetMessage(exec_res));
        WasmEdge_VMDelete(vm);
        return payload;
    }

    int32_t ret_val = WasmEdge_ValueGetI32(returns[0]);

    std::string result;
    if (ret_val == -1) {
        result = payload;
    } else if (ret_val == 0) {
        result = "";
    } else if (ret_val > 0 && ret_val < 65536) {
        const uint32_t out_offset = 327680;
        std::vector<uint8_t> out_buf(ret_val);
        WasmEdge_Result get_res = WasmEdge_MemoryInstanceGetData(
            mem, out_buf.data(), out_offset, ret_val);
        if (WasmEdge_ResultOK(get_res)) {
            result = std::string(out_buf.begin(), out_buf.end());
        } else {
            std::printf("[BpfManager] Output memory read failed\n");
            result = payload;
        }
    }

    WasmEdge_VMDelete(vm);

    if (stat) {
        uint64_t instr_count = WasmEdge_StatisticsGetInstrCount(stat);
        std::printf("[BpfManager] BPF filter executed | instructions=%llu\n",
                    (unsigned long long)instr_count);
    }

    return result;
}

std::string BpfManager::run_hook(const std::string& hook_point,
                                  const std::string& payload) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = hooks_.find(hook_point);
    if (it == hooks_.end() || !it->second.active) {
        return payload;
    }

    total_invokes_++;
    it->second.invoke_count++;

    std::printf("[BpfManager] HOOK %s | payload_len=%zu | running BPF filter...\n",
                hook_point.c_str(), payload.size());

    std::string result = execute_bpf_filter(
        it->second.wasm_path, it->second.export_func, payload);

    if (result.empty() || result == "[KERNEL_DROP_MALICIOUS_INTENT]") {
        total_drops_++;
        it->second.drop_count++;
        std::printf("[BpfManager] HOOK %s | DROPPED (malicious intent circuit break)\n",
                    hook_point.c_str());
        EventBus::instance().publish(EventType::WASM_TRAP, "BpfManager",
            "BPF hook " + hook_point + " dropped payload (malicious intent)");
        return "";
    } else {
        std::printf("[BpfManager] HOOK %s | PASSED | result_len=%zu\n",
                    hook_point.c_str(), result.size());
    }

    return result;
}

bool BpfManager::has_hook(const std::string& hook_point) const {
    std::lock_guard<std::mutex> lock(mutex_);
    return hooks_.count(hook_point) > 0 && hooks_.at(hook_point).active;
}

std::vector<BpfHookEntry> BpfManager::list_hooks() const {
    std::lock_guard<std::mutex> lock(mutex_);
    std::vector<BpfHookEntry> result;
    for (const auto& [k, v] : hooks_) {
        result.push_back(v);
    }
    return result;
}

size_t BpfManager::total_invokes() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return total_invokes_;
}

size_t BpfManager::total_drops() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return total_drops_;
}

} // namespace aios

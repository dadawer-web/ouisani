#pragma once

#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace aios {

struct BpfHookEntry {
    std::string hook_point;
    std::string wasm_path;
    std::string export_func;
    bool active;
    size_t invoke_count;
    size_t drop_count;
};

class BpfManager {
public:
    static BpfManager& instance();

    bool load_bpf_program(const std::string& hook_point,
                          const std::string& wasm_path,
                          const std::string& export_func = "bpf_filter");

    bool unload_bpf_program(const std::string& hook_point);

    std::string run_hook(const std::string& hook_point,
                         const std::string& payload);

    bool has_hook(const std::string& hook_point) const;

    std::vector<BpfHookEntry> list_hooks() const;

    size_t total_invokes() const;
    size_t total_drops() const;

private:
    BpfManager() = default;

    std::string execute_bpf_filter(const std::string& wasm_path,
                                   const std::string& export_func,
                                   const std::string& payload);

    mutable std::mutex mutex_;
    std::unordered_map<std::string, BpfHookEntry> hooks_;
    size_t total_invokes_ = 0;
    size_t total_drops_ = 0;
};

} // namespace aios

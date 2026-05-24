#pragma once

#include "aios/vfs_node.h"

#include <cstdio>
#include <memory>
#include <mutex>
#include <string>

namespace aios {

class LlmAdapter;

class WasmNode : public VfsNode {
public:
    WasmNode(const std::string& path, const std::string& wasm_file_path);
    ~WasmNode() override = default;

    std::string execute(const std::string& payload) override;

    const std::string& wasm_file_path() const { return wasm_file_path_; }

    void set_wasm_file_path(const std::string& p) { wasm_file_path_ = p; }

    static std::shared_ptr<aios::LlmAdapter> g_llm;
    static void SetGlobalLlm(std::shared_ptr<aios::LlmAdapter> llm);

    static void SendSignal(int agent_id, int signum);
    static int CheckSignal(int agent_id);

private:
    std::string wasm_file_path_;
    std::mutex exec_mutex_;
};

} // namespace aios

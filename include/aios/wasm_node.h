#pragma once

#include "aios/vfs_node.h"

#include <cstdio>
#include <mutex>
#include <string>

namespace aios {

class WasmNode : public VfsNode {
public:
    WasmNode(const std::string& path, const std::string& wasm_file_path);
    ~WasmNode() override = default;

    std::string execute(const std::string& payload) override;

    const std::string& wasm_file_path() const { return wasm_file_path_; }

    void set_wasm_file_path(const std::string& p) { wasm_file_path_ = p; }

private:
    std::string wasm_file_path_;
    std::mutex exec_mutex_;
};

} // namespace aios

#pragma once

#include <string>

namespace aios {

struct CompileResult {
    bool success;
    std::string wasm_path;
    std::string error_msg;
    int exit_code;
};

class CompilerBridge {
public:
    static CompileResult CompileToWasm(int agent_id, const std::string& c_code);

    static bool CheckClangAvailable();

private:
    static std::string GetTaskDir();
    static std::string GetCFilePath(int agent_id);
    static std::string GetWasmFilePath(int agent_id);
    static std::string GetErrorLogPath(int agent_id);
};

} // namespace aios

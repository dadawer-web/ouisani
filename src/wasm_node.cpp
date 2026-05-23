#include "aios/wasm_node.h"

#include <wasmedge/wasmedge.h>

#include <cstdio>
#include <cstring>
#include <mutex>
#include <nlohmann/json.hpp>
#include <string>
#include <unistd.h>
#include <vector>

namespace aios {

WasmNode::WasmNode(const std::string& path, const std::string& wasm_file_path)
    : VfsNode(VfsNodeType::WASM, path)
    , wasm_file_path_(wasm_file_path)
{
    std::printf("[WasmNode] Created: %s -> %s\n", path.c_str(), wasm_file_path.c_str());
}

static std::string read_pipe_read_end(int read_fd) {
    std::string output;
    char buf[4096];
    ssize_t n;
    while ((n = read(read_fd, buf, sizeof(buf))) > 0) {
        output.append(buf, static_cast<size_t>(n));
    }
    while (!output.empty() && (output.back() == '\n' || output.back() == '\r')) {
        output.pop_back();
    }
    return output;
}

std::string WasmNode::execute(const std::string& payload) {
    std::lock_guard<std::mutex> lock(exec_mutex_);

    std::string wasm_file = wasm_file_path_;
    std::string func_name_str = "_start";
    std::vector<WasmEdge_Value> params;

    if (!payload.empty()) {
        auto parsed = nlohmann::json::parse(payload, nullptr, false);
        if (!parsed.is_discarded() && parsed.is_object()) {
            if (parsed.contains("file") && parsed["file"].is_string()) {
                wasm_file = parsed["file"].get<std::string>();
            }
            if (parsed.contains("func") && parsed["func"].is_string()) {
                func_name_str = parsed["func"].get<std::string>();
            }
            if (parsed.contains("args") && parsed["args"].is_array()) {
                for (const auto& arg : parsed["args"]) {
                    if (arg.is_number_integer()) {
                        params.push_back(WasmEdge_ValueGenI32(arg.get<int32_t>()));
                    }
                }
            }
        }
    }

    std::printf("[WasmNode] Executing: %s | func=%s | args=%zu\n",
                wasm_file.c_str(), func_name_str.c_str(), params.size());

    int pipefd[2];
    if (pipe(pipefd) < 0) {
        return "[WasmNode ERROR] pipe() failed";
    }

    int saved_stdout = dup(STDOUT_FILENO);
    if (saved_stdout < 0) {
        close(pipefd[0]);
        close(pipefd[1]);
        return "[WasmNode ERROR] dup(STDOUT) failed";
    }

    int saved_stderr = -1;

    dup2(pipefd[1], STDOUT_FILENO);
    close(pipefd[1]);

    std::string captured_output;
    int wasm_exit_code = -1;

    WasmEdge_ConfigureContext* conf = WasmEdge_ConfigureCreate();
    if (!conf) {
        fflush(stdout);
        fflush(stderr);
        dup2(saved_stdout, STDOUT_FILENO);
        dup2(saved_stderr, STDERR_FILENO);
        close(saved_stdout);
        close(saved_stderr);
        close(pipefd[0]);
        return "[WasmNode ERROR] Failed to create WasmEdge configure";
    }

    WasmEdge_ConfigureAddHostRegistration(conf, WasmEdge_HostRegistration_Wasi);

    WasmEdge_VMContext* vm = WasmEdge_VMCreate(conf, nullptr);
    WasmEdge_ConfigureDelete(conf);

    if (!vm) {
        fflush(stdout);
        fflush(stderr);
        dup2(saved_stdout, STDOUT_FILENO);
        dup2(saved_stderr, STDERR_FILENO);
        close(saved_stdout);
        close(saved_stderr);
        close(pipefd[0]);
        return "[WasmNode ERROR] Failed to create WasmEdge VM";
    }

    WasmEdge_ModuleInstanceContext* wasi_module =
        WasmEdge_VMGetImportModuleContext(vm, WasmEdge_HostRegistration_Wasi);
    if (wasi_module) {
        const char* args[] = {wasm_file.c_str(), nullptr};
        WasmEdge_ModuleInstanceInitWASI(wasi_module, args, 1, nullptr, 0, nullptr, 0);
    }

    WasmEdge_String func_name = WasmEdge_StringWrap(
        func_name_str.c_str(),
        static_cast<uint32_t>(func_name_str.size()));

    WasmEdge_Result exec_res;

    if (func_name_str == "_start" && params.empty()) {
        exec_res = WasmEdge_VMRunWasmFromFile(
            vm, wasm_file.c_str(), func_name, nullptr, 0, nullptr, 0);
    } else {
        WasmEdge_Result load_res = WasmEdge_VMLoadWasmFromFile(vm, wasm_file.c_str());
        if (!WasmEdge_ResultOK(load_res)) {
            fflush(stdout);
            fflush(stderr);
            dup2(saved_stdout, STDOUT_FILENO);
            dup2(saved_stderr, STDERR_FILENO);
            close(saved_stdout);
            close(saved_stderr);
            captured_output = read_pipe_read_end(pipefd[0]);
            close(pipefd[0]);
            WasmEdge_VMDelete(vm);
            std::printf("[WasmNode ERROR] Load failed: %s\n", wasm_file.c_str());
            return "[WasmNode ERROR] Failed to load WASM file: " + wasm_file;
        }

        WasmEdge_Result validate_res = WasmEdge_VMValidate(vm);
        if (!WasmEdge_ResultOK(validate_res)) {
            fflush(stdout);
            fflush(stderr);
            dup2(saved_stdout, STDOUT_FILENO);
            dup2(saved_stderr, STDERR_FILENO);
            close(saved_stdout);
            close(saved_stderr);
            captured_output = read_pipe_read_end(pipefd[0]);
            close(pipefd[0]);
            WasmEdge_VMDelete(vm);
            std::printf("[WasmNode ERROR] Validate failed\n");
            return "[WasmNode ERROR] WASM validation failed";
        }

        WasmEdge_Result instantiate_res = WasmEdge_VMInstantiate(vm);
        if (!WasmEdge_ResultOK(instantiate_res)) {
            fflush(stdout);
            fflush(stderr);
            dup2(saved_stdout, STDOUT_FILENO);
            dup2(saved_stderr, STDERR_FILENO);
            close(saved_stdout);
            close(saved_stderr);
            captured_output = read_pipe_read_end(pipefd[0]);
            close(pipefd[0]);
            WasmEdge_VMDelete(vm);
            std::printf("[WasmNode ERROR] Instantiate failed\n");
            return "[WasmNode ERROR] WASM instantiation failed";
        }

        const WasmEdge_FunctionTypeContext* func_type =
            WasmEdge_VMGetFunctionType(vm, func_name);
        if (!func_type) {
            func_name = WasmEdge_StringWrap("run", 3);
            func_type = WasmEdge_VMGetFunctionType(vm, func_name);
        }
        if (!func_type) {
            func_name = WasmEdge_StringWrap("_start", 6);
            func_type = WasmEdge_VMGetFunctionType(vm, func_name);
        }
        if (!func_type) {
            fflush(stdout);
            fflush(stderr);
            dup2(saved_stdout, STDOUT_FILENO);
            dup2(saved_stderr, STDERR_FILENO);
            close(saved_stdout);
            close(saved_stderr);
            captured_output = read_pipe_read_end(pipefd[0]);
            close(pipefd[0]);
            WasmEdge_VMDelete(vm);
            std::printf("[WasmNode ERROR] No '%s' or 'run' or '_start' export found\n",
                        func_name_str.c_str());
            return "[WasmNode ERROR] No exportable function found";
        }

        uint32_t param_count = WasmEdge_FunctionTypeGetParametersLength(func_type);
        uint32_t return_count = WasmEdge_FunctionTypeGetReturnsLength(func_type);

        std::vector<WasmEdge_Value> actual_params;
        for (uint32_t i = 0; i < param_count && i < static_cast<uint32_t>(params.size()); ++i) {
            actual_params.push_back(params[i]);
        }
        while (actual_params.size() < param_count) {
            actual_params.push_back(WasmEdge_ValueGenI32(0));
        }

        std::vector<WasmEdge_Value> returns(return_count > 0 ? return_count : 1);

        if (param_count == 0) {
            exec_res = WasmEdge_VMExecute(vm, func_name, nullptr, 0,
                                           returns.data(), returns.size());
        } else {
            exec_res = WasmEdge_VMExecute(vm, func_name,
                                           actual_params.data(), actual_params.size(),
                                           returns.data(), returns.size());
        }

        if (WasmEdge_ResultOK(exec_res) && return_count > 0) {
            wasm_exit_code = WasmEdge_ValueGetI32(returns[0]);
        }
    }

    if (func_name_str == "_start" && params.empty()) {
        if (WasmEdge_ResultOK(exec_res)) {
            wasm_exit_code = 0;
        }
    }

    fflush(stdout);
    fflush(stderr);

    dup2(saved_stdout, STDOUT_FILENO);
    dup2(saved_stderr, STDERR_FILENO);
    close(saved_stdout);
    close(saved_stderr);

    captured_output = read_pipe_read_end(pipefd[0]);
    close(pipefd[0]);

    WasmEdge_VMDelete(vm);

    nlohmann::json result_json;
    result_json["file"] = wasm_file;
    result_json["func"] = func_name_str;

    if (WasmEdge_ResultOK(exec_res)) {
        result_json["status"] = "ok";
        result_json["exit_code"] = wasm_exit_code;
        if (!captured_output.empty()) {
            result_json["stdout"] = captured_output;
        }
        std::printf("[WasmNode] OK | func=%s | exit=%d | stdout=%zu bytes\n",
                    func_name_str.c_str(), wasm_exit_code, captured_output.size());
    } else {
        result_json["status"] = "error";
        result_json["code"] = static_cast<unsigned>(WasmEdge_ResultGetCode(exec_res));
        if (!captured_output.empty()) {
            result_json["stdout"] = captured_output;
        }
        std::printf("[WasmNode ERROR] Execution failed (code=%u) | stdout=%zu bytes\n",
                    static_cast<unsigned>(WasmEdge_ResultGetCode(exec_res)),
                    captured_output.size());
    }

    return result_json.dump();
}

} // namespace aios

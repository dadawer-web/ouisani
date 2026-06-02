#include "aios/compiler_bridge.h"

#include <cstdio>
#include <fcntl.h>
#include <fstream>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

namespace aios {

static const char* WASI_SDK_PATH = "/opt/wasi-sdk";
static const char* WASI_SYSROOT_PATH = "/opt/wasi-sdk/share/wasi-sysroot";

static bool file_exists(const std::string& path) {
    struct stat st;
    return stat(path.c_str(), &st) == 0;
}

static bool wasi_sdk_available() {
    return file_exists(std::string(WASI_SDK_PATH) + "/bin/clang") &&
           file_exists(std::string(WASI_SYSROOT_PATH) + "/include/wasm32-wasi/stdio.h");
}

static std::string UnescapeCode(const std::string& payload) {
    std::string unescaped;
    unescaped.reserve(payload.size());
    for (size_t i = 0; i < payload.length(); ++i) {
        if (payload[i] == '\\' && i + 1 < payload.length()) {
            if (payload[i + 1] == 'n') {
                unescaped += '\n';
                i++;
            } else if (payload[i + 1] == '"') {
                unescaped += '"';
                i++;
            } else if (payload[i + 1] == '\\') {
                unescaped += '\\';
                i++;
            } else if (payload[i + 1] == 't') {
                unescaped += '\t';
                i++;
            } else {
                unescaped += payload[i];
            }
        } else {
            unescaped += payload[i];
        }
    }
    return unescaped;
}

std::string CompilerBridge::GetTaskDir() {
    return "/tmp/aios_tasks/";
}

std::string CompilerBridge::GetCFilePath(int agent_id) {
    return GetTaskDir() + "task_" + std::to_string(agent_id) + ".c";
}

std::string CompilerBridge::GetWasmFilePath(int agent_id) {
    return GetTaskDir() + "task_" + std::to_string(agent_id) + ".wasm";
}

std::string CompilerBridge::GetErrorLogPath(int agent_id) {
    return GetTaskDir() + "task_" + std::to_string(agent_id) + ".err";
}

bool CompilerBridge::CheckClangAvailable() {
    pid_t pid = fork();
    if (pid < 0) return false;

    if (pid == 0) {
        int fd = open("/dev/null", O_WRONLY);
        if (fd >= 0) {
            dup2(fd, STDOUT_FILENO);
            dup2(fd, STDERR_FILENO);
            close(fd);
        }
        execlp("clang", "clang", "--version", nullptr);
        _exit(127);
    }

    int status;
    waitpid(pid, &status, 0);
    return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

CompileResult CompilerBridge::CompileToWasm(int agent_id, const std::string& c_code) {
    CompileResult result;
    result.success = false;
    result.exit_code = -1;

    std::string task_dir = GetTaskDir();
    mkdir(task_dir.c_str(), 0777);

    std::string c_file_path = GetCFilePath(agent_id);
    std::string wasm_file_path = GetWasmFilePath(agent_id);
    std::string err_log_path = GetErrorLogPath(agent_id);

    bool has_real_newline = c_code.find('\n') != std::string::npos;
    std::string real_c_code = has_real_newline ? c_code : UnescapeCode(c_code);
    std::ofstream c_file(c_file_path, std::ios::trunc);
    if (!c_file.is_open()) {
        result.error_msg = "[CompilerBridge] Cannot create temp C file: " + c_file_path;
        std::printf("%s\n", result.error_msg.c_str());
        return result;
    }
    c_file << real_c_code;
    c_file.close();

    bool use_wasi = wasi_sdk_available();
    bool has_includes = real_c_code.find("#include") != std::string::npos;

    std::printf("[CompilerBridge] Agent#%d | Source: %s (%zu bytes) | WASI=%s | has_includes=%s\n",
                agent_id, c_file_path.c_str(), c_code.size(),
                use_wasi ? "YES" : "NO",
                has_includes ? "YES" : "NO");

    std::string err_log_path_capture = err_log_path;
    pid_t pid = fork();
    if (pid < 0) {
        result.error_msg = "[CompilerBridge] fork() failed";
        std::printf("%s\n", result.error_msg.c_str());
        return result;
    }

    if (pid == 0) {
        int err_fd = open(err_log_path_capture.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (err_fd >= 0) {
            dup2(err_fd, STDOUT_FILENO);
            dup2(err_fd, STDERR_FILENO);
            close(err_fd);
        }

        std::vector<std::string> args;

        if (use_wasi && has_includes) {
            std::string clang_path = std::string(WASI_SDK_PATH) + "/bin/clang";
            if (!file_exists(clang_path)) {
                clang_path = "clang";
            }

            args = {
                clang_path,
                "--target=wasm32-wasi-threads",
                "--sysroot=" + std::string(WASI_SYSROOT_PATH),
                "-pthread",
                "-matomics",
                "-mbulk-memory",
                "-msimd128",
                "-O3",
                "-o", wasm_file_path,
                c_file_path
            };
        } else if (use_wasi) {
            std::string clang_path = std::string(WASI_SDK_PATH) + "/bin/clang";
            if (!file_exists(clang_path)) {
                clang_path = "clang";
            }

            args = {
                clang_path,
                "--target=wasm32-wasi-threads",
                "--sysroot=" + std::string(WASI_SYSROOT_PATH),
                "-pthread",
                "-matomics",
                "-mbulk-memory",
                "-msimd128",
                "-nostdlib",
                "-Wl,--no-entry",
                "-Wl,--export-all",
                "-O3",
                "-o", wasm_file_path,
                c_file_path
            };
        } else {
            args = {
                "clang",
                "--target=wasm32",
                "-pthread",
                "-matomics",
                "-mbulk-memory",
                "-msimd128",
                "-nostdlib",
                "-Wl,--no-entry",
                "-Wl,--export-all",
                "-O3",
                "-o", wasm_file_path,
                c_file_path
            };
        }

        std::vector<char*> c_args;
        for (const auto& arg : args) {
            c_args.push_back(const_cast<char*>(arg.c_str()));
        }
        c_args.push_back(nullptr);

        execvp(c_args[0], c_args.data());

        std::fprintf(stderr, "[CompilerBridge FATAL] execvp failed\n");
        _exit(127);
    }

    int status;
    waitpid(pid, &status, 0);

    if (WIFEXITED(status)) {
        result.exit_code = WEXITSTATUS(status);

        if (result.exit_code == 0) {
            struct stat st;
            if (stat(wasm_file_path.c_str(), &st) == 0 && st.st_size > 0) {
                result.success = true;
                result.wasm_path = wasm_file_path;
                std::printf("[CompilerBridge] Agent#%d | Compile SUCCESS -> %s (%ld bytes)\n",
                            agent_id, wasm_file_path.c_str(), st.st_size);
            } else {
                result.error_msg = "[CompilerBridge] clang exited 0 but .wasm missing or empty";
                std::printf("%s\n", result.error_msg.c_str());
            }
        } else {
            std::ifstream err_stream(err_log_path);
            std::string compile_errors;
            if (err_stream.is_open()) {
                std::stringstream ss;
                ss << err_stream.rdbuf();
                compile_errors = ss.str();
            }

            result.error_msg = "[CompilerBridge] clang failed (exit=" +
                               std::to_string(result.exit_code) + "): " +
                               (compile_errors.size() > 500 ?
                                    compile_errors.substr(0, 500) + "..." :
                                    compile_errors);

            std::printf("[CompilerBridge] Agent#%d | Compile FAILED (exit=%d)\n",
                        agent_id, result.exit_code);
        }
    } else {
        result.error_msg = "[CompilerBridge] clang process terminated abnormally";
        std::printf("%s\n", result.error_msg.c_str());
    }

    return result;
}

} // namespace aios

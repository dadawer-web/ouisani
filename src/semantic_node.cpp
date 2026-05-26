#include "aios/semantic_node.h"
#include "aios/kernel_logger.h"
#include "aios/memory_manager.h"
#include "aios/vfs_manager.h"

#include <chrono>
#include <cstdio>
#include <future>
#include <nlohmann/json.hpp>
#include <string>

namespace aios {

SemanticNode::SemanticNode(const std::string& path,
                           SubmitLlmFn submit_llm_fn,
                           SubmitTaskFn submit_task_fn,
                           std::shared_ptr<MemoryManager> mmgr)
    : VfsNode(VfsNodeType::DEVICE, path)
    , submit_llm_fn_(std::move(submit_llm_fn))
    , submit_task_fn_(std::move(submit_task_fn))
    , memory_mgr_(std::move(mmgr))
{
    std::printf("[VFS] SemanticNode mounted at %s (LLM-driven semantic router)\n", path.c_str());
}

std::string SemanticNode::read() const {
    std::lock_guard<std::mutex> lock(result_mutex_);
    if (!last_result_.empty()) {
        return last_result_;
    }
    return "[Semantic VFS] Write natural language to /dev/semantic to route VFS operations.\n"
           "Example: echo '读取101的记忆' > /dev/semantic\n"
           "Supported actions: READ, WRITE, COMPILE_AND_EXECUTE\n"
           "Supported paths: /dev/mem/<id>, /workspace/modules/<name>\n";
}

std::string SemanticNode::last_result() const {
    std::lock_guard<std::mutex> lock(result_mutex_);
    return last_result_;
}

std::shared_ptr<VfsNode> SemanticNode::resolve_or_create(const std::string& target_path) {
    auto& vfs = VfsManager::instance();
    auto node = vfs.resolve_path(target_path);
    if (node) return node;
    return vfs.resolve_or_create_mem(target_path, memory_mgr_);
}

bool SemanticNode::write(const std::string& data) {
    if (data.empty()) {
        std::printf("[SemanticNode] Empty input, ignoring\n");
        return false;
    }

    if (!submit_llm_fn_) {
        std::printf("[SemanticNode] ERROR: No LLM submit function bound\n");
        return false;
    }

    std::printf("[SemanticNode] Received natural language: \"%s\"\n",
                data.size() > 100 ? (data.substr(0, 100) + "...").c_str() : data.c_str());

    KernelLogger::instance().log("[Semantic VFS] Natural language input: " + data);

    std::string prompt =
        "你是一个操作系统的 VFS 路由器。当前系统支持以下操作：\n"
        "1. READ: 读取文件/设备内容。路径格式: /dev/mem/<id> (读取Agent记忆), /workspace/modules/<name> (WASM模块)\n"
        "2. WRITE: 写入数据到文件/设备。路径格式同上，需提供 payload 字段\n"
        "3. COMPILE_AND_EXECUTE: 编译并执行 C 语言代码。需提供 code 字段（完整的 C 源码）和 func 字段（入口函数名，默认 _start）\n"
        "\n"
        "用户的意图是：'" + data + "'\n"
        "\n"
        "请严格输出一段合法的 JSON，不要任何其他解释。格式示例：\n"
        "- 读取记忆: {\"action\": \"READ\", \"path\": \"/dev/mem/101\"}\n"
        "- 写入数据: {\"action\": \"WRITE\", \"path\": \"/dev/mem/101\", \"payload\": \"要写入的内容\"}\n"
        "- 编译执行: {\"action\": \"COMPILE_AND_EXECUTE\", \"code\": \"完整的C语言源码\", \"func\": \"_start\"}\n";

    auto promise_ptr = std::make_shared<std::promise<std::string>>();
    auto future = promise_ptr->get_future();

    auto task = std::make_shared<AgentTask>(
        0, 99, TaskStatus::READY,
        prompt, TaskType::LLM_INFERENCE, "", "", -1
    );

    task->set_response_callback([promise_ptr](int /*fd*/, const std::string& response) {
        nlohmann::json resp;
        try {
            resp = nlohmann::json::parse(response);
        } catch (...) {
            promise_ptr->set_value(response);
            return;
        }

        if (resp.contains("data") && resp["data"].is_object()) {
            if (resp["data"].contains("response") && resp["data"]["response"].is_string()) {
                promise_ptr->set_value(resp["data"]["response"].get<std::string>());
                return;
            }
        }

        promise_ptr->set_value(response);
    });

    submit_llm_fn_(task);

    std::printf("[SemanticNode] Waiting for LLM translation (priority=99)...\n");

    std::string llm_response;
    try {
        llm_response = future.get();
    } catch (const std::exception& e) {
        std::printf("[SemanticNode] future.get() exception: %s\n", e.what());
        std::lock_guard<std::mutex> lock(result_mutex_);
        last_result_ = "{\"status\":\"error\",\"message\":\"语义解析失败: future exception\"}";
        return false;
    }

    std::printf("[SemanticNode] LLM response: \"%s\"\n",
                llm_response.size() > 300 ? (llm_response.substr(0, 300) + "...").c_str()
                                           : llm_response.c_str());

    KernelLogger::instance().log("[Semantic VFS] LLM translated: " + llm_response);

    nlohmann::json parsed;
    try {
        std::string json_str = llm_response;

        auto json_start = llm_response.find('{');
        auto json_end = llm_response.rfind('}');
        if (json_start != std::string::npos && json_end != std::string::npos && json_end > json_start) {
            json_str = llm_response.substr(json_start, json_end - json_start + 1);
        }

        parsed = nlohmann::json::parse(json_str);
    } catch (const nlohmann::json::parse_error& e) {
        std::printf("[SemanticNode] JSON parse error: %s\n", e.what());
        KernelLogger::instance().log("[Semantic VFS] Parse failed: " + std::string(e.what()));
        std::lock_guard<std::mutex> lock(result_mutex_);
        last_result_ = "{\"status\":\"error\",\"message\":\"语义解析失败: LLM 返回非法 JSON\"}";
        return false;
    }

    if (!parsed.contains("action") || !parsed["action"].is_string()) {
        std::printf("[SemanticNode] Missing 'action' field in LLM response\n");
        std::lock_guard<std::mutex> lock(result_mutex_);
        last_result_ = "{\"status\":\"error\",\"message\":\"语义解析失败: 缺少 action 字段\"}";
        return false;
    }

    std::string action = parsed["action"].get<std::string>();

    if (action == "COMPILE_AND_EXECUTE") {
        std::string c_code = parsed.value("code", "");
        std::string func = parsed.value("func", "_start");

        if (c_code.empty()) {
            std::printf("[SemanticNode] COMPILE_AND_EXECUTE but no code provided\n");
            std::lock_guard<std::mutex> lock(result_mutex_);
            last_result_ = "{\"status\":\"error\",\"message\":\"语义解析失败: COMPILE_AND_EXECUTE 缺少 code 字段\"}";
            return false;
        }

        if (!submit_task_fn_) {
            std::printf("[SemanticNode] ERROR: No task submit function bound for COMPILE_AND_EXECUTE\n");
            std::lock_guard<std::mutex> lock(result_mutex_);
            last_result_ = "{\"status\":\"error\",\"message\":\"内核未绑定任务提交接口\"}";
            return false;
        }

        std::printf("[SemanticNode] Syscall Replay: COMPILE_AND_EXECUTE | code=%zu bytes | func=%s\n",
                    c_code.size(), func.c_str());

        nlohmann::json compile_payload;
        compile_payload["code"] = c_code;
        compile_payload["func"] = func;

        auto compile_promise = std::make_shared<std::promise<std::string>>();
        auto compile_future = compile_promise->get_future();

        auto compile_task = std::make_shared<AgentTask>(
            0, 50, TaskStatus::READY,
            compile_payload.dump(), TaskType::VFS_CALL,
            "COMPILE_AND_EXECUTE", "", -1
        );

        compile_task->set_response_callback([compile_promise](int /*fd*/, const std::string& response) {
            compile_promise->set_value(response);
        });

        submit_task_fn_(compile_task);

        std::printf("[SemanticNode] Waiting for COMPILE_AND_EXECUTE result...\n");

        std::string compile_result;
        try {
            compile_result = compile_future.get();
        } catch (const std::exception& e) {
            std::printf("[SemanticNode] COMPILE_AND_EXECUTE future exception: %s\n", e.what());
            std::lock_guard<std::mutex> lock(result_mutex_);
            last_result_ = "{\"status\":\"error\",\"message\":\"编译执行失败: " + std::string(e.what()) + "\"}";
            return false;
        }

        std::printf("[SemanticNode] COMPILE_AND_EXECUTE complete: %zu bytes\n", compile_result.size());

        nlohmann::json result;
        try {
            auto compile_resp = nlohmann::json::parse(compile_result);
            result["status"] = compile_resp.value("status", "unknown");
            result["action"] = "COMPILE_AND_EXECUTE";
            result["message"] = compile_resp.value("message", "");

            if (compile_resp.contains("data")) {
                nlohmann::json inner;
                if (compile_resp["data"].is_string()) {
                    inner = nlohmann::json::parse(compile_resp["data"].get<std::string>(), nullptr, false);
                } else if (compile_resp["data"].is_object()) {
                    inner = compile_resp["data"];
                }

                if (!inner.is_discarded() && inner.is_object()) {
                    result["compile_stage"] = inner.value("stage", "");
                    result["wasm_path"] = inner.value("wasm_path", "");
                    result["output"] = inner.value("output", "");
                    result["stdout"] = inner.value("stdout", "");
                    result["compile_success"] = inner.value("compile", false);
                    result["mount"] = inner.value("mount", "");
                    if (inner.contains("error")) {
                        result["error"] = inner.value("error", "");
                    }
                }
            }
        } catch (...) {
            result["status"] = "ok";
            result["action"] = "COMPILE_AND_EXECUTE";
            result["raw"] = compile_result;
        }

        std::lock_guard<std::mutex> lock(result_mutex_);
        last_result_ = result.dump();
        return result["status"] == "ok";

    } else if (action == "READ") {
        std::string target_path = parsed.value("path", "");
        if (target_path.empty()) {
            std::printf("[SemanticNode] Missing 'path' field in LLM response\n");
            std::lock_guard<std::mutex> lock(result_mutex_);
            last_result_ = "{\"status\":\"error\",\"message\":\"语义解析失败: 缺少 path 字段\"}";
            return false;
        }

        std::printf("[SemanticNode] Syscall Replay: READ | path=%s\n", target_path.c_str());

        auto node = resolve_or_create(target_path);
        if (!node) {
            std::printf("[SemanticNode] VFS path not found: %s\n", target_path.c_str());
            KernelLogger::instance().log("[Semantic VFS] READ failed: path not found " + target_path);
            std::lock_guard<std::mutex> lock(result_mutex_);
            last_result_ = "{\"status\":\"error\",\"message\":\"语义路径不存在: " + target_path + "\"}";
            return false;
        }

        std::string content = node->read();
        std::printf("[SemanticNode] READ %s -> %zu bytes\n", target_path.c_str(), content.size());
        KernelLogger::instance().log("[Semantic VFS] READ " + target_path + " -> " + std::to_string(content.size()) + " bytes");

        nlohmann::json result;
        result["status"] = "ok";
        result["action"] = "READ";
        result["path"] = target_path;
        result["content"] = content;

        std::lock_guard<std::mutex> lock(result_mutex_);
        last_result_ = result.dump();
        return true;

    } else if (action == "WRITE") {
        std::string target_path = parsed.value("path", "");
        std::string payload = parsed.value("payload", "");

        if (target_path.empty()) {
            std::printf("[SemanticNode] Missing 'path' field in LLM response\n");
            std::lock_guard<std::mutex> lock(result_mutex_);
            last_result_ = "{\"status\":\"error\",\"message\":\"语义解析失败: 缺少 path 字段\"}";
            return false;
        }

        std::printf("[SemanticNode] Syscall Replay: WRITE | path=%s | payload=%zu bytes\n",
                    target_path.c_str(), payload.size());

        auto node = resolve_or_create(target_path);
        if (!node) {
            std::printf("[SemanticNode] VFS path not found: %s\n", target_path.c_str());
            KernelLogger::instance().log("[Semantic VFS] WRITE failed: path not found " + target_path);
            std::lock_guard<std::mutex> lock(result_mutex_);
            last_result_ = "{\"status\":\"error\",\"message\":\"语义路径不存在: " + target_path + "\"}";
            return false;
        }

        bool ok = node->write(payload);
        std::printf("[SemanticNode] WRITE %s -> %s\n", target_path.c_str(), ok ? "ok" : "failed");
        KernelLogger::instance().log("[Semantic VFS] WRITE " + target_path + " -> " + (ok ? "ok" : "failed"));

        nlohmann::json result;
        result["status"] = ok ? "ok" : "error";
        result["action"] = "WRITE";
        result["path"] = target_path;
        result["message"] = ok ? "语义写入成功" : "语义写入失败";

        std::lock_guard<std::mutex> lock(result_mutex_);
        last_result_ = result.dump();
        return ok;

    } else {
        std::printf("[SemanticNode] Unknown action: %s\n", action.c_str());
        std::lock_guard<std::mutex> lock(result_mutex_);
        last_result_ = "{\"status\":\"error\",\"message\":\"语义解析失败: 未知 action '" + action + "'\"}";
        return false;
    }
}

} // namespace aios

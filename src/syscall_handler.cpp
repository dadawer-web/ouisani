#include "aios/syscall_handler.h"

#include <nlohmann/json.hpp>

#include <cstdio>

namespace aios {

SyscallHandler::SyscallHandler(SubmitTaskFn submit_fn,
                               std::shared_ptr<MemoryManager> memory_mgr)
    : submit_fn_(std::move(submit_fn))
    , memory_mgr_(std::move(memory_mgr))
{}

SyscallResponse SyscallHandler::handle(const std::string& json_str) {
    nlohmann::json req;
    try {
        req = nlohmann::json::parse(json_str);
    } catch (const nlohmann::json::parse_error& e) {
        std::printf("[SyscallHandler] JSON parse error: %s\n", e.what());
        return {false, "invalid JSON", ""};
    }

    if (!req.contains("syscall") || !req["syscall"].is_string()) {
        return {false, "missing or invalid 'syscall' field", ""};
    }

    std::string syscall_name = req["syscall"].get<std::string>();
    int agent_id = req.value("agent_id", -1);

    if (syscall_name == "WRITE_MEMORY") {
        std::string role = req.value("role", "user");
        std::string content = req.value("content", req.value("data", ""));
        return handle_write_memory(agent_id, role, content);
    } else if (syscall_name == "READ_MEMORY") {
        std::string keyword = req.value("keyword", "");
        return handle_read_memory(agent_id, keyword);
    } else if (syscall_name == "EXECUTE_TOOL") {
        std::string data = req.value("data", "");
        int priority = req.value("priority", 1);
        return handle_execute_tool(agent_id, data, priority);
    } else {
        std::printf("[SyscallHandler] Unknown syscall: %s\n", syscall_name.c_str());
        return {false, "unknown syscall: " + syscall_name, ""};
    }
}

SyscallResponse SyscallHandler::handle_write_memory(int agent_id, const std::string& role,
                                                     const std::string& content) {
    std::printf("[SyscallHandler] WRITE_MEMORY | agent_id=%d | role=%s | content=\"%s\"\n",
                agent_id, role.c_str(), content.c_str());

    MemoryPage page;
    page.agent_id = agent_id;
    page.role = role;
    page.content = content;

    std::string page_id = memory_mgr_->write_page(page);

    nlohmann::json resp_data;
    resp_data["page_id"] = page_id;
    return {true, "memory written for agent " + std::to_string(agent_id), resp_data.dump()};
}

SyscallResponse SyscallHandler::handle_read_memory(int agent_id, const std::string& keyword) {
    std::printf("[SyscallHandler] READ_MEMORY | agent_id=%d | keyword=\"%s\"\n",
                agent_id, keyword.c_str());

    if (keyword.empty()) {
        auto pages = memory_mgr_->read_pages(agent_id);
        nlohmann::json arr = nlohmann::json::array();
        for (const auto& p : pages) {
            nlohmann::json obj;
            obj["page_id"] = p.page_id;
            obj["role"] = p.role;
            obj["content"] = p.content;
            arr.push_back(obj);
        }
        return {true, "memory read for agent " + std::to_string(agent_id), arr.dump()};
    }

    auto page = memory_mgr_->read_page_by_keyword(agent_id, keyword);
    if (page.page_id.empty()) {
        return {false, "page not found for keyword: " + keyword, ""};
    }

    nlohmann::json obj;
    obj["page_id"] = page.page_id;
    obj["role"] = page.role;
    obj["content"] = page.content;
    return {true, "memory read for agent " + std::to_string(agent_id), obj.dump()};
}

SyscallResponse SyscallHandler::handle_execute_tool(int agent_id, const std::string& data, int priority) {
    std::printf("[SyscallHandler] EXECUTE_TOOL | agent_id=%d | priority=%d | data=\"%s\"\n",
                agent_id, priority, data.c_str());

    if (submit_fn_) {
        submit_fn_(agent_id, priority, data);
    }

    return {true, "task submitted for agent " + std::to_string(agent_id), ""};
}

std::string SyscallResponse::to_json() const {
    nlohmann::json j;
    j["status"] = ok ? "ok" : "error";
    j["message"] = message;
    if (!data.empty()) {
        j["data"] = nlohmann::json::parse(data, nullptr, false);
        if (!j["data"].is_string() && j["data"].is_discarded()) {
            j["data"] = data;
        }
    }
    return j.dump();
}

} // namespace aios

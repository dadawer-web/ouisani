#pragma once

#include "aios/memory_manager.h"

#include <functional>
#include <memory>
#include <string>

namespace aios {

struct SyscallResponse {
    bool ok;
    std::string message;
    std::string data;
    std::string to_json() const;
};

class SyscallHandler {
public:
    using SubmitTaskFn = std::function<void(int agent_id, int priority, const std::string& payload)>;

    SyscallHandler(SubmitTaskFn submit_fn,
                   std::shared_ptr<MemoryManager> memory_mgr);

    SyscallResponse handle(const std::string& json_str);

private:
    SyscallResponse handle_write_memory(int agent_id, const std::string& role,
                                        const std::string& content);
    SyscallResponse handle_read_memory(int agent_id, const std::string& keyword);
    SyscallResponse handle_execute_tool(int agent_id, const std::string& data, int priority);

    SubmitTaskFn submit_fn_;
    std::shared_ptr<MemoryManager> memory_mgr_;
};

} // namespace aios

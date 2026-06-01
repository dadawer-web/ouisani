#pragma once

#include <atomic>
#include <functional>
#include <memory>
#include <string>

namespace aios {

enum class TaskStatus {
    READY,
    RUNNING,
    WAITING,
    SUSPENDED,
    CANCELLED,
    OOM_KILLED,
    CRASHED
};

enum class TaskType {
    LLM_CHAT,
    LLM_INFERENCE,
    TOOL_CALL,
    WRITE_MEMORY,
    READ_MEMORY,
    CANCEL_TASK,
    VFS_CALL,
    PROCESS_CTRL
};

struct AgentTask {
    int agent_id;
    int priority;
    TaskStatus status;
    TaskType type;
    std::string task_payload;
    std::string tool_name;
    std::string tool_code;

    int client_fd;

    std::string role;
    std::string content;
    std::string keyword;

    int gas_limit;
    int gas_used;

    std::string stdin_path;
    std::string stdout_path;

    std::string root_dir;

    std::shared_ptr<std::atomic<bool>> is_cancelled;

    using ResponseCallback = std::function<void(int fd, const std::string& response)>;
    ResponseCallback response_callback_;

    AgentTask(int id, int prio, TaskStatus s, std::string payload,
              TaskType t = TaskType::LLM_CHAT,
              std::string tname = "",
              std::string tcode = "",
              int fd = -1)
        : agent_id(id)
        , priority(prio)
        , status(s)
        , type(t)
        , task_payload(std::move(payload))
        , tool_name(std::move(tname))
        , tool_code(std::move(tcode))
        , client_fd(fd)
        , gas_limit(10000)
        , gas_used(0)
        , is_cancelled(std::make_shared<std::atomic<bool>>(false))
    {}

    void set_response_callback(ResponseCallback cb) {
        response_callback_ = std::move(cb);
    }

    bool cancelled() const {
        return is_cancelled && is_cancelled->load(std::memory_order_relaxed);
    }

    void cancel() {
        if (is_cancelled) {
            is_cancelled->store(true, std::memory_order_relaxed);
        }
    }
};

} // namespace aios

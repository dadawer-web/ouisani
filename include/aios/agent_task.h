#pragma once

#include <atomic>
#include <memory>
#include <string>

namespace aios {

enum class TaskStatus {
    READY,
    RUNNING,
    WAITING,
    SUSPENDED,
    CANCELLED
};

enum class TaskType {
    LLM_CHAT,
    TOOL_CALL,
    WRITE_MEMORY,
    READ_MEMORY,
    CANCEL_TASK,
    VFS_CALL
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

    std::shared_ptr<std::atomic<bool>> is_cancelled;

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
        , is_cancelled(std::make_shared<std::atomic<bool>>(false))
    {}

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

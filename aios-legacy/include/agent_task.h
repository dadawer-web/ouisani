#pragma once

#include <string>

namespace aios {

enum class TaskStatus {
    READY,
    RUNNING,
    WAITING,
    SUSPENDED
};

enum class TaskType {
    VFS_CALL,
    PROCESS_CTRL,
    LLM_INFERENCE,
    LLM_CHAT,
    TOOL_CALL,
    WRITE_MEMORY,
    READ_MEMORY,
    CANCEL_TASK
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
    std::string root_dir;
    int gas_limit;
    int gas_used;
    std::string stdin_path;
    std::string stdout_path;
    std::string role;
    std::string content;
    std::string keyword;

    AgentTask(int id, int prio, TaskStatus s, std::string payload, std::string root = "/")
        : agent_id(id)
        , priority(prio)
        , status(s)
        , type(TaskType::VFS_CALL)
        , task_payload(std::move(payload))
        , client_fd(-1)
        , root_dir(std::move(root))
        , gas_limit(10000)
        , gas_used(0)
    {}

    AgentTask(int id, int prio, TaskStatus s, std::string payload,
              TaskType t, std::string tname, std::string tcode, int fd,
              std::string root = "/")
        : agent_id(id)
        , priority(prio)
        , status(s)
        , type(t)
        , task_payload(std::move(payload))
        , tool_name(std::move(tname))
        , tool_code(std::move(tcode))
        , client_fd(fd)
        , root_dir(std::move(root))
        , gas_limit(10000)
        , gas_used(0)
    {}
};

inline const char* task_type_to_string(TaskType t) {
    switch (t) {
        case TaskType::VFS_CALL:       return "VFS_CALL";
        case TaskType::PROCESS_CTRL:   return "PROCESS_CTRL";
        case TaskType::LLM_INFERENCE:  return "LLM_INFERENCE";
        case TaskType::LLM_CHAT:       return "LLM_CHAT";
        case TaskType::TOOL_CALL:      return "TOOL_CALL";
        case TaskType::WRITE_MEMORY:   return "WRITE_MEMORY";
        case TaskType::READ_MEMORY:    return "READ_MEMORY";
        case TaskType::CANCEL_TASK:    return "CANCEL_TASK";
        default:                       return "UNKNOWN";
    }
}

inline const char* task_status_to_string(TaskStatus s) {
    switch (s) {
        case TaskStatus::READY:    return "READY";
        case TaskStatus::RUNNING:  return "RUNNING";
        case TaskStatus::WAITING:  return "WAITING";
        case TaskStatus::SUSPENDED: return "SUSPENDED";
        default:                   return "UNKNOWN";
    }
}

} // namespace aios

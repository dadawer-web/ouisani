#pragma once

#include <string>

namespace aios {

enum class TaskStatus {
    READY,
    RUNNING,
    WAITING,
    SUSPENDED
};

struct AgentTask {
    int agent_id;
    int priority;
    TaskStatus status;
    std::string task_payload;

    AgentTask(int id, int prio, TaskStatus s, std::string payload)
        : agent_id(id)
        , priority(prio)
        , status(s)
        , task_payload(std::move(payload))
    {}
};

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

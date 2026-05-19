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

} // namespace aios

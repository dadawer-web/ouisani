#pragma once

#include "aios/agent_task.h"

#include <memory>
#include <queue>
#include <vector>

namespace aios {

class ISchedulerStrategy {
public:
    virtual ~ISchedulerStrategy() = default;
    virtual void push(std::shared_ptr<AgentTask> task) = 0;
    virtual std::shared_ptr<AgentTask> pop() = 0;
    virtual bool is_empty() const = 0;
    virtual size_t size() const = 0;
};

class PrioritySchedulerStrategy : public ISchedulerStrategy {
public:
    void push(std::shared_ptr<AgentTask> task) override {
        queue_.push(std::move(task));
    }

    std::shared_ptr<AgentTask> pop() override {
        if (queue_.empty()) return nullptr;
        auto task = queue_.top();
        queue_.pop();
        return task;
    }

    bool is_empty() const override {
        return queue_.empty();
    }

    size_t size() const override {
        return queue_.size();
    }

private:
    struct Compare {
        bool operator()(const std::shared_ptr<AgentTask>& a,
                        const std::shared_ptr<AgentTask>& b) const {
            return a->priority < b->priority;
        }
    };

    std::priority_queue<
        std::shared_ptr<AgentTask>,
        std::vector<std::shared_ptr<AgentTask>>,
        Compare
    > queue_;
};

class FifoSchedulerStrategy : public ISchedulerStrategy {
public:
    void push(std::shared_ptr<AgentTask> task) override {
        queue_.push(std::move(task));
    }

    std::shared_ptr<AgentTask> pop() override {
        if (queue_.empty()) return nullptr;
        auto task = std::move(queue_.front());
        queue_.pop();
        return task;
    }

    bool is_empty() const override {
        return queue_.empty();
    }

    size_t size() const override {
        return queue_.size();
    }

private:
    std::queue<std::shared_ptr<AgentTask>> queue_;
};

} // namespace aios

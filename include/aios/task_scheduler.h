#pragma once

#include "aios/agent_task.h"
#include "aios/llm_adapter.h"
#include "aios/memory_manager.h"

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <queue>
#include <thread>
#include <vector>

namespace aios {

struct TaskComparator {
    bool operator()(const std::shared_ptr<AgentTask>& a,
                    const std::shared_ptr<AgentTask>& b) const {
        return a->priority < b->priority;
    }
};

class TaskScheduler {
public:
    TaskScheduler(size_t worker_count,
                  std::shared_ptr<LlmAdapter> llm,
                  std::shared_ptr<MemoryManager> memory_mgr);
    ~TaskScheduler();

    TaskScheduler(const TaskScheduler&) = delete;
    TaskScheduler& operator=(const TaskScheduler&) = delete;
    TaskScheduler(TaskScheduler&&) = delete;
    TaskScheduler& operator=(TaskScheduler&&) = delete;

    void submit(std::shared_ptr<AgentTask> task);
    void start();
    void shutdown();

    size_t pending_count() const;

private:
    void worker_loop();
    std::string build_prompt(int agent_id, const std::string& current_payload);

    std::priority_queue<
        std::shared_ptr<AgentTask>,
        std::vector<std::shared_ptr<AgentTask>>,
        TaskComparator
    > ready_queue_;

    mutable std::mutex queue_mutex_;
    std::condition_variable queue_cv_;

    std::vector<std::thread> workers_;
    size_t worker_count_;

    std::shared_ptr<LlmAdapter> llm_;
    std::shared_ptr<MemoryManager> memory_mgr_;

    std::atomic<bool> running_{false};
    std::atomic<size_t> active_tasks_{0};
};

} // namespace aios

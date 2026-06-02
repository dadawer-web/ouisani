#pragma once

#include "agent_task.h"

#ifdef __MINGW32__
#include "mingw-std-threads/mingw.thread.h"
#include "mingw-std-threads/mingw.mutex.h"
#include "mingw-std-threads/mingw.condition_variable.h"
#else
#include <condition_variable>
#include <mutex>
#include <thread>
#endif

#include <atomic>
#include <memory>
#include <queue>
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
    explicit TaskScheduler(size_t worker_count = 4);
    ~TaskScheduler();

    TaskScheduler(const TaskScheduler&) = delete;
    TaskScheduler& operator=(const TaskScheduler&) = delete;
    TaskScheduler(TaskScheduler&&) = delete;
    TaskScheduler& operator=(TaskScheduler&&) = delete;

    void start();
    void stop();
    void submit(std::shared_ptr<AgentTask> task);

    size_t pending_count() const;
    bool is_running() const;

private:
    void worker_loop();

    std::priority_queue<
        std::shared_ptr<AgentTask>,
        std::vector<std::shared_ptr<AgentTask>>,
        TaskComparator
    > ready_queue_;

    mutable std::mutex queue_mutex_;
    std::condition_variable queue_cv_;

    std::vector<std::thread> workers_;
    size_t worker_count_;

    std::atomic<bool> running_{false};
    std::atomic<size_t> active_tasks_{0};
};

} // namespace aios

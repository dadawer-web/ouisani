#pragma once

#include "aios/agent_task.h"
#include "aios/device_driver.h"
#include "aios/llm_adapter.h"
#include "aios/memory_manager.h"
#include "aios/thread_pool.h"

#include <atomic>
#include <condition_variable>
#include <functional>
#include <list>
#include <memory>
#include <mutex>
#include <queue>
#include <string>
#include <unordered_map>
#include <vector>

namespace aios {

struct TaskComparator {
    bool operator()(const std::shared_ptr<AgentTask>& a,
                    const std::shared_ptr<AgentTask>& b) const {
        return a->priority < b->priority;
    }
};

using ResponseCallback = std::function<void(int fd, const std::string& response)>;

class TaskScheduler {
public:
    TaskScheduler(size_t dispatch_threads,
                  size_t io_threads,
                  std::shared_ptr<LlmAdapter> llm,
                  std::shared_ptr<MemoryManager> memory_mgr);

    ~TaskScheduler();

    TaskScheduler(const TaskScheduler&) = delete;
    TaskScheduler& operator=(const TaskScheduler&) = delete;
    TaskScheduler(TaskScheduler&&) = delete;
    TaskScheduler& operator=(TaskScheduler&&) = delete;

    void submit(std::shared_ptr<AgentTask> task);
    void cancel_agent(int agent_id);
    void start();
    void shutdown();

    void register_driver(const std::string& name, std::shared_ptr<DeviceDriver> driver);
    void set_response_callback(ResponseCallback cb);

    size_t pending_count() const;
    size_t active_io_count() const;

private:
    void dispatch_loop();
    void handle_write_memory(std::shared_ptr<AgentTask> task);
    void handle_read_memory(std::shared_ptr<AgentTask> task);
    void dispatch_llm_task(std::shared_ptr<AgentTask> task);
    void dispatch_tool_task(std::shared_ptr<AgentTask> task);
    void dispatch_vfs_task(std::shared_ptr<AgentTask> task);
    void dispatch_process_ctrl(std::shared_ptr<AgentTask> task);
    void try_compress(int agent_id);
    std::vector<ChatMessage> build_messages(int agent_id, const std::string& current_payload);

    std::string make_response(bool ok, const std::string& message,
                              const std::string& data = "");

    std::priority_queue<
        std::shared_ptr<AgentTask>,
        std::vector<std::shared_ptr<AgentTask>>,
        TaskComparator
    > ready_queue_;

    mutable std::mutex queue_mutex_;
    std::condition_variable queue_cv_;

    ThreadPool dispatch_pool_;
    ThreadPool io_pool_;

    std::shared_ptr<LlmAdapter> llm_;
    std::shared_ptr<MemoryManager> memory_mgr_;
    std::unordered_map<std::string, std::shared_ptr<DeviceDriver>> drivers_;

    ResponseCallback response_cb_;

    std::mutex active_mutex_;
    std::unordered_map<int, std::vector<std::shared_ptr<AgentTask>>> active_tasks_;

    std::atomic<bool> running_{false};
    std::atomic<size_t> active_io_tasks_{0};
};

} // namespace aios

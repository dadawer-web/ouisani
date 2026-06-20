#pragma once
#include "Task.h"
#include "TaskQueue.h"
#include "Worker.h"
#include <string>
#include <memory>
#include <vector>
#include <functional>
#include <future>

namespace DistributedTaskQueue {

// 分布式任务队列配置
struct TaskQueueConfig {
    size_t max_queue_size = 1000;          // 最大队列大小
    size_t max_workers = 4;                // 最大工作节点数
    bool auto_start_workers = true;        // 是否自动启动工作节点
    size_t cleanup_interval_seconds = 300; // 清理间隔（秒）
};

// 分布式任务队列主类
class DistributedTaskQueueManager {
public:
    DistributedTaskQueueManager(const TaskQueueConfig& config = TaskQueueConfig{});
    ~DistributedTaskQueueManager();

    // 提交任务
    template<typename F, typename... Args>
    auto submitTask(F&& func, Args&&... args) 
        -> std::future<typename std::result_of<F(Args...)>::type>;
    
    // 提交任务（带优先级）
    template<typename F, typename... Args>
    auto submitTaskWithPriority(F&& func, TaskPriority priority, Args&&... args)
        -> std::future<typename std::result_of<F(Args...)>::type>;
    
    // 提交任务（带标签）
    TaskId submitTaskWithTag(const std::string& tag, TaskFunction func, 
                           TaskPriority priority = TaskPriority::MEDIUM);
    
    // 获取任务状态
    std::shared_ptr<Task> getTaskStatus(const TaskId& task_id) const;
    
    // 取消任务
    bool cancelTask(const TaskId& task_id);
    
    // 等待任务完成
    template<typename T>
    T waitForTask(const TaskId& task_id);
    
    // 等待所有任务完成
    void waitForAllTasks();
    
    // 获取队列统计信息
    struct QueueStats {
        size_t total_tasks;
        size_t pending_tasks;
        size_t running_tasks;
        size_t completed_tasks;
        size_t failed_tasks;
        size_t worker_count;
        size_t active_workers;
    };
    
    QueueStats getStats() const;
    
    // 启动/停止管理器
    void start();
    void stop();
    
    // 清理已完成的任务
    void cleanup();

private:
    TaskQueueConfig config_;
    std::shared_ptr<TaskQueue> task_queue_;
    std::unique_ptr<WorkerManager> worker_manager_;
    
    // 后台清理线程
    std::thread cleanup_thread_;
    std::atomic<bool> running_{false};
    
    // 任务标签映射
    std::unordered_map<std::string, std::vector<TaskId>> task_tags_;
    mutable std::mutex tag_mutex_;
    
    // 清理线程函数
    void cleanupLoop();
    
    // 生成唯一任务ID
    TaskId generateTaskId();
    std::atomic<uint64_t> task_counter_{0};
};

// 模板方法实现
template<typename F, typename... Args>
auto DistributedTaskQueueManager::submitTask(F&& func, Args&&... args) 
    -> std::future<typename std::result_of<F(Args...)>::type>
{
    using return_type = typename std::result_of<F(Args...)>::type;
    
    auto task = std::make_shared<std::packaged_task<return_type()>>(
        std::bind(std::forward<F>(func), std::forward<Args>(args)...)
    );
    
    std::future<return_type> result = task->get_future();
    
    TaskId task_id = generateTaskId();
    auto wrapper = [task]() -> std::any {
        (*task)();
        return std::any{};
    };
    
    auto task_obj = std::make_shared<Task>(task_id, wrapper, TaskPriority::MEDIUM);
    task_queue_->enqueue(task_obj);
    
    return result;
}

template<typename F, typename... Args>
auto DistributedTaskQueueManager::submitTaskWithPriority(F&& func, TaskPriority priority, Args&&... args)
    -> std::future<typename std::result_of<F(Args...)>::type>
{
    using return_type = typename std::result_of<F(Args...)>::type;
    
    auto task = std::make_shared<std::packaged_task<return_type()>>(
        std::bind(std::forward<F>(func), std::forward<Args>(args)...)
    );
    
    std::future<return_type> result = task->get_future();
    
    TaskId task_id = generateTaskId();
    auto wrapper = [task]() -> std::any {
        (*task)();
        return std::any{};
    };
    
    auto task_obj = std::make_shared<Task>(task_id, wrapper, priority);
    task_queue_->enqueue(task_obj);
    
    return result;
}

template<typename T>
T DistributedTaskQueueManager::waitForTask(const TaskId& task_id) {
    auto task = task_queue_->getTask(task_id);
    if (!task) {
        throw std::runtime_error("Task not found: " + task_id);
    }
    
    task_queue_->waitForCompletion(task_id);
    
    if (task->getStatus() == TaskStatus::FAILED) {
        throw std::runtime_error("Task failed: " + task_id);
    }
    
    // 这里需要实际的结果转换，简化处理
    return T{};
}

} // namespace DistributedTaskQueue
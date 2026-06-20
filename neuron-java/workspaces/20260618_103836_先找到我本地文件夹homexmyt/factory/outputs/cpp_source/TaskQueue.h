#pragma once
#include "Task.h"
#include <queue>
#include <vector>
#include <mutex>
#include <condition_variable>
#include <memory>
#include <unordered_map>
#include <atomic>

namespace DistributedTaskQueue {

// 任务队列类
class TaskQueue {
public:
    TaskQueue(size_t max_size = 1000);
    ~TaskQueue();

    // 添加任务到队列
    bool enqueue(std::shared_ptr<Task> task);
    
    // 从队列获取任务
    std::shared_ptr<Task> dequeue();
    
    // 获取任务状态
    std::shared_ptr<Task> getTask(const TaskId& task_id) const;
    
    // 取消任务
    bool cancelTask(const TaskId& task_id);
    
    // 获取队列状态
    size_t size() const;
    bool empty() const;
    size_t pendingCount() const;
    size_t completedCount() const;
    size_t failedCount() const;
    
    // 等待任务完成
    void waitForCompletion(const TaskId& task_id);
    void waitForAll();
    
    // 获取所有任务状态
    std::vector<std::shared_ptr<Task>> getAllTasks() const;
    
    // 清理已完成的任务
    void cleanupCompleted();

private:
    // 使用优先队列（按优先级排序）
    struct TaskCompare {
        bool operator()(const std::shared_ptr<Task>& a, const std::shared_ptr<Task>& b) const;
    };
    
    std::priority_queue<
        std::shared_ptr<Task>,
        std::vector<std::shared_ptr<Task>>,
        TaskCompare
    > queue_;
    
    // 所有任务的映射（包括已完成的）
    std::unordered_map<TaskId, std::shared_ptr<Task>> tasks_;
    
    // 同步原语
    mutable std::mutex mutex_;
    std::condition_variable not_empty_;
    std::condition_variable task_completed_;
    
    // 配置
    size_t max_size_;
    
    // 统计信息
    std::atomic<size_t> pending_count_{0};
    std::atomic<size_t> running_count_{0};
    std::atomic<size_t> completed_count_{0};
    std::atomic<size_t> failed_count_{0};
};

} // namespace DistributedTaskQueue
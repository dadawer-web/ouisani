#pragma once
#include <string>
#include <functional>
#include <any>
#include <chrono>
#include <atomic>

namespace DistributedTaskQueue {

// 任务状态枚举
enum class TaskStatus {
    PENDING,     // 等待执行
    RUNNING,     // 执行中
    COMPLETED,   // 完成
    FAILED,      // 失败
    CANCELLED    // 取消
};

// 任务优先级
enum class TaskPriority {
    LOW = 0,
    MEDIUM = 1,
    HIGH = 2,
    CRITICAL = 3
};

// 任务ID类型
using TaskId = std::string;

// 任务函数类型
using TaskFunction = std::function<std::any()>;

// 任务类
class Task {
public:
    Task(TaskId id, TaskFunction func, TaskPriority priority = TaskPriority::MEDIUM);
    ~Task() = default;

    // 执行任务
    std::any execute();
    
    // 获取任务信息
    TaskId getId() const { return id_; }
    TaskStatus getStatus() const { return status_; }
    TaskPriority getPriority() const { return priority_; }
    std::chrono::system_clock::time_point getCreationTime() const { return creation_time_; }
    std::chrono::system_clock::time_point getStartTime() const { return start_time_; }
    std::chrono::system_clock::time_point getEndTime() const { return end_time_; }
    std::string getWorkerId() const { return worker_id_; }
    
    // 设置任务信息
    void setStatus(TaskStatus status) { status_ = status; }
    void setWorkerId(const std::string& worker_id) { worker_id_ = worker_id; }
    void setResult(const std::any& result) { result_ = result; }
    std::any getResult() const { return result_; }
    
    // 比较操作符（用于优先队列）
    bool operator<(const Task& other) const;
    bool operator>(const Task& other) const;

private:
    TaskId id_;
    TaskFunction func_;
    TaskPriority priority_;
    TaskStatus status_;
    std::string worker_id_;
    std::any result_;
    
    std::chrono::system_clock::time_point creation_time_;
    std::chrono::system_clock::time_point start_time_;
    std::chrono::system_clock::time_point end_time_;
    
    // 错误信息
    std::string error_message_;
};

} // namespace DistributedTaskQueue
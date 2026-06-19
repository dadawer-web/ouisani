#pragma once
#include "Task.h"
#include "TaskQueue.h"
#include <string>
#include <thread>
#include <atomic>
#include <memory>
#include <functional>

namespace DistributedTaskQueue {

// 工作节点类
class Worker {
public:
    Worker(std::string worker_id, std::shared_ptr<TaskQueue> task_queue);
    ~Worker();

    // 启动工作线程
    void start();
    
    // 停止工作线程
    void stop();
    
    // 检查是否运行中
    bool isRunning() const { return running_; }
    
    // 获取工作节点信息
    std::string getId() const { return worker_id_; }
    size_t getProcessedCount() const { return processed_count_; }
    
    // 设置工作节点能力
    void setCapability(const std::string& capability);
    bool hasCapability(const std::string& capability) const;

private:
    // 工作线程函数
    void workLoop();
    
    std::string worker_id_;
    std::shared_ptr<TaskQueue> task_queue_;
    std::thread work_thread_;
    std::atomic<bool> running_{false};
    std::atomic<size_t> processed_count_{0};
    
    // 能力标签
    std::vector<std::string> capabilities_;
    mutable std::mutex capability_mutex_;
};

// 工作节点管理器
class WorkerManager {
public:
    WorkerManager(std::shared_ptr<TaskQueue> task_queue);
    ~WorkerManager();

    // 添加工作节点
    std::shared_ptr<Worker> addWorker(const std::string& worker_id = "");
    
    // 移除工作节点
    bool removeWorker(const std::string& worker_id);
    
    // 获取工作节点
    std::shared_ptr<Worker> getWorker(const std::string& worker_id) const;
    
    // 获取所有工作节点
    std::vector<std::shared_ptr<Worker>> getAllWorkers() const;
    
    // 启动所有工作节点
    void startAll();
    
    // 停止所有工作节点
    void stopAll();
    
    // 获取工作节点状态
    size_t getWorkerCount() const;
    size_t getActiveWorkerCount() const;

private:
    std::shared_ptr<TaskQueue> task_queue_;
    std::unordered_map<std::string, std::shared_ptr<Worker>> workers_;
    mutable std::mutex mutex_;
    std::atomic<size_t> worker_counter_{0};
};

} // namespace DistributedTaskQueue
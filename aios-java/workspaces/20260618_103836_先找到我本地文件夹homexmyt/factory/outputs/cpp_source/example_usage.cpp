#include "DistributedTaskQueue.h"
#include <iostream>
#include <thread>
#include <chrono>

using namespace DistributedTaskQueue;

// 示例任务函数
int computeFactorial(int n) {
    if (n <= 1) return 1;
    
    // 模拟计算耗时
    std::this_thread::sleep_for(std::chrono::milliseconds(100));
    
    int result = 1;
    for (int i = 2; i <= n; ++i) {
        result *= i;
    }
    return result;
}

// 示例任务函数（带异常）
void riskyTask() {
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    if (rand() % 3 == 0) {
        throw std::runtime_error("Random failure occurred");
    }
    std::cout << "Risky task completed successfully" << std::endl;
}

int main() {
    std::cout << "Starting Distributed Task Queue Example..." << std::endl;
    
    // 创建任务队列配置
    TaskQueueConfig config;
    config.max_queue_size = 100;
    config.max_workers = 3;
    config.auto_start_workers = true;
    
    // 创建任务队列管理器
    DistributedTaskQueueManager manager(config);
    manager.start();
    
    // 提交多个任务
    std::vector<std::future<int>> futures;
    
    for (int i = 1; i <= 10; ++i) {
        auto future = manager.submitTask(computeFactorial, i);
        futures.push_back(std::move(future));
    }
    
    // 提交带优先级的任务
    auto highPriorityFuture = manager.submitTaskWithPriority(
        []() -> int {
            std::cout << "High priority task executing..." << std::endl;
            return 42;
        },
        TaskPriority::HIGH
    );
    
    // 提交一些可能失败的任务
    for (int i = 0; i < 5; ++i) {
        manager.submitTask(riskyTask);
    }
    
    // 获取队列统计信息
    auto stats = manager.getStats();
    std::cout << "\nQueue Statistics:" << std::endl;
    std::cout << "Total tasks: " << stats.total_tasks << std::endl;
    std::cout << "Pending tasks: " << stats.pending_tasks << std::endl;
    std::cout << "Running tasks: " << stats.running_tasks << std::endl;
    std::cout << "Completed tasks: " << stats.completed_tasks << std::endl;
    std::cout << "Failed tasks: " << stats.failed_tasks << std::endl;
    std::cout << "Worker count: " << stats.worker_count << std::endl;
    std::cout << "Active workers: " << stats.active_workers << std::endl;
    
    // 等待所有任务完成
    std::cout << "\nWaiting for all tasks to complete..." << std::endl;
    manager.waitForAllTasks();
    
    // 获取结果
    std::cout << "\nResults:" << std::endl;
    for (size_t i = 0; i < futures.size(); ++i) {
        try {
            int result = futures[i].get();
            std::cout << "Factorial of " << (i + 1) << " = " << result << std::endl;
        } catch (const std::exception& e) {
            std::cout << "Factorial of " << (i + 1) << " failed: " << e.what() << std::endl;
        }
    }
    
    // 获取高优先级任务结果
    try {
        int highPriorityResult = highPriorityFuture.get();
        std::cout << "\nHigh priority task result: " << highPriorityResult << std::endl;
    } catch (const std::exception& e) {
        std::cout << "High priority task failed: " << e.what() << std::endl;
    }
    
    // 最终统计
    stats = manager.getStats();
    std::cout << "\nFinal Statistics:" << std::endl;
    std::cout << "Total tasks processed: " << stats.total_tasks << std::endl;
    std::cout << "Successfully completed: " << stats.completed_tasks << std::endl;
    std::cout << "Failed: " << stats.failed_tasks << std::endl;
    
    manager.stop();
    
    std::cout << "\nExample completed!" << std::endl;
    return 0;
}
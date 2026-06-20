# Distributed Task Queue

一个基于C++17的分布式任务队列实现，支持优先级、任务取消、工作节点管理等特性。

## 特性

- **任务优先级**: 支持LOW、MEDIUM、HIGH、CRITICAL四个优先级
- **任务管理**: 提交、取消、查询任务状态
- **工作节点管理**: 动态添加/移除工作节点
- **线程安全**: 所有操作都是线程安全的
- **异常处理**: 完善的异常处理机制
- **统计信息**: 提供详细的队列统计信息

## 架构

```
DistributedTaskQueueManager
├── TaskQueue (任务队列)
│   └── Task (任务)
└── WorkerManager (工作节点管理器)
    └── Worker (工作节点)
```

## 使用示例

```cpp
#include "DistributedTaskQueue.h"

// 创建任务队列
DistributedTaskQueue::TaskQueueConfig config;
config.max_workers = 4;

DistributedTaskQueue::DistributedTaskQueueManager manager(config);
manager.start();

// 提交任务
auto future = manager.submitTask([]() {
    return 42;
});

// 获取结果
int result = future.get();

// 提交带优先级的任务
auto highFuture = manager.submitTaskWithPriority(
    []() { return "important"; },
    DistributedTaskQueue::TaskPriority::HIGH
);

manager.stop();
```

## 编译

```bash
mkdir build && cd build
cmake ..
make
```

## 运行示例

```bash
./task_queue_example
```

## 配置选项

```cpp
struct TaskQueueConfig {
    size_t max_queue_size = 1000;          // 最大队列大小
    size_t max_workers = 4;                // 最大工作节点数
    bool auto_start_workers = true;        // 是否自动启动工作节点
    size_t cleanup_interval_seconds = 300; // 清理间隔（秒）
};
```

## API 文档

### DistributedTaskQueueManager

- `submitTask(func, args...)` - 提交任务，返回std::future
- `submitTaskWithPriority(func, priority, args...)` - 提交带优先级的任务
- `submitTaskWithTag(tag, func, priority)` - 提交带标签的任务
- `getTaskStatus(task_id)` - 获取任务状态
- `cancelTask(task_id)` - 取消任务
- `waitForTask<T>(task_id)` - 等待任务完成并返回结果
- `waitForAllTasks()` - 等待所有任务完成
- `getStats()` - 获取队列统计信息
- `start()` - 启动管理器
- `stop()` - 停止管理器
- `cleanup()` - 清理已完成的任务

## 线程安全

所有公共方法都是线程安全的，可以在多个线程中同时调用。

## 错误处理

- 任务执行异常会被捕获并记录
- 队列满时会拒绝新任务
- 任务取消会设置相应状态

## 性能考虑

- 使用优先队列确保高优先级任务优先执行
- 工作节点独立运行，避免阻塞
- 内存管理使用智能指针，避免内存泄漏

## 扩展

可以通过继承Worker类来实现自定义工作节点，添加特定的处理逻辑。
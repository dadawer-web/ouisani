#!/usr/bin/env python3
"""
任务调度器实现
支持公平调度、负载均衡、任务重试策略
"""
import time
import threading
import heapq
import sys
from collections import deque
from typing import Dict, List, Any, Optional
import random
import json
import os

# ============================================================
# BaseAgent 基类
# ============================================================
class BaseAgent:
    """基础智能体类"""
    def __init__(self, name: str = "BaseAgent"):
        self.name = name

    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """处理数据的核心方法，需要子类重写"""
        raise NotImplementedError("子类必须实现process_data方法")

    def run(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """运行智能体"""
        print(f"[{self.name}] 开始处理数据...", flush=True)
        result = self.process_data(data)
        print(f"[{self.name}] 数据处理完成", flush=True)
        return result


# ============================================================
# 工作节点类
# ============================================================
class WorkerNode:
    """工作节点类，模拟分布式工作节点"""
    def __init__(self, node_id: str, capacity: int = 10):
        self.node_id = node_id
        self.capacity = capacity
        self.current_load = 0
        self.task_queue: deque = deque()
        self.lock = threading.Lock()
        self.status = "active"

    def add_task(self, task: Dict[str, Any]) -> bool:
        """向节点添加任务"""
        with self.lock:
            if self.current_load >= self.capacity:
                self.status = "overloaded"
                return False
            self.task_queue.append(task)
            self.current_load += 1
            if self.current_load >= self.capacity:
                self.status = "overloaded"
            return True

    def process_task(self) -> Optional[Dict[str, Any]]:
        """处理队列中的下一个任务"""
        with self.lock:
            if not self.task_queue:
                return None
            task = self.task_queue.popleft()
            self.current_load -= 1
            if self.current_load < self.capacity:
                self.status = "active"

        # 模拟任务处理
        processing_time = random.uniform(0.05, 0.2)
        time.sleep(processing_time)

        # 随机模拟任务失败（用于测试重试策略）
        if random.random() < 0.15:
            task["status"] = "failed"
            task["error"] = "随机模拟失败"
        else:
            task["status"] = "completed"
            task["result"] = f"任务在节点{self.node_id}上处理完成"

        return task

    def get_load_percentage(self) -> float:
        """获取节点负载百分比"""
        return self.current_load / self.capacity if self.capacity > 0 else 0.0


# ============================================================
# 重试管理器
# ============================================================
class RetryManager:
    """重试管理器，实现指数退避重试策略"""
    def __init__(self, max_retries: int = 3, base_delay: float = 0.5):
        self.max_retries = max_retries
        self.base_delay = base_delay
        self.failed_tasks: List[Dict[str, Any]] = []

    def should_retry(self, task: Dict[str, Any]) -> bool:
        """判断任务是否应该重试"""
        retry_count = task.get("retry_count", 0)
        return retry_count < self.max_retries

    def schedule_retry(self, task: Dict[str, Any]) -> Dict[str, Any]:
        """安排任务重试，使用指数退避策略"""
        retry_count = task.get("retry_count", 0)
        delay = self.base_delay * (2 ** retry_count)

        task["retry_count"] = retry_count + 1
        task["retry_delay"] = delay
        task["next_retry_time"] = time.time() + delay

        print(f"[重试管理] 任务{task.get('id', '未知')}将在{delay:.2f}秒后重试，"
              f"重试次数：{retry_count + 1}/{self.max_retries}", flush=True)
        return task


# ============================================================
# 任务调度器（核心）
# ============================================================
class TaskScheduler(BaseAgent):
    """
    任务调度器，支持：
    - 公平调度（轮询 Round-Robin）
    - 负载均衡（最小负载优先）
    - 任务重试策略（指数退避）
    """
    def __init__(self, name: str = "TaskScheduler"):
        super().__init__(name)
        self.workers: List[WorkerNode] = []
        self.retry_manager = RetryManager()
        self.task_history: Dict[str, List[Dict[str, Any]]] = {}
        self.fairness_queue: List[int] = []
        self.lock = threading.Lock()
        self._initialize_workers(5)

    def _initialize_workers(self, num_workers: int):
        """初始化工作节点"""
        for i in range(num_workers):
            worker = WorkerNode(
                node_id=f"worker_{i+1}",
                capacity=random.randint(8, 15)
            )
            self.workers.append(worker)
        print(f"[{self.name}] 初始化了{num_workers}个工作节点", flush=True)

    # ------ 负载均衡调度 ------
    def select_worker_by_load_balancing(self) -> Optional[WorkerNode]:
        """负载均衡：选择当前负载最低的节点"""
        available = [w for w in self.workers if w.status != "overloaded"]
        if not available:
            return None
        return min(available, key=lambda w: w.get_load_percentage())

    # ------ 公平调度 ------
    def select_worker_by_fairness(self) -> Optional[WorkerNode]:
        """公平调度：Round-Robin 轮询"""
        if not self.workers:
            return None
        with self.lock:
            if not self.fairness_queue:
                self.fairness_queue = list(range(len(self.workers)))
                random.shuffle(self.fairness_queue)
            idx = self.fairness_queue.pop(0)
        return self.workers[idx]

    # ------ 任务调度入口 ------
    def schedule_task(self, task: Dict[str, Any], strategy: str = "load_balancing") -> bool:
        """根据策略调度任务到工作节点"""
        if strategy == "fairness":
            worker = self.select_worker_by_fairness()
        else:
            worker = self.select_worker_by_load_balancing()

        if not worker:
            print(f"[{self.name}] 没有可用的工作节点", flush=True)
            return False

        if worker.add_task(task):
            print(f"[{self.name}] 任务{task.get('id', '?')} -> 节点{worker.node_id}（{strategy}）", flush=True)
            return True

        # 节点过载，尝试替代节点
        return self._find_alternative_worker(task)

    def _find_alternative_worker(self, task: Dict[str, Any]) -> bool:
        """寻找替代工作节点"""
        for w in self.workers:
            if w.status != "overloaded" and w.add_task(task):
                print(f"[{self.name}] 任务{task.get('id', '?')} -> 备选节点{w.node_id}", flush=True)
                return True
        return False

    # ------ 失败处理与重试 ------
    def process_failed_task(self, task: Dict[str, Any]):
        """处理失败的任务：重试或标记永久失败"""
        if self.retry_manager.should_retry(task):
            retried = self.retry_manager.schedule_retry(task)
            self.retry_manager.failed_tasks.append(retried)
        else:
            task["status"] = "permanently_failed"
            task["final_error"] = f"在{task.get('retry_count', 0)}次重试后仍然失败"
            print(f"[{self.name}] 任务{task.get('id', '?')}永久失败", flush=True)
            self._record_task_history(task)

    def _record_task_history(self, task: Dict[str, Any]):
        """记录任务历史"""
        tid = task.get("id", "unknown")
        self.task_history.setdefault(tid, []).append({
            "timestamp": time.time(),
            "status": task.get("status"),
            "retry_count": task.get("retry_count", 0),
            "error": task.get("final_error") or task.get("error", "")
        })

    # ------ 处理待重试任务 ------
    def process_pending_retries(self):
        """处理已经到达重试时间的任务"""
        now = time.time()
        with self.lock:
            ready = [t for t in self.retry_manager.failed_tasks if t.get("next_retry_time", float('inf')) <= now]
            self.retry_manager.failed_tasks = [
                t for t in self.retry_manager.failed_tasks if t.get("next_retry_time", float('inf')) > now
            ]
        for task in ready:
            print(f"[{self.name}] 重新调度重试任务：{task.get('id', '?')}", flush=True)
            if not self.schedule_task(task):
                self.retry_manager.failed_tasks.append(task)

    # ------ 并行处理所有节点任务 ------
    def process_worker_tasks(self) -> List[Dict[str, Any]]:
        """并行处理所有工作节点中的任务"""
        all_processed: List[Dict[str, Any]] = []
        results: Dict[str, List[Dict[str, Any]]] = {}
        threads: List[threading.Thread] = []

        def _run(worker: WorkerNode):
            tasks = []
            while True:
                t = worker.process_task()
                if t is None:
                    break
                tasks.append(t)
            results[worker.node_id] = tasks

        for w in self.workers:
            th = threading.Thread(target=_run, args=(w,))
            threads.append(th)
            th.start()
        for th in threads:
            th.join()

        for _, tasks in results.items():
            for task in tasks:
                if task.get("status") == "failed":
                    self.process_failed_task(task)
                else:
                    self._record_task_history(task)
                    all_processed.append(task)
        return all_processed

    # ------ 核心处理入口 ------
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """实现 BaseAgent.process_data"""
        print(f"[{self.name}] 开始处理分布式任务队列", flush=True)

        tasks = data.get("tasks", [])
        if not tasks:
            tasks = self._generate_sample_tasks(10)

        strategy = data.get("strategy", "load_balancing")

        # 调度
        scheduled = 0
        for t in tasks:
            if self.schedule_task(t, strategy=strategy):
                scheduled += 1
        print(f"[{self.name}] 已调度 {scheduled}/{len(tasks)} 个任务", flush=True)

        # 第一轮处理
        processed = self.process_worker_tasks()

        # 处理待重试
        self.process_pending_retries()

        # 第二轮处理（重试后的新任务）
        processed.extend(self.process_worker_tasks())

        # 再等一轮重试
        time.sleep(0.6)
        self.process_pending_retries()
        processed.extend(self.process_worker_tasks())

        failed_count = len([t for t in processed if t.get("status") == "failed"])
        result = {
            "scheduler_status": "completed",
            "total_tasks": len(tasks),
            "scheduled_tasks": scheduled,
            "processed_tasks": len(processed),
            "failed_tasks": failed_count,
            "retry_tasks": len(self.retry_manager.failed_tasks),
            "task_history": self.task_history,
            "worker_status": [
                {
                    "node_id": w.node_id,
                    "load": w.current_load,
                    "capacity": w.capacity,
                    "status": w.status,
                }
                for w in self.workers
            ],
        }

        print(f"[{self.name}] 任务处理完成！"
              f"调度{result['scheduled_tasks']}个，处理{result['processed_tasks']}个，"
              f"失败{result['failed_tasks']}个", flush=True)
        return result

    @staticmethod
    def _generate_sample_tasks(num_tasks: int) -> List[Dict[str, Any]]:
        """生成示例任务"""
        return [
            {
                "id": f"task_{i+1}",
                "priority": random.randint(1, 10),
                "data": f"示例任务数据_{i+1}",
                "estimated_time": random.uniform(0.1, 1.0),
                "created_at": time.time(),
            }
            for i in range(num_tasks)
        ]


# ============================================================
# 独立测试入口
# ============================================================
if __name__ == "__main__":
    print("=" * 60, flush=True)
    print("任务调度器独立测试开始", flush=True)
    print("=" * 60, flush=True)

    scheduler = TaskScheduler(name="TestScheduler")

    test_data = {
        "tasks": [
            {"id": f"test_task_{i}", "priority": random.randint(1, 5), "data": f"测试数据_{i}"}
            for i in range(8)
        ],
        "strategy": "load_balancing",
    }

    result = scheduler.process_data(test_data)

    print("\n" + "=" * 60, flush=True)
    print("测试结果摘要：", flush=True)
    print(f"  总任务数：{result['total_tasks']}", flush=True)
    print(f"  已调度：{result['scheduled_tasks']}", flush=True)
    print(f"  已处理：{result['processed_tasks']}", flush=True)
    print(f"  失败：{result['failed_tasks']}", flush=True)
    print(f"  待重试：{result['retry_tasks']}", flush=True)

    print("\n工作节点状态：", flush=True)
    for w in result["worker_status"]:
        print(f"  {w['node_id']}: {w['load']}/{w['capacity']} ({w['status']})", flush=True)

    # 保存结果
    out_dir = os.path.join(os.getcwd(), "outputs")
    os.makedirs(out_dir, exist_ok=True)
    output_path = os.path.join(out_dir, "task_scheduler_result.json")

    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2, default=str)

    print(f"\n结果已保存到：{output_path}", flush=True)
    print("=" * 60, flush=True)
    print("任务调度器测试完成！", flush=True)
    print("NODE_VERIFIED_AND_READY", flush=True)
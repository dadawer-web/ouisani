#!/usr/bin/env python3
"""
Consumer/Worker Process Implementation
实现消费者/工作者进程，能够从队列中拉取任务、执行任务函数、处理成功/失败，并更新任务状态。

Features:
- 基于文件系统的任务队列
- 任务状态追踪与持久化
- 重试机制与错误处理
- 死信队列支持
- 并发任务执行控制
"""

import json
import os
import sys
import time
import uuid
import threading
import traceback
from typing import Dict, List, Any, Optional, Callable
from enum import Enum
from datetime import datetime
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor, as_completed
from queue import Queue, Empty
from dataclasses import dataclass, asdict, field

# BaseAgent 兼容层
try:
    from ai_core.base_agent import BaseAgent
except ImportError:
    class BaseAgent:
        def process_data(self, data):
            raise NotImplementedError("Subclasses must implement process_data")


class TaskStatus(Enum):
    """任务状态枚举"""
    PENDING = "pending"          # 等待处理
    QUEUED = "queued"            # 已入队
    PROCESSING = "processing"    # 处理中
    SUCCESS = "success"          # 成功
    FAILED = "failed"            # 失败
    RETRYING = "retrying"        # 重试中
    DEAD_LETTER = "dead_letter"  # 死信（重试耗尽）
    CANCELLED = "cancelled"      # 已取消


@dataclass
class Task:
    """任务数据结构"""
    task_id: str
    task_type: str
    payload: Dict[str, Any]
    status: str = TaskStatus.PENDING.value
    priority: int = 0
    created_at: str = ""
    updated_at: str = ""
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None
    retry_count: int = 0
    max_retries: int = 3
    timeout: int = 60  # 秒
    worker_id: Optional[str] = None
    
    def __post_init__(self):
        if not self.created_at:
            self.created_at = datetime.now().isoformat()
        if not self.updated_at:
            self.updated_at = self.created_at


@dataclass
class WorkerStats:
    """工作者统计信息"""
    worker_id: str
    tasks_processed: int = 0
    tasks_succeeded: int = 0
    tasks_failed: int = 0
    tasks_retried: int = 0
    uptime_start: str = ""
    last_task_at: Optional[str] = None
    
    def __post_init__(self):
        if not self.uptime_start:
            self.uptime_start = datetime.now().isoformat()


class TaskQueue:
    """基于文件系统的任务队列"""
    
    def __init__(self, queue_dir: str = "/tmp/aios_task_queue"):
        self.queue_dir = Path(queue_dir)
        self.queue_dir.mkdir(parents=True, exist_ok=True)
        
        # 子目录结构
        self.pending_dir = self.queue_dir / "pending"
        self.processing_dir = self.queue_dir / "processing"
        self.completed_dir = self.queue_dir / "completed"
        self.failed_dir = self.queue_dir / "failed"
        self.dead_letter_dir = self.queue_dir / "dead_letter"
        
        for dir_path in [self.pending_dir, self.processing_dir, 
                         self.completed_dir, self.failed_dir, self.dead_letter_dir]:
            dir_path.mkdir(exist_ok=True)
    
    def enqueue(self, task: Task) -> str:
        """将任务加入队列"""
        task.status = TaskStatus.QUEUED.value
        task.updated_at = datetime.now().isoformat()
        
        task_file = self.pending_dir / f"{task.task_id}.json"
        with open(task_file, 'w') as f:
            json.dump(asdict(task), f, indent=2)
        
        print(f"[QUEUE] Task {task.task_id} enqueued to pending", flush=True)
        return task.task_id
    
    def dequeue(self, worker_id: str) -> Optional[Task]:
        """从队列中取出任务"""
        pending_files = sorted(self.pending_dir.glob("*.json"))
        
        for task_file in pending_files:
            try:
                # 原子性移动到处理目录
                processing_file = self.processing_dir / task_file.name
                task_file.rename(processing_file)
                
                # 读取任务数据
                with open(processing_file, 'r') as f:
                    task_data = json.load(f)
                
                task = Task(**task_data)
                task.status = TaskStatus.PROCESSING.value
                task.started_at = datetime.now().isoformat()
                task.updated_at = task.started_at
                task.worker_id = worker_id
                
                # 更新任务文件
                with open(processing_file, 'w') as f:
                    json.dump(asdict(task), f, indent=2)
                
                print(f"[QUEUE] Task {task.task_id} dequeued by worker {worker_id}", flush=True)
                return task
                
            except (FileNotFoundError, json.JSONDecodeError) as e:
                # 文件可能已被其他工作者取走
                continue
        
        return None
    
    def complete_task(self, task: Task, result: Dict[str, Any]):
        """标记任务为完成"""
        task.status = TaskStatus.SUCCESS.value
        task.result = result
        task.completed_at = datetime.now().isoformat()
        task.updated_at = task.completed_at
        
        # 从处理目录移到完成目录
        processing_file = self.processing_dir / f"{task.task_id}.json"
        completed_file = self.completed_dir / f"{task.task_id}.json"
        
        if processing_file.exists():
            processing_file.unlink()
        
        with open(completed_file, 'w') as f:
            json.dump(asdict(task), f, indent=2)
        
        print(f"[QUEUE] Task {task.task_id} completed successfully", flush=True)
    
    def fail_task(self, task: Task, error: str, can_retry: bool = True):
        """标记任务为失败"""
        task.error = error
        task.updated_at = datetime.now().isoformat()
        
        if can_retry and task.retry_count < task.max_retries:
            # 重新入队重试
            task.status = TaskStatus.RETRYING.value
            task.retry_count += 1
            
            processing_file = self.processing_dir / f"{task.task_id}.json"
            if processing_file.exists():
                processing_file.unlink()
            
            task_file = self.pending_dir / f"{task.task_id}.json"
            with open(task_file, 'w') as f:
                json.dump(asdict(task), f, indent=2)
            
            print(f"[QUEUE] Task {task.task_id} failed, retrying ({task.retry_count}/{task.max_retries})", flush=True)
        else:
            # 移到死信队列
            task.status = TaskStatus.DEAD_LETTER.value
            task.completed_at = datetime.now().isoformat()
            
            processing_file = self.processing_dir / f"{task.task_id}.json"
            dead_letter_file = self.dead_letter_dir / f"{task.task_id}.json"
            
            if processing_file.exists():
                processing_file.unlink()
            
            with open(dead_letter_file, 'w') as f:
                json.dump(asdict(task), f, indent=2)
            
            print(f"[QUEUE] Task {task.task_id} moved to dead letter queue", flush=True)
    
    def get_task_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        """获取任务状态"""
        # 在所有目录中查找任务
        for dir_path in [self.pending_dir, self.processing_dir, 
                         self.completed_dir, self.failed_dir, self.dead_letter_dir]:
            task_file = dir_path / f"{task_id}.json"
            if task_file.exists():
                with open(task_file, 'r') as f:
                    return json.load(f)
        return None
    
    def get_queue_stats(self) -> Dict[str, int]:
        """获取队列统计信息"""
        return {
            "pending": len(list(self.pending_dir.glob("*.json"))),
            "processing": len(list(self.processing_dir.glob("*.json"))),
            "completed": len(list(self.completed_dir.glob("*.json"))),
            "failed": len(list(self.failed_dir.glob("*.json"))),
            "dead_letter": len(list(self.dead_letter_dir.glob("*.json")))
        }


class TaskExecutor:
    """任务执行器 - 负责执行具体的任务函数"""
    
    def __init__(self):
        # 任务类型到处理函数的映射
        self.task_handlers: Dict[str, Callable] = {}
        self._register_default_handlers()
    
    def _register_default_handlers(self):
        """注册默认的任务处理函数"""
        self.register_handler("echo", self._handle_echo)
        self.register_handler("compute", self._handle_compute)
        self.register_handler("simulate", self._handle_simulate)
        self.register_handler("process_data", self._handle_process_data)
    
    def register_handler(self, task_type: str, handler: Callable):
        """注册任务处理函数"""
        self.task_handlers[task_type] = handler
        print(f"[EXECUTOR] Registered handler for task type: {task_type}", flush=True)
    
    def execute(self, task: Task) -> Dict[str, Any]:
        """执行任务"""
        handler = self.task_handlers.get(task.task_type)
        
        if handler is None:
            raise ValueError(f"Unknown task type: {task.task_type}")
        
        print(f"[EXECUTOR] Executing task {task.task_id} (type: {task.task_type})", flush=True)
        
        # 执行任务处理函数
        result = handler(task.payload)
        
        return result
    
    def _handle_echo(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """Echo 处理器 - 简单返回输入"""
        time.sleep(0.5)  # 模拟处理时间
        return {"echo": payload, "timestamp": datetime.now().isoformat()}
    
    def _handle_compute(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """计算处理器 - 执行数学计算"""
        operation = payload.get("operation", "add")
        a = payload.get("a", 0)
        b = payload.get("b", 0)
        
        if operation == "add":
            result = a + b
        elif operation == "subtract":
            result = a - b
        elif operation == "multiply":
            result = a * b
        elif operation == "divide":
            if b == 0:
                raise ValueError("Division by zero")
            result = a / b
        else:
            raise ValueError(f"Unknown operation: {operation}")
        
        time.sleep(0.3)  # 模拟计算时间
        return {"operation": operation, "a": a, "b": b, "result": result}
    
    def _handle_simulate(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """模拟处理器 - 模拟长时间运行的任务"""
        duration = payload.get("duration", 1)
        should_fail = payload.get("should_fail", False)
        
        print(f"[EXECUTOR] Simulating task for {duration} seconds...", flush=True)
        time.sleep(duration)
        
        if should_fail:
            raise RuntimeError("Simulated task failure as requested")
        
        return {"simulated": True, "duration": duration}
    
    def _handle_process_data(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        """数据处理处理器"""
        data = payload.get("data", [])
        operation = payload.get("operation", "sum")
        
        if operation == "sum":
            result = sum(data)
        elif operation == "avg":
            result = sum(data) / len(data) if data else 0
        elif operation == "max":
            result = max(data) if data else None
        elif operation == "min":
            result = min(data) if data else None
        else:
            raise ValueError(f"Unknown operation: {operation}")
        
        return {"operation": operation, "input_size": len(data), "result": result}


class ConsumerWorker(BaseAgent):
    """
    消费者/工作者进程
    
    负责从任务队列中拉取任务、执行任务、处理成功/失败，并更新任务状态。
    """
    
    def __init__(self, worker_id: str = None, max_workers: int = 4, 
                 queue_dir: str = "/tmp/aios_task_queue"):
        self.worker_id = worker_id or f"worker_{uuid.uuid4().hex[:8]}"
        self.max_workers = max_workers
        self.queue = TaskQueue(queue_dir)
        self.executor = TaskExecutor()
        self.stats = WorkerStats(worker_id=self.worker_id)
        
        # 控制标志
        self._running = False
        self._task_queue = Queue()
        self._worker_threads = []
        
        print(f"[WORKER] ConsumerWorker {self.worker_id} initialized", flush=True)
        print(f"[WORKER] Max workers: {max_workers}", flush=True)
    
    def register_task_handler(self, task_type: str, handler: Callable):
        """注册自定义任务处理函数"""
        self.executor.register_handler(task_type, handler)
    
    def submit_task(self, task_type: str, payload: Dict[str, Any], 
                    priority: int = 0, max_retries: int = 3, timeout: int = 60) -> str:
        """提交任务到队列"""
        task = Task(
            task_id=f"task_{uuid.uuid4().hex[:12]}",
            task_type=task_type,
            payload=payload,
            priority=priority,
            max_retries=max_retries,
            timeout=timeout
        )
        return self.queue.enqueue(task)
    
    def _process_single_task(self):
        """处理单个任务"""
        task = self.queue.dequeue(self.worker_id)
        
        if task is None:
            return False
        
        print(f"[WORKER-{self.worker_id}] Processing task {task.task_id}", flush=True)
        
        try:
            # 执行任务
            result = self.executor.execute(task)
            
            # 标记任务完成
            self.queue.complete_task(task, result)
            
            # 更新统计
            self.stats.tasks_processed += 1
            self.stats.tasks_succeeded += 1
            self.stats.last_task_at = datetime.now().isoformat()
            
            print(f"[WORKER-{self.worker_id}] Task {task.task_id} completed: {result}", flush=True)
            
        except Exception as e:
            error_msg = f"{type(e).__name__}: {str(e)}"
            print(f"[WORKER-{self.worker_id}] Task {task.task_id} failed: {error_msg}", flush=True)
            
            # 标记任务失败（可能触发重试）
            self.queue.fail_task(task, error_msg, can_retry=True)
            
            # 更新统计
            self.stats.tasks_processed += 1
            self.stats.tasks_failed += 1
        
        return True
    
    def _worker_loop(self):
        """工作者主循环"""
        print(f"[WORKER-{self.worker_id}] Worker loop started", flush=True)
        
        while self._running:
            try:
                # 尝试处理任务
                has_task = self._process_single_task()
                
                if not has_task:
                    # 没有任务时等待
                    time.sleep(0.5)
                    
            except Exception as e:
                print(f"[WORKER-{self.worker_id}] Error in worker loop: {e}", flush=True)
                time.sleep(1)
        
        print(f"[WORKER-{self.worker_id}] Worker loop stopped", flush=True)
    
    def start(self):
        """启动工作者"""
        self._running = True
        
        # 启动工作线程
        for i in range(self.max_workers):
            thread = threading.Thread(
                target=self._worker_loop,
                name=f"WorkerThread-{self.worker_id}-{i}",
                daemon=True
            )
            thread.start()
            self._worker_threads.append(thread)
            print(f"[WORKER] Started worker thread: {thread.name}", flush=True)
        
        print(f"[WORKER] ConsumerWorker {self.worker_id} started with {self.max_workers} threads", flush=True)
    
    def stop(self):
        """停止工作者"""
        self._running = False
        
        # 等待所有线程结束
        for thread in self._worker_threads:
            thread.join(timeout=5)
        
        self._worker_threads = []
        print(f"[WORKER] ConsumerWorker {self.worker_id} stopped", flush=True)
    
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """
        BaseAgent 接口实现
        
        处理来自编排器的数据，执行工作者任务。
        
        Args:
            data: 包含工作者配置的字典
                - action: 操作类型 (start/stop/submit/status/stats)
                - task_type: 任务类型 (submit时必填)
                - payload: 任务载荷 (submit时必填)
                - duration: 运行时长 (start时可选，默认30秒)
        
        Returns:
            包含操作结果的字典
        """
        action = data.get("action", "start")
        duration = data.get("duration", 30)
        
        print(f"[CONSUMER_WORKER] Processing action: {action}", flush=True)
        
        if action == "start":
            return self._action_start(duration)
        
        elif action == "stop":
            return self._action_stop()
        
        elif action == "submit":
            task_type = data.get("task_type")
            payload = data.get("payload", {})
            return self._action_submit(task_type, payload)
        
        elif action == "submit_batch":
            tasks = data.get("tasks", [])
            return self._action_submit_batch(tasks)
        
        elif action == "status":
            return self._action_status()
        
        elif action == "stats":
            return self._action_stats()
        
        else:
            return {"error": f"Unknown action: {action}"}
    
    def _action_start(self, duration: int) -> Dict[str, Any]:
        """启动工作者并运行指定时长"""
        print(f"[CONSUMER_WORKER] Starting worker for {duration} seconds...", flush=True)
        
        # 启动工作者
        self.start()
        
        # 生成一些测试任务
        test_tasks = self._generate_test_tasks()
        submitted_ids = []
        for task_type, payload in test_tasks:
            task_id = self.submit_task(task_type, payload)
            submitted_ids.append(task_id)
        
        print(f"[CONSUMER_WORKER] Submitted {len(submitted_ids)} test tasks", flush=True)
        
        # 运行指定时长
        start_time = time.time()
        while time.time() - start_time < duration:
            # 定期打印状态
            elapsed = int(time.time() - start_time)
            if elapsed % 5 == 0:
                stats = self.queue.get_queue_stats()
                print(f"[CONSUMER_WORKER] Elapsed: {elapsed}s, Queue stats: {stats}", flush=True)
            time.sleep(1)
        
        # 停止工作者
        self.stop()
        
        # 收集结果
        results = self._collect_results(submitted_ids)
        
        return {
            "status": "completed",
            "worker_id": self.worker_id,
            "duration": duration,
            "tasks_submitted": len(submitted_ids),
            "queue_stats": self.queue.get_queue_stats(),
            "worker_stats": asdict(self.stats),
            "results": results
        }
    
    def _action_stop(self) -> Dict[str, Any]:
        """停止工作者"""
        self.stop()
        return {"status": "stopped", "worker_id": self.worker_id}
    
    def _action_submit(self, task_type: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        """提交单个任务"""
        if not task_type:
            return {"error": "task_type is required"}
        
        task_id = self.submit_task(task_type, payload)
        return {"status": "submitted", "task_id": task_id}
    
    def _action_submit_batch(self, tasks: List[Dict[str, Any]]) -> Dict[str, Any]:
        """批量提交任务"""
        submitted_ids = []
        for task_info in tasks:
            task_type = task_info.get("task_type")
            payload = task_info.get("payload", {})
            if task_type:
                task_id = self.submit_task(task_type, payload)
                submitted_ids.append(task_id)
        
        return {"status": "submitted", "count": len(submitted_ids), "task_ids": submitted_ids}
    
    def _action_status(self) -> Dict[str, Any]:
        """获取当前状态"""
        return {
            "worker_id": self.worker_id,
            "running": self._running,
            "queue_stats": self.queue.get_queue_stats(),
            "worker_stats": asdict(self.stats)
        }
    
    def _action_stats(self) -> Dict[str, Any]:
        """获取详细统计信息"""
        return asdict(self.stats)
    
    def _generate_test_tasks(self) -> List[tuple]:
        """生成测试任务"""
        tasks = []
        
        # Echo 任务
        tasks.append(("echo", {"message": "Hello World", "index": 1}))
        tasks.append(("echo", {"message": "Test Message", "index": 2}))
        
        # 计算任务
        tasks.append(("compute", {"operation": "add", "a": 10, "b": 20}))
        tasks.append(("compute", {"operation": "multiply", "a": 5, "b": 6}))
        tasks.append(("compute", {"operation": "divide", "a": 100, "b": 7}))
        
        # 模拟任务
        tasks.append(("simulate", {"duration": 1, "should_fail": False}))
        tasks.append(("simulate", {"duration": 0.5, "should_fail": True}))  # 这个会失败并重试
        
        # 数据处理任务
        tasks.append(("process_data", {"data": [1, 2, 3, 4, 5], "operation": "sum"}))
        tasks.append(("process_data", {"data": [10, 20, 30, 40, 50], "operation": "avg"}))
        
        return tasks
    
    def _collect_results(self, task_ids: List[str]) -> Dict[str, Any]:
        """收集任务结果"""
        results = {}
        for task_id in task_ids:
            status = self.queue.get_task_status(task_id)
            if status:
                results[task_id] = {
                    "status": status.get("status"),
                    "result": status.get("result"),
                    "error": status.get("error")
                }
        return results


def main():
    """主函数 - 用于独立测试"""
    print("=" * 60, flush=True)
    print("CONSUMER_WORKER_TEST: Starting ConsumerWorker test...", flush=True)
    print("=" * 60, flush=True)
    
    # 创建工作者
    worker = ConsumerWorker(
        worker_id="test_worker_001",
        max_workers=2,
        queue_dir="/tmp/aios_task_queue_test"
    )
    
    # 测试数据
    test_data = {
        "action": "start",
        "duration": 15  # 运行15秒
    }
    
    print(f"\n[TEST] Input data: {test_data}", flush=True)
    print("-" * 60, flush=True)
    
    # 执行测试
    try:
        result = worker.process_data(test_data)
        
        print("\n" + "=" * 60, flush=True)
        print("CONSUMER_WORKER_TEST: Test completed!", flush=True)
        print("=" * 60, flush=True)
        
        print(f"\n[RESULT] Worker ID: {result.get('worker_id')}", flush=True)
        print(f"[RESULT] Duration: {result.get('duration')} seconds", flush=True)
        print(f"[RESULT] Tasks submitted: {result.get('tasks_submitted')}", flush=True)
        print(f"[RESULT] Queue stats: {result.get('queue_stats')}", flush=True)
        
        # 打印部分任务结果
        task_results = result.get('results', {})
        print(f"\n[RESULTS] Task Results ({len(task_results)} tasks):", flush=True)
        for task_id, task_result in list(task_results.items())[:5]:
            print(f"  - {task_id}: {task_result.get('status')}", flush=True)
        
        # 保存结果到输出文件
        output_path = "/shared/outputs/consumer_worker_result.json"
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        with open(output_path, 'w') as f:
            json.dump(result, f, indent=2, default=str)
        print(f"\n[OUTPUT] Results saved to: {output_path}", flush=True)
        
        print("\nAGENT_SUCCESS: ConsumerWorker test completed successfully!", flush=True)
        
    except Exception as e:
        print(f"\n[ERROR] Test failed: {e}", flush=True)
        traceback.print_exc()
        print("\nAGENT_FAILED: ConsumerWorker test failed!", flush=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
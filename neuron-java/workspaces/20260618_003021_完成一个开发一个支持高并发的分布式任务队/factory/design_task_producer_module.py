#!/usr/bin/env python3
"""
并行设计任务生产者模块 (Design Task Producer Module)
支持多种提交方式：同步/异步、延迟任务、周期性任务
"""

import threading
import queue
import uuid
import time
import json
import dataclasses
from dataclasses import dataclass, asdict
from typing import Any, Callable, Optional, Dict, List
from enum import Enum
from concurrent.futures import ThreadPoolExecutor, Future
from datetime import datetime, timedelta

# 尝试导入 BaseAgent，如果不存在则提供基础实现
try:
    from aiows import BaseAgent
except ImportError:
    class BaseAgent:
        """BaseAgent 基础实现，用于测试和独立运行"""
        def __init__(self, agent_id: str = "design_task_producer"):
            self.agent_id = agent_id
            
        def process_data(self, data: Any) -> Any:
            """处理数据的主方法，需要被子类重写"""
            raise NotImplementedError("Subclass must implement process_data method")
            
        def run(self):
            """运行入口"""
            print(f"Agent {self.agent_id} started")

# 任务类型枚举
class TaskType(Enum):
    SYNC = "sync"          # 同步任务
    ASYNC = "async"        # 异步任务
    DELAYED = "delayed"    # 延迟任务
    PERIODIC = "periodic"  # 周期性任务

# 任务状态枚举
class TaskStatus(Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"

@dataclass
class DesignTask:
    """设计任务数据结构"""
    task_id: str
    task_type: TaskType
    design_spec: Dict[str, Any]
    priority: int = 1
    status: TaskStatus = TaskStatus.PENDING
    created_at: datetime = dataclasses.field(default_factory=datetime.now)
    scheduled_at: Optional[datetime] = None
    period_seconds: Optional[float] = None
    result: Optional[Any] = None
    error: Optional[str] = None

class DesignTaskProducer(BaseAgent):
    """
    并行设计任务生产者模块
    支持同步/异步、延迟任务、周期性任务的生产与调度
    """
    
    def __init__(self, agent_id: str = "design_task_producer"):
        super().__init__(agent_id)
        self.task_queue = queue.PriorityQueue()
        self.active_tasks: Dict[str, DesignTask] = {}
        self.completed_tasks: Dict[str, DesignTask] = {}
        self.thread_pool = ThreadPoolExecutor(max_workers=4)
        self.lock = threading.Lock()
        self.scheduler_thread = None
        self.running = True
        
        # 事件总线引用（在实际AIOS环境中由框架提供）
        self.event_bus = None
        
        # 输出目录
        self.output_dir = "/factory/outputs/"
        
        print(f"[{self.agent_id}] 初始化完成，线程池最大工作线程: 4")
        
    def start_scheduler(self):
        """启动任务调度器"""
        if self.scheduler_thread is None or not self.scheduler_thread.is_alive():
            self.scheduler_thread = threading.Thread(
                target=self._scheduler_loop,
                daemon=True,
                name=f"{self.agent_id}-scheduler"
            )
            self.scheduler_thread.start()
            print(f"[{self.agent_id}] 任务调度器已启动")
            
    def _scheduler_loop(self):
        """调度器主循环"""
        while self.running:
            try:
                self._check_delayed_tasks()
                self._check_periodic_tasks()
                time.sleep(0.1)  # 避免CPU空转
            except Exception as e:
                print(f"[{self.agent_id}] 调度器异常: {str(e)}")
                
    def _check_delayed_tasks(self):
        """检查延迟任务是否到期"""
        current_time = datetime.now()
        with self.lock:
            tasks_to_run = []
            for task_id, task in list(self.active_tasks.items()):
                if (task.task_type == TaskType.DELAYED and 
                    task.scheduled_at and 
                    task.scheduled_at <= current_time):
                    tasks_to_run.append(task)
                    
            for task in tasks_to_run:
                self._execute_task(task)
                
    def _check_periodic_tasks(self):
        """检查周期性任务是否需要执行"""
        current_time = datetime.now()
        with self.lock:
            tasks_to_execute = []
            for task_id, task in list(self.active_tasks.items()):
                if (task.task_type == TaskType.PERIODIC and 
                    task.period_seconds and 
                    task.status == TaskStatus.PENDING):
                    tasks_to_execute.append(task)
                    
            for task in tasks_to_execute:
                self._execute_task(task)
                
    def submit_sync_task(self, design_spec: Dict[str, Any], priority: int = 1) -> str:
        """
        提交同步任务并等待结果
        返回: 任务ID
        """
        task_id = str(uuid.uuid4())
        task = DesignTask(
            task_id=task_id,
            task_type=TaskType.SYNC,
            design_spec=design_spec,
            priority=priority
        )
        
        print(f"[{self.agent_id}] 提交同步任务: {task_id}, 优先级: {priority}")
        
        # 同步任务直接执行
        result = self._execute_design_task(task)
        return task_id
        
    def submit_async_task(self, design_spec: Dict[str, Any], priority: int = 1) -> str:
        """
        提交异步任务
        返回: 任务ID
        """
        task_id = str(uuid.uuid4())
        task = DesignTask(
            task_id=task_id,
            task_type=TaskType.ASYNC,
            design_spec=design_spec,
            priority=priority
        )
        
        with self.lock:
            self.active_tasks[task_id] = task
            # 优先级队列：优先级越小越优先，使用负优先级实现反转
            self.task_queue.put((-priority, task_id, task))
            
        print(f"[{self.agent_id}] 提交异步任务: {task_id}, 优先级: {priority}")
        
        # 异步执行
        future = self.thread_pool.submit(self._execute_task, task)
        future.add_done_callback(lambda f: self._on_task_complete(task_id, f))
        
        return task_id
        
    def submit_delayed_task(self, design_spec: Dict[str, Any], 
                           delay_seconds: float, priority: int = 1) -> str:
        """
        提交延迟任务
        返回: 任务ID
        """
        task_id = str(uuid.uuid4())
        scheduled_at = datetime.now() + timedelta(seconds=delay_seconds)
        
        task = DesignTask(
            task_id=task_id,
            task_type=TaskType.DELAYED,
            design_spec=design_spec,
            priority=priority,
            scheduled_at=scheduled_at
        )
        
        with self.lock:
            self.active_tasks[task_id] = task
            
        print(f"[{self.agent_id}] 提交延迟任务: {task_id}, 延迟: {delay_seconds}秒, 执行时间: {scheduled_at}")
        
        # 确保调度器运行
        self.start_scheduler()
        
        return task_id
        
    def submit_periodic_task(self, design_spec: Dict[str, Any], 
                            period_seconds: float, priority: int = 1) -> str:
        """
        提交周期性任务
        返回: 任务ID
        """
        task_id = str(uuid.uuid4())
        task = DesignTask(
            task_id=task_id,
            task_type=TaskType.PERIODIC,
            design_spec=design_spec,
            priority=priority,
            period_seconds=period_seconds,
            scheduled_at=datetime.now()  # 立即开始第一次执行
        )
        
        with self.lock:
            self.active_tasks[task_id] = task
            
        print(f"[{self.agent_id}] 提交周期性任务: {task_id}, 周期: {period_seconds}秒")
        
        # 确保调度器运行
        self.start_scheduler()
        
        return task_id
        
    def _execute_task(self, task: DesignTask):
        """执行单个任务"""
        with self.lock:
            task.status = TaskStatus.RUNNING
            
        print(f"[{self.agent_id}] 开始执行任务: {task.task_id} (类型: {task.task_type.value})")
        
        try:
            # 模拟设计任务处理
            result = self._process_design(task.design_spec)
            
            with self.lock:
                task.status = TaskStatus.COMPLETED
                task.result = result
                
            # 保存结果到文件
            self._save_task_result(task)
            
            # 通过事件总线发布完成事件（如果可用）
            self._publish_event("task_completed", {
                "task_id": task.task_id,
                "type": task.task_type.value,
                "status": task.status.value,
                "result_summary": str(result)[:100] + "..." if result else None
            })
            
            print(f"[{self.agent_id}] 任务完成: {task.task_id}")
            
            # 如果是周期性任务，重置状态为pending以便下次执行
            if task.task_type == TaskType.PERIODIC:
                with self.lock:
                    task.status = TaskStatus.PENDING
                    
        except Exception as e:
            with self.lock:
                task.status = TaskStatus.FAILED
                task.error = str(e)
                
            print(f"[{self.agent_id}] 任务失败: {task.task_id}, 错误: {str(e)}")
            
            # 发布失败事件
            self._publish_event("task_failed", {
                "task_id": task.task_id,
                "type": task.task_type.value,
                "error": str(e)
            })
            
    def _execute_design_task(self, task: DesignTask) -> Any:
        """执行同步设计任务（阻塞直到完成）"""
        with self.lock:
            task.status = TaskStatus.RUNNING
            
        try:
            # 模拟设计处理
            result = self._process_design(task.design_spec)
            
            with self.lock:
                task.status = TaskStatus.COMPLETED
                task.result = result
                self.completed_tasks[task.task_id] = task
                
            # 保存结果
            self._save_task_result(task)
            
            return result
            
        except Exception as e:
            with self.lock:
                task.status = TaskStatus.FAILED
                task.error = str(e)
                self.completed_tasks[task.task_id] = task
                
            raise
            
    def _process_design(self, design_spec: Dict[str, Any]) -> Dict[str, Any]:
        """
        处理设计规格（模拟实际设计工作）
        实际应用中应替换为真实的设计算法
        """
        # 模拟设计处理时间
        time.sleep(0.1)
        
        # 返回设计结果
        return {
            "design_id": str(uuid.uuid4()),
            "specification": design_spec,
            "timestamp": datetime.now().isoformat(),
            "complexity_score": len(str(design_spec)) / 100,
            "feasibility": "high" if len(design_spec) > 3 else "medium"
        }
        
    def _save_task_result(self, task: DesignTask):
        """保存任务结果到文件"""
        try:
            import os
            os.makedirs(self.output_dir, exist_ok=True)
            
            filename = f"{self.output_dir}task_{task.task_id}.json"
            with open(filename, 'w', encoding='utf-8') as f:
                json.dump(asdict(task), f, ensure_ascii=False, indent=2, default=str)
                
            print(f"[{self.agent_id}] 任务结果已保存: {filename}")
            
        except Exception as e:
            print(f"[{self.agent_id}] 保存任务结果失败: {str(e)}")
            
    def _on_task_complete(self, task_id: str, future: Future):
        """异步任务完成回调"""
        try:
            if future.exception():
                print(f"[{self.agent_id}] 异步任务 {task_id} 执行异常: {future.exception()}")
        except Exception as e:
            print(f"[{self.agent_id}] 回调处理异常: {str(e)}")
            
    def _publish_event(self, event_type: str, data: Dict[str, Any]):
        """发布事件到事件总线"""
        if self.event_bus:
            try:
                self.event_bus.broadcast(event_type, data)
                print(f"[{self.agent_id}] 发布事件: {event_type}")
            except Exception as e:
                print(f"[{self.agent_id}] 事件发布失败: {str(e)}")
        else:
            # 在没有事件总线时，仅打印日志
            print(f"[{self.agent_id}] 事件（无总线）: {event_type} - {json.dumps(data, default=str)[:100]}")
            
    def get_task_status(self, task_id: str) -> Optional[TaskStatus]:
        """获取任务状态"""
        with self.lock:
            if task_id in self.active_tasks:
                return self.active_tasks[task_id].status
            elif task_id in self.completed_tasks:
                return self.completed_tasks[task_id].status
        return None
        
    def get_task_result(self, task_id: str) -> Optional[Any]:
        """获取任务结果"""
        with self.lock:
            if task_id in self.completed_tasks:
                return self.completed_tasks[task_id].result
            elif task_id in self.active_tasks:
                return self.active_tasks[task_id].result
        return None
        
    def get_all_tasks_summary(self) -> Dict[str, int]:
        """获取所有任务统计摘要"""
        with self.lock:
            summary = {
                "total": len(self.active_tasks) + len(self.completed_tasks),
                "active": len(self.active_tasks),
                "completed": len(self.completed_tasks),
                "by_type": {t.value: 0 for t in TaskType},
                "by_status": {s.value: 0 for s in TaskStatus}
            }
            
            for task in list(self.active_tasks.values()) + list(self.completed_tasks.values()):
                summary["by_type"][task.task_type.value] += 1
                summary["by_status"][task.status.value] += 1
                
            return summary
            
    def process_data(self, data: Any) -> Any:
        """
        BaseAgent 的主处理方法
        根据输入数据类型执行相应操作
        """
        if isinstance(data, dict):
            action = data.get("action")
            spec = data.get("spec", {})
            priority = data.get("priority", 1)
            
            if action == "submit_sync":
                task_id = self.submit_sync_task(spec, priority)
                return {"task_id": task_id, "status": "submitted"}
                
            elif action == "submit_async":
                task_id = self.submit_async_task(spec, priority)
                return {"task_id": task_id, "status": "submitted"}
                
            elif action == "submit_delayed":
                delay = data.get("delay_seconds", 5)
                task_id = self.submit_delayed_task(spec, delay, priority)
                return {"task_id": task_id, "status": "scheduled", "delay": delay}
                
            elif action == "submit_periodic":
                period = data.get("period_seconds", 10)
                task_id = self.submit_periodic_task(spec, period, priority)
                return {"task_id": task_id, "status": "scheduled", "period": period}
                
            elif action == "get_status":
                task_id = data.get("task_id")
                if task_id:
                    status = self.get_task_status(task_id)
                    result = self.get_task_result(task_id)
                    return {
                        "task_id": task_id,
                        "status": status.value if status else "not_found",
                        "result": result
                    }
                return {"error": "task_id required"}
                
            elif action == "summary":
                return self.get_all_tasks_summary()
                
            else:
                return {"error": f"Unknown action: {action}"}
                
        elif isinstance(data, list):
            # 批量提交任务
            results = []
            for item in data:
                if isinstance(item, dict):
                    result = self.process_data(item)
                    results.append(result)
            return results
            
        return {"error": "Invalid data format"}
        
    def shutdown(self):
        """优雅关闭"""
        print(f"[{self.agent_id}] 正在关闭...")
        self.running = False
        
        # 等待调度器线程结束
        if self.scheduler_thread and self.scheduler_thread.is_alive():
            self.scheduler_thread.join(timeout=2)
            
        # 关闭线程池
        self.thread_pool.shutdown(wait=False)
        
        # 打印最终统计
        summary = self.get_all_tasks_summary()
        print(f"[{self.agent_id}] 关闭完成，任务统计: {summary}")
        
        # 保存最终状态
        self._save_final_summary(summary)
        
    def _save_final_summary(self, summary: Dict[str, Any]):
        """保存最终摘要"""
        try:
            import os
            os.makedirs(self.output_dir, exist_ok=True)
            
            filename = f"{self.output_dir}producer_summary_{self.agent_id}.json"
            with open(filename, 'w', encoding='utf-8') as f:
                json.dump({
                    "agent_id": self.agent_id,
                    "shutdown_time": datetime.now().isoformat(),
                    "summary": summary
                }, f, ensure_ascii=False, indent=2)
                
            print(f"[{self.agent_id}] 最终摘要已保存: {filename}")
            
        except Exception as e:
            print(f"[{self.agent_id}] 保存摘要失败: {str(e)}")

# 独立测试入口
if __name__ == "__main__":
    print("=" * 60)
    print("DESIGN_TASK_PRODUCER_MODULE - 独立测试模式")
    print("=" * 60)
    
    # 创建生产者实例
    producer = DesignTaskProducer("test_producer")
    
    # 测试用例
    test_cases = [
        # 同步任务
        {"action": "submit_sync", "spec": {"type": "UI", "pages": 5}, "priority": 1},
        
        # 异步任务
        {"action": "submit_async", "spec": {"type": "API", "endpoints": 10}, "priority": 2},
        
        # 延迟任务
        {"action": "submit_delayed", "spec": {"type": "Database", "tables": 3}, 
         "delay_seconds": 0.5, "priority": 1},
        
        # 周期性任务
        {"action": "submit_periodic", "spec": {"type": "Monitoring", "interval": "1m"}, 
         "period_seconds": 2, "priority": 3}
    ]
    
    print("\n🚀 开始测试任务提交...")
    results = []
    for i, test_case in enumerate(test_cases, 1):
        print(f"\n📝 测试用例 {i}: {test_case['action']}")
        result = producer.process_data(test_case)
        results.append(result)
        print(f"   结果: {result}")
        
    # 等待异步任务完成
    print("\n⏳ 等待异步任务完成...")
    time.sleep(3)
    
    # 获取摘要
    print("\n📊 任务摘要:")
    summary = producer.process_data({"action": "summary"})
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    
    # 检查每个任务的状态
    print("\n🔍 任务状态检查:")
    for result in results:
        if "task_id" in result:
            task_id = result["task_id"]
            status_result = producer.process_data({"action": "get_status", "task_id": task_id})
            print(f"任务 {task_id[:8]}...: {status_result.get('status', 'unknown')}")
            
    # 关闭生产者
    producer.shutdown()
    
    print("\n" + "=" * 60)
    print("✅ 测试完成")
    print("=" * 60)
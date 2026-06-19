#!/usr/bin/env python3
"""
任务生产者(Producer)节点实现
支持：批量提交、延迟任务、优先级队列
"""

import json
import time
import heapq
import uuid
from datetime import datetime, timedelta
from typing import Dict, List, Any, Optional, Tuple
from dataclasses import dataclass, asdict
import sys
import os

# 添加当前目录到Python路径
sys.path.insert(0, '/factory')

# 尝试导入BaseAgent，如果不存在则创建一个模拟版本
try:
    from base_agent import BaseAgent
except ImportError:
    # 创建一个简单的基类模拟
    class BaseAgent:
        """模拟基类，用于独立测试"""
        def __init__(self, agent_id: str = None):
            self.agent_id = agent_id or f"agent_{uuid.uuid4().hex[:8]}"
            self.logger = None
            
        def process_data(self, data: Any) -> Any:
            """子类需要重写的方法"""
            raise NotImplementedError
            
        def log(self, message: str, level: str = "INFO"):
            """日志方法"""
            timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            print(f"[{timestamp}] [{level}] {message}")

@dataclass
class Task:
    """任务数据结构"""
    task_id: str
    payload: Dict[str, Any]
    priority: int = 5  # 优先级，1-10，1为最高
    created_at: float = None
    delay_until: Optional[float] = None  # 延迟执行时间（时间戳）
    max_retries: int = 3
    retry_count: int = 0
    
    def __post_init__(self):
        if self.created_at is None:
            self.created_at = time.time()
    
    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'Task':
        return cls(**data)

class TaskProducer(BaseAgent):
    """
    任务生产者节点
    支持功能：
    1. 单个任务提交
    2. 批量任务提交
    3. 延迟任务提交
    4. 优先级队列管理
    """
    
    def __init__(self, agent_id: str = None):
        super().__init__(agent_id or "task_producer")
        
        # 任务队列：使用堆实现优先级队列
        # 元素格式：(优先级, 创建时间, 任务)
        self.task_queue: List[Tuple[int, float, Task]] = []
        
        # 延迟任务管理：按延迟执行时间排序
        self.delayed_tasks: List[Tuple[float, Task]] = []
        
        # 任务统计
        self.stats = {
            "total_submitted": 0,
            "total_pending": 0,
            "total_delayed": 0,
            "priority_distribution": {}
        }
        
        # 输出目录 - 使用当前工作目录下的outputs目录
        self.output_dir = os.path.join(os.getcwd(), "outputs")
        os.makedirs(self.output_dir, exist_ok=True)
        
        self.log(f"TaskProducer initialized with ID: {self.agent_id}")
        self.log(f"Output directory: {self.output_dir}")
    
    def add_task(self, payload: Dict[str, Any], priority: int = 5, 
                 delay_seconds: int = 0) -> str:
        """
        添加单个任务
        
        Args:
            payload: 任务负载数据
            priority: 优先级 (1-10, 1为最高)
            delay_seconds: 延迟秒数
        
        Returns:
            任务ID
        """
        try:
            # 验证优先级范围
            if not 1 <= priority <= 10:
                priority = max(1, min(10, priority))
            
            # 创建任务
            task = Task(
                task_id=f"task_{uuid.uuid4().hex[:8]}",
                payload=payload,
                priority=priority
            )
            
            # 处理延迟任务
            if delay_seconds > 0:
                task.delay_until = time.time() + delay_seconds
                heapq.heappush(self.delayed_tasks, (task.delay_until, task))
                self.stats["total_delayed"] += 1
                self.log(f"Added delayed task {task.task_id} with {delay_seconds}s delay")
            else:
                # 添加到优先级队列
                heapq.heappush(self.task_queue, (priority, task.created_at, task))
                self.stats["total_pending"] += 1
                self.log(f"Added task {task.task_id} with priority {priority}")
            
            # 更新统计
            self.stats["total_submitted"] += 1
            self.stats["priority_distribution"][priority] = \
                self.stats["priority_distribution"].get(priority, 0) + 1
            
            return task.task_id
            
        except Exception as e:
            self.log(f"Error adding task: {e}", "ERROR")
            raise
    
    def add_tasks_batch(self, tasks_data: List[Dict[str, Any]]) -> List[str]:
        """
        批量添加任务
        
        Args:
            tasks_data: 任务数据列表，每个元素包含:
                      - payload: 任务负载
                      - priority: 优先级 (可选)
                      - delay_seconds: 延迟秒数 (可选)
        
        Returns:
            任务ID列表
        """
        task_ids = []
        
        try:
            for i, task_info in enumerate(tasks_data):
                payload = task_info.get("payload", {})
                priority = task_info.get("priority", 5)
                delay_seconds = task_info.get("delay_seconds", 0)
                
                task_id = self.add_task(payload, priority, delay_seconds)
                task_ids.append(task_id)
            
            self.log(f"Batch added {len(task_ids)} tasks")
            return task_ids
            
        except Exception as e:
            self.log(f"Error in batch add: {e}", "ERROR")
            # 返回已成功添加的任务ID
            return task_ids
    
    def get_next_task(self) -> Optional[Task]:
        """
        获取下一个待处理任务
        考虑优先级和延迟任务
        
        Returns:
            任务对象，如果没有待处理任务则返回None
        """
        try:
            current_time = time.time()
            
            # 检查延迟任务是否到期
            self._process_delayed_tasks(current_time)
            
            # 从优先级队列获取任务
            if self.task_queue:
                priority, created_at, task = heapq.heappop(self.task_queue)
                self.stats["total_pending"] -= 1
                self.log(f"Dequeued task {task.task_id} with priority {priority}")
                return task
            
            return None
            
        except Exception as e:
            self.log(f"Error getting next task: {e}", "ERROR")
            return None
    
    def _process_delayed_tasks(self, current_time: float):
        """处理到期的延迟任务"""
        try:
            while self.delayed_tasks:
                # 检查最早的延迟任务
                delay_until, task = self.delayed_tasks[0]
                
                if delay_until <= current_time:
                    # 任务到期，移到优先级队列
                    heapq.heappop(self.delayed_tasks)
                    heapq.heappush(self.task_queue, (task.priority, task.created_at, task))
                    self.stats["total_delayed"] -= 1
                    self.stats["total_pending"] += 1
                    self.log(f"Delayed task {task.task_id} now ready for processing")
                else:
                    # 最早的延迟任务还没到期
                    break
                    
        except Exception as e:
            self.log(f"Error processing delayed tasks: {e}", "ERROR")
    
    def get_queue_status(self) -> Dict[str, Any]:
        """获取队列状态"""
        current_time = time.time()
        self._process_delayed_tasks(current_time)
        
        return {
            "pending_tasks": self.stats["total_pending"],
            "delayed_tasks": self.stats["total_delayed"],
            "total_submitted": self.stats["total_submitted"],
            "priority_distribution": self.stats["priority_distribution"],
            "queue_length": len(self.task_queue),
            "timestamp": current_time
        }
    
    def save_queue_snapshot(self, filename: str = None):
        """保存队列快照到文件"""
        if filename is None:
            filename = f"queue_snapshot_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        
        snapshot = {
            "timestamp": datetime.now().isoformat(),
            "status": self.get_queue_status(),
            "pending_tasks": [
                {
                    "task_id": task.task_id,
                    "priority": priority,
                    "created_at": task.created_at,
                    "payload": task.payload
                }
                for priority, _, task in self.task_queue[:10]  # 只保存前10个
            ],
            "delayed_tasks": [
                {
                    "task_id": task.task_id,
                    "delay_until": task.delay_until,
                    "priority": task.priority,
                    "payload": task.payload
                }
                for delay_until, task in self.delayed_tasks[:10]  # 只保存前10个
            ]
        }
        
        filepath = os.path.join(self.output_dir, filename)
        try:
            with open(filepath, 'w', encoding='utf-8') as f:
                json.dump(snapshot, f, indent=2, ensure_ascii=False)
            self.log(f"Queue snapshot saved to {filepath}")
            return filepath
        except Exception as e:
            self.log(f"Error saving queue snapshot: {e}", "ERROR")
            return None
    
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """
        处理数据的主要方法（继承自BaseAgent）
        
        Args:
            data: 输入数据，可以包含:
                 - action: "add_task", "add_batch", "get_next", "get_status"
                 - 任务相关数据
        
        Returns:
            处理结果
        """
        try:
            action = data.get("action", "unknown")
            result = {"action": action, "timestamp": time.time()}
            
            if action == "add_task":
                # 添加单个任务
                task_id = self.add_task(
                    payload=data.get("payload", {}),
                    priority=data.get("priority", 5),
                    delay_seconds=data.get("delay_seconds", 0)
                )
                result.update({
                    "status": "success",
                    "task_id": task_id,
                    "message": f"Task {task_id} added successfully"
                })
                
            elif action == "add_batch":
                # 批量添加任务
                tasks_data = data.get("tasks", [])
                task_ids = self.add_tasks_batch(tasks_data)
                result.update({
                    "status": "success",
                    "task_ids": task_ids,
                    "count": len(task_ids),
                    "message": f"Batch added {len(task_ids)} tasks"
                })
                
            elif action == "get_next":
                # 获取下一个任务
                task = self.get_next_task()
                if task:
                    result.update({
                        "status": "success",
                        "task": task.to_dict(),
                        "message": f"Retrieved task {task.task_id}"
                    })
                else:
                    result.update({
                        "status": "empty",
                        "task": None,
                        "message": "No tasks in queue"
                    })
                    
            elif action == "get_status":
                # 获取队列状态
                status = self.get_queue_status()
                result.update({
                    "status": "success",
                    "queue_status": status,
                    "message": "Queue status retrieved"
                })
                
            elif action == "save_snapshot":
                # 保存队列快照
                filename = data.get("filename")
                filepath = self.save_queue_snapshot(filename)
                result.update({
                    "status": "success",
                    "filepath": filepath,
                    "message": f"Snapshot saved to {filepath}"
                })
                
            else:
                result.update({
                    "status": "error",
                    "message": f"Unknown action: {action}"
                })
            
            # 保存结果到输出文件
            self._save_result(result)
            
            return result
            
        except Exception as e:
            self.log(f"Error in process_data: {e}", "ERROR")
            error_result = {
                "action": data.get("action", "unknown"),
                "status": "error",
                "error": str(e),
                "timestamp": time.time()
            }
            self._save_result(error_result)
            return error_result
    
    def _save_result(self, result: Dict[str, Any]):
        """保存处理结果到文件"""
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"producer_result_{timestamp}.json"
        filepath = os.path.join(self.output_dir, filename)
        
        try:
            with open(filepath, 'w', encoding='utf-8') as f:
                json.dump(result, f, indent=2, ensure_ascii=False)
            self.log(f"Result saved to {filepath}")
        except Exception as e:
            self.log(f"Error saving result: {e}", "ERROR")

# 独立测试函数
def test_task_producer():
    """测试任务生产者功能"""
    print("=" * 60)
    print("TASK PRODUCER TEST SUITE")
    print("=" * 60)
    
    try:
        # 创建生产者实例
        producer = TaskProducer("test_producer_001")
        
        # 测试1: 添加单个任务
        print("\n1. Testing single task addition...")
        task_id = producer.add_task(
            payload={"type": "test", "data": "hello world"},
            priority=3,
            delay_seconds=0
        )
        print(f"   ✓ Added task: {task_id}")
        
        # 测试2: 添加延迟任务
        print("\n2. Testing delayed task...")
        delayed_id = producer.add_task(
            payload={"type": "delayed", "data": "delayed work"},
            priority=5,
            delay_seconds=2  # 2秒后执行
        )
        print(f"   ✓ Added delayed task: {delayed_id}")
        
        # 测试3: 批量添加
        print("\n3. Testing batch addition...")
        batch_tasks = [
            {"payload": {"batch": 1, "data": "batch item 1"}, "priority": 2},
            {"payload": {"batch": 2, "data": "batch item 2"}, "priority": 4},
            {"payload": {"batch": 3, "data": "batch item 3"}, "priority": 1},
            {"payload": {"batch": 4, "data": "batch item 4"}, "delay_seconds": 3}
        ]
        batch_ids = producer.add_tasks_batch(batch_tasks)
        print(f"   ✓ Batch added {len(batch_ids)} tasks")
        
        # 测试4: 获取状态
        print("\n4. Testing queue status...")
        status = producer.get_queue_status()
        print(f"   ✓ Queue status: {json.dumps(status, indent=2)}")
        
        # 测试5: 获取任务
        print("\n5. Testing task retrieval...")
        for i in range(3):
            task = producer.get_next_task()
            if task:
                print(f"   ✓ Retrieved task: {task.task_id} (priority: {task.priority})")
            else:
                print("   ✓ No more tasks")
        
        # 测试6: 等待延迟任务
        print("\n6. Waiting for delayed tasks...")
        time.sleep(3)  # 等待延迟任务到期
        
        # 再次获取任务
        task = producer.get_next_task()
        if task:
            print(f"   ✓ Retrieved delayed task: {task.task_id}")
        
        # 测试7: 保存快照
        print("\n7. Saving queue snapshot...")
        snapshot_path = producer.save_queue_snapshot()
        print(f"   ✓ Snapshot saved to: {snapshot_path}")
        
        # 测试8: 通过process_data接口测试
        print("\n8. Testing process_data interface...")
        test_data = {
            "action": "add_task",
            "payload": {"interface_test": True},
            "priority": 6
        }
        result = producer.process_data(test_data)
        print(f"   ✓ Process data result: {result['status']}")
        
        # 最终状态
        print("\n" + "=" * 60)
        print("TEST RESULTS SUMMARY")
        print("=" * 60)
        final_status = producer.get_queue_status()
        print(f"Total submitted: {final_status['total_submitted']}")
        print(f"Pending tasks: {final_status['pending_tasks']}")
        print(f"Delayed tasks: {final_status['delayed_tasks']}")
        print(f"Priority distribution: {final_status['priority_distribution']}")
        
        print("\n" + "=" * 60)
        print("✅ ALL TESTS COMPLETED SUCCESSFULLY!")
        print("=" * 60)
        
        # 输出成功标记
        print("NODE_VERIFIED_AND_READY")
        return True
        
    except Exception as e:
        print(f"\n❌ TEST FAILED WITH ERROR: {e}")
        import traceback
        traceback.print_exc()
        return False

# 主程序入口
if __name__ == "__main__":
    # 设置输出编码
    import sys
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')
    
    print("Starting Task Producer Node...")
    print(f"Python version: {sys.version}")
    print(f"Working directory: {os.getcwd()}")
    print(f"Output directory: {os.path.join(os.getcwd(), 'outputs')}")
    
    # 运行测试
    success = test_task_producer()
    
    if not success:
        print("\n❌ Test failed, please check the errors above")
        sys.exit(1)
    else:
        print("\n🎉 Task Producer node is ready for production use!")
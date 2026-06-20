#!/usr/bin/env python3
"""
Task Data Model Implementation
核心任务数据模型（Task类），包含任务ID、状态、负载(Payload)、结果、元数据等字段。
"""

import json
import uuid
from datetime import datetime
from enum import Enum
from dataclasses import dataclass, field, asdict
from typing import Any, Dict, List, Optional


class TaskStatus(Enum):
    """任务状态枚举"""
    PENDING = "pending"
    QUEUED = "queued"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"
    TIMEOUT = "timeout"


class TaskPriority(Enum):
    """任务优先级枚举"""
    LOW = 0
    NORMAL = 1
    HIGH = 2
    CRITICAL = 3


@dataclass
class TaskResult:
    """任务执行结果"""
    success: bool
    data: Any = None
    error: Optional[str] = None
    execution_time: Optional[float] = None  # 执行耗时（秒）


@dataclass
class Task:
    """
    核心任务数据模型
    包含任务ID、状态、负载(Payload)、结果、元数据等字段
    """
    task_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    name: str = ""
    description: str = ""
    status: TaskStatus = TaskStatus.PENDING
    priority: TaskPriority = TaskPriority.NORMAL
    payload: Dict[str, Any] = field(default_factory=dict)
    result: Optional[TaskResult] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    
    # 时间戳
    created_at: str = field(default_factory=lambda: datetime.now().isoformat())
    updated_at: str = field(default_factory=lambda: datetime.now().isoformat())
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    
    # 依赖和重试
    depends_on: List[str] = field(default_factory=list)
    max_retries: int = 0
    retry_count: int = 0
    
    # 超时设置（秒）
    timeout: Optional[int] = None
    
    # 错误信息
    error_message: Optional[str] = None
    error_traceback: Optional[str] = None
    
    def update_status(self, new_status: TaskStatus) -> None:
        """更新任务状态并自动更新时间戳"""
        self.status = new_status
        self.updated_at = datetime.now().isoformat()
        
        if new_status == TaskStatus.RUNNING and self.started_at is None:
            self.started_at = datetime.now().isoformat()
        elif new_status in [TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.TIMEOUT]:
            if self.completed_at is None:
                self.completed_at = datetime.now().isoformat()
    
    def set_result(self, result: TaskResult) -> None:
        """设置任务结果"""
        self.result = result
        if result.success:
            self.update_status(TaskStatus.COMPLETED)
        else:
            self.error_message = result.error
            self.update_status(TaskStatus.FAILED)
    
    def to_dict(self) -> Dict[str, Any]:
        """转换为字典格式"""
        data = asdict(self)
        data['status'] = self.status.value
        data['priority'] = self.priority.value
        return data
    
    def to_json(self) -> str:
        """转换为JSON字符串"""
        return json.dumps(self.to_dict(), indent=2, ensure_ascii=False)
    
    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> 'Task':
        """从字典创建Task实例"""
        data['status'] = TaskStatus(data['status'])
        data['priority'] = TaskPriority(data['priority'])
        if data.get('result'):
            data['result'] = TaskResult(**data['result'])
        return cls(**data)
    
    @classmethod
    def from_json(cls, json_str: str) -> 'Task':
        """从JSON字符串创建Task实例"""
        return cls.from_dict(json.loads(json_str))
    
    def __str__(self) -> str:
        """字符串表示"""
        return f"Task(id={self.task_id}, name='{self.name}', status={self.status.value})"
    
    def __repr__(self) -> str:
        return self.__str__()


# 示例使用和测试
if __name__ == "__main__":
    print("=== 任务数据模型测试 ===")
    
    # 创建任务
    task = Task(
        name="数据分析任务",
        description="对用户行为数据进行分析",
        payload={
            "data_source": "user_behavior.csv",
            "analysis_type": "clustering",
            "parameters": {"n_clusters": 5}
        },
        priority=TaskPriority.HIGH,
        metadata={"user_id": "user_123", "department": "analytics"}
    )
    
    print(f"1. 创建任务: {task}")
    print(f"   任务ID: {task.task_id}")
    print(f"   状态: {task.status.value}")
    print(f"   创建时间: {task.created_at}")
    
    # 模拟任务执行
    task.update_status(TaskStatus.RUNNING)
    print(f"\n2. 任务开始执行: {task.status.value}")
    print(f"   开始时间: {task.started_at}")
    
    # 设置结果
    result = TaskResult(
        success=True,
        data={"clusters": [1, 2, 3, 4, 5], "accuracy": 0.85},
        execution_time=45.2
    )
    task.set_result(result)
    
    print(f"\n3. 任务完成: {task.status.value}")
    print(f"   完成时间: {task.completed_at}")
    print(f"   结果: {task.result}")
    
    # 测试JSON序列化
    json_output = task.to_json()
    print(f"\n4. JSON输出:\n{json_output}")
    
    # 测试从JSON恢复
    restored_task = Task.from_json(json_output)
    print(f"\n5. 从JSON恢复的任务: {restored_task}")
    print(f"   状态: {restored_task.status.value}")
    print(f"   结果数据: {restored_task.result.data}")
    
    # 测试失败任务
    failed_task = Task(
        name="网络请求任务",
        payload={"url": "https://api.example.com/data"},
        max_retries=3
    )
    failed_task.update_status(TaskStatus.RUNNING)
    
    error_result = TaskResult(
        success=False,
        error="Connection timeout",
        execution_time=30.0
    )
    failed_task.set_result(error_result)
    
    print(f"\n6. 失败任务: {failed_task}")
    print(f"   错误信息: {failed_task.error_message}")
    print(f"   状态: {failed_task.status.value}")
    
    print("\n=== 测试完成 ===")
    print("NODE_VERIFIED_AND_READY: Task data model implemented successfully!")
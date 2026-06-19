#!/usr/bin/env python3
"""
Core Data Model Design for Distributed Task Queue System
Node: design_core_data_model
Task: 设计核心数据模型（Task, Worker, Queue, Job Status等）与API规范
"""

import json
import os
from dataclasses import dataclass, field, asdict
from enum import Enum
from typing import List, Optional, Dict, Any
from datetime import datetime, timezone


# ============================================================
# 1. Core Enums - 核心枚举定义
# ============================================================

class TaskStatus(Enum):
    """任务状态枚举"""
    PENDING = "pending"          # 等待执行
    QUEUED = "queued"            # 已入队等待分配
    ASSIGNED = "assigned"        # 已分配给Worker
    RUNNING = "running"          # 正在执行
    SUCCESS = "success"          # 执行成功
    FAILED = "failed"            # 执行失败
    RETRYING = "retrying"        # 重试中
    TIMEOUT = "timeout"          # 执行超时
    CANCELLED = "cancelled"      # 已取消
    DEAD_LETTER = "dead_letter"  # 进入死信队列


class TaskPriority(Enum):
    """任务优先级枚举"""
    CRITICAL = 0   # 最高优先级
    HIGH = 1
    NORMAL = 2
    LOW = 3
    IDLE = 4       # 最低优先级（空闲时执行）


class WorkerStatus(Enum):
    """Worker状态枚举"""
    IDLE = "idle"              # 空闲
    BUSY = "busy"              # 忙碌
    DRAINING = "draining"      # 排空模式（不接受新任务）
    OFFLINE = "offline"        # 离线
    ERROR = "error"            # 错误状态
    MAINTENANCE = "maintenance"  # 维护模式


class QueueType(Enum):
    """队列类型枚举"""
    STANDARD = "standard"      # 标准队列
    PRIORITY = "priority"      # 优先级队列
    DELAYED = "delayed"        # 延迟队列
    DEAD_LETTER = "dead_letter"  # 死信队列
    FIFO = "fifo"              # 先进先出队列


class JobType(Enum):
    """任务类型枚举"""
    BATCH = "batch"            # 批处理任务
    STREAMING = "streaming"    # 流式任务
    CRON = "cron"              # 定时任务
    ONE_SHOT = "one_shot"      # 一次性任务
    CHAIN = "chain"            # 链式任务（DAG工作流）


# ============================================================
# 2. Core Data Models - 核心数据模型
# ============================================================

@dataclass
class RetryPolicy:
    """重试策略"""
    max_retries: int = 3
    backoff_type: str = "exponential"  # linear, exponential, fixed
    initial_delay_ms: int = 1000
    max_delay_ms: int = 60000
    backoff_multiplier: float = 2.0
    retryable_errors: List[str] = field(default_factory=lambda: ["TimeoutError", "ConnectionError"])


@dataclass
class ResourceRequirement:
    """资源需求定义"""
    cpu_cores: float = 1.0
    memory_mb: int = 512
    gpu_count: int = 0
    disk_mb: int = 1024
    custom_resources: Dict[str, Any] = field(default_factory=dict)


@dataclass
class TaskDefinition:
    """任务定义 - 系统核心数据模型之一"""
    task_id: str
    task_name: str
    task_type: str  # JobType enum value
    queue_name: str
    payload: Dict[str, Any]
    priority: str = "NORMAL"  # TaskPriority enum value
    status: str = "PENDING"   # TaskStatus enum value
    created_at: str = ""
    updated_at: str = ""
    scheduled_at: Optional[str] = None
    started_at: Optional[str] = None
    completed_at: Optional[str] = None
    timeout_ms: int = 30000
    retry_policy: Dict[str, Any] = field(default_factory=lambda: RetryPolicy().__dict__)
    resource_requirement: Dict[str, Any] = field(default_factory=lambda: ResourceRequirement().__dict__)
    metadata: Dict[str, Any] = field(default_factory=dict)
    result: Optional[Dict[str, Any]] = None
    error_message: Optional[str] = None
    retry_count: int = 0
    assigned_worker_id: Optional[str] = None
    tags: List[str] = field(default_factory=list)
    version: int = 1
    
    def to_dict(self):
        return {k: v for k, v in asdict(self).items() if v is not None}
    
    def transition_to(self, new_status: str):
        """状态转换 - 包含合法性校验"""
        valid_transitions = {
            "PENDING": ["QUEUED", "CANCELLED"],
            "QUEUED": ["ASSIGNED", "CANCELLED"],
            "ASSIGNED": ["RUNNING", "CANCELLED", "QUEUED"],
            "RUNNING": ["SUCCESS", "FAILED", "TIMEOUT", "CANCELLED"],
            "FAILED": ["RETRYING", "DEAD_LETTER", "CANCELLED"],
            "RETRYING": ["QUEUED", "DEAD_LETTER"],
            "TIMEOUT": ["RETRYING", "DEAD_LETTER", "CANCELLED"],
            "SUCCESS": [],
            "CANCELLED": [],
            "DEAD_LETTER": ["CANCELLED"],
        }
        allowed = valid_transitions.get(self.status, [])
        if new_status not in allowed:
            raise ValueError(f"Invalid status transition: {self.status} -> {new_status}")
        self.status = new_status
        self.updated_at = datetime.now(timezone.utc).isoformat()


@dataclass
class WorkerNode:
    """Worker节点 - 系统核心数据模型之二"""
    worker_id: str
    worker_name: str
    host: str
    port: int
    status: str = "IDLE"  # WorkerStatus enum value
    capabilities: List[str] = field(default_factory=list)
    max_concurrent_tasks: int = 5
    current_task_count: int = 0
    current_tasks: List[str] = field(default_factory=list)
    resource_capacity: Dict[str, Any] = field(default_factory=lambda: {
        "cpu_cores": 4.0,
        "memory_mb": 8192,
        "gpu_count": 0,
    })
    resource_used: Dict[str, Any] = field(default_factory=lambda: {
        "cpu_cores": 0.0,
        "memory_mb": 0,
        "gpu_count": 0,
    })
    registered_at: str = ""
    last_heartbeat: str = ""
    health_check_url: Optional[str] = None
    tags: List[str] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)
    completed_tasks: int = 0
    failed_tasks: int = 0
    
    def to_dict(self):
        return {k: v for k, v in asdict(self).items() if v is not None}
    
    @property
    def is_available(self) -> bool:
        """判断Worker是否可接受新任务"""
        return (
            self.status in ["IDLE", "BUSY"]
            and self.current_task_count < self.max_concurrent_tasks
        )
    
    @property
    def load_factor(self) -> float:
        """负载因子 (0.0 ~ 1.0)"""
        if self.max_concurrent_tasks == 0:
            return 1.0
        return self.current_task_count / self.max_concurrent_tasks


@dataclass
class TaskQueue:
    """任务队列 - 系统核心数据模型之三"""
    queue_id: str
    queue_name: str
    queue_type: str = "STANDARD"  # QueueType enum value
    max_size: int = 10000
    current_size: int = 0
    pending_count: int = 0
    processing_count: int = 0
    completed_count: int = 0
    failed_count: int = 0
    dead_letter_count: int = 0
    consumer_groups: List[str] = field(default_factory=list)
    priority_enabled: bool = True
    ttl_seconds: int = 86400  # 默认24小时TTL
    created_at: str = ""
    updated_at: str = ""
    metadata: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self):
        return {k: v for k, v in asdict(self).items() if v is not None}
    
    @property
    def is_full(self) -> bool:
        return self.current_size >= self.max_size
    
    @property
    def utilization(self) -> float:
        """队列利用率"""
        if self.max_size == 0:
            return 1.0
        return self.current_size / self.max_size


@dataclass
class JobResult:
    """任务执行结果"""
    task_id: str
    worker_id: str
    status: str  # TaskStatus enum value
    result_data: Dict[str, Any] = field(default_factory=dict)
    error_message: Optional[str] = None
    error_traceback: Optional[str] = None
    started_at: str = ""
    completed_at: str = ""
    duration_ms: int = 0
    metrics: Dict[str, Any] = field(default_factory=dict)
    
    def to_dict(self):
        return {k: v for k, v in asdict(self).items() if v is not None}


@dataclass
class ScheduledTask:
    """定时任务配置"""
    schedule_id: str
    task_template: Dict[str, Any]
    cron_expression: Optional[str] = None
    interval_ms: Optional[int] = None
    next_run_at: Optional[str] = None
    last_run_at: Optional[str] = None
    enabled: bool = True
    max_instances: int = 1
    misfire_policy: str = "DO_NOTHING"  # DO_NOTHING, FIRE_ONCE, RUN_NOW
    
    def to_dict(self):
        return {k: v for k, v in asdict(self).items() if v is not None}


# ============================================================
# 3. API Specification - API规范定义
# ============================================================

API_SPEC = {
    "openapi": "3.0.3",
    "info": {
        "title": "Distributed Task Queue API",
        "version": "1.0.0",
        "description": "高并发分布式任务队列系统 API 规范"
    },
    "servers": [
        {"url": "http://localhost:8080/api/v1", "description": "本地开发服务器"}
    ],
    "paths": {
        # --- Task Management ---
        "/tasks": {
            "post": {
                "summary": "提交新任务",
                "tags": ["Task Management"],
                "requestBody": {
                    "required": True,
                    "content": {
                        "application/json": {
                            "schema": {"$ref": "#/components/schemas/TaskCreateRequest"}
                        }
                    }
                },
                "responses": {
                    "201": {"description": "任务创建成功", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/TaskDefinition"}}}},
                    "400": {"description": "请求参数错误"},
                    "429": {"description": "队列已满，拒绝提交"}
                }
            },
            "get": {
                "summary": "查询任务列表",
                "tags": ["Task Management"],
                "parameters": [
                    {"name": "status", "in": "query", "schema": {"type": "string", "enum": [s.value for s in TaskStatus]}},
                    {"name": "queue", "in": "query", "schema": {"type": "string"}},
                    {"name": "priority", "in": "query", "schema": {"type": "string"}},
                    {"name": "limit", "in": "query", "schema": {"type": "integer", "default": 20}},
                    {"name": "offset", "in": "query", "schema": {"type": "integer", "default": 0}},
                ],
                "responses": {
                    "200": {"description": "任务列表", "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/TaskDefinition"}}}}}
                }
            }
        },
        "/tasks/{task_id}": {
            "get": {
                "summary": "获取任务详情",
                "tags": ["Task Management"],
                "parameters": [{"name": "task_id", "in": "path", "required": True, "schema": {"type": "string"}}],
                "responses": {
                    "200": {"description": "任务详情", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/TaskDefinition"}}}},
                    "404": {"description": "任务不存在"}
                }
            },
            "delete": {
                "summary": "取消任务",
                "tags": ["Task Management"],
                "parameters": [{"name": "task_id", "in": "path", "required": True, "schema": {"type": "string"}}],
                "responses": {
                    "200": {"description": "任务已取消"},
                    "404": {"description": "任务不存在"}
                }
            }
        },
        "/tasks/{task_id}/retry": {
            "post": {
                "summary": "重试失败任务",
                "tags": ["Task Management"],
                "parameters": [{"name": "task_id", "in": "path", "required": True, "schema": {"type": "string"}}],
                "responses": {"200": {"description": "任务已重新入队"}, "400": {"description": "任务状态不允许重试"}}
            }
        },
        "/tasks/batch": {
            "post": {
                "summary": "批量提交任务",
                "tags": ["Task Management"],
                "requestBody": {
                    "required": True,
                    "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/TaskCreateRequest"}}}}
                },
                "responses": {"201": {"description": "批量任务创建成功"}}
            }
        },
        
        # --- Queue Management ---
        "/queues": {
            "get": {
                "summary": "获取所有队列信息",
                "tags": ["Queue Management"],
                "responses": {"200": {"description": "队列列表", "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/TaskQueue"}}}}}}
            },
            "post": {
                "summary": "创建新队列",
                "tags": ["Queue Management"],
                "requestBody": {"required": True, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/QueueCreateRequest"}}}},
                "responses": {"201": {"description": "队列创建成功"}}
            }
        },
        "/queues/{queue_name}": {
            "get": {
                "summary": "获取队列详情",
                "tags": ["Queue Management"],
                "parameters": [{"name": "queue_name", "in": "path", "required": True, "schema": {"type": "string"}}],
                "responses": {"200": {"description": "队列详情"}}
            },
            "delete": {
                "summary": "删除队列",
                "tags": ["Queue Management"],
                "parameters": [{"name": "queue_name", "in": "path", "required": True, "schema": {"type": "string"}}],
                "responses": {"204": {"description": "队列已删除"}}
            }
        },
        "/queues/{queue_name}/purge": {
            "post": {
                "summary": "清空队列",
                "tags": ["Queue Management"],
                "parameters": [{"name": "queue_name", "in": "path", "required": True, "schema": {"type": "string"}}],
                "responses": {"200": {"description": "队列已清空"}}
            }
        },
        
        # --- Worker Management ---
        "/workers": {
            "get": {
                "summary": "获取所有Worker列表",
                "tags": ["Worker Management"],
                "parameters": [
                    {"name": "status", "in": "query", "schema": {"type": "string"}},
                ],
                "responses": {"200": {"description": "Worker列表", "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/WorkerNode"}}}}}}
            }
        },
        "/workers/{worker_id}": {
            "get": {
                "summary": "获取Worker详情",
                "tags": ["Worker Management"],
                "parameters": [{"name": "worker_id", "in": "path", "required": True, "schema": {"type": "string"}}],
                "responses": {"200": {"description": "Worker详情"}, "404": {"description": "Worker不存在"}}
            }
        },
        "/workers/{worker_id}/heartbeat": {
            "post": {
                "summary": "Worker心跳上报",
                "tags": ["Worker Management"],
                "parameters": [{"name": "worker_id", "in": "path", "required": True, "schema": {"type": "string"}}],
                "requestBody": {"required": True, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/HeartbeatRequest"}}}},
                "responses": {"200": {"description": "心跳确认"}}
            }
        },
        "/workers/{worker_id}/drain": {
            "post": {
                "summary": "排空Worker（优雅下线）",
                "tags": ["Worker Management"],
                "parameters": [{"name": "worker_id", "in": "path", "required": True, "schema": {"type": "string"}}],
                "responses": {"200": {"description": "已进入排空模式"}}
            }
        },
        
        # --- Monitoring ---
        "/metrics": {
            "get": {
                "summary": "获取系统指标",
                "tags": ["Monitoring"],
                "responses": {"200": {"description": "系统指标", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/SystemMetrics"}}}}}
            }
        },
        "/health": {
            "get": {
                "summary": "健康检查",
                "tags": ["Monitoring"],
                "responses": {"200": {"description": "系统健康"}}
            }
        }
    },
    "components": {
        "schemas": {
            "TaskCreateRequest": {
                "type": "object",
                "required": ["task_name", "task_type", "payload"],
                "properties": {
                    "task_name": {"type": "string", "description": "任务名称"},
                    "task_type": {"type": "string", "enum": [j.value for j in JobType], "description": "任务类型"},
                    "queue_name": {"type": "string", "default": "default", "description": "目标队列"},
                    "payload": {"type": "object", "description": "任务负载数据"},
                    "priority": {"type": "string", "enum": [p.name for p in TaskPriority], "default": "NORMAL"},
                    "timeout_ms": {"type": "integer", "default": 30000, "description": "超时时间(ms)"},
                    "retry_policy": {"$ref": "#/components/schemas/RetryPolicy"},
                    "resource_requirement": {"$ref": "#/components/schemas/ResourceRequirement"},
                    "scheduled_at": {"type": "string", "format": "date-time", "description": "定时执行时间"},
                    "tags": {"type": "array", "items": {"type": "string"}},
                    "metadata": {"type": "object"}
                }
            },
            "TaskDefinition": {
                "type": "object",
                "properties": {
                    "task_id": {"type": "string"},
                    "task_name": {"type": "string"},
                    "task_type": {"type": "string"},
                    "queue_name": {"type": "string"},
                    "payload": {"type": "object"},
                    "priority": {"type": "string"},
                    "status": {"type": "string", "enum": [s.value for s in TaskStatus]},
                    "created_at": {"type": "string", "format": "date-time"},
                    "updated_at": {"type": "string", "format": "date-time"},
                    "started_at": {"type": "string", "format": "date-time"},
                    "completed_at": {"type": "string", "format": "date-time"},
                    "timeout_ms": {"type": "integer"},
                    "retry_policy": {"$ref": "#/components/schemas/RetryPolicy"},
                    "assigned_worker_id": {"type": "string"},
                    "result": {"type": "object"},
                    "error_message": {"type": "string"},
                    "retry_count": {"type": "integer"},
                    "tags": {"type": "array", "items": {"type": "string"}},
                    "version": {"type": "integer"}
                }
            },
            "RetryPolicy": {
                "type": "object",
                "properties": {
                    "max_retries": {"type": "integer", "default": 3},
                    "backoff_type": {"type": "string", "enum": ["linear", "exponential", "fixed"], "default": "exponential"},
                    "initial_delay_ms": {"type": "integer", "default": 1000},
                    "max_delay_ms": {"type": "integer", "default": 60000},
                    "backoff_multiplier": {"type": "number", "default": 2.0},
                    "retryable_errors": {"type": "array", "items": {"type": "string"}}
                }
            },
            "ResourceRequirement": {
                "type": "object",
                "properties": {
                    "cpu_cores": {"type": "number", "default": 1.0},
                    "memory_mb": {"type": "integer", "default": 512},
                    "gpu_count": {"type": "integer", "default": 0},
                    "disk_mb": {"type": "integer", "default": 1024},
                    "custom_resources": {"type": "object"}
                }
            },
            "TaskQueue": {
                "type": "object",
                "properties": {
                    "queue_id": {"type": "string"},
                    "queue_name": {"type": "string"},
                    "queue_type": {"type": "string", "enum": [q.value for q in QueueType]},
                    "max_size": {"type": "integer"},
                    "current_size": {"type": "integer"},
                    "pending_count": {"type": "integer"},
                    "processing_count": {"type": "integer"},
                    "completed_count": {"type": "integer"},
                    "failed_count": {"type": "integer"},
                    "consumer_groups": {"type": "array", "items": {"type": "string"}},
                    "ttl_seconds": {"type": "integer"},
                    "created_at": {"type": "string", "format": "date-time"}
                }
            },
            "WorkerNode": {
                "type": "object",
                "properties": {
                    "worker_id": {"type": "string"},
                    "worker_name": {"type": "string"},
                    "host": {"type": "string"},
                    "port": {"type": "integer"},
                    "status": {"type": "string", "enum": [w.value for w in WorkerStatus]},
                    "capabilities": {"type": "array", "items": {"type": "string"}},
                    "max_concurrent_tasks": {"type": "integer"},
                    "current_task_count": {"type": "integer"},
                    "resource_capacity": {"type": "object"},
                    "resource_used": {"type": "object"},
                    "last_heartbeat": {"type": "string", "format": "date-time"},
                    "completed_tasks": {"type": "integer"},
                    "failed_tasks": {"type": "integer"},
                    "tags": {"type": "array", "items": {"type": "string"}}
                }
            },
            "QueueCreateRequest": {
                "type": "object",
                "required": ["queue_name"],
                "properties": {
                    "queue_name": {"type": "string"},
                    "queue_type": {"type": "string", "enum": [q.value for q in QueueType], "default": "standard"},
                    "max_size": {"type": "integer", "default": 10000},
                    "ttl_seconds": {"type": "integer", "default": 86400},
                    "priority_enabled": {"type": "boolean", "default": True}
                }
            },
            "HeartbeatRequest": {
                "type": "object",
                "properties": {
                    "status": {"type": "string", "enum": [w.value for w in WorkerStatus]},
                    "current_tasks": {"type": "array", "items": {"type": "string"}},
                    "resource_used": {"type": "object"}
                }
            },
            "SystemMetrics": {
                "type": "object",
                "properties": {
                    "total_tasks": {"type": "integer"},
                    "pending_tasks": {"type": "integer"},
                    "running_tasks": {"type": "integer"},
                    "completed_tasks": {"type": "integer"},
                    "failed_tasks": {"type": "integer"},
                    "active_workers": {"type": "integer"},
                    "idle_workers": {"type": "integer"},
                    "total_queues": {"type": "integer"},
                    "avg_task_duration_ms": {"type": "number"},
                    "tasks_per_second": {"type": "number"},
                    "queue_depth_by_name": {"type": "object"}
                }
            }
        }
    }
}


# ============================================================
# 4. Validation & Utilities
# ============================================================

class ModelValidator:
    """数据模型验证器"""
    
    @staticmethod
    def validate_task(task_dict: dict) -> List[str]:
        """验证任务定义的合法性"""
        errors = []
        required_fields = ["task_id", "task_name", "task_type", "queue_name", "payload"]
        for field_name in required_fields:
            if field_name not in task_dict:
                errors.append(f"Missing required field: {field_name}")
        
        if "task_type" in task_dict:
            valid_types = [j.value for j in JobType]
            if task_dict["task_type"] not in valid_types:
                errors.append(f"Invalid task_type: {task_dict['task_type']}. Must be one of {valid_types}")
        
        if "priority" in task_dict:
            valid_priorities = [p.name for p in TaskPriority]
            if task_dict["priority"] not in valid_priorities:
                errors.append(f"Invalid priority: {task_dict['priority']}. Must be one of {valid_priorities}")
        
        if "timeout_ms" in task_dict:
            if not isinstance(task_dict["timeout_ms"], int) or task_dict["timeout_ms"] <= 0:
                errors.append("timeout_ms must be a positive integer")
        
        return errors
    
    @staticmethod
    def validate_worker(worker_dict: dict) -> List[str]:
        """验证Worker节点定义的合法性"""
        errors = []
        required_fields = ["worker_id", "worker_name", "host", "port"]
        for field_name in required_fields:
            if field_name not in worker_dict:
                errors.append(f"Missing required field: {field_name}")
        
        if "port" in worker_dict:
            port = worker_dict["port"]
            if not isinstance(port, int) or not (1 <= port <= 65535):
                errors.append(f"Invalid port: {port}. Must be 1-65535")
        
        return errors
    
    @staticmethod
    def validate_queue(queue_dict: dict) -> List[str]:
        """验证队列定义的合法性"""
        errors = []
        if "queue_name" not in queue_dict:
            errors.append("Missing required field: queue_name")
        
        if "queue_type" in queue_dict:
            valid_types = [q.value for q in QueueType]
            if queue_dict["queue_type"] not in valid_types:
                errors.append(f"Invalid queue_type: {queue_dict['queue_type']}")
        
        if "max_size" in queue_dict:
            if not isinstance(queue_dict["max_size"], int) or queue_dict["max_size"] <= 0:
                errors.append("max_size must be a positive integer")
        
        return errors


# ============================================================
# 5. Export Data Model Documentation
# ============================================================

def generate_model_documentation() -> dict:
    """生成数据模型文档"""
    doc = {
        "system": "Distributed Task Queue",
        "version": "1.0.0",
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "enums": {
            "TaskStatus": {e.name: e.value for e in TaskStatus},
            "TaskPriority": {e.name: e.value for e in TaskPriority},
            "WorkerStatus": {e.name: e.value for e in WorkerStatus},
            "QueueType": {e.name: e.value for e in QueueType},
            "JobType": {e.name: e.value for e in JobType},
        },
        "models": {
            "TaskDefinition": {
                "description": "任务定义，系统核心数据模型。包含任务的所有元数据、状态、重试策略等。",
                "fields": {k: str(v) for k, v in TaskDefinition.__dataclass_fields__.items()},
                "state_machine": {
                    "PENDING -> QUEUED": "任务被提交到队列",
                    "QUEUED -> ASSIGNED": "任务被分配给Worker",
                    "ASSIGNED -> RUNNING": "Worker开始执行",
                    "RUNNING -> SUCCESS": "执行成功",
                    "RUNNING -> FAILED": "执行失败",
                    "FAILED -> RETRYING -> QUEUED": "重试循环",
                    "FAILED -> DEAD_LETTER": "超过最大重试次数",
                    "* -> TIMEOUT": "执行超时",
                    "* -> CANCELLED": "手动取消",
                }
            },
            "WorkerNode": {
                "description": "Worker节点模型，代表分布式系统中的一个执行单元。",
                "fields": {k: str(v) for k, v in WorkerNode.__dataclass_fields__.items()},
            },
            "TaskQueue": {
                "description": "任务队列模型，支持多种队列类型。",
                "fields": {k: str(v) for k, v in TaskQueue.__dataclass_fields__.items()},
            },
            "JobResult": {
                "description": "任务执行结果模型。",
                "fields": {k: str(v) for k, v in JobResult.__dataclass_fields__.items()},
            },
            "ScheduledTask": {
                "description": "定时任务配置模型。",
                "fields": {k: str(v) for k, v in ScheduledTask.__dataclass_fields__.items()},
            }
        },
        "api_summary": {
            "total_endpoints": sum(len(methods) for methods in API_SPEC["paths"].values()),
            "categories": {
                "Task Management": ["/tasks", "/tasks/{task_id}", "/tasks/{task_id}/retry", "/tasks/batch"],
                "Queue Management": ["/queues", "/queues/{queue_name}", "/queues/{queue_name}/purge"],
                "Worker Management": ["/workers", "/workers/{worker_id}", "/workers/{worker_id}/heartbeat", "/workers/{worker_id}/drain"],
                "Monitoring": ["/metrics", "/health"],
            }
        },
        "design_decisions": [
            "采用有限状态机(FSM)管理任务生命周期，确保状态转换合法性",
            "任务支持指数退避重试策略，自动处理瞬时故障",
            "Worker支持排空模式(Draining)，实现优雅下线",
            "队列支持TTL机制，避免过期任务占用资源",
            "资源需求声明式设计，支持CPU/Memory/GPU异构调度",
            "所有模型支持序列化为字典，便于JSON传输",
            "版本号字段支持乐观锁并发控制",
        ]
    }
    return doc


# ============================================================
# 6. Main Entry Point
# ============================================================

if __name__ == "__main__":
    print("=" * 70)
    print("  Distributed Task Queue - Core Data Model Design")
    print("  Node: design_core_data_model")
    print("=" * 70)
    
    # 1. 演示模型创建
    print("\n[1] Creating sample data models...")
    
    sample_task = TaskDefinition(
        task_id="task-001",
        task_name="process-image-batch",
        task_type=JobType.BATCH.value,
        queue_name="image-processing",
        payload={"image_urls": ["http://example.com/img1.jpg"], "resize": "1024x768"},
        priority=TaskPriority.HIGH.name,
        tags=["image", "batch-processing"],
    )
    print(f"  ✅ TaskDefinition: {sample_task.task_id} - {sample_task.task_name}")
    
    sample_worker = WorkerNode(
        worker_id="worker-gpu-01",
        worker_name="GPU Worker Node 1",
        host="10.0.1.101",
        port=9090,
        capabilities=["gpu", "image-processing", "ml-inference"],
        max_concurrent_tasks=3,
        resource_capacity={"cpu_cores": 8.0, "memory_mb": 32768, "gpu_count": 2},
        tags=["gpu", "production"],
    )
    print(f"  ✅ WorkerNode: {sample_worker.worker_id} - {sample_worker.worker_name}")
    
    sample_queue = TaskQueue(
        queue_id="q-001",
        queue_name="image-processing",
        queue_type=QueueType.PRIORITY.value,
        max_size=50000,
        priority_enabled=True,
        ttl_seconds=172800,
        consumer_groups=["gpu-workers", "cpu-workers"],
    )
    print(f"  ✅ TaskQueue: {sample_queue.queue_id} - {sample_queue.queue_name}")
    
    sample_result = JobResult(
        task_id="task-001",
        worker_id="worker-gpu-01",
        status=TaskStatus.SUCCESS.value,
        result_data={"output_url": "http://storage.example.com/output/001.jpg", "dimensions": "1024x768"},
        duration_ms=2350,
        metrics={"cpu_usage": 65.2, "memory_peak_mb": 4096, "gpu_utilization": 89.5},
    )
    print(f"  ✅ JobResult: task={sample_result.task_id}, status={sample_result.status}, duration={sample_result.duration_ms}ms")
    
    # 2. 演示状态机
    print("\n[2] Task State Machine Demo...")
    task_demo = TaskDefinition(
        task_id="task-demo",
        task_name="state-machine-test",
        task_type=JobType.ONE_SHOT.value,
        queue_name="default",
        payload={"data": "test"},
    )
    print(f"  Initial state: {task_demo.status}")
    
    for new_status in ["QUEUED", "ASSIGNED", "RUNNING", "SUCCESS"]:
        task_demo.transition_to(new_status)
        print(f"  Transition -> {new_status} ✅ (updated_at: {task_demo.updated_at})")
    
    # 3. 演示验证器
    print("\n[3] Model Validation Demo...")
    invalid_task = {"task_name": "test"}  # Missing required fields
    errors = ModelValidator.validate_task(invalid_task)
    print(f"  Validation errors for invalid task: {errors}")
    
    valid_task = sample_task.to_dict()
    errors = ModelValidator.validate_task(valid_task)
    print(f"  Validation errors for valid task: {errors if errors else 'None ✅'}")
    
    # 4. Worker可用性检测
    print("\n[4] Worker Availability Demo...")
    print(f"  Worker {sample_worker.worker_id}:")
    print(f"    - is_available: {sample_worker.is_available}")
    print(f"    - load_factor: {sample_worker.load_factor}")
    print(f"    - status: {sample_worker.status}")
    
    # 5. 队列利用率
    print("\n[5] Queue Utilization Demo...")
    sample_queue.current_size = 12500
    print(f"  Queue '{sample_queue.queue_name}':")
    print(f"    - utilization: {sample_queue.utilization:.1%}")
    print(f"    - is_full: {sample_queue.is_full}")
    
    # 6. 导出完整文档
    print("\n[6] Exporting Data Model Documentation...")
    os.makedirs("outputs", exist_ok=True)
    
    # 保存API规范
    api_path = "outputs/api_specification.json"
    with open(api_path, "w", encoding="utf-8") as f:
        json.dump(API_SPEC, f, indent=2, ensure_ascii=False)
    print(f"  ✅ API Spec saved to: {api_path}")
    
    # 保存数据模型文档
    doc = generate_model_documentation()
    doc_path = "outputs/data_model_documentation.json"
    with open(doc_path, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2, ensure_ascii=False)
    print(f"  ✅ Model Documentation saved to: {doc_path}")
    
    # 保存样本数据
    samples = {
        "sample_task": sample_task.to_dict(),
        "sample_worker": sample_worker.to_dict(),
        "sample_queue": sample_queue.to_dict(),
        "sample_result": sample_result.to_dict(),
    }
    samples_path = "outputs/sample_models.json"
    with open(samples_path, "w", encoding="utf-8") as f:
        json.dump(samples, f, indent=2, ensure_ascii=False)
    print(f"  ✅ Sample Models saved to: {samples_path}")
    
    # 7. 输出统计
    print("\n" + "=" * 70)
    print("  DESIGN SUMMARY")
    print("=" * 70)
    print(f"  📦 Data Models:     {len(doc['models'])} (TaskDefinition, WorkerNode, TaskQueue, JobResult, ScheduledTask)")
    print(f"  🔧 Enums:           {len(doc['enums'])} (TaskStatus, TaskPriority, WorkerStatus, QueueType, JobType)")
    print(f"  🌐 API Endpoints:   {doc['api_summary']['total_endpoints']}")
    print(f"  📋 API Categories:  {len(doc['api_summary']['categories'])}")
    print(f"  💡 Design Decisions: {len(doc['design_decisions'])}")
    print("=" * 70)
    print("\nNODE_VERIFIED_AND_READY")
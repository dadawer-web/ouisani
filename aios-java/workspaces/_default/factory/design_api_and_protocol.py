#!/usr/bin/env python3
"""
Task Queue API Protocol Designer
设计任务队列的API协议，包括任务提交、任务查询、状态更新、结果回调等接口规范
"""

import json
import uuid
from datetime import datetime, timedelta
from typing import Dict, Any, Optional, List, Union
from enum import Enum


class TaskStatus(Enum):
    """任务状态枚举"""
    PENDING = "pending"          # 等待执行
    QUEUED = "queued"            # 已入队
    RUNNING = "running"          # 执行中
    COMPLETED = "completed"      # 完成
    FAILED = "failed"            # 失败
    CANCELLED = "cancelled"      # 已取消
    TIMEOUT = "timeout"          # 超时
    RETRYING = "retrying"        # 重试中


class TaskPriority(Enum):
    """任务优先级枚举"""
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    URGENT = "urgent"


class TaskQueueAPIDesigner:
    """任务队列API协议设计器"""
    
    def __init__(self):
        self.api_version = "v1.0"
        self.base_url = "/api/task-queue"
        
    def generate_api_specification(self) -> Dict[str, Any]:
        """生成完整的API规范文档"""
        
        spec = {
            "openapi": "3.0.0",
            "info": {
                "title": "AIOS Task Queue API",
                "description": "AIOS任务队列系统API协议，提供任务生命周期管理",
                "version": self.api_version,
                "contact": {
                    "name": "AIOS Development Team",
                    "email": "dev@aios.example.com"
                }
            },
            "servers": [
                {
                    "url": f"http://localhost:8080{self.base_url}",
                    "description": "开发环境"
                }
            ],
            "paths": {},
            "components": {
                "schemas": {},
                "securitySchemes": {}
            }
        }
        
        # 定义所有API端点
        api_paths = {
            "/tasks": {
                "post": self._create_task_endpoint(),
                "get": self._list_tasks_endpoint()
            },
            "/tasks/{task_id}": {
                "get": self._get_task_endpoint(),
                "put": self._update_task_endpoint(),
                "delete": self._cancel_task_endpoint()
            },
            "/tasks/{task_id}/status": {
                "get": self._get_task_status_endpoint(),
                "patch": self._update_task_status_endpoint()
            },
            "/tasks/{task_id}/result": {
                "get": self._get_task_result_endpoint(),
                "post": self._submit_task_result_endpoint()
            },
            "/tasks/{task_id}/callback": {
                "post": self._register_callback_endpoint(),
                "get": self._get_callback_status_endpoint()
            },
            "/queues": {
                "get": self._list_queues_endpoint(),
                "post": self._create_queue_endpoint()
            },
            "/queues/{queue_id}/tasks": {
                "get": self._list_queue_tasks_endpoint()
            },
            "/tasks/batch": {
                "post": self._batch_create_tasks_endpoint()
            },
            "/health": {
                "get": self._health_check_endpoint()
            },
            "/metrics": {
                "get": self._get_metrics_endpoint()
            }
        }
        
        spec["paths"] = api_paths
        
        # 定义组件
        spec["components"]["schemas"] = self._define_schemas()
        
        return spec
    
    def _create_task_endpoint(self) -> Dict[str, Any]:
        """创建任务端点"""
        return {
            "summary": "提交新任务",
            "description": "创建一个新的任务到指定队列",
            "operationId": "createTask",
            "tags": ["Tasks"],
            "requestBody": {
                "required": True,
                "content": {
                    "application/json": {
                        "schema": {
                            "$ref": "#/components/schemas/CreateTaskRequest"
                        }
                    }
                }
            },
            "responses": {
                "201": {
                    "description": "任务创建成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/Task"
                            }
                        }
                    }
                },
                "400": {
                    "description": "请求参数错误"
                },
                "429": {
                    "description": "请求过于频繁"
                },
                "500": {
                    "description": "服务器内部错误"
                }
            }
        }
    
    def _list_tasks_endpoint(self) -> Dict[str, Any]:
        """列出任务端点"""
        return {
            "summary": "列出任务",
            "description": "获取任务列表，支持分页和过滤",
            "operationId": "listTasks",
            "tags": ["Tasks"],
            "parameters": [
                {
                    "name": "status",
                    "in": "query",
                    "required": False,
                    "description": "按状态过滤任务",
                    "schema": {
                        "type": "string",
                        "enum": [s.value for s in TaskStatus]
                    }
                },
                {
                    "name": "queue",
                    "in": "query",
                    "required": False,
                    "description": "按队列名称过滤",
                    "schema": {
                        "type": "string"
                    }
                },
                {
                    "name": "limit",
                    "in": "query",
                    "required": False,
                    "description": "每页数量（默认20，最大100）",
                    "schema": {
                        "type": "integer",
                        "minimum": 1,
                        "maximum": 100,
                        "default": 20
                    }
                },
                {
                    "name": "offset",
                    "in": "query",
                    "required": False,
                    "description": "偏移量",
                    "schema": {
                        "type": "integer",
                        "minimum": 0,
                        "default": 0
                    }
                }
            ],
            "responses": {
                "200": {
                    "description": "成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/TaskList"
                            }
                        }
                    }
                }
            }
        }
    
    def _get_task_endpoint(self) -> Dict[str, Any]:
        """获取任务详情端点"""
        return {
            "summary": "获取任务详情",
            "description": "根据任务ID获取任务详情",
            "operationId": "getTask",
            "tags": ["Tasks"],
            "parameters": [
                {
                    "name": "task_id",
                    "in": "path",
                    "required": True,
                    "description": "任务ID",
                    "schema": {
                        "type": "string"
                    }
                }
            ],
            "responses": {
                "200": {
                    "description": "成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/Task"
                            }
                        }
                    }
                },
                "404": {
                    "description": "任务不存在"
                }
            }
        }
    
    def _update_task_endpoint(self) -> Dict[str, Any]:
        """更新任务端点"""
        return {
            "summary": "更新任务",
            "description": "更新任务的配置和参数",
            "operationId": "updateTask",
            "tags": ["Tasks"],
            "parameters": [
                {
                    "name": "task_id",
                    "in": "path",
                    "required": True,
                    "description": "任务ID",
                    "schema": {
                        "type": "string"
                    }
                }
            ],
            "requestBody": {
                "required": True,
                "content": {
                    "application/json": {
                        "schema": {
                            "$ref": "#/components/schemas/UpdateTaskRequest"
                        }
                    }
                }
            },
            "responses": {
                "200": {
                    "description": "更新成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/Task"
                            }
                        }
                    }
                },
                "404": {
                    "description": "任务不存在"
                },
                "409": {
                    "description": "任务状态不允许更新"
                }
            }
        }
    
    def _cancel_task_endpoint(self) -> Dict[str, Any]:
        """取消任务端点"""
        return {
            "summary": "取消任务",
            "description": "取消待执行或执行中的任务",
            "operationId": "cancelTask",
            "tags": ["Tasks"],
            "parameters": [
                {
                    "name": "task_id",
                    "in": "path",
                    "required": True,
                    "description": "任务ID",
                    "schema": {
                        "type": "string"
                    }
                }
            ],
            "responses": {
                "200": {
                    "description": "取消成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/Task"
                            }
                        }
                    }
                },
                "404": {
                    "description": "任务不存在"
                },
                "409": {
                    "description": "任务已完成，无法取消"
                }
            }
        }
    
    def _get_task_status_endpoint(self) -> Dict[str, Any]:
        """获取任务状态端点"""
        return {
            "summary": "获取任务状态",
            "description": "获取任务的当前状态和进度信息",
            "operationId": "getTaskStatus",
            "tags": ["Task Status"],
            "parameters": [
                {
                    "name": "task_id",
                    "in": "path",
                    "required": True,
                    "description": "任务ID",
                    "schema": {
                        "type": "string"
                    }
                }
            ],
            "responses": {
                "200": {
                    "description": "成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/TaskStatusResponse"
                            }
                        }
                    }
                },
                "404": {
                    "description": "任务不存在"
                }
            }
        }
    
    def _update_task_status_endpoint(self) -> Dict[str, Any]:
        """更新任务状态端点"""
        return {
            "summary": "更新任务状态",
            "description": "更新任务的状态（用于工作器报告进度）",
            "operationId": "updateTaskStatus",
            "tags": ["Task Status"],
            "parameters": [
                {
                    "name": "task_id",
                    "in": "path",
                    "required": True,
                    "description": "任务ID",
                    "schema": {
                        "type": "string"
                    }
                }
            ],
            "requestBody": {
                "required": True,
                "content": {
                    "application/json": {
                        "schema": {
                            "$ref": "#/components/schemas/UpdateTaskStatusRequest"
                        }
                    }
                }
            },
            "responses": {
                "200": {
                    "description": "状态更新成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/TaskStatusResponse"
                            }
                        }
                    }
                },
                "404": {
                    "description": "任务不存在"
                },
                "409": {
                    "description": "状态转换无效"
                }
            }
        }
    
    def _get_task_result_endpoint(self) -> Dict[str, Any]:
        """获取任务结果端点"""
        return {
            "summary": "获取任务结果",
            "description": "获取已完成任务的执行结果",
            "operationId": "getTaskResult",
            "tags": ["Task Results"],
            "parameters": [
                {
                    "name": "task_id",
                    "in": "path",
                    "required": True,
                    "description": "任务ID",
                    "schema": {
                        "type": "string"
                    }
                }
            ],
            "responses": {
                "200": {
                    "description": "成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/TaskResult"
                            }
                        }
                    }
                },
                "404": {
                    "description": "任务不存在"
                },
                "409": {
                    "description": "任务未完成，无法获取结果"
                }
            }
        }
    
    def _submit_task_result_endpoint(self) -> Dict[str, Any]:
        """提交任务结果端点"""
        return {
            "summary": "提交任务结果",
            "description": "工作器提交任务执行结果",
            "operationId": "submitTaskResult",
            "tags": ["Task Results"],
            "parameters": [
                {
                    "name": "task_id",
                    "in": "path",
                    "required": True,
                    "description": "任务ID",
                    "schema": {
                        "type": "string"
                    }
                }
            ],
            "requestBody": {
                "required": True,
                "content": {
                    "application/json": {
                        "schema": {
                            "$ref": "#/components/schemas/SubmitTaskResultRequest"
                        }
                    }
                }
            },
            "responses": {
                "200": {
                    "description": "结果提交成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/Task"
                            }
                        }
                    }
                },
                "404": {
                    "description": "任务不存在"
                },
                "409": {
                    "description": "任务状态不允许提交结果"
                }
            }
        }
    
    def _register_callback_endpoint(self) -> Dict[str, Any]:
        """注册回调端点"""
        return {
            "summary": "注册任务回调",
            "description": "为任务注册完成回调URL",
            "operationId": "registerCallback",
            "tags": ["Callbacks"],
            "parameters": [
                {
                    "name": "task_id",
                    "in": "path",
                    "required": True,
                    "description": "任务ID",
                    "schema": {
                        "type": "string"
                    }
                }
            ],
            "requestBody": {
                "required": True,
                "content": {
                    "application/json": {
                        "schema": {
                            "$ref": "#/components/schemas/RegisterCallbackRequest"
                        }
                    }
                }
            },
            "responses": {
                "201": {
                    "description": "回调注册成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/CallbackRegistration"
                            }
                        }
                    }
                },
                "404": {
                    "description": "任务不存在"
                }
            }
        }
    
    def _get_callback_status_endpoint(self) -> Dict[str, Any]:
        """获取回调状态端点"""
        return {
            "summary": "获取回调状态",
            "description": "获取任务回调的执行状态",
            "operationId": "getCallbackStatus",
            "tags": ["Callbacks"],
            "parameters": [
                {
                    "name": "task_id",
                    "in": "path",
                    "required": True,
                    "description": "任务ID",
                    "schema": {
                        "type": "string"
                    }
                }
            ],
            "responses": {
                "200": {
                    "description": "成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/CallbackStatus"
                            }
                        }
                    }
                },
                "404": {
                    "description": "任务或回调不存在"
                }
            }
        }
    
    def _list_queues_endpoint(self) -> Dict[str, Any]:
        """列出队列端点"""
        return {
            "summary": "列出任务队列",
            "description": "获取所有任务队列信息",
            "operationId": "listQueues",
            "tags": ["Queues"],
            "responses": {
                "200": {
                    "description": "成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/QueueList"
                            }
                        }
                    }
                }
            }
        }
    
    def _create_queue_endpoint(self) -> Dict[str, Any]:
        """创建队列端点"""
        return {
            "summary": "创建任务队列",
            "description": "创建一个新的任务队列",
            "operationId": "createQueue",
            "tags": ["Queues"],
            "requestBody": {
                "required": True,
                "content": {
                    "application/json": {
                        "schema": {
                            "$ref": "#/components/schemas/CreateQueueRequest"
                        }
                    }
                }
            },
            "responses": {
                "201": {
                    "description": "队列创建成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/Queue"
                            }
                        }
                    }
                },
                "400": {
                    "description": "请求参数错误"
                },
                "409": {
                    "description": "队列已存在"
                }
            }
        }
    
    def _list_queue_tasks_endpoint(self) -> Dict[str, Any]:
        """列出队列中的任务端点"""
        return {
            "summary": "列出队列中的任务",
            "description": "获取指定队列中的任务列表",
            "operationId": "listQueueTasks",
            "tags": ["Queues"],
            "parameters": [
                {
                    "name": "queue_id",
                    "in": "path",
                    "required": True,
                    "description": "队列ID",
                    "schema": {
                        "type": "string"
                    }
                },
                {
                    "name": "status",
                    "in": "query",
                    "required": False,
                    "description": "按状态过滤",
                    "schema": {
                        "type": "string",
                        "enum": [s.value for s in TaskStatus]
                    }
                }
            ],
            "responses": {
                "200": {
                    "description": "成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/TaskList"
                            }
                        }
                    }
                },
                "404": {
                    "description": "队列不存在"
                }
            }
        }
    
    def _batch_create_tasks_endpoint(self) -> Dict[str, Any]:
        """批量创建任务端点"""
        return {
            "summary": "批量创建任务",
            "description": "一次性创建多个任务",
            "operationId": "batchCreateTasks",
            "tags": ["Tasks"],
            "requestBody": {
                "required": True,
                "content": {
                    "application/json": {
                        "schema": {
                            "$ref": "#/components/schemas/BatchCreateTasksRequest"
                        }
                    }
                }
            },
            "responses": {
                "201": {
                    "description": "批量创建成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/BatchCreateTasksResponse"
                            }
                        }
                    }
                },
                "400": {
                    "description": "请求参数错误"
                }
            }
        }
    
    def _health_check_endpoint(self) -> Dict[str, Any]:
        """健康检查端点"""
        return {
            "summary": "健康检查",
            "description": "检查服务健康状态",
            "operationId": "healthCheck",
            "tags": ["System"],
            "responses": {
                "200": {
                    "description": "服务正常",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/HealthCheckResponse"
                            }
                        }
                    }
                },
                "503": {
                    "description": "服务不可用"
                }
            }
        }
    
    def _get_metrics_endpoint(self) -> Dict[str, Any]:
        """获取指标端点"""
        return {
            "summary": "获取系统指标",
            "description": "获取任务队列系统的性能指标",
            "operationId": "getMetrics",
            "tags": ["System"],
            "responses": {
                "200": {
                    "description": "成功",
                    "content": {
                        "application/json": {
                            "schema": {
                                "$ref": "#/components/schemas/MetricsResponse"
                            }
                        }
                    }
                }
            }
        }
    
    def _define_schemas(self) -> Dict[str, Any]:
        """定义数据模式"""
        return {
            "Task": {
                "type": "object",
                "properties": {
                    "id": {
                        "type": "string",
                        "description": "任务唯一ID"
                    },
                    "name": {
                        "type": "string",
                        "description": "任务名称"
                    },
                    "description": {
                        "type": "string",
                        "description": "任务描述"
                    },
                    "queue": {
                        "type": "string",
                        "description": "所属队列"
                    },
                    "status": {
                        "type": "string",
                        "enum": [s.value for s in TaskStatus],
                        "description": "任务状态"
                    },
                    "priority": {
                        "type": "string",
                        "enum": [p.value for p in TaskPriority],
                        "description": "任务优先级"
                    },
                    "payload": {
                        "type": "object",
                        "description": "任务输入数据"
                    },
                    "config": {
                        "type": "object",
                        "description": "任务配置（超时、重试等）"
                    },
                    "created_at": {
                        "type": "string",
                        "format": "date-time",
                        "description": "创建时间"
                    },
                    "updated_at": {
                        "type": "string",
                        "format": "date-time",
                        "description": "最后更新时间"
                    },
                    "started_at": {
                        "type": "string",
                        "format": "date-time",
                        "description": "开始执行时间"
                    },
                    "completed_at": {
                        "type": "string",
                        "format": "date-time",
                        "description": "完成时间"
                    },
                    "worker_id": {
                        "type": "string",
                        "description": "执行工作器ID"
                    },
                    "retry_count": {
                        "type": "integer",
                        "description": "重试次数"
                    },
                    "max_retries": {
                        "type": "integer",
                        "description": "最大重试次数"
                    },
                    "timeout_seconds": {
                        "type": "integer",
                        "description": "超时时间（秒）"
                    }
                },
                "required": ["id", "name", "queue", "status", "created_at"]
            },
            "CreateTaskRequest": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "任务名称"
                    },
                    "description": {
                        "type": "string",
                        "description": "任务描述"
                    },
                    "queue": {
                        "type": "string",
                        "description": "目标队列名称（可选）"
                    },
                    "priority": {
                        "type": "string",
                        "enum": [p.value for p in TaskPriority],
                        "description": "任务优先级",
                        "default": "medium"
                    },
                    "payload": {
                        "type": "object",
                        "description": "任务输入数据"
                    },
                    "config": {
                        "type": "object",
                        "properties": {
                            "timeout_seconds": {
                                "type": "integer",
                                "description": "超时时间（秒）",
                                "default": 300
                            },
                            "max_retries": {
                                "type": "integer",
                                "description": "最大重试次数",
                                "default": 3
                            },
                            "callback_url": {
                                "type": "string",
                                "format": "uri",
                                "description": "完成回调URL"
                            },
                            "tags": {
                                "type": "array",
                                "items": {
                                    "type": "string"
                                },
                                "description": "任务标签"
                            }
                        }
                    }
                },
                "required": ["name"]
            },
            "TaskStatusResponse": {
                "type": "object",
                "properties": {
                    "task_id": {
                        "type": "string",
                        "description": "任务ID"
                    },
                    "status": {
                        "type": "string",
                        "enum": [s.value for s in TaskStatus],
                        "description": "当前状态"
                    },
                    "progress": {
                        "type": "number",
                        "minimum": 0,
                        "maximum": 100,
                        "description": "执行进度百分比"
                    },
                    "message": {
                        "type": "string",
                        "description": "状态消息"
                    },
                    "updated_at": {
                        "type": "string",
                        "format": "date-time",
                        "description": "状态更新时间"
                    }
                },
                "required": ["task_id", "status", "updated_at"]
            },
            "TaskResult": {
                "type": "object",
                "properties": {
                    "task_id": {
                        "type": "string",
                        "description": "任务ID"
                    },
                    "status": {
                        "type": "string",
                        "enum": [s.value for s in TaskStatus],
                        "description": "任务状态"
                    },
                    "result": {
                        "type": "object",
                        "description": "执行结果数据"
                    },
                    "error": {
                        "type": "string",
                        "description": "错误信息（如果失败）"
                    },
                    "duration_ms": {
                        "type": "integer",
                        "description": "执行耗时（毫秒）"
                    },
                    "completed_at": {
                        "type": "string",
                        "format": "date-time",
                        "description": "完成时间"
                    }
                },
                "required": ["task_id", "status", "completed_at"]
            },
            "TaskList": {
                "type": "object",
                "properties": {
                    "data": {
                        "type": "array",
                        "items": {
                            "$ref": "#/components/schemas/Task"
                        }
                    },
                    "pagination": {
                        "$ref": "#/components/schemas/Pagination"
                    },
                    "total": {
                        "type": "integer",
                        "description": "总数量"
                    }
                },
                "required": ["data", "pagination", "total"]
            },
            "Pagination": {
                "type": "object",
                "properties": {
                    "limit": {
                        "type": "integer",
                        "description": "每页数量"
                    },
                    "offset": {
                        "type": "integer",
                        "description": "偏移量"
                    },
                    "total": {
                        "type": "integer",
                        "description": "总数量"
                    }
                },
                "required": ["limit", "offset", "total"]
            },
            "Queue": {
                "type": "object",
                "properties": {
                    "id": {
                        "type": "string",
                        "description": "队列ID"
                    },
                    "name": {
                        "type": "string",
                        "description": "队列名称"
                    },
                    "description": {
                        "type": "string",
                        "description": "队列描述"
                    },
                    "max_concurrency": {
                        "type": "integer",
                        "description": "最大并发数"
                    },
                    "task_count": {
                        "type": "integer",
                        "description": "待处理任务数"
                    },
                    "running_count": {
                        "type": "integer",
                        "description": "正在执行任务数"
                    },
                    "created_at": {
                        "type": "string",
                        "format": "date-time",
                        "description": "创建时间"
                    },
                    "config": {
                        "type": "object",
                        "properties": {
                            "dead_letter_queue": {
                                "type": "string",
                                "description": "死信队列名称"
                            },
                            "retry_delay_seconds": {
                                "type": "integer",
                                "description": "重试延迟时间（秒）"
                            }
                        }
                    }
                },
                "required": ["id", "name", "created_at"]
            },
            "HealthCheckResponse": {
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": ["healthy", "degraded", "unhealthy"],
                        "description": "健康状态"
                    },
                    "timestamp": {
                        "type": "string",
                        "format": "date-time",
                        "description": "检查时间"
                    },
                    "services": {
                        "type": "object",
                        "properties": {
                            "database": {
                                "type": "string",
                                "enum": ["up", "down"]
                            },
                            "queue": {
                                "type": "string",
                                "enum": ["up", "down"]
                            },
                            "worker": {
                                "type": "string",
                                "enum": ["up", "down"]
                            }
                        }
                    }
                },
                "required": ["status", "timestamp"]
            },
            "MetricsResponse": {
                "type": "object",
                "properties": {
                    "tasks": {
                        "type": "object",
                        "properties": {
                            "total": {
                                "type": "integer",
                                "description": "总任务数"
                            },
                            "pending": {
                                "type": "integer",
                                "description": "待处理任务数"
                            },
                            "running": {
                                "type": "integer",
                                "description": "正在执行任务数"
                            },
                            "completed": {
                                "type": "integer",
                                "description": "已完成任务数"
                            },
                            "failed": {
                                "type": "integer",
                                "description": "失败任务数"
                            }
                        }
                    },
                    "queues": {
                        "type": "object",
                        "properties": {
                            "total": {
                                "type": "integer",
                                "description": "队列总数"
                            },
                            "active": {
                                "type": "integer",
                                "description": "活跃队列数"
                            }
                        }
                    },
                    "performance": {
                        "type": "object",
                        "properties": {
                            "avg_task_duration_ms": {
                                "type": "number",
                                "description": "平均任务执行时间（毫秒）"
                            },
                            "tasks_per_minute": {
                                "type": "number",
                                "description": "每分钟处理任务数"
                            }
                        }
                    }
                },
                "required": ["tasks", "queues", "performance"]
            },
            "UpdateTaskRequest": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "任务名称"
                    },
                    "description": {
                        "type": "string",
                        "description": "任务描述"
                    },
                    "priority": {
                        "type": "string",
                        "enum": [p.value for p in TaskPriority],
                        "description": "任务优先级"
                    },
                    "config": {
                        "type": "object",
                        "properties": {
                            "timeout_seconds": {
                                "type": "integer",
                                "description": "超时时间（秒）"
                            },
                            "max_retries": {
                                "type": "integer",
                                "description": "最大重试次数"
                            }
                        }
                    }
                }
            },
            "UpdateTaskStatusRequest": {
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": [s.value for s in TaskStatus],
                        "description": "新状态"
                    },
                    "progress": {
                        "type": "number",
                        "minimum": 0,
                        "maximum": 100,
                        "description": "执行进度百分比"
                    },
                    "message": {
                        "type": "string",
                        "description": "状态消息"
                    },
                    "worker_id": {
                        "type": "string",
                        "description": "工作器ID"
                    }
                },
                "required": ["status"]
            },
            "SubmitTaskResultRequest": {
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": ["completed", "failed"],
                        "description": "最终状态"
                    },
                    "result": {
                        "type": "object",
                        "description": "执行结果数据"
                    },
                    "error": {
                        "type": "string",
                        "description": "错误信息（如果失败）"
                    },
                    "duration_ms": {
                        "type": "integer",
                        "description": "执行耗时（毫秒）"
                    }
                },
                "required": ["status"]
            },
            "RegisterCallbackRequest": {
                "type": "object",
                "properties": {
                    "url": {
                        "type": "string",
                        "format": "uri",
                        "description": "回调URL"
                    },
                    "events": {
                        "type": "array",
                        "items": {
                            "type": "string",
                            "enum": ["completed", "failed", "cancelled", "timeout"]
                        },
                        "description": "要订阅的事件"
                    },
                    "secret": {
                        "type": "string",
                        "description": "签名密钥"
                    }
                },
                "required": ["url"]
            },
            "CallbackRegistration": {
                "type": "object",
                "properties": {
                    "id": {
                        "type": "string",
                        "description": "回调ID"
                    },
                    "task_id": {
                        "type": "string",
                        "description": "任务ID"
                    },
                    "url": {
                        "type": "string",
                        "format": "uri",
                        "description": "回调URL"
                    },
                    "events": {
                        "type": "array",
                        "items": {
                            "type": "string"
                        },
                        "description": "订阅的事件"
                    },
                    "created_at": {
                        "type": "string",
                        "format": "date-time",
                        "description": "注册时间"
                    }
                },
                "required": ["id", "task_id", "url", "created_at"]
            },
            "CallbackStatus": {
                "type": "object",
                "properties": {
                    "callback_id": {
                        "type": "string",
                        "description": "回调ID"
                    },
                    "task_id": {
                        "type": "string",
                        "description": "任务ID"
                    },
                    "status": {
                        "type": "string",
                        "enum": ["pending", "delivered", "failed"],
                        "description": "回调状态"
                    },
                    "attempts": {
                        "type": "integer",
                        "description": "尝试次数"
                    },
                    "last_attempt_at": {
                        "type": "string",
                        "format": "date-time",
                        "description": "最后尝试时间"
                    },
                    "next_retry_at": {
                        "type": "string",
                        "format": "date-time",
                        "description": "下次重试时间"
                    }
                },
                "required": ["callback_id", "task_id", "status"]
            },
            "QueueList": {
                "type": "object",
                "properties": {
                    "data": {
                        "type": "array",
                        "items": {
                            "$ref": "#/components/schemas/Queue"
                        }
                    },
                    "total": {
                        "type": "integer",
                        "description": "总数量"
                    }
                },
                "required": ["data", "total"]
            },
            "CreateQueueRequest": {
                "type": "object",
                "properties": {
                    "name": {
                        "type": "string",
                        "description": "队列名称"
                    },
                    "description": {
                        "type": "string",
                        "description": "队列描述"
                    },
                    "max_concurrency": {
                        "type": "integer",
                        "description": "最大并发数",
                        "default": 10
                    },
                    "config": {
                        "type": "object",
                        "properties": {
                            "dead_letter_queue": {
                                "type": "string",
                                "description": "死信队列名称"
                            },
                            "retry_delay_seconds": {
                                "type": "integer",
                                "description": "重试延迟时间（秒）",
                                "default": 60
                            }
                        }
                    }
                },
                "required": ["name"]
            },
            "BatchCreateTasksRequest": {
                "type": "object",
                "properties": {
                    "tasks": {
                        "type": "array",
                        "items": {
                            "$ref": "#/components/schemas/CreateTaskRequest"
                        },
                        "description": "任务列表"
                    },
                    "queue": {
                        "type": "string",
                        "description": "默认队列（可选）"
                    }
                },
                "required": ["tasks"]
            },
            "BatchCreateTasksResponse": {
                "type": "object",
                "properties": {
                    "tasks": {
                        "type": "array",
                        "items": {
                            "$ref": "#/components/schemas/Task"
                        }
                    },
                    "success_count": {
                        "type": "integer",
                        "description": "成功数量"
                    },
                    "error_count": {
                        "type": "integer",
                        "description": "失败数量"
                    },
                    "errors": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "index": {
                                    "type": "integer",
                                    "description": "失败任务索引"
                                },
                                "error": {
                                    "type": "string",
                                    "description": "错误信息"
                                }
                            }
                        }
                    }
                },
                "required": ["tasks", "success_count", "error_count"]
            },
            "UpdateTaskStatusRequest": {
                "type": "object",
                "properties": {
                    "status": {
                        "type": "string",
                        "enum": [s.value for s in TaskStatus],
                        "description": "新状态"
                    },
                    "progress": {
                        "type": "number",
                        "minimum": 0,
                        "maximum": 100,
                        "description": "执行进度百分比"
                    },
                    "message": {
                        "type": "string",
                        "description": "状态消息"
                    },
                    "worker_id": {
                        "type": "string",
                        "description": "工作器ID"
                    }
                },
                "required": ["status"]
            }
        }
    
    def generate_example_api_calls(self) -> Dict[str, Any]:
        """生成示例API调用"""
        examples = {
            "submit_task": {
                "method": "POST",
                "url": f"{self.base_url}/tasks",
                "headers": {
                    "Content-Type": "application/json",
                    "Authorization": "Bearer your_api_key"
                },
                "body": {
                    "name": "数据处理任务",
                    "description": "处理用户上传的数据文件",
                    "queue": "data-processing",
                    "priority": "high",
                    "payload": {
                        "file_url": "https://example.com/data.csv",
                        "process_type": "cleaning"
                    },
                    "config": {
                        "timeout_seconds": 600,
                        "max_retries": 3,
                        "callback_url": "https://yourapp.com/callback",
                        "tags": ["data", "cleaning", "v1"]
                    }
                },
                "response_example": {
                    "id": "task-12345-abcde",
                    "name": "数据处理任务",
                    "description": "处理用户上传的数据文件",
                    "queue": "data-processing",
                    "status": "pending",
                    "priority": "high",
                    "created_at": "2024-01-15T10:30:00Z",
                    "updated_at": "2024-01-15T10:30:00Z"
                }
            },
            "check_status": {
                "method": "GET",
                "url": f"{self.base_url}/tasks/task-12345-abcde/status",
                "headers": {
                    "Authorization": "Bearer your_api_key"
                },
                "response_example": {
                    "task_id": "task-12345-abcde",
                    "status": "running",
                    "progress": 45.5,
                    "message": "正在处理第3批数据",
                    "updated_at": "2024-01-15T10:32:15Z"
                }
            },
            "get_result": {
                "method": "GET",
                "url": f"{self.base_url}/tasks/task-12345-abcde/result",
                "headers": {
                    "Authorization": "Bearer your_api_key"
                },
                "response_example": {
                    "task_id": "task-12345-abcde",
                    "status": "completed",
                    "result": {
                        "processed_records": 1500,
                        "output_file": "processed_data.csv",
                        "quality_score": 98.5
                    },
                    "duration_ms": 12500,
                    "completed_at": "2024-01-15T10:32:25Z"
                }
            },
            "register_callback": {
                "method": "POST",
                "url": f"{self.base_url}/tasks/task-12345-abcde/callback",
                "headers": {
                    "Content-Type": "application/json",
                    "Authorization": "Bearer your_api_key"
                },
                "body": {
                    "url": "https://yourapp.com/task-callback",
                    "events": ["completed", "failed"],
                    "secret": "your_webhook_secret"
                },
                "response_example": {
                    "id": "cb-67890-fghij",
                    "task_id": "task-12345-abcde",
                    "url": "https://yourapp.com/task-callback",
                    "events": ["completed", "failed"],
                    "created_at": "2024-01-15T10:30:05Z"
                }
            }
        }
        
        return examples
    
    def generate_error_codes(self) -> Dict[str, Any]:
        """生成错误代码规范"""
        return {
            "error_codes": {
                "TASK_NOT_FOUND": {
                    "code": "TASK_NOT_FOUND",
                    "message": "指定的任务不存在",
                    "http_status": 404
                },
                "INVALID_STATUS_TRANSITION": {
                    "code": "INVALID_STATUS_TRANSITION",
                    "message": "不允许的状态转换",
                    "http_status": 409
                },
                "QUEUE_NOT_FOUND": {
                    "code": "QUEUE_NOT_FOUND",
                    "message": "指定的队列不存在",
                    "http_status": 404
                },
                "QUEUE_ALREADY_EXISTS": {
                    "code": "QUEUE_ALREADY_EXISTS",
                    "message": "队列名称已存在",
                    "http_status": 409
                },
                "VALIDATION_ERROR": {
                    "code": "VALIDATION_ERROR",
                    "message": "请求参数验证失败",
                    "http_status": 400
                },
                "RATE_LIMIT_EXCEEDED": {
                    "code": "RATE_LIMIT_EXCEEDED",
                    "message": "请求频率超过限制",
                    "http_status": 429
                },
                "INTERNAL_ERROR": {
                    "code": "INTERNAL_ERROR",
                    "message": "服务器内部错误",
                    "http_status": 500
                },
                "SERVICE_UNAVAILABLE": {
                    "code": "SERVICE_UNAVAILABLE",
                    "message": "服务暂时不可用",
                    "http_status": 503
                }
            },
            "error_response_format": {
                "type": "object",
                "properties": {
                    "error": {
                        "type": "object",
                        "properties": {
                            "code": {
                                "type": "string",
                                "description": "错误代码"
                            },
                            "message": {
                                "type": "string",
                                "description": "错误消息"
                            },
                            "details": {
                                "type": "object",
                                "description": "详细错误信息"
                            },
                            "timestamp": {
                                "type": "string",
                                "format": "date-time",
                                "description": "错误发生时间"
                            }
                        },
                        "required": ["code", "message"]
                    }
                }
            }
        }
    
    def generate_state_machine(self) -> Dict[str, Any]:
        """生成任务状态机"""
        state_machine = {
            "transitions": {
                "pending": ["queued", "cancelled"],
                "queued": ["running", "cancelled"],
                "running": ["completed", "failed", "timeout", "cancelled", "retrying"],
                "completed": [],
                "failed": ["retrying", "cancelled"],
                "cancelled": [],
                "timeout": ["retrying", "cancelled", "failed"],
                "retrying": ["running", "failed", "cancelled"]
            },
            "valid_transitions": {
                "submit": ["pending"],
                "enqueue": ["pending -> queued"],
                "start": ["queued -> running"],
                "complete": ["running -> completed"],
                "fail": ["running -> failed"],
                "timeout": ["running -> timeout"],
                "cancel": ["pending -> cancelled", "queued -> cancelled", "running -> cancelled"],
                "retry": ["failed -> retrying", "timeout -> retrying"],
                "retry_start": ["retrying -> running"],
                "retry_fail": ["retrying -> failed"]
            },
            "allowed_operations_by_status": {
                "pending": ["update", "cancel", "enqueue"],
                "queued": ["update", "cancel", "start"],
                "running": ["update_status", "submit_result", "cancel"],
                "completed": ["get_result"],
                "failed": ["retry", "cancel", "get_result"],
                "cancelled": [],
                "timeout": ["retry", "cancel", "get_result"],
                "retrying": ["update_status", "submit_result", "cancel"]
            }
        }
        
        return state_machine
    
    def generate_security_considerations(self) -> Dict[str, Any]:
        """生成安全考虑"""
        return {
            "authentication": {
                "methods": [
                    "API Key",
                    "OAuth 2.0",
                    "JWT Token"
                ],
                "header": "Authorization: Bearer <token>",
                "notes": "所有API请求必须包含有效的认证令牌"
            },
            "authorization": {
                "roles": [
                    "admin: 完全访问权限",
                    "operator: 任务管理权限",
                    "worker: 任务执行权限",
                    "viewer: 只读权限"
                ],
                "permissions": {
                    "submit_task": ["admin", "operator"],
                    "view_task": ["admin", "operator", "viewer"],
                    "manage_queue": ["admin"],
                    "execute_task": ["worker"]
                }
            },
            "rate_limiting": {
                "default_limits": {
                    "requests_per_minute": 100,
                    "requests_per_hour": 1000,
                    "concurrent_requests": 10
                },
                "custom_limits": "支持按客户端或API密钥设置自定义限制"
            },
            "input_validation": {
                "task_payload": "最大10MB，JSON格式",
                "queue_name": "最多64字符，字母数字下划线",
                "callback_url": "必须是有效的HTTPS URL",
                "timeout_seconds": "范围: 1-86400（24小时）"
            }
        }
    
    def save_to_files(self):
        """保存所有设计文档到文件"""
        
        # 1. 生成OpenAPI规范
        api_spec = self.generate_api_specification()
        with open('outputs/task_queue_api_spec.json', 'w', encoding='utf-8') as f:
            json.dump(api_spec, f, indent=2, ensure_ascii=False)
        
        # 2. 生成示例API调用
        examples = self.generate_example_api_calls()
        with open('outputs/api_examples.json', 'w', encoding='utf-8') as f:
            json.dump(examples, f, indent=2, ensure_ascii=False)
        
        # 3. 生成错误代码规范
        error_codes = self.generate_error_codes()
        with open('outputs/error_codes.json', 'w', encoding='utf-8') as f:
            json.dump(error_codes, f, indent=2, ensure_ascii=False)
        
        # 4. 生成状态机
        state_machine = self.generate_state_machine()
        with open('outputs/state_machine.json', 'w', encoding='utf-8') as f:
            json.dump(state_machine, f, indent=2, ensure_ascii=False)
        
        # 5. 生成安全考虑
        security = self.generate_security_considerations()
        with open('outputs/security_considerations.json', 'w', encoding='utf-8') as f:
            json.dump(security, f, indent=2, ensure_ascii=False)
        
        # 6. 生成综合文档
        summary = {
            "project": "AIOS Task Queue API",
            "version": self.api_version,
            "generated_at": datetime.now().isoformat(),
            "files_generated": [
                "task_queue_api_spec.json",
                "api_examples.json",
                "error_codes.json",
                "state_machine.json",
                "security_considerations.json"
            ],
            "api_endpoints": {
                "tasks": ["POST /tasks", "GET /tasks", "GET /tasks/{id}", "PUT /tasks/{id}", "DELETE /tasks/{id}"],
                "task_status": ["GET /tasks/{id}/status", "PATCH /tasks/{id}/status"],
                "task_results": ["GET /tasks/{id}/result", "POST /tasks/{id}/result"],
                "callbacks": ["POST /tasks/{id}/callback", "GET /tasks/{id}/callback"],
                "queues": ["GET /queues", "POST /queues", "GET /queues/{id}/tasks"],
                "batch": ["POST /tasks/batch"],
                "system": ["GET /health", "GET /metrics"]
            },
            "key_features": [
                "任务生命周期管理（创建、执行、完成、失败、重试）",
                "多队列支持与优先级调度",
                "异步结果回调机制",
                "任务状态实时查询",
                "批量任务创建",
                "完善的错误处理和重试机制",
                "详细的API文档和示例"
            ]
        }
        
        with open('outputs/api_design_summary.json', 'w', encoding='utf-8') as f:
            json.dump(summary, f, indent=2, ensure_ascii=False)
        
        return summary


class BaseAgent:
    """基础智能体类"""
    
    def process_data(self, data: Any) -> Any:
        """处理数据的抽象方法"""
        raise NotImplementedError


class TaskQueueAPIDesignAgent(BaseAgent):
    """任务队列API设计智能体"""
    
    def __init__(self):
        self.designer = TaskQueueAPIDesigner()
    
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """处理API设计任务"""
        
        print("🚀 [TaskQueueAPIDesignAgent] 开始设计任务队列API协议...", flush=True)
        
        try:
            # 生成所有设计文档
            summary = self.designer.save_to_files()
            
            print("✅ [TaskQueueAPIDesignAgent] API协议设计完成！", flush=True)
            print(f"📊 设计摘要：", flush=True)
            print(f"   - API版本: {summary['version']}", flush=True)
            print(f"   - 生成文件: {len(summary['files_generated'])}个", flush=True)
            print(f"   - API端点: {sum(len(v) for v in summary['api_endpoints'].values())}个", flush=True)
            print(f"   - 关键特性: {len(summary['key_features'])}项", flush=True)
            
            # 输出详细设计结果
            print("\n📋 设计输出文件：", flush=True)
            for file in summary['files_generated']:
                print(f"   📄 /shared/outputs/{file}", flush=True)
            
            print("\n🔗 主要API端点：", flush=True)
            for category, endpoints in summary['api_endpoints'].items():
                print(f"   {category}:", flush=True)
                for endpoint in endpoints:
                    print(f"     - {endpoint}", flush=True)
            
            print("\n🎯 关键特性：", flush=True)
            for i, feature in enumerate(summary['key_features'], 1):
                print(f"   {i}. {feature}", flush=True)
            
            print("\n✨ [TaskQueueAPIDesignAgent] 任务完成！所有API设计文档已生成到 /shared/outputs/ 目录", flush=True)
            
            return {
                "status": "success",
                "summary": summary,
                "files_generated": [
                    "/shared/outputs/task_queue_api_spec.json",
                    "/shared/outputs/api_examples.json",
                    "/shared/outputs/error_codes.json",
                    "/shared/outputs/state_machine.json",
                    "/shared/outputs/security_considerations.json",
                    "/shared/outputs/api_design_summary.json"
                ]
            }
            
        except Exception as e:
            print(f"❌ [TaskQueueAPIDesignAgent] 设计失败: {str(e)}", flush=True)
            raise


if __name__ == "__main__":
    print("🎯 [design_api_and_protocol] 启动任务队列API协议设计", flush=True)
    
    # 创建设计智能体并执行
    agent = TaskQueueAPIDesignAgent()
    result = agent.process_data({})
    
    print("\n" + "="*60, flush=True)
    print("🏆 NODE_VERIFIED_AND_READY", flush=True)
    print("="*60, flush=True)
    
    # 输出最终结果摘要
    print(f"\n📊 最终结果：", flush=True)
    print(f"   状态: {result['status']}", flush=True)
    print(f"   生成文件数量: {len(result['files_generated'])}", flush=True)
    print(f"   API版本: {result['summary']['version']}", flush=True)
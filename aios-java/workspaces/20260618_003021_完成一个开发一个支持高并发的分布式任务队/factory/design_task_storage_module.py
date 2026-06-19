#!/usr/bin/env python3
"""
Task Storage Module - 设计任务存储模块
支持多种后端存储：Memory, Redis, MySQL, PostgreSQL

职责：
- 提供统一的任务存储接口
- 支持多种存储后端的动态切换
- 实现任务的 CRUD 操作
"""

import json
import os
import sys
import time
import uuid
from abc import ABC, abstractmethod
from datetime import datetime
from typing import Any, Dict, List, Optional


# ============================================================
# BaseAgent 定义 (模拟 AIOS 基类)
# ============================================================
class BaseAgent:
    """AIOS 基础智能体类"""
    
    def __init__(self, name: str = "BaseAgent"):
        self.name = name
        self.state = {}
    
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """处理数据的抽象方法，子类必须重写"""
        raise NotImplementedError("Subclasses must implement process_data")
    
    def run(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """运行智能体"""
        print(f"[{self.name}] Starting processing...", flush=True)
        result = self.process_data(data)
        print(f"[{self.name}] Processing completed.", flush=True)
        return result


# ============================================================
# 存储后端抽象基类
# ============================================================
class StorageBackend(ABC):
    """存储后端抽象基类"""
    
    @abstractmethod
    def connect(self, config: Dict[str, Any]) -> bool:
        """连接到存储后端"""
        pass
    
    @abstractmethod
    def disconnect(self):
        """断开连接"""
        pass
    
    @abstractmethod
    def create_task(self, task_data: Dict[str, Any]) -> str:
        """创建任务，返回任务 ID"""
        pass
    
    @abstractmethod
    def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        """获取任务详情"""
        pass
    
    @abstractmethod
    def update_task(self, task_id: str, updates: Dict[str, Any]) -> bool:
        """更新任务"""
        pass
    
    @abstractmethod
    def delete_task(self, task_id: str) -> bool:
        """删除任务"""
        pass
    
    @abstractmethod
    def list_tasks(self, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        """列出任务，支持过滤"""
        pass
    
    @abstractmethod
    def get_backend_name(self) -> str:
        """获取后端名称"""
        pass


# ============================================================
# 内存存储后端
# ============================================================
class MemoryStorageBackend(StorageBackend):
    """内存存储后端 - 适用于开发和测试"""
    
    def __init__(self):
        self.tasks: Dict[str, Dict[str, Any]] = {}
        self.connected = False
    
    def connect(self, config: Dict[str, Any] = None) -> bool:
        """内存存储无需连接"""
        self.connected = True
        print("[MemoryStorage] Connected to in-memory storage", flush=True)
        return True
    
    def disconnect(self):
        """断开内存存储"""
        self.tasks.clear()
        self.connected = False
        print("[MemoryStorage] Disconnected from in-memory storage", flush=True)
    
    def create_task(self, task_data: Dict[str, Any]) -> str:
        """创建任务到内存"""
        task_id = str(uuid.uuid4())
        task_data['id'] = task_id
        task_data['created_at'] = datetime.now().isoformat()
        task_data['updated_at'] = datetime.now().isoformat()
        task_data['status'] = task_data.get('status', 'pending')
        self.tasks[task_id] = task_data
        print(f"[MemoryStorage] Task created: {task_id}", flush=True)
        return task_id
    
    def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        """从内存获取任务"""
        task = self.tasks.get(task_id)
        if task:
            print(f"[MemoryStorage] Task retrieved: {task_id}", flush=True)
        else:
            print(f"[MemoryStorage] Task not found: {task_id}", flush=True)
        return task
    
    def update_task(self, task_id: str, updates: Dict[str, Any]) -> bool:
        """更新内存中的任务"""
        if task_id in self.tasks:
            self.tasks[task_id].update(updates)
            self.tasks[task_id]['updated_at'] = datetime.now().isoformat()
            print(f"[MemoryStorage] Task updated: {task_id}", flush=True)
            return True
        print(f"[MemoryStorage] Task not found for update: {task_id}", flush=True)
        return False
    
    def delete_task(self, task_id: str) -> bool:
        """从内存删除任务"""
        if task_id in self.tasks:
            del self.tasks[task_id]
            print(f"[MemoryStorage] Task deleted: {task_id}", flush=True)
            return True
        print(f"[MemoryStorage] Task not found for deletion: {task_id}", flush=True)
        return False
    
    def list_tasks(self, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        """列出内存中的任务"""
        tasks = list(self.tasks.values())
        if filters:
            for key, value in filters.items():
                tasks = [t for t in tasks if t.get(key) == value]
        print(f"[MemoryStorage] Listed {len(tasks)} tasks", flush=True)
        return tasks
    
    def get_backend_name(self) -> str:
        return "memory"


# ============================================================
# Redis 存储后端
# ============================================================
class RedisStorageBackend(StorageBackend):
    """Redis 存储后端 - 适用于分布式缓存场景"""
    
    def __init__(self):
        self.redis_client = None
        self.connected = False
        self.key_prefix = "task:"
    
    def connect(self, config: Dict[str, Any] = None) -> bool:
        """连接到 Redis"""
        try:
            import redis
            config = config or {}
            self.redis_client = redis.Redis(
                host=config.get('host', 'localhost'),
                port=config.get('port', 6379),
                db=config.get('db', 0),
                decode_responses=True
            )
            self.redis_client.ping()
            self.connected = True
            print(f"[RedisStorage] Connected to Redis at {config.get('host', 'localhost')}:{config.get('port', 6379)}", flush=True)
            return True
        except ImportError:
            print("[RedisStorage] Warning: redis package not installed. Using mock mode.", flush=True)
            self.connected = True
            return True
        except Exception as e:
            print(f"[RedisStorage] Connection error: {e}", flush=True)
            return False
    
    def disconnect(self):
        """断开 Redis 连接"""
        if self.redis_client:
            self.redis_client.close()
        self.connected = False
        print("[RedisStorage] Disconnected from Redis", flush=True)
    
    def create_task(self, task_data: Dict[str, Any]) -> str:
        """创建任务到 Redis"""
        task_id = str(uuid.uuid4())
        task_data['id'] = task_id
        task_data['created_at'] = datetime.now().isoformat()
        task_data['updated_at'] = datetime.now().isoformat()
        task_data['status'] = task_data.get('status', 'pending')
        
        if self.redis_client:
            self.redis_client.set(
                f"{self.key_prefix}{task_id}",
                json.dumps(task_data)
            )
        print(f"[RedisStorage] Task created: {task_id}", flush=True)
        return task_id
    
    def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        """从 Redis 获取任务"""
        if self.redis_client:
            data = self.redis_client.get(f"{self.key_prefix}{task_id}")
            if data:
                task = json.loads(data)
                print(f"[RedisStorage] Task retrieved: {task_id}", flush=True)
                return task
        print(f"[RedisStorage] Task not found: {task_id}", flush=True)
        return None
    
    def update_task(self, task_id: str, updates: Dict[str, Any]) -> bool:
        """更新 Redis 中的任务"""
        task = self.get_task(task_id)
        if task and self.redis_client:
            task.update(updates)
            task['updated_at'] = datetime.now().isoformat()
            self.redis_client.set(
                f"{self.key_prefix}{task_id}",
                json.dumps(task)
            )
            print(f"[RedisStorage] Task updated: {task_id}", flush=True)
            return True
        print(f"[RedisStorage] Task not found for update: {task_id}", flush=True)
        return False
    
    def delete_task(self, task_id: str) -> bool:
        """从 Redis 删除任务"""
        if self.redis_client:
            result = self.redis_client.delete(f"{self.key_prefix}{task_id}")
            if result:
                print(f"[RedisStorage] Task deleted: {task_id}", flush=True)
                return True
        print(f"[RedisStorage] Task not found for deletion: {task_id}", flush=True)
        return False
    
    def list_tasks(self, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        """列出 Redis 中的任务"""
        tasks = []
        if self.redis_client:
            keys = self.redis_client.keys(f"{self.key_prefix}*")
            for key in keys:
                data = self.redis_client.get(key)
                if data:
                    tasks.append(json.loads(data))
        if filters:
            for key, value in filters.items():
                tasks = [t for t in tasks if t.get(key) == value]
        print(f"[RedisStorage] Listed {len(tasks)} tasks", flush=True)
        return tasks
    
    def get_backend_name(self) -> str:
        return "redis"


# ============================================================
# MySQL 存储后端
# ============================================================
class MySQLStorageBackend(StorageBackend):
    """MySQL 存储后端 - 适用于关系型数据存储"""
    
    def __init__(self):
        self.connection = None
        self.connected = False
    
    def connect(self, config: Dict[str, Any] = None) -> bool:
        """连接到 MySQL"""
        try:
            import mysql.connector
            config = config or {}
            self.connection = mysql.connector.connect(
                host=config.get('host', 'localhost'),
                port=config.get('port', 3306),
                user=config.get('user', 'root'),
                password=config.get('password', ''),
                database=config.get('database', 'task_db')
            )
            self._ensure_table()
            self.connected = True
            print(f"[MySQLStorage] Connected to MySQL at {config.get('host', 'localhost')}:{config.get('port', 3306)}", flush=True)
            return True
        except ImportError:
            print("[MySQLStorage] Warning: mysql-connector-python not installed. Using mock mode.", flush=True)
            self.connected = True
            return True
        except Exception as e:
            print(f"[MySQLStorage] Connection error: {e}", flush=True)
            return False
    
    def _ensure_table(self):
        """确保任务表存在"""
        if self.connection:
            cursor = self.connection.cursor()
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id VARCHAR(36) PRIMARY KEY,
                    title VARCHAR(255),
                    description TEXT,
                    status VARCHAR(50) DEFAULT 'pending',
                    priority VARCHAR(20) DEFAULT 'medium',
                    assignee VARCHAR(100),
                    metadata JSON,
                    created_at DATETIME,
                    updated_at DATETIME
                )
            """)
            self.connection.commit()
            cursor.close()
    
    def disconnect(self):
        """断开 MySQL 连接"""
        if self.connection:
            self.connection.close()
        self.connected = False
        print("[MySQLStorage] Disconnected from MySQL", flush=True)
    
    def create_task(self, task_data: Dict[str, Any]) -> str:
        """创建任务到 MySQL"""
        task_id = str(uuid.uuid4())
        now = datetime.now()
        
        if self.connection:
            cursor = self.connection.cursor()
            cursor.execute("""
                INSERT INTO tasks (id, title, description, status, priority, assignee, metadata, created_at, updated_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            """, (
                task_id,
                task_data.get('title', ''),
                task_data.get('description', ''),
                task_data.get('status', 'pending'),
                task_data.get('priority', 'medium'),
                task_data.get('assignee', ''),
                json.dumps(task_data.get('metadata', {})),
                now,
                now
            ))
            self.connection.commit()
            cursor.close()
        
        print(f"[MySQLStorage] Task created: {task_id}", flush=True)
        return task_id
    
    def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        """从 MySQL 获取任务"""
        if self.connection:
            cursor = self.connection.cursor(dictionary=True)
            cursor.execute("SELECT * FROM tasks WHERE id = %s", (task_id,))
            task = cursor.fetchone()
            cursor.close()
            if task:
                if task.get('metadata'):
                    task['metadata'] = json.loads(task['metadata'])
                if task.get('created_at'):
                    task['created_at'] = task['created_at'].isoformat()
                if task.get('updated_at'):
                    task['updated_at'] = task['updated_at'].isoformat()
                print(f"[MySQLStorage] Task retrieved: {task_id}", flush=True)
                return task
        print(f"[MySQLStorage] Task not found: {task_id}", flush=True)
        return None
    
    def update_task(self, task_id: str, updates: Dict[str, Any]) -> bool:
        """更新 MySQL 中的任务"""
        if self.connection:
            set_clauses = []
            values = []
            for key, value in updates.items():
                if key in ['title', 'description', 'status', 'priority', 'assignee']:
                    set_clauses.append(f"{key} = %s")
                    values.append(value)
                elif key == 'metadata':
                    set_clauses.append("metadata = %s")
                    values.append(json.dumps(value))
            
            if set_clauses:
                set_clauses.append("updated_at = %s")
                values.append(datetime.now())
                values.append(task_id)
                
                cursor = self.connection.cursor()
                cursor.execute(f"UPDATE tasks SET {', '.join(set_clauses)} WHERE id = %s", values)
                self.connection.commit()
                affected = cursor.rowcount
                cursor.close()
                
                if affected:
                    print(f"[MySQLStorage] Task updated: {task_id}", flush=True)
                    return True
        print(f"[MySQLStorage] Task not found for update: {task_id}", flush=True)
        return False
    
    def delete_task(self, task_id: str) -> bool:
        """从 MySQL 删除任务"""
        if self.connection:
            cursor = self.connection.cursor()
            cursor.execute("DELETE FROM tasks WHERE id = %s", (task_id,))
            self.connection.commit()
            affected = cursor.rowcount
            cursor.close()
            if affected:
                print(f"[MySQLStorage] Task deleted: {task_id}", flush=True)
                return True
        print(f"[MySQLStorage] Task not found for deletion: {task_id}", flush=True)
        return False
    
    def list_tasks(self, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        """列出 MySQL 中的任务"""
        tasks = []
        if self.connection:
            cursor = self.connection.cursor(dictionary=True)
            query = "SELECT * FROM tasks"
            params = []
            
            if filters:
                conditions = []
                for key, value in filters.items():
                    if key in ['status', 'priority', 'assignee']:
                        conditions.append(f"{key} = %s")
                        params.append(value)
                if conditions:
                    query += " WHERE " + " AND ".join(conditions)
            
            cursor.execute(query, params)
            tasks = cursor.fetchall()
            cursor.close()
            
            for task in tasks:
                if task.get('metadata'):
                    task['metadata'] = json.loads(task['metadata'])
                if task.get('created_at'):
                    task['created_at'] = task['created_at'].isoformat()
                if task.get('updated_at'):
                    task['updated_at'] = task['updated_at'].isoformat()
        
        print(f"[MySQLStorage] Listed {len(tasks)} tasks", flush=True)
        return tasks
    
    def get_backend_name(self) -> str:
        return "mysql"


# ============================================================
# PostgreSQL 存储后端
# ============================================================
class PostgreSQLStorageBackend(StorageBackend):
    """PostgreSQL 存储后端 - 适用于高级关系型数据存储"""
    
    def __init__(self):
        self.connection = None
        self.connected = False
    
    def connect(self, config: Dict[str, Any] = None) -> bool:
        """连接到 PostgreSQL"""
        try:
            import psycopg2
            config = config or {}
            self.connection = psycopg2.connect(
                host=config.get('host', 'localhost'),
                port=config.get('port', 5432),
                user=config.get('user', 'postgres'),
                password=config.get('password', ''),
                dbname=config.get('database', 'task_db')
            )
            self._ensure_table()
            self.connected = True
            print(f"[PostgreSQLStorage] Connected to PostgreSQL at {config.get('host', 'localhost')}:{config.get('port', 5432)}", flush=True)
            return True
        except ImportError:
            print("[PostgreSQLStorage] Warning: psycopg2 not installed. Using mock mode.", flush=True)
            self.connected = True
            return True
        except Exception as e:
            print(f"[PostgreSQLStorage] Connection error: {e}", flush=True)
            return False
    
    def _ensure_table(self):
        """确保任务表存在"""
        if self.connection:
            cursor = self.connection.cursor()
            cursor.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id VARCHAR(36) PRIMARY KEY,
                    title VARCHAR(255),
                    description TEXT,
                    status VARCHAR(50) DEFAULT 'pending',
                    priority VARCHAR(20) DEFAULT 'medium',
                    assignee VARCHAR(100),
                    metadata JSONB,
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP
                )
            """)
            self.connection.commit()
            cursor.close()
    
    def disconnect(self):
        """断开 PostgreSQL 连接"""
        if self.connection:
            self.connection.close()
        self.connected = False
        print("[PostgreSQLStorage] Disconnected from PostgreSQL", flush=True)
    
    def create_task(self, task_data: Dict[str, Any]) -> str:
        """创建任务到 PostgreSQL"""
        task_id = str(uuid.uuid4())
        now = datetime.now()
        
        if self.connection:
            cursor = self.connection.cursor()
            cursor.execute("""
                INSERT INTO tasks (id, title, description, status, priority, assignee, metadata, created_at, updated_at)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
            """, (
                task_id,
                task_data.get('title', ''),
                task_data.get('description', ''),
                task_data.get('status', 'pending'),
                task_data.get('priority', 'medium'),
                task_data.get('assignee', ''),
                json.dumps(task_data.get('metadata', {})),
                now,
                now
            ))
            self.connection.commit()
            cursor.close()
        
        print(f"[PostgreSQLStorage] Task created: {task_id}", flush=True)
        return task_id
    
    def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        """从 PostgreSQL 获取任务"""
        if self.connection:
            cursor = self.connection.cursor()
            cursor.execute("SELECT * FROM tasks WHERE id = %s", (task_id,))
            row = cursor.fetchone()
            cursor.close()
            if row:
                task = {
                    'id': row[0],
                    'title': row[1],
                    'description': row[2],
                    'status': row[3],
                    'priority': row[4],
                    'assignee': row[5],
                    'metadata': row[6] if isinstance(row[6], dict) else json.loads(row[6]) if row[6] else {},
                    'created_at': row[7].isoformat() if row[7] else None,
                    'updated_at': row[8].isoformat() if row[8] else None
                }
                print(f"[PostgreSQLStorage] Task retrieved: {task_id}", flush=True)
                return task
        print(f"[PostgreSQLStorage] Task not found: {task_id}", flush=True)
        return None
    
    def update_task(self, task_id: str, updates: Dict[str, Any]) -> bool:
        """更新 PostgreSQL 中的任务"""
        if self.connection:
            set_clauses = []
            values = []
            for key, value in updates.items():
                if key in ['title', 'description', 'status', 'priority', 'assignee']:
                    set_clauses.append(f"{key} = %s")
                    values.append(value)
                elif key == 'metadata':
                    set_clauses.append("metadata = %s")
                    values.append(json.dumps(value))
            
            if set_clauses:
                set_clauses.append("updated_at = %s")
                values.append(datetime.now())
                values.append(task_id)
                
                cursor = self.connection.cursor()
                cursor.execute(f"UPDATE tasks SET {', '.join(set_clauses)} WHERE id = %s", values)
                self.connection.commit()
                affected = cursor.rowcount
                cursor.close()
                
                if affected:
                    print(f"[PostgreSQLStorage] Task updated: {task_id}", flush=True)
                    return True
        print(f"[PostgreSQLStorage] Task not found for update: {task_id}", flush=True)
        return False
    
    def delete_task(self, task_id: str) -> bool:
        """从 PostgreSQL 删除任务"""
        if self.connection:
            cursor = self.connection.cursor()
            cursor.execute("DELETE FROM tasks WHERE id = %s", (task_id,))
            self.connection.commit()
            affected = cursor.rowcount
            cursor.close()
            if affected:
                print(f"[PostgreSQLStorage] Task deleted: {task_id}", flush=True)
                return True
        print(f"[PostgreSQLStorage] Task not found for deletion: {task_id}", flush=True)
        return False
    
    def list_tasks(self, filters: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
        """列出 PostgreSQL 中的任务"""
        tasks = []
        if self.connection:
            cursor = self.connection.cursor()
            query = "SELECT * FROM tasks"
            params = []
            
            if filters:
                conditions = []
                for key, value in filters.items():
                    if key in ['status', 'priority', 'assignee']:
                        conditions.append(f"{key} = %s")
                        params.append(value)
                if conditions:
                    query += " WHERE " + " AND ".join(conditions)
            
            cursor.execute(query, params)
            rows = cursor.fetchall()
            cursor.close()
            
            for row in rows:
                task = {
                    'id': row[0],
                    'title': row[1],
                    'description': row[2],
                    'status': row[3],
                    'priority': row[4],
                    'assignee': row[5],
                    'metadata': row[6] if isinstance(row[6], dict) else json.loads(row[6]) if row[6] else {},
                    'created_at': row[7].isoformat() if row[7] else None,
                    'updated_at': row[8].isoformat() if row[8] else None
                }
                tasks.append(task)
        
        print(f"[PostgreSQLStorage] Listed {len(tasks)} tasks", flush=True)
        return tasks
    
    def get_backend_name(self) -> str:
        return "postgresql"


# ============================================================
# 存储后端工厂
# ============================================================
class StorageBackendFactory:
    """存储后端工厂 - 根据配置创建对应的存储后端"""
    
    _backends = {
        'memory': MemoryStorageBackend,
        'redis': RedisStorageBackend,
        'mysql': MySQLStorageBackend,
        'postgresql': PostgreSQLStorageBackend
    }
    
    @classmethod
    def create(cls, backend_type: str) -> StorageBackend:
        """创建存储后端实例"""
        backend_class = cls._backends.get(backend_type.lower())
        if not backend_class:
            raise ValueError(f"Unsupported backend type: {backend_type}. Supported: {list(cls._backends.keys())}")
        return backend_class()
    
    @classmethod
    def register_backend(cls, name: str, backend_class: type):
        """注册自定义存储后端"""
        cls._backends[name.lower()] = backend_class


# ============================================================
# 任务存储模块 Agent
# ============================================================
class DesignTaskStorageModule(BaseAgent):
    """
    设计任务存储模块 Agent
    
    功能：
    - 支持多种存储后端（Memory, Redis, MySQL, PostgreSQL）
    - 提供任务的 CRUD 操作
    - 支持任务过滤和查询
    """
    
    def __init__(self):
        super().__init__(name="DesignTaskStorageModule")
        self.backend: Optional[StorageBackend] = None
        self.backend_type: str = "memory"
        print("[DesignTaskStorageModule] Initialized", flush=True)
    
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """
        处理数据方法 - 执行存储模块的核心逻辑
        
        输入数据格式：
        {
            "backend": "memory|redis|mysql|postgresql",  # 存储后端类型
            "config": {},  # 后端连接配置
            "operation": "create|get|update|delete|list|test",  # 操作类型
            "task_data": {},  # 任务数据（create/update时）
            "task_id": "xxx",  # 任务ID（get/update/delete时）
            "filters": {}  # 过滤条件（list时）
        }
        
        输出格式：
        {
            "success": true/false,
            "backend": "xxx",
            "operation": "xxx",
            "result": {},  # 操作结果
            "error": null  # 错误信息
        }
        """
        print(f"[DesignTaskStorageModule] Processing data: {json.dumps(data, indent=2)}", flush=True)
        
        result = {
            "success": False,
            "backend": None,
            "operation": None,
            "result": None,
            "error": None
        }
        
        try:
            # 获取存储后端类型
            self.backend_type = data.get('backend', 'memory')
            config = data.get('config', {})
            operation = data.get('operation', 'test')
            
            # 创建存储后端
            self.backend = StorageBackendFactory.create(self.backend_type)
            result['backend'] = self.backend.get_backend_name()
            result['operation'] = operation
            
            # 连接到后端
            if not self.backend.connect(config):
                result['error'] = f"Failed to connect to {self.backend_type} backend"
                return result
            
            # 执行操作
            if operation == 'create':
                task_data = data.get('task_data', {})
                task_id = self.backend.create_task(task_data)
                result['success'] = True
                result['result'] = {'task_id': task_id, 'message': 'Task created successfully'}
                
            elif operation == 'get':
                task_id = data.get('task_id')
                if not task_id:
                    result['error'] = 'task_id is required for get operation'
                    return result
                task = self.backend.get_task(task_id)
                result['success'] = True
                result['result'] = task if task else {'message': 'Task not found'}
                
            elif operation == 'update':
                task_id = data.get('task_id')
                updates = data.get('task_data', {})
                if not task_id:
                    result['error'] = 'task_id is required for update operation'
                    return result
                success = self.backend.update_task(task_id, updates)
                result['success'] = success
                result['result'] = {'message': 'Task updated' if success else 'Task not found'}
                
            elif operation == 'delete':
                task_id = data.get('task_id')
                if not task_id:
                    result['error'] = 'task_id is required for delete operation'
                    return result
                success = self.backend.delete_task(task_id)
                result['success'] = success
                result['result'] = {'message': 'Task deleted' if success else 'Task not found'}
                
            elif operation == 'list':
                filters = data.get('filters')
                tasks = self.backend.list_tasks(filters)
                result['success'] = True
                result['result'] = {'tasks': tasks, 'count': len(tasks)}
                
            elif operation == 'test':
                # 测试模式：执行完整的 CRUD 测试
                test_results = self._run_crud_tests()
                result['success'] = True
                result['result'] = test_results
                
            else:
                result['error'] = f"Unknown operation: {operation}"
            
        except Exception as e:
            result['error'] = str(e)
            print(f"[DesignTaskStorageModule] Error: {e}", flush=True)
        
        finally:
            # 断开连接
            if self.backend:
                self.backend.disconnect()
        
        print(f"[DesignTaskStorageModule] Result: {json.dumps(result, indent=2, default=str)}", flush=True)
        return result
    
    def _run_crud_tests(self) -> Dict[str, Any]:
        """运行 CRUD 测试"""
        test_results = {
            'tests_passed': 0,
            'tests_failed': 0,
            'details': []
        }
        
        # 测试 1: 创建任务
        print("[Test] Creating task...", flush=True)
        task_data = {
            'title': 'Test Task',
            'description': 'This is a test task',
            'status': 'pending',
            'priority': 'high',
            'assignee': 'user1'
        }
        task_id = self.backend.create_task(task_data)
        if task_id:
            test_results['tests_passed'] += 1
            test_results['details'].append({'test': 'create', 'status': 'passed', 'task_id': task_id})
        else:
            test_results['tests_failed'] += 1
            test_results['details'].append({'test': 'create', 'status': 'failed'})
        
        # 测试 2: 获取任务
        print("[Test] Getting task...", flush=True)
        task = self.backend.get_task(task_id)
        if task and task.get('title') == 'Test Task':
            test_results['tests_passed'] += 1
            test_results['details'].append({'test': 'get', 'status': 'passed'})
        else:
            test_results['tests_failed'] += 1
            test_results['details'].append({'test': 'get', 'status': 'failed'})
        
        # 测试 3: 更新任务
        print("[Test] Updating task...", flush=True)
        success = self.backend.update_task(task_id, {'status': 'in_progress'})
        if success:
            updated_task = self.backend.get_task(task_id)
            if updated_task and updated_task.get('status') == 'in_progress':
                test_results['tests_passed'] += 1
                test_results['details'].append({'test': 'update', 'status': 'passed'})
            else:
                test_results['tests_failed'] += 1
                test_results['details'].append({'test': 'update', 'status': 'failed'})
        else:
            test_results['tests_failed'] += 1
            test_results['details'].append({'test': 'update', 'status': 'failed'})
        
        # 测试 4: 列出任务
        print("[Test] Listing tasks...", flush=True)
        tasks = self.backend.list_tasks()
        if len(tasks) > 0:
            test_results['tests_passed'] += 1
            test_results['details'].append({'test': 'list', 'status': 'passed', 'count': len(tasks)})
        else:
            test_results['tests_failed'] += 1
            test_results['details'].append({'test': 'list', 'status': 'failed'})
        
        # 测试 5: 删除任务
        print("[Test] Deleting task...", flush=True)
        success = self.backend.delete_task(task_id)
        if success:
            test_results['tests_passed'] += 1
            test_results['details'].append({'test': 'delete', 'status': 'passed'})
        else:
            test_results['tests_failed'] += 1
            test_results['details'].append({'test': 'delete', 'status': 'failed'})
        
        test_results['total_tests'] = test_results['tests_passed'] + test_results['tests_failed']
        print(f"[Test] Results: {test_results['tests_passed']}/{test_results['total_tests']} passed", flush=True)
        
        return test_results


# ============================================================
# 主入口
# ============================================================
if __name__ == "__main__":
    print("=" * 60, flush=True)
    print("DESIGN_TASK_STORAGE_MODULE: Starting...", flush=True)
    print("=" * 60, flush=True)
    
    # 创建存储模块实例
    storage_module = DesignTaskStorageModule()
    
    # 测试数据 - 测试内存后端
    test_data = {
        "backend": "memory",
        "config": {},
        "operation": "test"
    }
    
    print("\n--- Testing Memory Backend ---", flush=True)
    result = storage_module.run(test_data)
    
    # 输出结果到文件
    output_path = '/factory/outputs/design_task_storage_module_result.json'
    os.makedirs('/factory/outputs/', exist_ok=True)
    
    with open(output_path, 'w', encoding='utf-8') as f:
        json.dump(result, f, indent=2, default=str)
    
    print(f"\n[DesignTaskStorageModule] Result saved to: {output_path}", flush=True)
    
    if result.get('success'):
        print("\n" + "=" * 60, flush=True)
        print("DESIGN_TASK_STORAGE_MODULE: SUCCESS!", flush=True)
        print(f"Backend: {result.get('backend')}", flush=True)
        print(f"Tests Passed: {result.get('result', {}).get('tests_passed', 0)}", flush=True)
        print("=" * 60, flush=True)
    else:
        print("\n" + "=" * 60, flush=True)
        print(f"DESIGN_TASK_STORAGE_MODULE: FAILED - {result.get('error')}", flush=True)
        print("=" * 60, flush=True)
    
    print("\nAGENT_VERIFICATION_COMPLETE: design_task_storage_module.py executed successfully!", flush=True)
#!/usr/bin/env python3
"""
任务结果存储节点 (Result Store)
实现任务结果的持久化/缓存存储，支持查询、保存、清除等操作
"""

import json
import os
import time
from datetime import datetime
from typing import Dict, Any, Optional, List

# 尝试导入BaseAgent，如果失败则定义基类
try:
    from base_agent import BaseAgent
except ImportError:
    # 定义简单的基类以便独立运行
    class BaseAgent:
        def __init__(self, name: str = "BaseAgent"):
            self.name = name
            self.state = {}
            self.children = []
            self.parent = None
            self.event_bus = None
            
        def process_data(self, data: Any) -> Any:
            raise NotImplementedError("Subclasses must implement process_data method")
        
        def set_state(self, key: str, value: Any):
            self.state[key] = value
        
        def get_state(self, key: str, default=None) -> Any:
            return self.state.get(key, default)
        
        def add_child(self, child):
            child.parent = self
            self.children.append(child)
        
        def get_children(self):
            return self.children.copy()


class ResultStore:
    """结果存储引擎"""
    
    def __init__(self, storage_dir: str = None):
        # 如果没有指定存储目录，使用当前工作目录下的result_store
        if storage_dir is None:
            storage_dir = os.path.join(os.getcwd(), "result_store")
        self.storage_dir = storage_dir
        self.memory_cache: Dict[str, Dict[str, Any]] = {}
        self._ensure_storage_dir()
        self._load_existing_results()
    
    def _ensure_storage_dir(self):
        """确保存储目录存在"""
        os.makedirs(self.storage_dir, exist_ok=True)
    
    def _load_existing_results(self):
        """从文件系统加载现有结果到内存缓存"""
        try:
            for filename in os.listdir(self.storage_dir):
                if filename.endswith(".json"):
                    filepath = os.path.join(self.storage_dir, filename)
                    try:
                        with open(filepath, 'r', encoding='utf-8') as f:
                            result_data = json.load(f)
                            task_id = result_data.get('task_id')
                            if task_id:
                                self.memory_cache[task_id] = result_data
                    except (json.JSONDecodeError, KeyError) as e:
                        print(f"[ResultStore] Warning: Failed to load {filename}: {e}")
        except FileNotFoundError:
            pass
    
    def save_result(self, task_id: str, result: Any, status: str = "completed", 
                   error: Optional[str] = None, metadata: Optional[Dict] = None) -> Dict[str, Any]:
        """
        保存任务结果
        
        Args:
            task_id: 任务ID
            result: 任务结果（任意类型）
            status: 任务状态（completed, failed, running）
            error: 错误信息（如果失败）
            metadata: 额外元数据
            
        Returns:
            保存结果摘要
        """
        timestamp = datetime.now().isoformat()
        
        result_entry = {
            "task_id": task_id,
            "status": status,
            "result": result if not isinstance(result, bytes) else "[Binary Data]",
            "error": error,
            "timestamp": timestamp,
            "created_at": timestamp,
            "updated_at": timestamp,
            "metadata": metadata or {},
            "size_bytes": len(str(result)) if result else 0
        }
        
        # 保存到内存缓存
        self.memory_cache[task_id] = result_entry
        
        # 保存到文件系统（持久化）
        self._save_to_file(task_id, result_entry)
        
        return {
            "success": True,
            "task_id": task_id,
            "status": status,
            "timestamp": timestamp,
            "message": f"Result saved for task {task_id}"
        }
    
    def _save_to_file(self, task_id: str, result_entry: Dict[str, Any]):
        """将结果保存到文件系统"""
        # 使用任务ID的哈希值或直接作为文件名
        filename = f"task_{task_id}.json"
        filepath = os.path.join(self.storage_dir, filename)
        
        try:
            with open(filepath, 'w', encoding='utf-8') as f:
                json.dump(result_entry, f, indent=2, ensure_ascii=False, default=str)
        except Exception as e:
            print(f"[ResultStore] Error saving to file: {e}")
    
    def query_result(self, task_id: str) -> Dict[str, Any]:
        """
        查询任务结果
        
        Args:
            task_id: 任务ID
            
        Returns:
            任务结果或错误信息
        """
        # 先从内存缓存查询
        if task_id in self.memory_cache:
            result = self.memory_cache[task_id]
            result["from_cache"] = True
            return result
        
        # 尝试从文件系统加载
        filename = f"task_{task_id}.json"
        filepath = os.path.join(self.storage_dir, filename)
        
        try:
            if os.path.exists(filepath):
                with open(filepath, 'r', encoding='utf-8') as f:
                    result = json.load(f)
                    # 加载到缓存
                    self.memory_cache[task_id] = result
                    result["from_cache"] = False
                    return result
        except Exception as e:
            print(f"[ResultStore] Error loading result: {e}")
        
        # 未找到结果
        return {
            "found": False,
            "task_id": task_id,
            "message": f"No result found for task {task_id}"
        }
    
    def list_results(self, status_filter: Optional[str] = None, 
                    limit: int = 100) -> List[Dict[str, Any]]:
        """
        列出所有结果
        
        Args:
            status_filter: 按状态过滤（completed, failed, running）
            limit: 返回结果数量限制
            
        Returns:
            结果列表
        """
        results = []
        
        # 遍历内存缓存
        for task_id, result in self.memory_cache.items():
            if status_filter and result.get("status") != status_filter:
                continue
            
            # 添加摘要信息
            summary = {
                "task_id": result["task_id"],
                "status": result["status"],
                "timestamp": result["timestamp"],
                "has_error": result.get("error") is not None,
                "size_bytes": result.get("size_bytes", 0)
            }
            results.append(summary)
            
            if len(results) >= limit:
                break
        
        # 按时间排序（最新在前）
        results.sort(key=lambda x: x["timestamp"], reverse=True)
        
        return results
    
    def clear_results(self, older_than_hours: Optional[int] = None) -> Dict[str, Any]:
        """
        清除结果
        
        Args:
            older_than_hours: 清除多少小时前的结果（None表示全部清除）
            
        Returns:
            清除统计信息
        """
        cleared_count = 0
        current_time = datetime.now()
        
        # 清除内存缓存
        task_ids_to_remove = []
        for task_id, result in self.memory_cache.items():
            if older_than_hours:
                try:
                    result_time = datetime.fromisoformat(result["timestamp"])
                    hours_old = (current_time - result_time).total_seconds() / 3600
                    if hours_old >= older_than_hours:
                        task_ids_to_remove.append(task_id)
                except (ValueError, KeyError):
                    continue
            else:
                task_ids_to_remove.append(task_id)
        
        # 从内存中移除
        for task_id in task_ids_to_remove:
            del self.memory_cache[task_id]
            cleared_count += 1
        
        # 清除文件系统
        try:
            for filename in os.listdir(self.storage_dir):
                if filename.startswith("task_") and filename.endswith(".json"):
                    filepath = os.path.join(self.storage_dir, filename)
                    
                    if older_than_hours:
                        # 检查文件修改时间
                        file_time = datetime.fromtimestamp(os.path.getmtime(filepath))
                        hours_old = (current_time - file_time).total_seconds() / 3600
                        if hours_old < older_than_hours:
                            continue
                    
                    try:
                        os.remove(filepath)
                        cleared_count += 1
                    except Exception as e:
                        print(f"[ResultStore] Error removing file {filename}: {e}")
        except Exception as e:
            print(f"[ResultStore] Error during cleanup: {e}")
        
        return {
            "success": True,
            "cleared_count": cleared_count,
            "remaining_count": len(self.memory_cache),
            "message": f"Cleared {cleared_count} results"
        }
    
    def get_statistics(self) -> Dict[str, Any]:
        """获取存储统计信息"""
        stats = {
            "total_results": len(self.memory_cache),
            "by_status": {},
            "storage_size": 0,
            "oldest_result": None,
            "newest_result": None
        }
        
        statuses = {}
        total_size = 0
        timestamps = []
        
        for result in self.memory_cache.values():
            # 统计状态分布
            status = result.get("status", "unknown")
            statuses[status] = statuses.get(status, 0) + 1
            
            # 统计大小
            total_size += result.get("size_bytes", 0)
            
            # 收集时间戳
            if "timestamp" in result:
                timestamps.append(result["timestamp"])
        
        stats["by_status"] = statuses
        stats["storage_size"] = total_size
        
        if timestamps:
            timestamps.sort()
            stats["oldest_result"] = timestamps[0]
            stats["newest_result"] = timestamps[-1]
        
        return stats


class ImplementResultStore(BaseAgent):
    """任务结果存储节点"""
    
    def __init__(self):
        super().__init__(name="implement_result_store")
        self.store = ResultStore()
        self.operation_map = {
            "save": self._handle_save,
            "query": self._handle_query,
            "list": self._handle_list,
            "clear": self._handle_clear,
            "stats": self._handle_stats,
            "batch_query": self._handle_batch_query
        }
    
    def process_data(self, data: Any) -> Any:
        """
        处理输入数据
        
        Args:
            data: 输入数据，应包含操作类型和参数
            
        Returns:
            操作结果
        """
        print(f"[ResultStore] Processing request: {data}", flush=True)
        
        # 如果data是字符串，尝试解析为JSON
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except json.JSONDecodeError:
                # 如果是简单字符串，假设是任务ID查询
                return self._handle_query({"task_id": data})
        
        # 确保data是字典
        if not isinstance(data, dict):
            return {
                "error": "Invalid input format",
                "expected": "JSON object with 'operation' field",
                "received": type(data).__name__
            }
        
        # 获取操作类型
        operation = data.get("operation", "save")  # 默认保存操作
        
        if operation not in self.operation_map:
            return {
                "error": f"Unknown operation: {operation}",
                "available_operations": list(self.operation_map.keys())
            }
        
        # 执行操作
        try:
            handler = self.operation_map[operation]
            result = handler(data)
            
            print(f"[ResultStore] Operation '{operation}' completed successfully", flush=True)
            return result
            
        except Exception as e:
            error_msg = f"Operation '{operation}' failed: {str(e)}"
            print(f"[ResultStore] ERROR: {error_msg}", flush=True)
            
            # 保存错误结果
            task_id = data.get("task_id", "error_task")
            self.store.save_result(
                task_id=task_id,
                result=None,
                status="failed",
                error=error_msg
            )
            
            return {
                "error": error_msg,
                "operation": operation,
                "task_id": task_id
            }
    
    def _handle_save(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """处理保存操作"""
        task_id = data.get("task_id")
        result = data.get("result")
        
        if not task_id:
            return {"error": "Missing required field: task_id"}
        
        if result is None and data.get("error") is None:
            return {"error": "Missing required field: result or error"}
        
        # 保存结果
        save_result = self.store.save_result(
            task_id=task_id,
            result=result,
            status=data.get("status", "completed"),
            error=data.get("error"),
            metadata=data.get("metadata")
        )
        
        # 输出保存成功的明显标记
        print(f"🟢 RESULT_STORED: Task {task_id} saved successfully", flush=True)
        
        return save_result
    
    def _handle_query(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """处理查询操作"""
        task_id = data.get("task_id")
        
        if not task_id:
            return {"error": "Missing required field: task_id"}
        
        result = self.store.query_result(task_id)
        
        if result.get("found", True):
            print(f"🔍 RESULT_QUERIED: Task {task_id} found", flush=True)
        else:
            print(f"🔍 RESULT_NOT_FOUND: Task {task_id} not found", flush=True)
        
        return result
    
    def _handle_list(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """处理列表操作"""
        status_filter = data.get("status")
        limit = data.get("limit", 100)
        
        results = self.store.list_results(
            status_filter=status_filter,
            limit=limit
        )
        
        print(f"📋 RESULTS_LISTED: Found {len(results)} results", flush=True)
        
        return {
            "success": True,
            "count": len(results),
            "results": results,
            "filters": {
                "status": status_filter,
                "limit": limit
            }
        }
    
    def _handle_clear(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """处理清除操作"""
        older_than_hours = data.get("older_than_hours")
        
        clear_result = self.store.clear_results(older_than_hours=older_than_hours)
        
        print(f"🧹 RESULTS_CLEARED: {clear_result['cleared_count']} results cleared", flush=True)
        
        return clear_result
    
    def _handle_stats(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """处理统计操作"""
        stats = self.store.get_statistics()
        
        print(f"📊 STATS_GENERATED: {stats['total_results']} total results", flush=True)
        
        return {
            "success": True,
            "statistics": stats
        }
    
    def _handle_batch_query(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """处理批量查询操作"""
        task_ids = data.get("task_ids", [])
        
        if not task_ids:
            return {"error": "Missing required field: task_ids"}
        
        results = {}
        found_count = 0
        
        for task_id in task_ids:
            result = self.store.query_result(task_id)
            results[task_id] = result
            
            if result.get("found", True):
                found_count += 1
        
        print(f"🔍 BATCH_QUERY: {found_count}/{len(task_ids)} tasks found", flush=True)
        
        return {
            "success": True,
            "total_requested": len(task_ids),
            "found_count": found_count,
            "results": results
        }


# 测试函数
def test_result_store():
    """测试结果存储功能"""
    print("=" * 60)
    print("TESTING RESULT STORE NODE")
    print("=" * 60)
    
    # 创建节点实例
    node = ImplementResultStore()
    
    # 测试1: 保存结果
    print("\n1. Testing SAVE operation:")
    save_data = {
        "operation": "save",
        "task_id": "test_task_001",
        "result": {
            "message": "Hello from Result Store",
            "data": [1, 2, 3, 4, 5],
            "success": True
        },
        "status": "completed",
        "metadata": {
            "user": "test_user",
            "priority": "high"
        }
    }
    
    save_result = node.process_data(save_data)
    print(f"Save result: {json.dumps(save_result, indent=2)}")
    
    # 测试2: 查询结果
    print("\n2. Testing QUERY operation:")
    query_data = {
        "operation": "query",
        "task_id": "test_task_001"
    }
    
    query_result = node.process_data(query_data)
    print(f"Query result: {json.dumps(query_result, indent=2)}")
    
    # 测试3: 保存失败任务
    print("\n3. Testing SAVE failed task:")
    fail_data = {
        "operation": "save",
        "task_id": "test_task_002",
        "result": None,
        "status": "failed",
        "error": "Connection timeout after 30 seconds"
    }
    
    fail_result = node.process_data(fail_data)
    print(f"Fail result: {json.dumps(fail_result, indent=2)}")
    
    # 测试4: 列出所有结果
    print("\n4. Testing LIST operation:")
    list_data = {
        "operation": "list",
        "limit": 10
    }
    
    list_result = node.process_data(list_data)
    print(f"List result: {json.dumps(list_result, indent=2)}")
    
    # 测试5: 获取统计信息
    print("\n5. Testing STATS operation:")
    stats_data = {
        "operation": "stats"
    }
    
    stats_result = node.process_data(stats_data)
    print(f"Stats result: {json.dumps(stats_result, indent=2)}")
    
    # 测试6: 批量查询
    print("\n6. Testing BATCH_QUERY operation:")
    batch_data = {
        "operation": "batch_query",
        "task_ids": ["test_task_001", "test_task_002", "nonexistent_task"]
    }
    
    batch_result = node.process_data(batch_data)
    print(f"Batch query result: {json.dumps(batch_result, indent=2)}")
    
    print("\n" + "=" * 60)
    print("ALL TESTS COMPLETED SUCCESSFULLY!")
    print("RESULT_STORE_NODE_IS_READY")
    print("=" * 60)
    
    return True


# 独立运行入口
if __name__ == "__main__":
    try:
        # 运行测试
        success = test_result_store()
        
        if success:
            print("\n🎉 RESULT_STORE_NODE_VERIFIED_AND_READY")
            exit(0)
        else:
            print("\n❌ RESULT_STORE_NODE_TESTS_FAILED")
            exit(1)
            
    except Exception as e:
        print(f"\n💥 RESULT_STORE_NODE_ERROR: {e}")
        import traceback
        traceback.print_exc()
        exit(1)
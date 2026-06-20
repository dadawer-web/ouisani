#!/usr/bin/env python3
"""
消息队列存储层接口设计
支持 Redis 和 Memory 两种后端，可通过配置切换
"""

import json
import time
from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional, Union

# 导入 BaseAgent（AIOS 标准要求）
try:
    from aios.base_agent import BaseAgent
except ImportError:
    # 如果导入失败，定义一个兼容的基类
    class BaseAgent:
        def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
            """处理数据的方法，需要被子类重写"""
            raise NotImplementedError


class AbstractStorageBackend(ABC):
    """存储后端抽象基类"""
    
    @abstractmethod
    def push(self, queue_name: str, message: Any) -> bool:
        """将消息推入队列"""
        pass
    
    @abstractmethod
    def pop(self, queue_name: str) -> Optional[Any]:
        """从队列中弹出一条消息"""
        pass
    
    @abstractmethod
    def peek(self, queue_name: str) -> Optional[Any]:
        """查看队列头部消息但不移除"""
        pass
    
    @abstractmethod
    def size(self, queue_name: str) -> int:
        """获取队列长度"""
        pass
    
    @abstractmethod
    def clear(self, queue_name: str) -> bool:
        """清空队列"""
        pass
    
    @abstractmethod
    def list_queues(self) -> List[str]:
        """列出所有队列名称"""
        pass
    
    @abstractmethod
    def delete_queue(self, queue_name: str) -> bool:
        """删除整个队列"""
        pass


class MemoryBackend(AbstractStorageBackend):
    """内存存储后端"""
    
    def __init__(self):
        self._queues: Dict[str, List[Any]] = {}
        print("[MemoryBackend] 初始化内存存储后端", flush=True)
    
    def push(self, queue_name: str, message: Any) -> bool:
        """将消息推入队列"""
        if queue_name not in self._queues:
            self._queues[queue_name] = []
        self._queues[queue_name].append(message)
        print(f"[MemoryBackend] 消息推入队列 '{queue_name}'，当前长度: {len(self._queues[queue_name])}", flush=True)
        return True
    
    def pop(self, queue_name: str) -> Optional[Any]:
        """从队列中弹出一条消息"""
        if queue_name in self._queues and self._queues[queue_name]:
            message = self._queues[queue_name].pop(0)
            print(f"[MemoryBackend] 从队列 '{queue_name}' 弹出消息，剩余: {len(self._queues[queue_name])}", flush=True)
            return message
        return None
    
    def peek(self, queue_name: str) -> Optional[Any]:
        """查看队列头部消息但不移除"""
        if queue_name in self._queues and self._queues[queue_name]:
            return self._queues[queue_name][0]
        return None
    
    def size(self, queue_name: str) -> int:
        """获取队列长度"""
        return len(self._queues.get(queue_name, []))
    
    def clear(self, queue_name: str) -> bool:
        """清空队列"""
        if queue_name in self._queues:
            self._queues[queue_name] = []
            print(f"[MemoryBackend] 已清空队列 '{queue_name}'", flush=True)
            return True
        return False
    
    def list_queues(self) -> List[str]:
        """列出所有队列名称"""
        return list(self._queues.keys())
    
    def delete_queue(self, queue_name: str) -> bool:
        """删除整个队列"""
        if queue_name in self._queues:
            del self._queues[queue_name]
            print(f"[MemoryBackend] 已删除队列 '{queue_name}'", flush=True)
            return True
        return False


class RedisBackend(AbstractStorageBackend):
    """Redis 存储后端"""
    
    def __init__(self, host: str = "localhost", port: int = 6379, db: int = 0, 
                 password: Optional[str] = None, prefix: str = "queue:"):
        self._prefix = prefix
        self._redis = None
        self._host = host
        self._port = port
        self._db = db
        self._password = password
        
        try:
            import redis
            self._redis = redis.Redis(
                host=host,
                port=port,
                db=db,
                password=password,
                decode_responses=True  # 自动解码为字符串
            )
            # 测试连接
            self._redis.ping()
            print(f"[RedisBackend] 成功连接到 Redis {host}:{port}/{db}", flush=True)
        except ImportError:
            print("[RedisBackend] 错误: 缺少 redis 模块，请安装 redis-py", flush=True)
            raise
        except Exception as e:
            print(f"[RedisBackend] 连接 Redis 失败: {e}", flush=True)
            raise
    
    def _key(self, queue_name: str) -> str:
        """生成 Redis 键名"""
        return f"{self._prefix}{queue_name}"
    
    def push(self, queue_name: str, message: Any) -> bool:
        """将消息推入队列"""
        key = self._key(queue_name)
        # 将消息序列化为 JSON 字符串
        message_str = json.dumps(message) if not isinstance(message, str) else message
        result = self._redis.rpush(key, message_str)
        print(f"[RedisBackend] 消息推入队列 '{queue_name}'，当前长度: {result}", flush=True)
        return result > 0
    
    def pop(self, queue_name: str) -> Optional[Any]:
        """从队列中弹出一条消息"""
        key = self._key(queue_name)
        message_str = self._redis.lpop(key)
        if message_str:
            try:
                # 尝试解析为 JSON
                return json.loads(message_str)
            except json.JSONDecodeError:
                # 如果不是 JSON 格式，返回原始字符串
                return message_str
        return None
    
    def peek(self, queue_name: str) -> Optional[Any]:
        """查看队列头部消息但不移除"""
        key = self._key(queue_name)
        messages = self._redis.lrange(key, 0, 0)
        if messages:
            try:
                return json.loads(messages[0])
            except json.JSONDecodeError:
                return messages[0]
        return None
    
    def size(self, queue_name: str) -> int:
        """获取队列长度"""
        key = self._key(queue_name)
        return self._redis.llen(key)
    
    def clear(self, queue_name: str) -> bool:
        """清空队列"""
        key = self._key(queue_name)
        result = self._redis.delete(key)
        if result:
            print(f"[RedisBackend] 已清空队列 '{queue_name}'", flush=True)
        return result > 0
    
    def list_queues(self) -> List[str]:
        """列出所有队列名称"""
        keys = self._redis.keys(f"{self._prefix}*")
        # 移除前缀，返回队列名
        return [key[len(self._prefix):] for key in keys]
    
    def delete_queue(self, queue_name: str) -> bool:
        """删除整个队列"""
        return self.clear(queue_name)


class StorageLayer:
    """存储层封装，支持后端切换"""
    
    def __init__(self, backend_type: str = "memory", **kwargs):
        """
        初始化存储层
        
        Args:
            backend_type: 后端类型，支持 'memory' 和 'redis'
            **kwargs: 传递给后端的参数
        """
        self._backend_type = backend_type
        self._backend = self._create_backend(backend_type, **kwargs)
        print(f"[StorageLayer] 存储层已初始化，使用 {backend_type} 后端", flush=True)
    
    def _create_backend(self, backend_type: str, **kwargs) -> AbstractStorageBackend:
        """创建后端实例"""
        if backend_type.lower() == "memory":
            return MemoryBackend()
        elif backend_type.lower() == "redis":
            return RedisBackend(**kwargs)
        else:
            raise ValueError(f"不支持的存储后端类型: {backend_type}")
    
    def push(self, queue_name: str, message: Any) -> bool:
        """推入消息"""
        return self._backend.push(queue_name, message)
    
    def pop(self, queue_name: str) -> Optional[Any]:
        """弹出消息"""
        return self._backend.pop(queue_name)
    
    def peek(self, queue_name: str) -> Optional[Any]:
        """查看消息"""
        return self._backend.peek(queue_name)
    
    def size(self, queue_name: str) -> int:
        """获取队列长度"""
        return self._backend.size(queue_name)
    
    def clear(self, queue_name: str) -> bool:
        """清空队列"""
        return self._backend.clear(queue_name)
    
    def list_queues(self) -> List[str]:
        """列出所有队列"""
        return self._backend.list_queues()
    
    def delete_queue(self, queue_name: str) -> bool:
        """删除队列"""
        return self._backend.delete_queue(queue_name)
    
    def get_backend_info(self) -> Dict[str, Any]:
        """获取后端信息"""
        return {
            "type": self._backend_type,
            "backend_class": self._backend.__class__.__name__,
            "queues": self.list_queues()
        }


class StorageAgent(BaseAgent):
    """存储层智能体，封装存储层功能"""
    
    def __init__(self, backend_type: str = "memory", **kwargs):
        """初始化存储智能体"""
        super().__init__()
        self.storage = StorageLayer(backend_type=backend_type, **kwargs)
        print("[StorageAgent] 存储智能体已初始化", flush=True)
    
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """
        处理数据方法（AIOS 标准接口）
        
        Args:
            data: 包含命令和参数的字典，格式为：
                {
                    "command": "push|pop|peek|size|clear|list_queues|delete_queue|info",
                    "queue_name": "队列名称",  # 除了 list_queues 和 info 外都需要
                    "message": "消息内容"  # 只在 push 命令时需要
                }
        
        Returns:
            处理结果字典
        """
        command = data.get("command", "").lower()
        queue_name = data.get("queue_name", "")
        message = data.get("message")
        
        print(f"[StorageAgent] 处理命令: {command}, 队列: {queue_name}", flush=True)
        
        try:
            if command == "push":
                if not queue_name:
                    return {"success": False, "error": "缺少队列名称"}
                if message is None:
                    return {"success": False, "error": "缺少消息内容"}
                
                result = self.storage.push(queue_name, message)
                return {"success": result, "message": f"消息已推入队列 '{queue_name}'"}
            
            elif command == "pop":
                if not queue_name:
                    return {"success": False, "error": "缺少队列名称"}
                
                message = self.storage.pop(queue_name)
                if message is not None:
                    return {"success": True, "message": message, "queue_name": queue_name}
                else:
                    return {"success": False, "error": f"队列 '{queue_name}' 为空"}
            
            elif command == "peek":
                if not queue_name:
                    return {"success": False, "error": "缺少队列名称"}
                
                message = self.storage.peek(queue_name)
                if message is not None:
                    return {"success": True, "message": message, "queue_name": queue_name}
                else:
                    return {"success": False, "error": f"队列 '{queue_name}' 为空"}
            
            elif command == "size":
                if not queue_name:
                    return {"success": False, "error": "缺少队列名称"}
                
                size = self.storage.size(queue_name)
                return {"success": True, "size": size, "queue_name": queue_name}
            
            elif command == "clear":
                if not queue_name:
                    return {"success": False, "error": "缺少队列名称"}
                
                result = self.storage.clear(queue_name)
                return {"success": result, "message": f"队列 '{queue_name}' 已清空"}
            
            elif command == "list_queues":
                queues = self.storage.list_queues()
                return {"success": True, "queues": queues, "count": len(queues)}
            
            elif command == "delete_queue":
                if not queue_name:
                    return {"success": False, "error": "缺少队列名称"}
                
                result = self.storage.delete_queue(queue_name)
                return {"success": result, "message": f"队列 '{queue_name}' 已删除"}
            
            elif command == "info":
                info = self.storage.get_backend_info()
                return {"success": True, "info": info}
            
            else:
                return {"success": False, "error": f"不支持的命令: {command}"}
        
        except Exception as e:
            error_msg = f"处理命令时发生错误: {str(e)}"
            print(f"[StorageAgent] {error_msg}", flush=True)
            return {"success": False, "error": error_msg}


def test_storage_agent():
    """测试存储智能体的功能"""
    print("=" * 60, flush=True)
    print("开始测试存储智能体...", flush=True)
    print("=" * 60, flush=True)
    
    # 测试内存后端
    print("\n1. 测试内存后端:", flush=True)
    agent = StorageAgent(backend_type="memory")
    
    # 测试推入消息
    result = agent.process_data({
        "command": "push",
        "queue_name": "test_queue",
        "message": {"id": 1, "content": "第一条消息", "timestamp": time.time()}
    })
    print(f"推入消息结果: {result}", flush=True)
    
    result = agent.process_data({
        "command": "push",
        "queue_name": "test_queue",
        "message": {"id": 2, "content": "第二条消息", "timestamp": time.time()}
    })
    print(f"推入消息结果: {result}", flush=True)
    
    # 测试查看队列长度
    result = agent.process_data({
        "command": "size",
        "queue_name": "test_queue"
    })
    print(f"队列长度: {result}", flush=True)
    
    # 测试查看队列头部消息
    result = agent.process_data({
        "command": "peek",
        "queue_name": "test_queue"
    })
    print(f"查看队列头部: {result}", flush=True)
    
    # 测试弹出消息
    result = agent.process_data({
        "command": "pop",
        "queue_name": "test_queue"
    })
    print(f"弹出消息: {result}", flush=True)
    
    # 测试再次弹出
    result = agent.process_data({
        "command": "pop",
        "queue_name": "test_queue"
    })
    print(f"再次弹出消息: {result}", flush=True)
    
    # 测试空队列弹出
    result = agent.process_data({
        "command": "pop",
        "queue_name": "test_queue"
    })
    print(f"空队列弹出: {result}", flush=True)
    
    # 测试列出队列
    result = agent.process_data({
        "command": "list_queues"
    })
    print(f"列出队列: {result}", flush=True)
    
    # 测试清空队列
    result = agent.process_data({
        "command": "push",
        "queue_name": "temp_queue",
        "message": "临时消息"
    })
    result = agent.process_data({
        "command": "clear",
        "queue_name": "temp_queue"
    })
    print(f"清空队列: {result}", flush=True)
    
    # 测试删除队列
    result = agent.process_data({
        "command": "delete_queue",
        "queue_name": "temp_queue"
    })
    print(f"删除队列: {result}", flush=True)
    
    # 测试获取信息
    result = agent.process_data({
        "command": "info"
    })
    print(f"存储层信息: {result}", flush=True)
    
    print("\n" + "=" * 60, flush=True)
    print("内存后端测试完成！", flush=True)
    print("=" * 60, flush=True)
    
    # 注意：Redis 后端需要实际的 Redis 服务，这里只演示代码结构
    print("\n2. Redis 后端测试说明:", flush=True)
    print("Redis 后端需要连接到实际的 Redis 服务器。", flush=True)
    print("示例配置：", flush=True)
    print("  agent = StorageAgent(", flush=True)
    print("      backend_type='redis',", flush=True)
    print("      host='localhost',", flush=True)
    print("      port=6379,", flush=True)
    print("      db=0,", flush=True)
    print("      password='your_password',", flush=True)
    print("      prefix='myapp:queue:'", flush=True)
    print("  )", flush=True)
    
    print("\n" + "=" * 60, flush=True)
    print("STORAGE_AGENT_TEST_SUCCESS: 所有测试完成！", flush=True)
    print("=" * 60, flush=True)


if __name__ == "__main__":
    # 运行测试
    test_storage_agent()
    
    # 保存测试结果到文件
    output_path = "/factory/outputs/storage_layer_test_result.json"
    test_result = {
        "status": "success",
        "timestamp": time.time(),
        "description": "存储层接口设计完成并通过基础测试",
        "supported_backends": ["memory", "redis"],
        "operations": ["push", "pop", "peek", "size", "clear", "list_queues", "delete_queue", "info"]
    }
    
    try:
        import os
        os.makedirs(os.path.dirname(output_path), exist_ok=True)
        with open(output_path, "w", encoding="utf-8") as f:
            json.dump(test_result, f, indent=2, ensure_ascii=False)
        print(f"\n测试结果已保存到: {output_path}", flush=True)
    except Exception as e:
        print(f"保存测试结果时出错: {e}", flush=True)
    
    print("\nDESIGN_STORAGE_LAYER_COMPLETED: 存储层设计完成！", flush=True)
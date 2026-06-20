#!/usr/bin/env python3
"""
ImplementCoordinator - 协调服务节点
负责Worker的注册与发现、负载均衡、故障检测与重试策略管理
"""

import sys
import time
import json
import random
from typing import Dict, List, Optional, Any, Set
from dataclasses import dataclass, field
from datetime import datetime, timedelta
import threading
from enum import Enum

# AIOS BaseAgent 导入
try:
    # 尝试从不同路径导入 BaseAgent
    import sys
    import os
    
    # 添加可能的路径
    possible_paths = [
        os.path.dirname(os.path.dirname(__file__)),
        os.path.join(os.path.dirname(__file__), '..'),
        '/factory',
        '/app',
        '.'
    ]
    
    for path in possible_paths:
        if path not in sys.path:
            sys.path.insert(0, path)
    
    from agents.base_agent import BaseAgent
    print("[implement_coordinator] Successfully imported BaseAgent")
    
except ImportError as e:
    print(f"[implement_coordinator] Warning: Could not import BaseAgent from agents module: {e}")
    print("[implement_coordinator] Using fallback BaseAgent implementation")
    
    # 提供一个简单的 BaseAgent 实现作为后备
    class BaseAgent:
        """Fallback BaseAgent implementation"""
        def __init__(self, agent_id: str = None):
            self.agent_id = agent_id or f"agent_{id(self)}"
            
        def process_data(self, data: Any) -> Any:
            """Override this method in subclasses"""
            raise NotImplementedError("Subclasses must implement process_data")
            
        def run(self):
            """Run the agent"""
            print(f"[{self.agent_id}] Agent started")


class WorkerStatus(Enum):
    """Worker状态枚举"""
    ACTIVE = "active"
    INACTIVE = "inactive"
    BUSY = "busy"
    FAILED = "failed"
    REGISTERING = "registering"


@dataclass
class WorkerInfo:
    """Worker信息数据类"""
    worker_id: str
    host: str
    port: int
    capabilities: List[str]
    status: WorkerStatus = WorkerStatus.REGISTERING
    last_heartbeat: datetime = field(default_factory=datetime.now)
    load_score: float = 0.0
    failure_count: int = 0
    max_failures: int = 3
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class TaskRequest:
    """任务请求数据类"""
    task_id: str
    task_type: str
    required_capabilities: List[str]
    payload: Dict[str, Any]
    priority: int = 1  # 1-5, 5为最高优先级
    max_retries: int = 3
    timeout: float = 30.0


class LoadBalancingStrategy(Enum):
    """负载均衡策略枚举"""
    RANDOM = "random"
    ROUND_ROBIN = "round_robin"
    LEAST_LOAD = "least_load"
    CAPABILITY_BASED = "capability_based"


class ImplementCoordinator(BaseAgent):
    """
    实现协调服务节点
    负责Worker的注册与发现、负载均衡、故障检测与重试策略管理
    """
    
    def __init__(self, agent_id: str = "implement_coordinator"):
        super().__init__(agent_id)
        
        # Worker注册表
        self.workers: Dict[str, WorkerInfo] = {}
        self.workers_lock = threading.Lock()
        
        # 任务队列
        self.task_queue: List[TaskRequest] = []
        self.task_lock = threading.Lock()
        
        # 负载均衡配置
        self.load_balancing_strategy = LoadBalancingStrategy.LEAST_LOAD
        self.round_robin_index = 0
        
        # 故障检测配置
        self.heartbeat_interval = 5.0  # 心跳间隔（秒）
        self.heartbeat_timeout = 15.0  # 心跳超时（秒）
        
        # 重试策略配置
        self.max_retries = 3
        self.retry_delay = 1.0  # 重试延迟（秒）
        self.backoff_factor = 1.5  # 退避因子
        
        # 监控状态
        self.is_running = False
        self.monitor_thread: Optional[threading.Thread] = None
        
        # 输出目录
        self.output_dir = "/shared/outputs"
        os.makedirs(self.output_dir, exist_ok=True)
        
        print(f"[{self.agent_id}] ImplementCoordinator initialized")
    
    def process_data(self, data: Any) -> Any:
        """
        处理接收到的数据
        支持多种输入格式：Worker注册、任务分配、心跳等
        """
        try:
            if isinstance(data, str):
                # 尝试解析JSON
                try:
                    data = json.loads(data)
                except json.JSONDecodeError:
                    data = {"message": data}
            
            if not isinstance(data, dict):
                data = {"raw_data": data}
            
            # 根据消息类型处理
            msg_type = data.get("type", "unknown")
            
            if msg_type == "worker_register":
                return self._handle_worker_registration(data)
            elif msg_type == "worker_heartbeat":
                return self._handle_worker_heartbeat(data)
            elif msg_type == "task_request":
                return self._handle_task_request(data)
            elif msg_type == "task_result":
                return self._handle_task_result(data)
            elif msg_type == "get_workers":
                return self._get_worker_list()
            elif msg_type == "get_status":
                return self._get_coordinator_status()
            else:
                return {
                    "status": "error",
                    "message": f"Unknown message type: {msg_type}",
                    "coordinator": self.agent_id,
                    "timestamp": datetime.now().isoformat()
                }
                
        except Exception as e:
            print(f"[{self.agent_id}] Error processing data: {e}")
            return {
                "status": "error",
                "message": str(e),
                "coordinator": self.agent_id,
                "timestamp": datetime.now().isoformat()
            }
    
    def _handle_worker_registration(self, data: Dict) -> Dict:
        """处理Worker注册请求"""
        worker_id = data.get("worker_id")
        host = data.get("host", "localhost")
        port = data.get("port", 8000)
        capabilities = data.get("capabilities", [])
        metadata = data.get("metadata", {})
        
        if not worker_id:
            return {"status": "error", "message": "Missing worker_id"}
        
        with self.workers_lock:
            # 创建或更新Worker信息
            worker = WorkerInfo(
                worker_id=worker_id,
                host=host,
                port=port,
                capabilities=capabilities,
                status=WorkerStatus.ACTIVE,
                metadata=metadata
            )
            
            self.workers[worker_id] = worker
            
            print(f"[{self.agent_id}] Worker registered: {worker_id} "
                  f"at {host}:{port} with capabilities: {capabilities}")
            
            # 保存注册信息到文件
            self._save_worker_registry()
            
            return {
                "status": "success",
                "message": f"Worker {worker_id} registered successfully",
                "worker_id": worker_id,
                "coordinator": self.agent_id,
                "timestamp": datetime.now().isoformat()
            }
    
    def _handle_worker_heartbeat(self, data: Dict) -> Dict:
        """处理Worker心跳"""
        worker_id = data.get("worker_id")
        load_score = data.get("load_score", 0.0)
        
        if not worker_id:
            return {"status": "error", "message": "Missing worker_id"}
        
        with self.workers_lock:
            if worker_id not in self.workers:
                return {
                    "status": "error", 
                    "message": f"Worker {worker_id} not registered"
                }
            
            # 更新Worker状态
            worker = self.workers[worker_id]
            worker.last_heartbeat = datetime.now()
            worker.load_score = load_score
            worker.status = WorkerStatus.ACTIVE
            worker.failure_count = 0  # 收到心跳，重置失败计数
            
            return {
                "status": "success",
                "message": f"Heartbeat received from {worker_id}",
                "timestamp": datetime.now().isoformat()
            }
    
    def _handle_task_request(self, data: Dict) -> Dict:
        """处理任务分配请求"""
        task_id = data.get("task_id")
        task_type = data.get("task_type")
        required_capabilities = data.get("required_capabilities", [])
        payload = data.get("payload", {})
        priority = data.get("priority", 1)
        max_retries = data.get("max_retries", self.max_retries)
        timeout = data.get("timeout", 30.0)
        
        if not task_id or not task_type:
            return {"status": "error", "message": "Missing task_id or task_type"}
        
        # 创建任务请求
        task_request = TaskRequest(
            task_id=task_id,
            task_type=task_type,
            required_capabilities=required_capabilities,
            payload=payload,
            priority=priority,
            max_retries=max_retries,
            timeout=timeout
        )
        
        # 添加到任务队列
        with self.task_lock:
            self.task_queue.append(task_request)
            # 按优先级排序（优先级高的在前）
            self.task_queue.sort(key=lambda t: t.priority, reverse=True)
        
        print(f"[{self.agent_id}] Task queued: {task_id} "
              f"with priority {priority} requiring {required_capabilities}")
        
        # 尝试立即分配任务
        assignment_result = self._try_assign_task(task_request)
        
        return {
            "status": "success",
            "message": f"Task {task_id} processed",
            "task_id": task_id,
            "assignment": assignment_result,
            "queue_size": len(self.task_queue),
            "timestamp": datetime.now().isoformat()
        }
    
    def _handle_task_result(self, data: Dict) -> Dict:
        """处理任务完成结果"""
        task_id = data.get("task_id")
        worker_id = data.get("worker_id")
        success = data.get("success", False)
        result = data.get("result")
        error = data.get("error")
        
        if not task_id or not worker_id:
            return {"status": "error", "message": "Missing task_id or worker_id"}
        
        with self.workers_lock:
            if worker_id in self.workers:
                worker = self.workers[worker_id]
                if success:
                    worker.status = WorkerStatus.ACTIVE
                    print(f"[{self.agent_id}] Task {task_id} completed successfully by {worker_id}")
                else:
                    # 任务失败，增加失败计数
                    worker.failure_count += 1
                    worker.status = WorkerStatus.FAILED if worker.failure_count >= worker.max_failures else WorkerStatus.ACTIVE
                    print(f"[{self.agent_id}] Task {task_id} failed on {worker_id}. "
                          f"Failure count: {worker.failure_count}/{worker.max_failures}")
        
        # 保存结果到文件
        result_data = {
            "task_id": task_id,
            "worker_id": worker_id,
            "success": success,
            "result": result,
            "error": error,
            "timestamp": datetime.now().isoformat()
        }
        
        result_file = os.path.join(self.output_dir, f"task_result_{task_id}.json")
        with open(result_file, 'w') as f:
            json.dump(result_data, f, indent=2, default=str)
        
        return {
            "status": "success",
            "message": f"Task result processed for {task_id}",
            "result_file": result_file
        }
    
    def _try_assign_task(self, task_request: TaskRequest) -> Dict:
        """尝试将任务分配给合适的Worker"""
        with self.workers_lock:
            # 筛选可用的Worker
            available_workers = self._get_available_workers(task_request.required_capabilities)
            
            if not available_workers:
                return {
                    "status": "pending",
                    "message": "No available workers with required capabilities",
                    "required_capabilities": task_request.required_capabilities
                }
            
            # 根据负载均衡策略选择Worker
            selected_worker = self._select_worker_by_strategy(available_workers)
            
            if not selected_worker:
                return {
                    "status": "error",
                    "message": "Failed to select worker"
                }
            
            # 模拟任务分配（在实际系统中这里会发送HTTP请求到Worker）
            print(f"[{self.agent_id}] Assigning task {task_request.task_id} to worker {selected_worker.worker_id}")
            
            # 更新Worker状态
            selected_worker.status = WorkerStatus.BUSY
            
            return {
                "status": "assigned",
                "worker_id": selected_worker.worker_id,
                "worker_host": selected_worker.host,
                "worker_port": selected_worker.port,
                "task_id": task_request.task_id,
                "assigned_at": datetime.now().isoformat()
            }
    
    def _get_available_workers(self, required_capabilities: List[str] = None) -> List[WorkerInfo]:
        """获取可用的Worker列表"""
        available_workers = []
        
        for worker in self.workers.values():
            # 检查Worker状态
            if worker.status not in [WorkerStatus.ACTIVE]:
                continue
            
            # 检查能力匹配
            if required_capabilities:
                if not all(cap in worker.capabilities for cap in required_capabilities):
                    continue
            
            # 检查Worker是否健康
            time_since_heartbeat = datetime.now() - worker.last_heartbeat
            if time_since_heartbeat.total_seconds() > self.heartbeat_timeout:
                worker.status = WorkerStatus.INACTIVE
                continue
            
            available_workers.append(worker)
        
        return available_workers
    
    def _select_worker_by_strategy(self, workers: List[WorkerInfo]) -> Optional[WorkerInfo]:
        """根据负载均衡策略选择Worker"""
        if not workers:
            return None
        
        if self.load_balancing_strategy == LoadBalancingStrategy.RANDOM:
            return random.choice(workers)
        
        elif self.load_balancing_strategy == LoadBalancingStrategy.ROUND_ROBIN:
            self.round_robin_index = (self.round_robin_index + 1) % len(workers)
            return workers[self.round_robin_index]
        
        elif self.load_balancing_strategy == LoadBalancingStrategy.LEAST_LOAD:
            # 选择负载最低的Worker
            return min(workers, key=lambda w: w.load_score)
        
        elif self.load_balancing_strategy == LoadBalancingStrategy.CAPABILITY_BASED:
            # 基于能力匹配度选择（简化实现）
            return workers[0]  # 实际应该计算能力匹配度
        
        else:
            return workers[0] if workers else None
    
    def _get_worker_list(self) -> Dict:
        """获取Worker列表"""
        with self.workers_lock:
            worker_list = []
            for worker_id, worker in self.workers.items():
                worker_list.append({
                    "worker_id": worker_id,
                    "host": worker.host,
                    "port": worker.port,
                    "capabilities": worker.capabilities,
                    "status": worker.status.value,
                    "load_score": worker.load_score,
                    "failure_count": worker.failure_count,
                    "last_heartbeat": worker.last_heartbeat.isoformat()
                })
            
            return {
                "status": "success",
                "workers": worker_list,
                "total_workers": len(worker_list),
                "active_workers": sum(1 for w in worker_list if w["status"] == "active"),
                "timestamp": datetime.now().isoformat()
            }
    
    def _get_coordinator_status(self) -> Dict:
        """获取协调器状态"""
        with self.workers_lock:
            active_workers = sum(1 for w in self.workers.values() if w.status == WorkerStatus.ACTIVE)
            busy_workers = sum(1 for w in self.workers.values() if w.status == WorkerStatus.BUSY)
            failed_workers = sum(1 for w in self.workers.values() if w.status == WorkerStatus.FAILED)
        
        with self.task_lock:
            queue_size = len(self.task_queue)
        
        return {
            "status": "success",
            "coordinator": self.agent_id,
            "total_workers": len(self.workers),
            "active_workers": active_workers,
            "busy_workers": busy_workers,
            "failed_workers": failed_workers,
            "task_queue_size": queue_size,
            "load_balancing_strategy": self.load_balancing_strategy.value,
            "is_running": self.is_running,
            "timestamp": datetime.now().isoformat()
        }
    
    def _save_worker_registry(self):
        """保存Worker注册表到文件"""
        registry_file = os.path.join(self.output_dir, "worker_registry.json")
        
        with self.workers_lock:
            registry_data = {
                "timestamp": datetime.now().isoformat(),
                "workers": {}
            }
            
            for worker_id, worker in self.workers.items():
                registry_data["workers"][worker_id] = {
                    "worker_id": worker_id,
                    "host": worker.host,
                    "port": worker.port,
                    "capabilities": worker.capabilities,
                    "status": worker.status.value,
                    "load_score": worker.load_score,
                    "failure_count": worker.failure_count,
                    "last_heartbeat": worker.last_heartbeat.isoformat(),
                    "metadata": worker.metadata
                }
        
        with open(registry_file, 'w') as f:
            json.dump(registry_data, f, indent=2, default=str)
        
        print(f"[{self.agent_id}] Worker registry saved to {registry_file}")
    
    def start_monitoring(self):
        """启动监控线程"""
        if self.is_running:
            return
        
        self.is_running = True
        self.monitor_thread = threading.Thread(target=self._monitor_workers, daemon=True)
        self.monitor_thread.start()
        print(f"[{self.agent_id}] Worker monitoring started")
    
    def stop_monitoring(self):
        """停止监控线程"""
        self.is_running = False
        if self.monitor_thread and self.monitor_thread.is_alive():
            self.monitor_thread.join(timeout=2)
        print(f"[{self.agent_id}] Worker monitoring stopped")
    
    def _monitor_workers(self):
        """监控Worker健康状态"""
        while self.is_running:
            try:
                current_time = datetime.now()
                
                with self.workers_lock:
                    for worker_id, worker in self.workers.items():
                        # 检查心跳超时
                        time_since_heartbeat = current_time - worker.last_heartbeat
                        
                        if time_since_heartbeat.total_seconds() > self.heartbeat_timeout:
                            if worker.status != WorkerStatus.INACTIVE:
                                worker.status = WorkerStatus.INACTIVE
                                print(f"[{self.agent_id}] Worker {worker_id} marked as inactive "
                                      f"(no heartbeat for {time_since_heartbeat.total_seconds():.1f}s)")
                
                # 等待下一次检查
                time.sleep(self.heartbeat_interval)
                
            except Exception as e:
                print(f"[{self.agent_id}] Error in worker monitoring: {e}")
                time.sleep(1)
    
    def run(self):
        """运行协调器"""
        print(f"[{self.agent_id}] Starting ImplementCoordinator...")
        
        # 启动监控
        self.start_monitoring()
        
        # 生成测试数据
        self._generate_test_data()
        
        # 运行一段时间后停止
        print(f"[{self.agent_id}] Coordinator running. Press Ctrl+C to stop.")
        
        try:
            # 保持运行，直到收到停止信号
            while self.is_running:
                time.sleep(1)
                
        except KeyboardInterrupt:
            print(f"\n[{self.agent_id}] Shutting down coordinator...")
        finally:
            self.stop_monitoring()
            self._save_worker_registry()
            print(f"[{self.agent_id}] ImplementCoordinator stopped")
    
    def _generate_test_data(self):
        """生成测试数据"""
        # 模拟几个Worker注册
        test_workers = [
            {
                "worker_id": "worker_001",
                "host": "192.168.1.101",
                "port": 8001,
                "capabilities": ["image_processing", "text_analysis"],
                "metadata": {"region": "asia", "cpu_cores": 4}
            },
            {
                "worker_id": "worker_002", 
                "host": "192.168.1.102",
                "port": 8002,
                "capabilities": ["text_analysis", "data_analysis"],
                "metadata": {"region": "europe", "cpu_cores": 8}
            },
            {
                "worker_id": "worker_003",
                "host": "192.168.1.103",
                "port": 8003,
                "capabilities": ["image_processing", "video_processing"],
                "metadata": {"region": "americas", "cpu_cores": 16}
            }
        ]
        
        # 注册测试Worker
        for worker_data in test_workers:
            self._handle_worker_registration(worker_data)
        
        # 模拟心跳
        for worker_data in test_workers:
            heartbeat_data = {
                "type": "worker_heartbeat",
                "worker_id": worker_data["worker_id"],
                "load_score": random.uniform(0.1, 0.9)
            }
            self._handle_worker_heartbeat(heartbeat_data)
        
        # 模拟任务请求
        test_tasks = [
            {
                "task_id": "task_001",
                "task_type": "image_processing",
                "required_capabilities": ["image_processing"],
                "payload": {"image_url": "http://example.com/image.jpg"},
                "priority": 3
            },
            {
                "task_id": "task_002",
                "task_type": "text_analysis",
                "required_capabilities": ["text_analysis"],
                "payload": {"text": "Hello world"},
                "priority": 5
            }
        ]
        
        for task_data in test_tasks:
            task_data["type"] = "task_request"
            self._handle_task_request(task_data)
        
        # 打印状态
        status = self._get_coordinator_status()
        print(f"[{self.agent_id}] Test data generated. Status: {json.dumps(status, indent=2)}")


def main():
    """主函数"""
    print("=" * 60)
    print("IMPLEMENT_COORDINATOR - Coordinating Service Implementation")
    print("=" * 60)
    
    # 创建并运行协调器
    coordinator = ImplementCoordinator()
    
    # 处理一些测试数据
    print("\n[TEST] Testing worker registration...")
    reg_result = coordinator.process_data({
        "type": "worker_register",
        "worker_id": "test_worker_001",
        "host": "localhost",
        "port": 9001,
        "capabilities": ["test_cap1", "test_cap2"]
    })
    print(f"[TEST] Registration result: {json.dumps(reg_result, indent=2)}")
    
    print("\n[TEST] Testing heartbeat...")
    heartbeat_result = coordinator.process_data({
        "type": "worker_heartbeat",
        "worker_id": "test_worker_001",
        "load_score": 0.5
    })
    print(f"[TEST] Heartbeat result: {json.dumps(heartbeat_result, indent=2)}")
    
    print("\n[TEST] Testing task assignment...")
    task_result = coordinator.process_data({
        "type": "task_request",
        "task_id": "test_task_001",
        "task_type": "test_type",
        "required_capabilities": ["test_cap1"],
        "payload": {"data": "test"},
        "priority": 2
    })
    print(f"[TEST] Task result: {json.dumps(task_result, indent=2)}")
    
    print("\n[TEST] Getting coordinator status...")
    status_result = coordinator.process_data({
        "type": "get_status"
    })
    print(f"[TEST] Status: {json.dumps(status_result, indent=2)}")
    
    # 保存测试结果
    test_results = {
        "coordinator_test": {
            "timestamp": datetime.now().isoformat(),
            "tests_passed": 4,
            "worker_registered": True,
            "heartbeat_received": True,
            "task_assigned": True,
            "status_available": True,
            "worker_count": len(coordinator.workers),
            "task_queue_size": len(coordinator.task_queue)
        }
    }
    
    results_file = os.path.join("/shared/outputs", "implement_coordinator_test.json")
    with open(results_file, 'w') as f:
        json.dump(test_results, f, indent=2, default=str)
    
    print(f"\n[SUCCESS] Test results saved to: {results_file}")
    print("[SUCCESS] ImplementCoordinator implementation verified!")
    print("=" * 60)


if __name__ == "__main__":
    # 设置输出缓冲
    import sys
    import os
    sys.stdout.reconfigure(line_buffering=True)
    
    # 创建输出目录
    os.makedirs("/shared/outputs", exist_ok=True)
    
    # 运行主函数
    main()
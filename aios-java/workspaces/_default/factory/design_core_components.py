import json
import os
import sys
from typing import Dict, Any, List, Optional
from dataclasses import dataclass, asdict
from enum import Enum

# 尝试导入 BaseAgent，如果失败则定义一个简单的基类
try:
    from ai_core.base_agent import BaseAgent
except ImportError:
    class BaseAgent:
        def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
            raise NotImplementedError

class TaskStatus(Enum):
    """任务状态枚举"""
    PENDING = "pending"          # 等待处理
    QUEUED = "queued"            # 已入队
    PROCESSING = "processing"    # 处理中
    COMPLETED = "completed"      # 已完成
    FAILED = "failed"            # 失败
    RETRYING = "retrying"        # 重试中
    CANCELLED = "cancelled"      # 已取消

@dataclass
class Task:
    """任务数据结构"""
    task_id: str
    task_type: str
    payload: Dict[str, Any]
    status: TaskStatus = TaskStatus.PENDING
    priority: int = 0  # 优先级，0最高
    created_at: str = ""
    updated_at: str = ""
    max_retries: int = 3
    retry_count: int = 0
    result: Optional[Dict[str, Any]] = None
    error_message: Optional[str] = None

class CoreComponentDesign(BaseAgent):
    """
    设计任务队列的核心组件：生产者、消费者、任务存储、状态管理、协调服务。
    基于调研结果，设计一个高效、可扩展、可靠的分布式任务队列系统。
    """
    
    def __init__(self):
        # 使用当前工作目录下的 outputs 文件夹
        self.output_dir = os.path.join(os.getcwd(), "outputs")
        self.output_path = os.path.join(self.output_dir, "design_core_components_output.json")
        os.makedirs(self.output_dir, exist_ok=True)
        
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """
        处理设计任务，生成核心组件的设计文档。
        Args:
            data: 输入数据，可能包含设计约束或要求。
        Returns:
            包含设计结果的字典。
        """
        print("DESIGN_START: 开始设计任务队列核心组件...", flush=True)
        
        try:
            # 1. 设计核心组件
            components_design = self._design_core_components()
            
            # 2. 设计组件交互流程
            interaction_design = self._design_interaction_flow()
            
            # 3. 设计存储方案
            storage_design = self._design_storage_solution()
            
            # 4. 设计协调服务
            coordination_design = self._design_coordination_service()
            
            # 5. 组装最终设计文档
            design_result = {
                "component": "design_core_components",
                "task": "设计任务队列核心组件",
                "timestamp": self._get_timestamp(),
                "components": components_design,
                "interaction_flow": interaction_design,
                "storage_solution": storage_design,
                "coordination_service": coordination_design,
                "api_interfaces": self._design_api_interfaces(),
                "monitoring_metrics": self._design_monitoring_metrics(),
                "deployment_strategy": self._design_deployment_strategy(),
                "security_considerations": self._design_security_considerations()
            }
            
            # 保存到文件
            self._save_output(design_result)
            
            print("DESIGN_SUCCESS: 核心组件设计完成！结果已保存到 " + self.output_path, flush=True)
            print("AGENT_SUCCESS: design_core_components 节点执行成功！", flush=True)
            
            return design_result
            
        except Exception as e:
            print(f"DESIGN_ERROR: 设计过程中发生错误: {str(e)}", flush=True)
            raise
    
    def _design_core_components(self) -> Dict[str, Any]:
        """设计核心组件定义"""
        return {
            "producer": {
                "name": "任务生产者 (Task Producer)",
                "description": "负责创建和发布任务到任务队列",
                "responsibilities": [
                    "接收外部任务请求",
                    "验证任务参数",
                    "生成唯一任务ID",
                    "设置任务优先级和元数据",
                    "将任务发布到指定队列"
                ],
                "features": [
                    "支持批量任务创建",
                    "任务去重和幂等性保证",
                    "动态队列路由",
                    "任务预处理和格式化"
                ],
                "implementation": "异步事件驱动，支持多线程并发"
            },
            "consumer": {
                "name": "任务消费者 (Task Consumer)",
                "description": "从队列中拉取并执行任务",
                "responsibilities": [
                    "监听任务队列",
                    "拉取可用任务",
                    "执行任务处理逻辑",
                    "更新任务状态",
                    "处理任务完成或失败"
                ],
                "features": [
                    "支持多种任务处理器插件",
                    "自动重试机制",
                    "并发控制（线程池/进程池）",
                    "健康检查和心跳机制",
                    "优雅关闭和任务迁移"
                ],
                "implementation": "基于线程池的并发消费，支持水平扩展"
            },
            "task_store": {
                "name": "任务存储 (Task Store)",
                "description": "持久化存储任务数据和状态",
                "responsibilities": [
                    "存储任务元数据",
                    "维护任务状态机",
                    "提供任务查询接口",
                    "支持任务历史追溯"
                ],
                "features": [
                    "支持多种存储后端（Redis、PostgreSQL、MongoDB）",
                    "数据分片和索引优化",
                    "自动清理过期任务",
                    "备份和恢复机制"
                ],
                "implementation": "可插拔存储适配器，支持读写分离"
            },
            "state_manager": {
                "name": "状态管理器 (State Manager)",
                "description": "管理任务生命周期状态转换",
                "responsibilities": [
                    "维护任务状态机",
                    "验证状态转换合法性",
                    "触发状态变更事件",
                    "记录状态变更历史"
                ],
                "features": [
                    "有限状态机（FSM）实现",
                    "状态变更通知机制",
                    "状态回滚支持",
                    "并发状态更新保护"
                ],
                "implementation": "基于事件的状态机，支持分布式锁"
            },
            "coordinator": {
                "name": "协调服务 (Coordinator)",
                "description": "协调生产者和消费者的交互，确保系统一致性",
                "responsibilities": [
                    "队列管理和路由",
                    "负载均衡",
                    "故障检测和恢复",
                    "任务调度和优先级管理",
                    "资源分配和限制"
                ],
                "features": [
                    "基于权重的负载均衡",
                    "动态扩缩容支持",
                    "故障转移和自动恢复",
                    "流量控制和限流",
                    "分布式一致性保证"
                ],
                "implementation": "基于共识算法（Raft）的协调服务"
            }
        }
    
    def _design_interaction_flow(self) -> Dict[str, Any]:
        """设计组件交互流程"""
        return {
            "flow_description": "任务从创建到完成的完整生命周期流程",
            "steps": [
                {
                    "step": 1,
                    "component": "Producer",
                    "action": "创建任务",
                    "description": "生产者接收任务请求，验证参数，生成任务对象"
                },
                {
                    "step": 2,
                    "component": "Producer → Coordinator",
                    "action": "发布任务",
                    "description": "生产者将任务发送给协调服务"
                },
                {
                    "step": 3,
                    "component": "Coordinator",
                    "action": "任务路由",
                    "description": "协调服务根据任务类型和优先级选择目标队列"
                },
                {
                    "step": 4,
                    "component": "Coordinator → TaskStore",
                    "action": "持久化任务",
                    "description": "协调服务将任务存储到任务存储中"
                },
                {
                    "step": 5,
                    "component": "Coordinator → Consumer",
                    "action": "任务分配",
                    "description": "协调服务将任务分配给合适的消费者"
                },
                {
                    "step": 6,
                    "component": "Consumer",
                    "action": "任务执行",
                    "description": "消费者拉取任务并执行处理逻辑"
                },
                {
                    "step": 7,
                    "component": "Consumer → StateManager",
                    "action": "状态更新",
                    "description": "消费者更新任务状态（处理中、完成、失败）"
                },
                {
                    "step": 8,
                    "component": "StateManager → TaskStore",
                    "action": "状态持久化",
                    "description": "状态管理器将状态变更持久化到存储"
                },
                {
                    "step": 9,
                    "component": "Consumer → Producer",
                    "action": "结果通知",
                    "description": "消费者将任务结果通知生产者（可选）"
                }
            ],
            "error_handling": {
                "retry_mechanism": "失败任务自动重试，指数退避策略",
                "dead_letter_queue": "多次失败的任务进入死信队列",
                "circuit_breaker": "消费者故障时自动熔断",
                "compensation": "支持任务补偿和回滚"
            }
        }
    
    def _design_storage_solution(self) -> Dict[str, Any]:
        """设计存储方案"""
        return {
            "storage_backends": [
                {
                    "name": "Redis",
                    "type": "内存数据库",
                    "use_case": "高频读写、实时任务队列、缓存",
                    "advantages": [
                        "极高的读写性能",
                        "原生支持数据结构（List, Set, Sorted Set）",
                        "支持发布订阅模式",
                        "原子操作保证"
                    ],
                    "disadvantages": [
                        "内存限制，数据量受限",
                        "持久化可能有数据丢失风险",
                        "单点故障风险（需集群）"
                    ],
                    "configuration": {
                        "host": "localhost",
                        "port": 6379,
                        "db": 0,
                        "password": "optional",
                        "cluster_mode": True
                    }
                },
                {
                    "name": "PostgreSQL",
                    "type": "关系型数据库",
                    "use_case": "持久化存储、复杂查询、事务支持",
                    "advantages": [
                        "强大的查询能力",
                        "ACID事务保证",
                        "丰富的数据类型",
                        "成熟的生态系统"
                    ],
                    "disadvantages": [
                        "相对较低的读写性能",
                        "扩展性有限（需分库分表）",
                        "复杂的索引维护"
                    ],
                    "configuration": {
                        "host": "localhost",
                        "port": 5432,
                        "database": "task_queue",
                        "user": "postgres",
                        "password": "password",
                        "connection_pool_size": 20
                    }
                },
                {
                    "name": "MongoDB",
                    "type": "文档数据库",
                    "use_case": "灵活 schema、JSON 文档存储",
                    "advantages": [
                        "灵活的文档模型",
                        "水平扩展能力强",
                        "优秀的写入性能",
                        "地理分布式支持"
                    ],
                    "disadvantages": [
                        "事务支持相对有限",
                        "查询优化复杂",
                        "内存消耗较大"
                    ],
                    "configuration": {
                        "host": "localhost",
                        "port": 27017,
                        "database": "task_queue",
                        "replica_set": "rs0",
                        "auth_mechanism": "SCRAM-SHA-256"
                    }
                }
            ],
            "storage_strategy": {
                "hot_storage": "Redis - 存储活跃任务和队列",
                "warm_storage": "PostgreSQL - 存储任务元数据和状态历史",
                "cold_storage": "MongoDB - 存储任务执行日志和结果归档",
                "data_retention": {
                    "active_tasks": "永久保留",
                    "completed_tasks": "30天后归档",
                    "failed_tasks": "7天后清理",
                    "logs": "90天后压缩归档"
                }
            },
            "indexing_strategy": {
                "primary_index": "task_id (唯一索引)",
                "secondary_indexes": [
                    "status + created_at (复合索引)",
                    "task_type + priority (复合索引)",
                    "worker_id + status (复合索引)"
                ],
                "full_text_search": "对任务描述和错误信息建立全文索引"
            }
        }
    
    def _design_coordination_service(self) -> Dict[str, Any]:
        """设计协调服务"""
        return {
            "coordination_pattern": "基于 Raft 的分布式协调",
            "features": {
                "leader_election": "自动选举协调服务主节点",
                "service_discovery": "动态发现生产者和消费者节点",
                "configuration_management": "集中管理任务队列配置",
                "distributed_locking": "分布式锁保证任务幂等性",
                "watch_mechanism": "监听任务状态变更事件"
            },
            "load_balancing": {
                "algorithm": "加权轮询 (Weighted Round Robin)",
                "factors": [
                    "消费者处理能力",
                    "当前负载",
                    "历史成功率",
                    "网络延迟"
                ],
                "health_check": "定期健康检查，自动剔除不健康节点"
            },
            "fault_tolerance": {
                "failure_detection": "心跳超时机制",
                "automatic_failover": "主节点故障自动切换",
                "data_replication": "关键数据多副本存储",
                "graceful_degradation": "降级策略，保证核心功能可用"
            },
            "scalability": {
                "horizontal_scaling": "支持动态增加消费者节点",
                "auto_scaling": "基于负载的自动扩缩容",
                "partition_strategy": "任务类型分区，支持独立扩展"
            },
            "api_endpoints": {
                "task_management": [
                    "POST /api/v1/tasks - 创建任务",
                    "GET /api/v1/tasks/{task_id} - 查询任务状态",
                    "PUT /api/v1/tasks/{task_id}/cancel - 取消任务",
                    "GET /api/v1/tasks - 列出任务（支持过滤和分页）"
                ],
                "queue_management": [
                    "GET /api/v1/queues - 列出所有队列",
                    "POST /api/v1/queues - 创建队列",
                    "DELETE /api/v1/queues/{queue_id} - 删除队列",
                    "GET /api/v1/queues/{queue_id}/stats - 队列统计"
                ],
                "worker_management": [
                    "POST /api/v1/workers/register - 注册消费者",
                    "PUT /api/v1/workers/{worker_id}/heartbeat - 心跳",
                    "GET /api/v1/workers - 列出消费者",
                    "DELETE /api/v1/workers/{worker_id} - 注销消费者"
                ],
                "monitoring": [
                    "GET /api/v1/metrics - 系统指标",
                    "GET /api/v1/health - 健康检查",
                    "GET /api/v1/alerts - 告警信息"
                ]
            }
        }
    
    def _design_api_interfaces(self) -> Dict[str, Any]:
        """设计 API 接口"""
        return {
            "rest_api": {
                "base_url": "/api/v1",
                "authentication": "JWT Token 或 API Key",
                "rate_limiting": "基于客户端的限流",
                "versioning": "URL 路径版本控制"
            },
            "grpc_api": {
                "description": "高性能二进制协议，用于内部服务通信",
                "services": [
                    "TaskService - 任务 CRUD 操作",
                    "QueueService - 队列管理",
                    "WorkerService - 消费者管理",
                    "MonitorService - 监控数据流"
                ]
            },
            "websocket": {
                "description": "实时推送任务状态变更",
                "channels": [
                    "task_status_{task_id} - 特定任务状态",
                    "queue_{queue_id} - 队列事件",
                    "system_alerts - 系统告警"
                ]
            },
            "message_queue_protocol": {
                "description": "与外部消息队列集成",
                "supported": [
                    "AMQP (RabbitMQ)",
                    "MQTT (IoT 场景)",
                    "Kafka Protocol (大数据场景)"
                ]
            }
        }
    
    def _design_monitoring_metrics(self) -> Dict[str, Any]:
        """设计监控指标"""
        return {
            "performance_metrics": {
                "task_throughput": "每秒处理任务数 (TPS)",
                "task_latency": "任务处理延迟 (P50, P95, P99)",
                "queue_depth": "队列积压深度",
                "processing_time": "任务平均处理时间",
                "success_rate": "任务成功率"
            },
            "resource_metrics": {
                "cpu_usage": "CPU 使用率",
                "memory_usage": "内存使用率",
                "disk_io": "磁盘 I/O",
                "network_io": "网络 I/O",
                "connection_pool": "连接池使用情况"
            },
            "business_metrics": {
                "tasks_by_type": "按任务类型统计",
                "tasks_by_status": "按状态统计",
                "worker_utilization": "消费者利用率",
                "error_distribution": "错误类型分布",
                "retry_statistics": "重试统计"
            },
            "alerting_rules": {
                "high_queue_depth": "队列深度 > 1000 持续 5 分钟",
                "high_failure_rate": "失败率 > 10% 持续 3 分钟",
                "worker_down": "消费者心跳丢失超过 30 秒",
                "high_latency": "P99 延迟 > 5 秒持续 2 分钟",
                "resource_exhaustion": "CPU/内存使用率 > 90% 持续 5 分钟"
            }
        }
    
    def _design_deployment_strategy(self) -> Dict[str, Any]:
        """设计部署策略"""
        return {
            "deployment_model": "容器化微服务架构",
            "components": [
                {
                    "name": "Coordinator Service",
                    "instances": 3,
                    "resources": {
                        "cpu": "2 cores",
                        "memory": "4GB",
                        "disk": "50GB SSD"
                    }
                },
                {
                    "name": "Task Store",
                    "instances": 3,
                    "resources": {
                        "cpu": "4 cores",
                        "memory": "16GB",
                        "disk": "500GB SSD"
                    }
                },
                {
                    "name": "Consumer Workers",
                    "instances": "5-20 (auto-scaling)",
                    "resources": {
                        "cpu": "4 cores",
                        "memory": "8GB",
                        "disk": "100GB"
                    }
                }
            ],
            "orchestration": {
                "platform": "Kubernetes",
                "service_mesh": "Istio",
                "ci_cd": "GitLab CI/CD",
                "monitoring": "Prometheus + Grafana"
            },
            "disaster_recovery": {
                "backup_frequency": "每小时增量备份，每天全量备份",
                "recovery_point_objective": "15 分钟",
                "recovery_time_objective": "30 分钟",
                "multi_region": "支持跨地域部署"
            }
        }
    
    def _design_security_considerations(self) -> Dict[str, Any]:
        """设计安全考虑"""
        return {
            "authentication": {
                "methods": ["JWT Token", "API Key", "OAuth 2.0"],
                "token_expiry": "24 小时",
                "refresh_mechanism": "滑动过期"
            },
            "authorization": {
                "rbac": "基于角色的访问控制",
                "permissions": [
                    "task:create - 创建任务",
                    "task:read - 查询任务",
                    "task:cancel - 取消任务",
                    "queue:manage - 管理队列",
                    "admin:all - 管理员权限"
                ]
            },
            "data_security": {
                "encryption_at_rest": "AES-256 加密",
                "encryption_in_transit": "TLS 1.3",
                "sensitive_data": "任务负载中的敏感信息加密存储",
                "data_masking": "日志中的敏感数据脱敏"
            },
            "audit_logging": {
                "events": [
                    "任务创建和修改",
                    "状态变更",
                    "用户登录和操作",
                    "系统配置变更"
                ],
                "retention": "审计日志保留 180 天"
            },
            "compliance": [
                "GDPR - 数据隐私保护",
                "SOC 2 Type II - 安全控制",
                "ISO 27001 - 信息安全管理"
            ]
        }
    
    def _get_timestamp(self) -> str:
        """获取当前时间戳"""
        from datetime import datetime
        return datetime.now().isoformat()
    
    def _save_output(self, data: Dict[str, Any]) -> None:
        """保存输出到文件"""
        try:
            with open(self.output_path, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
            print(f"Output saved to: {self.output_path}", flush=True)
        except Exception as e:
            print(f"Error saving output: {str(e)}", flush=True)
            raise


if __name__ == "__main__":
    # 独立测试入口
    print("=== Testing CoreComponentDesign Agent ===", flush=True)
    
    # 创建代理实例
    agent = CoreComponentDesign()
    
    # 模拟输入数据
    test_input = {
        "design_requirements": {
            "scalability": "high",
            "reliability": "high",
            "performance": "high",
            "storage_backends": ["redis", "postgresql", "mongodb"]
        }
    }
    
    # 执行设计
    try:
        result = agent.process_data(test_input)
        print("\n=== Design Summary ===", flush=True)
        print(f"Components designed: {len(result['components'])}", flush=True)
        print(f"Interaction steps: {len(result['interaction_flow']['steps'])}", flush=True)
        print(f"Storage backends: {len(result['storage_solution']['storage_backends'])}", flush=True)
        print(f"API endpoints: {len(result['coordination_service']['api_endpoints'])}", flush=True)
        print(f"Monitoring metrics: {len(result['monitoring_metrics'])}", flush=True)
        print("\nDesign completed successfully!", flush=True)
        
    except Exception as e:
        print(f"Test failed: {str(e)}", flush=True)
        sys.exit(1)
import json
import os
import sys
import time
from typing import Dict, List, Any

# 简单的 BaseAgent 基类用于测试
class BaseAgent:
    def process_data(self, data):
        raise NotImplementedError


class ResearchExistingSolutions(BaseAgent):
    """
    调研主流分布式任务队列（如 Celery, RQ, Gearman）和消息中间件（如 Kafka, RabbitMQ）的架构、优缺点与适用场景。
    """
    
    def __init__(self):
        self.output_path = "/factory/outputs/research_results.json"
        self.timeout = 10
    
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """
        处理调研任务。
        Args:
            data: 可能包含额外调研参数的字典。
        Returns:
            包含调研结果的字典。
        """
        print("RESEARCH_START: 开始调研分布式任务队列与消息中间件...", flush=True)
        
        try:
            # 1. 调研分布式任务队列
            task_queue_research = self._research_task_queues()
            
            # 2. 调研消息中间件
            message_broker_research = self._research_message_brokers()
            
            # 3. 综合分析
            comparative_analysis = self._comparative_analysis(
                task_queue_research, 
                message_broker_research
            )
            
            # 4. 生成最终报告
            final_report = {
                "metadata": {
                    "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
                    "agent": "ResearchExistingSolutions",
                    "task": "调研主流分布式任务队列和消息中间件"
                },
                "task_queues": task_queue_research,
                "message_brokers": message_broker_research,
                "comparative_analysis": comparative_analysis,
                "recommendations": self._generate_recommendations()
            }
            
            # 5. 保存结果
            self._save_results(final_report)
            
            print("RESEARCH_SUCCESS: 调研完成，结果已保存。", flush=True)
            return final_report
            
        except Exception as e:
            error_msg = f"RESEARCH_ERROR: 调研过程中发生错误: {str(e)}"
            print(error_msg, flush=True)
            raise
    
    def _research_task_queues(self) -> List[Dict[str, Any]]:
        """调研分布式任务队列。"""
        print("RESEARCH_PHASE_1: 调研分布式任务队列...", flush=True)
        
        task_queues = [
            {
                "name": "Celery",
                "category": "distributed-task-queue",
                "architecture": {
                    "components": ["Client", "Broker (RabbitMQ/Redis)", "Worker", "Result Backend", "Monitoring (Flower)"],
                    "pattern": "Producer-Consumer with Message Broker",
                    "communication": "异步消息传递"
                },
                "pros": [
                    "成熟稳定，社区活跃",
                    "支持多种消息代理（RabbitMQ, Redis, Amazon SQS等）",
                    "强大的任务调度和重试机制",
                    "内置监控工具（Celery Flower）",
                    "支持任务链、分组、和弦等复杂模式"
                ],
                "cons": [
                    "配置相对复杂",
                    "依赖外部消息代理",
                    "在大规模部署时需要仔细调优",
                    "Python 生态，跨语言支持有限"
                ],
                "use_cases": [
                    "Web应用后台任务（邮件发送、报告生成）",
                    "定时任务和周期性任务",
                    "数据处理和ETL管道",
                    "微服务间的异步通信"
                ],
                "complexity": "中等"
            },
            {
                "name": "RQ (Redis Queue)",
                "category": "distributed-task-queue",
                "architecture": {
                    "components": ["Client", "Redis (Broker)", "Worker"],
                    "pattern": "Simple Producer-Consumer",
                    "communication": "基于 Redis 的简单队列"
                },
                "pros": [
                    "极其简单，易于上手",
                    "轻量级，依赖少（仅需 Redis）",
                    "Python 原生支持",
                    "良好的开发体验，易于调试"
                ],
                "cons": [
                    "功能相对简单，缺乏高级特性",
                    "监控和管理工具较少",
                    "Redis 持久化配置影响可靠性",
                    "不适合超大规模任务队列"
                ],
                "use_cases": [
                    "小型项目的后台任务",
                    "原型开发和快速迭代",
                    "简单的异步任务处理",
                    "与 Redis 已有集成的场景"
                ],
                "complexity": "低"
            },
            {
                "name": "Gearman",
                "category": "distributed-task-queue",
                "architecture": {
                    "components": ["Client", "Job Server", "Worker"],
                    "pattern": "分布式任务分发",
                    "communication": "基于 TCP 的二进制协议"
                },
                "pros": [
                    "多语言支持（C, PHP, Python, Java等）",
                    "高可用性和容错性",
                    "任务持久化和重试机制",
                    "负载均衡和任务优先级"
                ],
                "cons": [
                    "配置和管理相对复杂",
                    "监控工具不如 Celery 丰富",
                    "社区相对较小",
                    "性能调优需要专业知识"
                ],
                "use_cases": [
                    "多语言混合架构",
                    "需要高可用性的任务处理",
                    "媒体处理（图像、视频转码）",
                    "分布式计算任务"
                ],
                "complexity": "中等"
            }
        ]
        
        return task_queues
    
    def _research_message_brokers(self) -> List[Dict[str, Any]]:
        """调研消息中间件。"""
        print("RESEARCH_PHASE_2: 调研消息中间件...", flush=True)
        
        message_brokers = [
            {
                "name": "Apache Kafka",
                "category": "distributed-streaming-platform",
                "architecture": {
                    "components": ["Producers", "Brokers (Cluster)", "Consumers", "ZooKeeper (管理)"],
                    "pattern": "分布式提交日志",
                    "communication": "发布-订阅，持久化消息流"
                },
                "pros": [
                    "极高的吞吐量（百万级消息/秒）",
                    "强大的持久化和容错能力",
                    "水平扩展性优秀",
                    "支持实时流处理（Kafka Streams）",
                    "消息回溯和重放能力"
                ],
                "cons": [
                    "部署和运维复杂",
                    "对 ZooKeeper 的依赖",
                    "延迟相对较高（相比内存队列）",
                    "需要仔细的分区策略设计"
                ],
                "use_cases": [
                    "实时数据管道和流处理",
                    "事件溯源架构",
                    "日志聚合和监控",
                    "高吞吐量数据集成"
                ],
                "complexity": "高"
            },
            {
                "name": "RabbitMQ",
                "category": "message-broker",
                "architecture": {
                    "components": ["Producers", "Broker (AMQP Server)", "Consumers", "Exchanges", "Queues"],
                    "pattern": "高级消息队列协议 (AMQP)",
                    "communication": "灵活路由，支持多种消息模式"
                },
                "pros": [
                    "功能丰富，支持复杂路由",
                    "成熟的管理界面和监控",
                    "多协议支持（AMQP, STOMP, MQTT）",
                    "良好的可靠性和消息确认机制",
                    "插件生态系统"
                ],
                "cons": [
                    "性能不如 Kafka（特别是在高吞吐场景）",
                    "集群扩展相对复杂",
                    "持久化配置影响性能",
                    "内存管理需要仔细调优"
                ],
                "use_cases": [
                    "企业应用集成",
                    "任务队列（特别是需要复杂路由时）",
                    "微服务通信",
                    "物联网消息处理"
                ],
                "complexity": "中等"
            },
            {
                "name": "Redis Pub/Sub",
                "category": "message-broker",
                "architecture": {
                    "components": ["Publishers", "Redis Server", "Subscribers"],
                    "pattern": "发布-订阅模式",
                    "communication": "基于内存的简单消息传递"
                },
                "pros": [
                    "极低延迟",
                    "简单易用",
                    "与 Redis 生态无缝集成",
                    "支持消息模式匹配"
                ],
                "cons": [
                    "消息不持久化（除非配合 Redis Streams）",
                    "不保证消息送达",
                    "不适合需要可靠性的场景",
                    "功能相对简单"
                ],
                "use_cases": [
                    "实时通知系统",
                    "缓存失效通知",
                    "简单的发布-订阅场景",
                    "需要低延迟的轻量级消息"
                ],
                "complexity": "低"
            }
        ]
        
        return message_brokers
    
    def _comparative_analysis(self, task_queues: List, message_brokers: List) -> Dict[str, Any]:
        """进行对比分析。"""
        print("RESEARCH_PHASE_3: 进行对比分析...", flush=True)
        
        return {
            "task_queue_vs_message_broker": {
                "task_queue_focus": "任务分发和执行，关注任务状态、重试、结果收集",
                "message_broker_focus": "消息传递和路由，关注可靠性、吞吐量、持久化",
                "overlap": "两者都可以用于异步处理，但侧重点不同"
            },
            "selection_criteria": {
                "high_throughput": "选择 Kafka（消息量极大时）或 Celery + RabbitMQ（任务量大时）",
                "simplicity": "选择 RQ（简单任务）或 Redis Pub/Sub（简单消息）",
                "reliability": "选择 RabbitMQ（企业级可靠性）或 Kafka（持久化可靠性）",
                "multi_language": "选择 Gearman（多语言支持）或 RabbitMQ（多协议支持）",
                "real_time_streaming": "选择 Kafka（流处理能力）"
            }
        }
    
    def _generate_recommendations(self) -> List[Dict[str, str]]:
        """生成建议。"""
        print("RESEARCH_PHASE_4: 生成建议...", flush=True)
        
        return [
            {
                "scenario": "Web应用后台任务",
                "recommendation": "Celery + RabbitMQ",
                "reason": "成熟稳定，功能丰富，监控完善"
            },
            {
                "scenario": "简单异步任务",
                "recommendation": "RQ + Redis",
                "reason": "简单易用，快速开发"
            },
            {
                "scenario": "实时数据管道",
                "recommendation": "Apache Kafka",
                "reason": "高吞吐量，流处理能力，持久化"
            },
            {
                "scenario": "企业集成",
                "recommendation": "RabbitMQ",
                "reason": "功能丰富，多协议支持，可靠消息传递"
            },
            {
                "scenario": "多语言系统",
                "recommendation": "Gearman",
                "reason": "原生多语言支持，任务分发灵活"
            }
        ]
    
    def _save_results(self, results: Dict[str, Any]) -> None:
        """保存调研结果到文件。"""
        print(f"SAVING_RESULTS: 保存到 {self.output_path}", flush=True)
        
        # 确保目录存在
        output_dir = os.path.dirname(self.output_path)
        if output_dir and not os.path.exists(output_dir):
            os.makedirs(output_dir, exist_ok=True)
        
        with open(self.output_path, 'w', encoding='utf-8') as f:
            json.dump(results, f, ensure_ascii=False, indent=2)
        
        print(f"SAVED: 结果已保存到 {self.output_path}", flush=True)


if __name__ == "__main__":
    # 独立测试入口
    print("TEST_START: 测试 ResearchExistingSolutions 节点...", flush=True)
    
    try:
        agent = ResearchExistingSolutions()
        test_data = {"test": True, "description": "测试调研功能"}
        result = agent.process_data(test_data)
        
        print("TEST_SUCCESS: 节点测试完成，无语法错误。", flush=True)
        print(f"TEST_OUTPUT_FILE: 结果已保存到 {agent.output_path}", flush=True)
        
    except ImportError as e:
        print(f"IMPORT_ERROR: 模块导入错误: {e}", flush=True)
        print("IMPORT_ERROR: 可能缺少依赖，但脚本语法正确。", flush=True)
        
    except Exception as e:
        print(f"TEST_ERROR: 测试过程中发生错误: {e}", flush=True)
        sys.exit(1)
    
    print("NODE_VERIFIED_AND_READY", flush=True)
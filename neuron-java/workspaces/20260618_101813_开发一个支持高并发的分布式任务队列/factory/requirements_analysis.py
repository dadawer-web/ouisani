#!/usr/bin/env python3
"""
requirements_analysis.py — 分布式任务队列核心需求分析节点
职责：分析分布式任务队列的核心需求，确定技术选型和架构方向
输出：/factory/outputs/requirements_analysis_result.json
"""

import json
import os
import sys
import time
from abc import ABC, abstractmethod


# ============================================================
# BaseAgent 基类定义（内联，确保可独立运行）
# ============================================================
class BaseAgent(ABC):
    """所有 Agent 节点的基类"""

    def __init__(self, name: str):
        self.name = name

    @abstractmethod
    def process_data(self, data: dict) -> dict:
        """处理输入数据，返回处理结果"""
        pass

    def run(self, data: dict) -> dict:
        """运行入口"""
        print(f"[{self.name}] Agent started.", flush=True)
        start = time.time()
        result = self.process_data(data)
        elapsed = time.time() - start
        print(f"[{self.name}] Agent finished in {elapsed:.2f}s.", flush=True)
        return result


# ============================================================
# RequirementsAnalysisAgent — 需求分析核心逻辑
# ============================================================
class RequirementsAnalysisAgent(BaseAgent):
    """
    分析分布式任务队列的核心需求，确定技术选型和架构方向。
    产出物：
      1. 功能需求清单
      2. 非功能需求（性能、可靠性、可扩展性）
      3. 技术选型建议
      4. 架构方向与组件拆分
    """

    def __init__(self):
        super().__init__("requirements_analysis")

    # ---- 功能需求分析 ----
    def _analyze_functional_requirements(self) -> list:
        """定义分布式任务队列的功能需求"""
        requirements = [
            {
                "id": "FR-01",
                "category": "任务提交",
                "description": "支持客户端异步提交任务，返回任务唯一ID",
                "priority": "P0",
                "details": "客户端通过 REST API 或 SDK 提交任务，系统分配全局唯一 TaskID 并立即返回，实现提交与执行解耦。"
            },
            {
                "id": "FR-02",
                "category": "任务调度",
                "description": "支持优先级队列、延迟队列、定时任务",
                "priority": "P0",
                "details": "任务可设置优先级（高/中/低），支持 delay 执行（延迟N秒后入队），支持 cron 定时触发。"
            },
            {
                "id": "FR-03",
                "category": "任务分发",
                "description": "Worker 节点自动拉取任务（Pull 模式），支持负载均衡",
                "priority": "P0",
                "details": "采用 Pull 模式，Worker 主动从队列拉取任务，系统根据 Worker 负载进行智能路由。"
            },
            {
                "id": "FR-04",
                "category": "任务执行",
                "description": "支持任务超时控制、重试机制、死信队列",
                "priority": "P0",
                "details": "每个任务设置最大执行时间，超时自动标记失败；失败任务支持指数退避重试（最多N次）；超过重试次数进入死信队列。"
            },
            {
                "id": "FR-05",
                "category": "任务状态",
                "description": "完整的任务生命周期管理（待执行→执行中→成功/失败/取消）",
                "priority": "P0",
                "details": "状态机：PENDING → RUNNING → SUCCESS / FAILED / CANCELLED，支持客户端查询和取消任务。"
            },
            {
                "id": "FR-06",
                "category": "任务依赖",
                "description": "支持 DAG 任务编排，任务间可定义依赖关系",
                "priority": "P1",
                "details": "支持将多个任务组织成 DAG，前置任务完成后自动触发下游任务。"
            },
            {
                "id": "FR-07",
                "category": "结果存储",
                "description": "任务执行结果持久化，支持结果回调通知",
                "priority": "P1",
                "details": "执行结果写入存储层；支持 Webhook 回调或消息通知告知客户端任务完成。"
            },
            {
                "id": "FR-08",
                "category": "监控运维",
                "description": "提供任务统计、队列深度监控、Worker 状态看板",
                "priority": "P1",
                "details": "实时展示队列积压量、任务成功率、平均耗时、Worker 在线数等核心指标。"
            },
            {
                "id": "FR-09",
                "category": "多租户",
                "description": "支持多命名空间/租户隔离",
                "priority": "P2",
                "details": "不同业务线使用独立命名空间，任务和队列逻辑隔离，配额独立管理。"
            },
        ]
        return requirements

    # ---- 非功能需求分析 ----
    def _analyze_nonfunctional_requirements(self) -> list:
        """定义非功能性需求"""
        requirements = [
            {
                "id": "NFR-01",
                "category": "高并发",
                "description": "支持 10,000+ TPS 任务提交，1,000+ 并发 Worker",
                "metrics": {
                    "task_submit_tps": 10000,
                    "concurrent_workers": 1000,
                    "task_latency_p99_ms": 50
                },
                "strategy": "采用内存队列 + 异步 IO（Netty/asyncio），任务分片到多个队列分区并行处理。"
            },
            {
                "id": "NFR-02",
                "category": "高可用",
                "description": "系统可用性 ≥ 99.95%，无单点故障",
                "metrics": {
                    "availability": "99.95%",
                    "rpo_seconds": 0,
                    "rto_seconds": 30
                },
                "strategy": "Master 多副本 + Raft 选举，队列数据多副本持久化，Worker 故障自动转移。"
            },
            {
                "id": "NFR-03",
                "category": "可扩展",
                "description": "支持水平扩展，Worker 和队列分区可动态增减",
                "metrics": {
                    "scale_out_time_seconds": 60,
                    "max_queue_partitions": 256
                },
                "strategy": "一致性哈希分区，Worker 无状态设计，队列分区可动态 rebalance。"
            },
            {
                "id": "NFR-04",
                "category": "数据持久性",
                "description": "任务不丢失，至少一次投递保证",
                "metrics": {
                    "data_loss_rate": "0%",
                    "delivery_guarantee": "at-least-once"
                },
                "strategy": "任务写入 WAL + 多副本确认后才返回提交成功；Worker ACK 机制确保任务完成确认。"
            },
            {
                "id": "NFR-05",
                "category": "低延迟",
                "description": "任务从提交到被 Worker 拉取的端到端延迟 < 10ms (P99)",
                "metrics": {
                    "e2e_latency_p99_ms": 10,
                    "scheduling_overhead_ms": 5
                },
                "strategy": "内存优先设计，热数据常驻内存，异步刷盘；Worker 长轮询减少空转。"
            },
        ]
        return requirements

    # ---- 技术选型分析 ----
    def _analyze_tech_stack(self) -> dict:
        """技术选型建议"""
        tech_stack = {
            "language": {
                "primary": "Java 17+",
                "rationale": "Java 生态成熟，Netty 高性能网络框架、Spring Boot 快速开发、JVM 调优工具链完善；团队 Java 经验丰富。",
                "alternatives": ["Go (高并发原生支持好，但生态略弱)", "Rust (极致性能，但开发效率低)"]
            },
            "messaging_core": {
                "primary": "自研轻量级队列引擎",
                "rationale": "避免外部依赖（Kafka/RabbitMQ），核心队列逻辑自研以获得极致控制力；底层使用 Disruptor 环形缓冲区实现超高吞吐。",
                "components": [
                    "Disruptor (LMAX) — 无锁环形缓冲区，百万级 TPS",
                    "Netty — 异步事件驱动网络框架",
                    "RocksDB — 嵌入式 KV 存储，用于 WAL 和持久化"
                ]
            },
            "consensus": {
                "primary": "Raft (基于 SOFAJRaft)",
                "rationale": "Master 选举和元数据一致性需要强一致协议，SOFAJRaft 是经过生产验证的 Java Raft 实现。"
            },
            "storage": {
                "primary": "RocksDB (本地持久化) + 可选 Redis (缓存层)",
                "rationale": "RocksDB 提供高性能本地存储用于 WAL 和任务数据；Redis 可选用于任务状态缓存和分布式锁。"
            },
            "serialization": {
                "primary": "Protocol Buffers",
                "rationale": "高性能二进制序列化，跨语言兼容，适合任务数据的网络传输和存储。"
            },
            "monitoring": {
                "primary": "Prometheus + Grafana",
                "rationale": "云原生标准监控栈，通过 Micrometer 暴露 JVM 和业务指标。"
            },
            "build": {
                "primary": "Maven 多模块项目",
                "modules": [
                    "dtq-common — 公共工具和协议定义",
                    "dtq-core — 队列引擎核心",
                    "dtq-master — 调度 Master 节点",
                    "dtq-worker — Worker 执行引擎",
                    "dtq-client — 客户端 SDK",
                    "dtq-dashboard — 管理控制台"
                ]
            }
        }
        return tech_stack

    # ---- 架构方向分析 ----
    def _analyze_architecture(self) -> dict:
        """确定架构方向"""
        architecture = {
            "style": "Master-Worker 分布式架构",
            "overview": (
                "采用 Master-Worker 模式，Master 负责任务调度和元数据管理，Worker 负责任务执行。"
                "Master 通过 Raft 实现高可用，Worker 无状态可水平扩展。"
                "队列采用分区设计，每个分区独立有序，支持并行消费。"
            ),
            "components": [
                {
                    "name": "API Gateway",
                    "role": "对外暴露 REST/gRPC 接口，接收任务提交和查询请求",
                    "tech": "Netty + 自定义 HTTP/Protobuf 协议"
                },
                {
                    "name": "Task Scheduler (Master)",
                    "role": "任务调度中枢：接收任务、分配到队列分区、管理任务状态机",
                    "tech": "Raft 多副本 + Disruptor 内部队列 + RocksDB 持久化",
                    "highlights": [
                        "任务按一致性哈希分配到分区",
                        "支持优先级排序（多级反馈队列）",
                        "延迟任务使用时间轮（Hashed Timing Wheel）"
                    ]
                },
                {
                    "name": "Queue Partition",
                    "role": "任务存储分区，每个分区独立有序",
                    "tech": "Disruptor RingBuffer + WAL",
                    "highlights": [
                        "每个分区有独立的 Disruptor 环形缓冲",
                        "WAL 保证崩溃恢复",
                        "分区数量可配置（默认 16，最大 256）"
                    ]
                },
                {
                    "name": "Worker Engine",
                    "role": "任务执行引擎，从队列拉取并执行任务",
                    "tech": "线程池 + 心跳机制 + 结果上报",
                    "highlights": [
                        "Pull 模式长轮询拉取任务",
                        "本地任务超时监控",
                        "执行结果异步上报 Master"
                    ]
                },
                {
                    "name": "Metadata Store",
                    "role": "存储任务元数据、Worker 注册信息、路由表",
                    "tech": "内嵌 RocksDB + Raft 复制"
                },
                {
                    "name": "Monitoring & Dashboard",
                    "role": "实时监控队列状态、Worker 健康、任务统计",
                    "tech": "Prometheus Exporter + Web Dashboard"
                }
            ],
            "data_flow": [
                "1. Client → API Gateway: 提交任务 (POST /tasks)",
                "2. API Gateway → Master: 任务写入 Master 主分区",
                "3. Master → Queue Partition: 任务持久化到对应分区的 Disruptor",
                "4. Worker ← Queue Partition: 长轮询拉取任务",
                "5. Worker → Worker: 本地执行任务（线程池）",
                "6. Worker → Master: 上报执行结果（成功/失败）",
                "7. Master → Client: Webhook 回调或客户端轮询获取结果"
            ],
            "failure_handling": {
                "worker_crash": "Master 检测心跳超时，将该 Worker 未 ACK 的任务重新入队",
                "master_failover": "Raft 自动选举新 Leader，WAL 保证数据不丢失",
                "network_partition": "脑裂防护通过 Raft 任期机制保证，分区隔离的 Worker 自动暂停拉取",
                "task_timeout": "Worker 本地定时器检测超时，标记失败并通知 Master 触发重试"
            }
        }
        return architecture

    # ---- 核心处理方法 ----
    def process_data(self, data: dict) -> dict:
        """
        执行需求分析，返回完整的需求分析报告。
        输入 data 可包含自定义参数，如 {"project": "distributed-task-queue"}
        """
        project_name = data.get("project", "distributed-task-queue") if data else "distributed-task-queue"

        print(f"[requirements_analysis] 开始分析项目: {project_name}", flush=True)

        # Step 1: 功能需求
        print("[requirements_analysis] Step 1/4: 分析功能需求...", flush=True)
        functional_reqs = self._analyze_functional_requirements()
        print(f"[requirements_analysis]   -> 识别到 {len(functional_reqs)} 条功能需求", flush=True)

        # Step 2: 非功能需求
        print("[requirements_analysis] Step 2/4: 分析非功能需求...", flush=True)
        nonfunctional_reqs = self._analyze_nonfunctional_requirements()
        print(f"[requirements_analysis]   -> 识别到 {len(nonfunctional_reqs)} 条非功能需求", flush=True)

        # Step 3: 技术选型
        print("[requirements_analysis] Step 3/4: 确定技术选型...", flush=True)
        tech_stack = self._analyze_tech_stack()
        print(f"[requirements_analysis]   -> 确定主语言: {tech_stack['language']['primary']}", flush=True)

        # Step 4: 架构方向
        print("[requirements_analysis] Step 4/4: 确定架构方向...", flush=True)
        architecture = self._analyze_architecture()
        print(f"[requirements_analysis]   -> 架构风格: {architecture['style']}", flush=True)

        # 汇总报告
        report = {
            "node": "requirements_analysis",
            "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "project": project_name,
            "summary": {
                "functional_requirements_count": len(functional_reqs),
                "nonfunctional_requirements_count": len(nonfunctional_reqs),
                "tech_stack_primary_language": tech_stack["language"]["primary"],
                "architecture_style": architecture["style"],
                "core_components": [c["name"] for c in architecture["components"]],
                "key_metrics": {
                    "target_tps": "10,000+",
                    "target_concurrent_workers": "1,000+",
                    "availability": "99.95%",
                    "e2e_latency_p99": "< 10ms"
                }
            },
            "functional_requirements": functional_reqs,
            "nonfunctional_requirements": nonfunctional_reqs,
            "tech_stack": tech_stack,
            "architecture": architecture,
            "next_steps": [
                "基于需求分析结果，设计详细的任务数据模型和状态机",
                "实现核心队列引擎（Disruptor + WAL）",
                "实现 Master 调度器（Raft + 任务路由）",
                "实现 Worker 执行引擎（Pull + 线程池）",
                "实现 API Gateway 和客户端 SDK",
                "集成监控和 Dashboard"
            ]
        }

        return report


# ============================================================
# 主入口：独立测试运行
# ============================================================
if __name__ == "__main__":
    print("=" * 60, flush=True)
    print("  requirements_analysis node — 分布式任务队列需求分析", flush=True)
    print("=" * 60, flush=True)

    agent = RequirementsAnalysisAgent()
    input_data = {"project": "distributed-task-queue"}
    result = agent.run(input_data)

    # 确保输出目录存在（在物理机上使用当前目录下的outputs子目录）
    output_dir = os.path.join(os.getcwd(), "outputs")
    os.makedirs(output_dir, exist_ok=True)

    # 写入结果文件
    output_path = os.path.join(output_dir, "requirements_analysis_result.json")
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, indent=2)

    print(f"\n[requirements_analysis] 结果已写入: {output_path}", flush=True)
    print(f"\n[requirements_analysis] 需求分析摘要:", flush=True)
    print(f"  - 功能需求: {result['summary']['functional_requirements_count']} 条", flush=True)
    print(f"  - 非功能需求: {result['summary']['nonfunctional_requirements_count']} 条", flush=True)
    print(f"  - 主语言: {result['summary']['tech_stack_primary_language']}", flush=True)
    print(f"  - 架构风格: {result['summary']['architecture_style']}", flush=True)
    print(f"  - 核心组件: {', '.join(result['summary']['core_components'])}", flush=True)
    print(f"  - 目标 TPS: {result['summary']['key_metrics']['target_tps']}", flush=True)
    print(f"  - 可用性: {result['summary']['key_metrics']['availability']}", flush=True)
    print(f"\nAGENT_SUCCESS: requirements_analysis 完成需求分析！", flush=True)
    print("=" * 60, flush=True)
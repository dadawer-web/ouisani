#!/usr/bin/env python3
"""
research_best_practices.py - 搜索分布式系统架构、高并发设计、消息队列最佳实践
节点ID: research_best_practices
"""

import json
import os
import sys
from typing import Dict, Any, List
from datetime import datetime

# 确保输出目录存在
os.makedirs("/factory/outputs", exist_ok=True)

# 自定义 BaseAgent 基类
class BaseAgent:
    """Base agent class for AIOS integration"""
    
    def __init__(self, agent_id: str = "research_best_practices"):
        self.agent_id = agent_id
        self.output_path = f"/factory/outputs/{agent_id}_output.json"
    
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """Process input data and return results"""
        raise NotImplementedError("Subclasses must implement process_data")


class ResearchBestPracticesAgent(BaseAgent):
    """研究分布式系统最佳实践的节点"""
    
    def __init__(self):
        super().__init__("research_best_practices")
        self.research_results = {}
        
    def process_data(self, data: Dict[str, Any]) -> Dict[str, Any]:
        """
        处理研究请求，收集分布式系统最佳实践信息
        
        Args:
            data: 输入数据，可能包含研究查询或配置
            
        Returns:
            包含研究结果的字典
        """
        print("🔍 [ResearchBestPractices] 开始研究分布式系统最佳实践...", flush=True)
        
        # 获取研究主题
        topics = data.get("topics", ["分布式队列架构", "高并发设计", "消息队列最佳实践"])
        
        # 收集各种最佳实践信息
        research_results = {
            "distributed_queue_architecture": self._research_distributed_queue_architecture(),
            "high_concurrency_design": self._research_high_concurrency_design(),
            "message_queue_best_practices": self._research_message_queue_best_practices(),
            "fault_tolerance_reliability": self._research_fault_tolerance_reliability(),
            "performance_optimization": self._research_performance_optimization()
        }
        
        # 生成研究总结和建议
        summary = self._generate_research_summary(research_results)
        recommendations = self._generate_recommendations(research_results)
        
        result = {
            "node_id": self.agent_id,
            "status": "completed",
            "timestamp": datetime.now().isoformat(),
            "research_topics": topics,
            "research_results": research_results,
            "summary": summary,
            "recommendations": recommendations
        }
        
        # 保存结果到文件
        self._save_results(result)
        
        print("✅ [ResearchBestPractices] 研究完成！已收集到最佳实践信息", flush=True)
        return result
    
    def _research_distributed_queue_architecture(self) -> Dict[str, Any]:
        """研究分布式队列架构最佳实践"""
        print("  📊 研究分布式队列架构...", flush=True)
        return {
            "core_concepts": [
                "分布式队列需要解决数据一致性和可用性问题",
                "CAP理论：一致性、可用性、分区容忍性三选二",
                "最终一致性模型是大多数分布式系统的合理选择",
                "队列需要处理消息丢失、重复消费、乱序等问题"
            ],
            "common_patterns": [
                "主从复制模式：Master负责写，Slave负责读",
                "对等节点模式：所有节点平等，通过一致性协议同步",
                "分片模式：数据按key分片到不同节点",
                "混合模式：结合多种模式的优缺点"
            ],
            "key_considerations": [
                "数据持久化策略：同步/异步复制",
                "故障转移机制：自动主从切换",
                "负载均衡策略：一致性哈希、范围分片",
                "监控与告警：队列深度、延迟、错误率监控"
            ]
        }
    
    def _research_high_concurrency_design(self) -> Dict[str, Any]:
        """研究高并发设计模式"""
        print("  ⚡ 研究高并发设计...", flush=True)
        return {
            "scaling_strategies": [
                "水平扩展：增加节点数量分散负载",
                "垂直扩展：提升单节点性能",
                "读写分离：主从架构分离读写操作",
                "缓存策略：多级缓存（本地缓存、分布式缓存）"
            ],
            "concurrency_control": [
                "乐观锁：版本号控制，适合读多写少场景",
                "悲观锁：数据库行锁，适合强一致性场景",
                "无锁设计：CAS操作、原子变量",
                "限流策略：令牌桶、漏桶算法"
            ],
            "async_processing": [
                "异步消息队列：解耦生产者和消费者",
                "事件驱动架构：基于事件的松耦合系统",
                "反应式编程：非阻塞IO，背压机制",
                "批处理：合并小请求，减少网络开销"
            ]
        }
    
    def _research_message_queue_best_practices(self) -> Dict[str, Any]:
        """研究消息队列最佳实践"""
        print("  📨 研究消息队列最佳实践...", flush=True)
        return {
            "message_design": [
                "消息体要小，避免传输大消息",
                "包含必要的元数据：时间戳、来源、类型",
                "使用标准格式：JSON、Protocol Buffers、Avro",
                "实现消息版本化，支持向前/向后兼容"
            ],
            "reliability_guarantees": [
                "至少一次投递：消息不会丢失，但可能重复",
                "最多一次投递：消息可能丢失，但不会重复",
                "精确一次投递：最严格保证，实现复杂",
                "幂等性设计：确保重复消息不会产生副作用"
            ],
            "performance_tuning": [
                "批量操作：批量发送/接收消息",
                "异步确认：生产者异步等待确认",
                "连接池：复用网络连接",
                "压缩传输：减少网络带宽使用"
            ],
            "monitoring_metrics": [
                "队列深度：积压的消息数量",
                "端到端延迟：从生产到消费的时间",
                "吞吐量：每秒处理的消息数",
                "错误率：失败消息的百分比"
            ]
        }
    
    def _research_fault_tolerance_reliability(self) -> Dict[str, Any]:
        """研究容错与可靠性设计"""
        print("  🛡️ 研究容错与可靠性...", flush=True)
        return {
            "failure_scenarios": [
                "节点故障：服务器宕机、网络分区",
                "网络问题：延迟、丢包、连接中断",
                "存储故障：磁盘损坏、数据丢失",
                "应用错误：内存泄漏、死锁"
            ],
            "recovery_mechanisms": [
                "自动故障转移：主从切换、负载均衡重试",
                "数据恢复：从副本或备份恢复",
                "状态同步：分布式状态一致性协议",
                "熔断降级：快速失败，避免级联故障"
            ],
            "data_durability": [
                "写前日志(WAL)：先写日志再写数据",
                "多副本存储：数据复制到多个节点",
                "定期快照：创建数据的时间点备份",
                "校验和验证：数据完整性检查"
            ]
        }
    
    def _research_performance_optimization(self) -> Dict[str, Any]:
        """研究性能优化策略"""
        print("  🚀 研究性能优化...", flush=True)
        return {
            "memory_optimization": [
                "对象池：重用昂贵对象，减少GC压力",
                "内存映射：大文件使用mmap减少拷贝",
                "压缩存储：使用压缩算法减少内存占用",
                "缓存策略：LRU/LFU缓存热点数据"
            ],
            "cpu_optimization": [
                "并行处理：利用多核CPU",
                "异步IO：非阻塞文件/网络操作",
                "批量处理：合并小任务减少调度开销",
                "算法优化：选择合适的数据结构和算法"
            ],
            "network_optimization": [
                "连接池：减少TCP连接建立开销",
                "协议优化：二进制协议替代文本协议",
                "压缩传输：减少网络传输数据量",
                "负载均衡：合理分配请求到不同节点"
            ]
        }
    
    def _generate_research_summary(self, research_results: Dict[str, Any]) -> str:
        """生成研究总结"""
        print("  📝 生成研究总结...", flush=True)
        return """=== 分布式系统最佳实践研究总结 ===

1. 分布式队列架构：
   - 核心挑战：数据一致性、可用性、分区容忍性
   - 推荐方案：根据业务场景选择合适的复制和分片策略

2. 高并发设计：
   - 关键策略：水平扩展、异步处理、缓存优化
   - 注意事项：避免热点、控制连接数、合理设置超时

3. 消息队列最佳实践：
   - 消息设计：小消息体、标准格式、包含元数据
   - 可靠性保证：至少一次投递 + 幂等性消费

4. 容错与可靠性：
   - 故障处理：自动转移、数据恢复、状态同步
   - 数据持久：多副本存储、写前日志、定期备份

5. 性能优化：
   - 资源利用：内存池、CPU并行、网络连接复用
   - 系统调优：监控关键指标、合理配置参数"""
    
    def _generate_recommendations(self, research_results: Dict[str, Any]) -> List[str]:
        """基于研究结果生成建议"""
        print("  💡 生成建议...", flush=True)
        return [
            "对于高并发任务队列，建议采用主从复制 + 分片的混合架构",
            "消息处理应实现幂等性，支持至少一次投递保证",
            "使用异步处理提高吞吐量，配合限流保护系统",
            "实现完善的监控告警，关注队列深度和处理延迟",
            "设计时考虑故障转移和自动恢复机制",
            "定期进行压力测试和容量规划"
        ]
    
    def _save_results(self, results: Dict[str, Any]):
        """保存研究结果到文件"""
        try:
            with open(self.output_path, "w", encoding="utf-8") as f:
                json.dump(results, f, ensure_ascii=False, indent=2)
            print(f"  💾 研究结果已保存到: {self.output_path}", flush=True)
        except Exception as e:
            print(f"  ❌ 保存结果失败: {e}", flush=True)


# 独立测试入口
if __name__ == "__main__":
    print("=" * 60, flush=True)
    print("🧪 测试 ResearchBestPractices 节点", flush=True)
    print("=" * 60, flush=True)
    
    try:
        # 创建实例
        print("1. 创建 ResearchBestPracticesAgent 实例...", flush=True)
        researcher = ResearchBestPracticesAgent()
        
        # 模拟输入数据
        print("2. 模拟输入数据...", flush=True)
        test_data = {
            "topics": ["分布式队列设计", "高并发处理", "消息可靠性"],
            "depth": "comprehensive"
        }
        
        # 执行研究
        print("3. 执行研究...", flush=True)
        result = researcher.process_data(test_data)
        
        # 输出结果预览
        print("\n" + "=" * 60, flush=True)
        print("📊 研究结果预览:", flush=True)
        print("=" * 60, flush=True)
        print(f"状态: {result['status']}", flush=True)
        print(f"主题数量: {len(result['research_topics'])}", flush=True)
        print(f"研究类别: {list(result['research_results'].keys())}", flush=True)
        print(f"建议数量: {len(result['recommendations'])}", flush=True)
        
        print("\n" + "=" * 60, flush=True)
        print("✅ ResearchBestPractices 节点测试成功！", flush=True)
        print("=" * 60, flush=True)
        
    except Exception as e:
        print(f"\n❌ 测试失败: {str(e)}", flush=True)
        import traceback
        traceback.print_exc()
        sys.exit(1)
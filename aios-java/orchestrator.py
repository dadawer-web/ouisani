#!/usr/bin/env python3
"""
AIOS 工作流编排器 (Orchestrator)
负责解析 workflow.json 并按照 DAG 拓扑顺序执行节点
"""

import json
import os
import sys
import subprocess
from pathlib import Path
from typing import Dict, List, Set, Any
from collections import defaultdict, deque


class WorkflowOrchestrator:
    """工作流编排器，负责解析和执行工作流"""
    
    def __init__(self, workflow_path: str = "workflow.json"):
        self.workflow_path = workflow_path
        self.workflow_data = None
        self.nodes = {}
        self.dependencies = defaultdict(set)  # node -> set of dependencies
        self.dependents = defaultdict(set)     # node -> set of dependents
        
    def load_workflow(self) -> bool:
        """加载并解析工作流文件"""
        try:
            with open(self.workflow_path, 'r', encoding='utf-8') as f:
                self.workflow_data = json.load(f)
            
            # 解析节点和依赖关系
            for node in self.workflow_data.get('workflow', {}).get('nodes', []):
                node_id = node['nodeId']
                self.nodes[node_id] = node
                
                # 记录依赖关系
                for dep in node.get('dependsOn', []):
                    self.dependencies[node_id].add(dep)
                    self.dependents[dep].add(node_id)
            
            print(f"[Orchestrator] 成功加载工作流，共 {len(self.nodes)} 个节点", flush=True)
            return True
            
        except Exception as e:
            print(f"[Orchestrator] 加载工作流失败: {e}", flush=True)
            return False
    
    def topological_sort(self) -> List[str]:
        """
        执行拓扑排序，返回节点执行顺序
        使用 Kahn 算法 (BFS-based topological sort)
        """
        # 计算入度
        in_degree = {node: len(deps) for node, deps in self.dependencies.items()}
        
        # 初始化队列：入度为0的节点
        queue = deque([node for node, degree in in_degree.items() if degree == 0])
        execution_order = []
        
        while queue:
            current_node = queue.popleft()
            execution_order.append(current_node)
            
            # 减少依赖当前节点的后续节点的入度
            for dependent in self.dependents[current_node]:
                in_degree[dependent] -= 1
                if in_degree[dependent] == 0:
                    queue.append(dependent)
        
        # 检查是否有环
        if len(execution_order) != len(self.nodes):
            print("[Orchestrator] 警告: 工作流可能存在循环依赖", flush=True)
            # 返回尽可能多的节点
            return execution_order
        
        return execution_order
    
    def execute_node(self, node_id: str) -> int:
        """
        执行单个节点
        返回进程退出码
        """
        node = self.nodes[node_id]
        script_path = node.get('scriptPath')
        
        if not script_path or not os.path.exists(script_path):
            print(f"[Orchestrator] 错误: 节点 {node_id} 的脚本路径无效: {script_path}", flush=True)
            return 1
        
        print(f"[DAG_TRACE] >>> NODE_START: {node_id}", flush=True)
        
        try:
            # 使用 -u 参数确保输出不被缓冲
            process = subprocess.Popen(
                ["python3", "-u", script_path],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True
            )
            
            # 实时输出节点日志，加上节点ID前缀
            for line in process.stdout:
                print(f"[{node_id}] {line}", end="", flush=True)
            
            process.wait()
            
            if process.returncode == 0:
                print(f"[DAG_TRACE] <<< NODE_SUCCESS: {node_id}", flush=True)
            else:
                print(f"[DAG_TRACE] !!! NODE_FAILED: {node_id}", flush=True)
                print("🚨 [GenericAppAgent] SYSTEM ERROR. Process terminated to trigger AutoMedic.", flush=True)
            
            return process.returncode
            
        except Exception as e:
            print(f"[Orchestrator] 执行节点 {node_id} 时发生异常: {e}", flush=True)
            print(f"[DAG_TRACE] !!! NODE_FAILED: {node_id}", flush=True)
            print("🚨 [GenericAppAgent] SYSTEM ERROR. Process terminated to trigger AutoMedic.", flush=True)
            return 1
    
    def execute_workflow(self) -> bool:
        """执行整个工作流"""
        if not self.workflow_data:
            if not self.load_workflow():
                return False
        
        # 获取拓扑排序后的执行顺序
        execution_order = self.topological_sort()
        print(f"[Orchestrator] 执行顺序: {execution_order}", flush=True)
        
        # 按顺序执行节点
        for node_id in execution_order:
            return_code = self.execute_node(node_id)
            if return_code != 0:
                print(f"[Orchestrator] 工作流因节点 {node_id} 失败而终止", flush=True)
                sys.exit(1)
        
        print("[Orchestrator] 工作流执行完成", flush=True)
        return True


def main():
    """主函数"""
    print("=== AIOS 工作流编排器启动 ===", flush=True)
    
    # 确定工作流文件路径
    script_dir = Path(__file__).parent
    workflow_path = script_dir / "workflow.json"
    
    # 创建编排器并执行
    orchestrator = WorkflowOrchestrator(str(workflow_path))
    success = orchestrator.execute_workflow()
    
    if success:
        print("=== 工作流执行成功 ===", flush=True)
        sys.exit(0)
    else:
        print("=== 工作流执行失败 ===", flush=True)
        sys.exit(1)


if __name__ == "__main__":
    main()

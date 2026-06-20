import json
import sys
import subprocess
import os
import time
from pathlib import Path
from typing import Dict, List, Any, Optional

class WorkflowOrchestrator:
    """
    AIOS 用户态工作流编排器 (Orchestrator)
    严格执行 DAG 拓扑，按依赖顺序执行工作流节点。
    """
    
    def __init__(self, workflow_file: str = "workflow.json"):
        self.workflow_file = workflow_file
        self.workflow = None
        self.nodes = {}
        self.executed_nodes = set()
        self.current_dir = os.getcwd()
        
    def load_workflow(self) -> bool:
        """加载工作流配置文件"""
        try:
            workflow_path = os.path.join(self.current_dir, "workspaces", "_default", "factory", self.workflow_file)
            if not os.path.exists(workflow_path):
                # 尝试从当前目录查找
                workflow_path = os.path.join(self.current_dir, self.workflow_file)
            
            if not os.path.exists(workflow_path):
                print(f"[ERROR] Workflow file not found: {self.workflow_file}", flush=True)
                return False
            
            with open(workflow_path, 'r', encoding='utf-8') as f:
                self.workflow = json.load(f)
            
            # 解析节点
            for node in self.workflow.get("nodes", []):
                node_id = node["nodeId"]
                self.nodes[node_id] = {
                    "script_path": node["scriptPath"],
                    "depends_on": node.get("dependsOn", []),
                    "description": node.get("description", ""),
                    "output_file": node.get("outputFile", ""),
                    "status": "pending"  # pending, ready, running, completed, failed
                }
            
            print(f"[ORCHESTRATOR] Loaded workflow: {self.workflow.get('workflow', {}).get('name', 'Unknown')}", flush=True)
            print(f"[ORCHESTRATOR] Total nodes: {len(self.nodes)}", flush=True)
            
            return True
            
        except Exception as e:
            print(f"[ERROR] Failed to load workflow: {str(e)}", flush=True)
            return False
    
    def get_ready_nodes(self) -> List[str]:
        """获取所有依赖已满足的就绪节点"""
        ready_nodes = []
        
        for node_id, node_info in self.nodes.items():
            if node_info["status"] != "pending":
                continue
                
            # 检查所有依赖是否已完成
            dependencies_met = True
            for dep in node_info["depends_on"]:
                if dep not in self.executed_nodes:
                    dependencies_met = False
                    break
            
            if dependencies_met:
                ready_nodes.append(node_id)
        
        return ready_nodes
    
    def execute_node(self, node_id: str) -> bool:
        """执行单个工作流节点"""
        if node_id not in self.nodes:
            print(f"[ERROR] Node {node_id} not found", flush=True)
            return False
        
        node_info = self.nodes[node_id]
        script_path = node_info["script_path"]
        
        # 构建脚本的完整路径
        full_script_path = os.path.join(self.current_dir, "workspaces", "_default", "factory", script_path)
        
        print(f"[DAG_TRACE] >>> NODE_START: {node_id}", flush=True)
        print(f"[DAG_TRACE] Script: {script_path}", flush=True)
        print(f"[DAG_TRACE] Description: {node_info['description']}", flush=True)
        
        try:
            # 使用 subprocess.Popen 执行脚本，实时输出
            process = subprocess.Popen(
                ["python3", "-u", full_script_path],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                universal_newlines=True
            )
            
            # 实时读取并输出
            while True:
                output = process.stdout.readline()
                if output == '' and process.poll() is not None:
                    break
                if output:
                    print(f"[{node_id}] {output.strip()}", flush=True)
            
            # 等待进程完成
            process.wait()
            
            if process.returncode == 0:
                print(f"[DAG_TRACE] <<< NODE_SUCCESS: {node_id}", flush=True)
                self.executed_nodes.add(node_id)
                node_info["status"] = "completed"
                return True
            else:
                print(f"[DAG_TRACE] !!! NODE_FAILED: {node_id}", flush=True)
                print("🚨 [GenericAppAgent] SYSTEM ERROR. Process terminated to trigger AutoMedic.", flush=True)
                node_info["status"] = "failed"
                return False
                
        except Exception as e:
            print(f"[DAG_TRACE] !!! NODE_ERROR: {node_id} - {str(e)}", flush=True)
            node_info["status"] = "failed"
            return False
    
    def run(self) -> bool:
        """执行整个工作流"""
        print("=" * 60, flush=True)
        print("[ORCHESTRATOR] Starting workflow execution", flush=True)
        print("=" * 60, flush=True)
        
        # 1. 加载工作流
        if not self.load_workflow():
            return False
        
        # 2. 检查是否有循环依赖（简单检查）
        if self._has_circular_dependencies():
            print("[ERROR] Circular dependencies detected in workflow", flush=True)
            return False
        
        # 3. 拓扑执行循环
        iteration = 0
        max_iterations = 100  # 防止无限循环
        
        while len(self.executed_nodes) < len(self.nodes) and iteration < max_iterations:
            iteration += 1
            
            # 获取就绪节点
            ready_nodes = self.get_ready_nodes()
            
            if not ready_nodes:
                # 检查是否有节点在运行中
                running_nodes = [n for n, info in self.nodes.items() if info["status"] == "running"]
                if running_nodes:
                    print(f"[ORCHESTRATOR] Waiting for running nodes: {running_nodes}", flush=True)
                    time.sleep(1)
                    continue
                else:
                    # 没有就绪节点也没有运行中节点，说明有无法解决的依赖
                    pending_nodes = [n for n, info in self.nodes.items() if info["status"] == "pending"]
                    print(f"[ERROR] No ready nodes found. Pending nodes: {pending_nodes}", flush=True)
                    return False
            
            # 执行所有就绪节点（这里简化为顺序执行，实际可并行）
            for node_id in ready_nodes:
                self.nodes[node_id]["status"] = "running"
                
                success = self.execute_node(node_id)
                
                if not success:
                    print(f"[ORCHESTRATOR] Workflow failed at node: {node_id}", flush=True)
                    return False
        
        if iteration >= max_iterations:
            print("[ERROR] Maximum iterations exceeded. Possible infinite loop.", flush=True)
            return False
        
        print("=" * 60, flush=True)
        print("[ORCHESTRATOR] Workflow completed successfully!", flush=True)
        print(f"[ORCHESTRATOR] Executed nodes: {len(self.executed_nodes)}/{len(self.nodes)}", flush=True)
        print("=" * 60, flush=True)
        
        return True
    
    def _has_circular_dependencies(self) -> bool:
        """检查是否有循环依赖（简化实现）"""
        # 使用拓扑排序检测循环
        visited = set()
        recursion_stack = set()
        
        def dfs(node_id: str) -> bool:
            visited.add(node_id)
            recursion_stack.add(node_id)
            
            for dep in self.nodes[node_id]["depends_on"]:
                if dep not in visited:
                    if dfs(dep):
                        return True
                elif dep in recursion_stack:
                    return True
            
            recursion_stack.remove(node_id)
            return False
        
        for node_id in self.nodes:
            if node_id not in visited:
                if dfs(node_id):
                    return True
        
        return False
    
    def print_status(self):
        """打印工作流状态"""
        print("\n" + "=" * 40, flush=True)
        print("Workflow Status:", flush=True)
        print("=" * 40, flush=True)
        
        for node_id, node_info in self.nodes.items():
            status_icon = {
                "pending": "⏳",
                "ready": "🟡",
                "running": "🔄",
                "completed": "✅",
                "failed": "❌"
            }.get(node_info["status"], "❓")
            
            print(f"{status_icon} {node_id}: {node_info['status']}", flush=True)
        
        print(f"\nExecuted: {len(self.executed_nodes)}/{len(self.nodes)}", flush=True)

if __name__ == "__main__":
    print("=" * 60, flush=True)
    print("AIOS Workflow Orchestrator", flush=True)
    print("=" * 60, flush=True)
    
    # 检查是否在正确的目录中
    current_dir = os.getcwd()
    print(f"Current directory: {current_dir}", flush=True)
    
    # 检查工作流文件是否存在
    workflow_file = "workflow.json"
    possible_paths = [
        os.path.join(current_dir, workflow_file),
        os.path.join(current_dir, "workspaces", "_default", "factory", workflow_file)
    ]
    
    workflow_found = False
    for path in possible_paths:
        if os.path.exists(path):
            print(f"Found workflow at: {path}", flush=True)
            workflow_found = True
            break
    
    if not workflow_found:
        print(f"[ERROR] Workflow file '{workflow_file}' not found in any expected location", flush=True)
        print("Please ensure the workflow file exists in the current directory or workspaces/_default/factory/", flush=True)
        sys.exit(1)
    
    # 创建编排器并执行
    orchestrator = WorkflowOrchestrator(workflow_file)
    
    try:
        success = orchestrator.run()
        
        if success:
            orchestrator.print_status()
            print("\n[SUCCESS] Workflow execution completed successfully!", flush=True)
            sys.exit(0)
        else:
            orchestrator.print_status()
            print("\n[FAILURE] Workflow execution failed!", flush=True)
            sys.exit(1)
            
    except KeyboardInterrupt:
        print("\n[INTERRUPTED] Workflow execution interrupted by user", flush=True)
        orchestrator.print_status()
        sys.exit(130)
    except Exception as e:
        print(f"\n[ERROR] Unexpected error: {str(e)}", flush=True)
        sys.exit(1)
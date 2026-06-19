#!/usr/bin/env python3
"""
BaseAgent 基类定义
所有 Agent 必须继承此类并重写 process_data 方法
"""
import json
import sys
from abc import ABC, abstractmethod


class BaseAgent(ABC):
    """基础代理类，所有代理必须继承此类"""
    
    def __init__(self, agent_name: str):
        """
        初始化代理
        
        Args:
            agent_name: 代理名称
        """
        self.agent_name = agent_name
        self.output_path = f"/factory/outputs/{agent_name}_output.json"
        
    @abstractmethod
    def process_data(self, data: dict) -> dict:
        """
        处理数据的核心方法，子类必须重写
        
        Args:
            data: 输入数据字典
            
        Returns:
            处理结果字典
        """
        raise NotImplementedError("子类必须实现 process_data 方法")
    
    def save_result(self, result: dict):
        """
        保存处理结果到文件
        
        Args:
            result: 要保存的结果字典
        """
        try:
            import os
            # 确保输出目录存在
            os.makedirs(os.path.dirname(self.output_path), exist_ok=True)
            
            with open(self.output_path, 'w', encoding='utf-8') as f:
                json.dump(result, f, ensure_ascii=False, indent=2)
            print(f"✅ [{self.agent_name}] 结果已保存到: {self.output_path}", flush=True)
        except Exception as e:
            print(f"❌ [{self.agent_name}] 保存结果失败: {str(e)}", file=sys.stderr, flush=True)
            raise
    
    def run(self, input_data: dict = None):
        """
        运行代理的主入口
        
        Args:
            input_data: 输入数据，默认为空字典
        """
        if input_data is None:
            input_data = {}
            
        print(f"🤖 [{self.agent_name}] 开始执行...", flush=True)
        
        try:
            # 执行核心处理逻辑
            result = self.process_data(input_data)
            
            # 保存结果
            self.save_result(result)
            
            print(f"✅ [{self.agent_name}] 执行完成", flush=True)
            return result
            
        except Exception as e:
            print(f"❌ [{self.agent_name}] 执行失败: {str(e)}", file=sys.stderr, flush=True)
            # 保存错误信息
            error_result = {
                "status": "error",
                "agent": self.agent_name,
                "error": str(e)
            }
            self.save_result(error_result)
            raise


if __name__ == "__main__":
    # 测试基类
    print("Testing BaseAgent class...", flush=True)
    
    class TestAgent(BaseAgent):
        def process_data(self, data):
            return {"test": "success", "input": data}
    
    agent = TestAgent("test_agent")
    result = agent.run({"test_input": "hello"})
    print(f"Test result: {result}", flush=True)
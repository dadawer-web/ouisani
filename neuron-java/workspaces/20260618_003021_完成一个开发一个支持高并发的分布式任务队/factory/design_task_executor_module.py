#!/usr/bin/env python3
"""
并行设计任务执行器/Worker模块
支持：多进程、多线程、协程，失败重试、超时控制
"""

import asyncio
import multiprocessing
import threading
import time
import json
import os
import sys
import traceback
from typing import Any, Callable, Dict, List, Optional, Tuple, Union
from dataclasses import dataclass, asdict
from enum import Enum
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor, as_completed, Future
import logging
from datetime import datetime

# 配置日志
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger("TaskExecutor")

# 确保输出目录存在
OUTPUT_DIR = "/factory/outputs"
TEMP_DIR = "/factory"
os.makedirs(OUTPUT_DIR, exist_ok=True)

class ConcurrencyMode(Enum):
    """并发模式枚举"""
    MULTIPROCESS = "multiprocess"
    MULTITHREAD = "multithread"
    ASYNCIO = "asyncio"

@dataclass
class TaskConfig:
    """任务配置数据类"""
    task_id: str
    name: str
    func: Callable
    args: Tuple = ()
    kwargs: Dict = None
    timeout: Optional[float] = None
    max_retries: int = 0
    retry_delay: float = 1.0
    priority: int = 0
    depends_on: List[str] = None

    def __post_init__(self):
        if self.kwargs is None:
            self.kwargs = {}
        if self.depends_on is None:
            self.depends_on = []

@dataclass
class TaskResult:
    """任务执行结果"""
    task_id: str
    status: str  # "success", "failed", "timeout", "cancelled"
    result: Any = None
    error: Optional[str] = None
    start_time: Optional[float] = None
    end_time: Optional[float] = None
    duration: Optional[float] = None
    attempts: int = 0

class TaskDependencyResolver:
    """任务依赖解析器"""
    
    def __init__(self):
        self.dependency_graph: Dict[str, List[str]] = {}
        self.in_degree: Dict[str, int] = {}
    
    def add_task(self, task_id: str, depends_on: List[str]):
        """添加任务及其依赖"""
        self.dependency_graph[task_id] = depends_on
        self.in_degree[task_id] = len(depends_on)
    
    def get_ready_tasks(self, completed_tasks: set) -> List[str]:
        """获取当前就绪的任务（依赖已完成）"""
        ready_tasks = []
        for task_id, deps in self.dependency_graph.items():
            if task_id not in completed_tasks and all(dep in completed_tasks for dep in deps):
                ready_tasks.append(task_id)
        return ready_tasks
    
    def has_cycle(self) -> bool:
        """检测依赖图中是否存在循环"""
        visited = set()
        rec_stack = set()
        
        def dfs(node):
            visited.add(node)
            rec_stack.add(node)
            
            for neighbor in self.dependency_graph.get(node, []):
                if neighbor not in visited:
                    if dfs(neighbor):
                        return True
                elif neighbor in rec_stack:
                    return True
            
            rec_stack.remove(node)
            return False
        
        for node in self.dependency_graph:
            if node not in visited:
                if dfs(node):
                    return True
        return False

class RetryableTask:
    """可重试的任务包装器"""
    
    def __init__(self, task_config: TaskConfig):
        self.config = task_config
        self.attempts = 0
    
    async def execute_async(self) -> TaskResult:
        """异步执行任务，支持重试和超时"""
        start_time = time.time()
        last_error = None
        
        for attempt in range(self.config.max_retries + 1):
            self.attempts = attempt + 1
            try:
                # 创建协程任务
                if self.config.timeout:
                    # 有超时限制
                    result = await asyncio.wait_for(
                        self._execute_coroutine(),
                        timeout=self.config.timeout
                    )
                else:
                    # 无超时限制
                    result = await self._execute_coroutine()
                
                # 成功执行
                return TaskResult(
                    task_id=self.config.task_id,
                    status="success",
                    result=result,
                    start_time=start_time,
                    end_time=time.time(),
                    duration=time.time() - start_time,
                    attempts=self.attempts
                )
                
            except asyncio.TimeoutError:
                last_error = f"Task timed out after {self.config.timeout} seconds"
                logger.warning(f"Task {self.config.task_id} timed out (attempt {attempt + 1})")
                
            except Exception as e:
                last_error = str(e)
                logger.error(f"Task {self.config.task_id} failed (attempt {attempt + 1}): {e}")
            
            # 重试前的延迟
            if attempt < self.config.max_retries:
                logger.info(f"Retrying task {self.config.task_id} in {self.config.retry_delay} seconds...")
                await asyncio.sleep(self.config.retry_delay)
        
        # 所有重试都失败
        return TaskResult(
            task_id=self.config.task_id,
            status="failed",
            error=last_error,
            start_time=start_time,
            end_time=time.time(),
            duration=time.time() - start_time,
            attempts=self.attempts
        )
    
    def execute_sync(self) -> TaskResult:
        """同步执行任务，支持重试和超时"""
        start_time = time.time()
        last_error = None
        
        for attempt in range(self.config.max_retries + 1):
            self.attempts = attempt + 1
            try:
                # 执行函数
                if self.config.timeout:
                    # 使用信号实现超时（仅Unix系统）
                    import signal
                    
                    def timeout_handler(signum, frame):
                        raise TimeoutError(f"Task timed out after {self.config.timeout} seconds")
                    
                    # 设置信号处理器
                    signal.signal(signal.SIGALRM, timeout_handler)
                    signal.alarm(int(self.config.timeout))
                    
                    try:
                        result = self.config.func(*self.config.args, **self.config.kwargs)
                    finally:
                        # 清除闹钟
                        signal.alarm(0)
                else:
                    result = self.config.func(*self.config.args, **self.config.kwargs)
                
                # 成功执行
                return TaskResult(
                    task_id=self.config.task_id,
                    status="success",
                    result=result,
                    start_time=start_time,
                    end_time=time.time(),
                    duration=time.time() - start_time,
                    attempts=self.attempts
                )
                
            except (TimeoutError, Exception) as e:
                last_error = str(e)
                if "timed out" in str(e).lower():
                    logger.warning(f"Task {self.config.task_id} timed out (attempt {attempt + 1})")
                else:
                    logger.error(f"Task {self.config.task_id} failed (attempt {attempt + 1}): {e}")
            
            # 重试前的延迟
            if attempt < self.config.max_retries:
                logger.info(f"Retrying task {self.config.task_id} in {self.config.retry_delay} seconds...")
                time.sleep(self.config.retry_delay)
        
        # 所有重试都失败
        return TaskResult(
            task_id=self.config.task_id,
            status="failed",
            error=last_error,
            start_time=start_time,
            end_time=time.time(),
            duration=time.time() - start_time,
            attempts=self.attempts
        )
    
    async def _execute_coroutine(self):
        """执行实际的任务函数（支持协程）"""
        if asyncio.iscoroutinefunction(self.config.func):
            return await self.config.func(*self.config.args, **self.config.kwargs)
        else:
            # 在线程池中执行同步函数
            loop = asyncio.get_event_loop()
            return await loop.run_in_executor(
                None,
                lambda: self.config.func(*self.config.args, **self.config.kwargs)
            )

class ParallelTaskExecutor:
    """并行任务执行器"""
    
    def __init__(self, 
                 concurrency_mode: ConcurrencyMode = ConcurrencyMode.ASYNCIO,
                 max_workers: int = None):
        """
        初始化执行器
        
        Args:
            concurrency_mode: 并发模式
            max_workers: 最大工作线程/进程数
        """
        self.concurrency_mode = concurrency_mode
        self.max_workers = max_workers or (multiprocessing.cpu_count() * 2)
        self.tasks: Dict[str, TaskConfig] = {}
        self.results: Dict[str, TaskResult] = {}
        self.dependency_resolver = TaskDependencyResolver()
        
        # 根据并发模式初始化执行器
        if concurrency_mode == ConcurrencyMode.MULTIPROCESS:
            self.executor = ProcessPoolExecutor(max_workers=self.max_workers)
        elif concurrency_mode == ConcurrencyMode.MULTITHREAD:
            self.executor = ThreadPoolExecutor(max_workers=self.max_workers)
        else:
            self.executor = None  # asyncio模式不需要执行器
        
        logger.info(f"TaskExecutor initialized: mode={concurrency_mode.value}, max_workers={self.max_workers}")
    
    def add_task(self, task_config: TaskConfig):
        """添加任务到执行器"""
        self.tasks[task_config.task_id] = task_config
        self.dependency_resolver.add_task(task_config.task_id, task_config.depends_on)
        
        if self.dependency_resolver.has_cycle():
            raise ValueError(f"Cycle detected in dependency graph when adding task {task_config.task_id}")
        
        logger.debug(f"Added task: {task_config.task_id} (depends on: {task_config.depends_on})")
    
    def execute_all(self, timeout: Optional[float] = None) -> Dict[str, TaskResult]:
        """
        执行所有任务
        
        Args:
            timeout: 总体超时时间（秒）
            
        Returns:
            任务结果字典
        """
        logger.info(f"Starting execution of {len(self.tasks)} tasks")
        start_time = time.time()
        
        try:
            if self.concurrency_mode == ConcurrencyMode.ASYNCIO:
                # 使用asyncio模式
                loop = asyncio.new_event_loop()
                asyncio.set_event_loop(loop)
                try:
                    return loop.run_until_complete(self._execute_async(timeout))
                finally:
                    loop.close()
            else:
                # 使用线程池或进程池模式
                return self._execute_with_executor(timeout)
                
        except Exception as e:
            logger.error(f"Execution failed: {e}")
            traceback.print_exc()
            raise
        finally:
            duration = time.time() - start_time
            logger.info(f"Total execution time: {duration:.2f} seconds")
            
            # 关闭执行器
            if self.executor:
                self.executor.shutdown(wait=False)
    
    async def _execute_async(self, timeout: Optional[float] = None) -> Dict[str, TaskResult]:
        """使用asyncio执行所有任务"""
        if timeout:
            return await asyncio.wait_for(self._execute_async_tasks(), timeout=timeout)
        else:
            return await self._execute_async_tasks()
    
    async def _execute_async_tasks(self) -> Dict[str, TaskResult]:
        """异步执行任务，考虑依赖关系"""
        completed_tasks = set()
        running_tasks = {}
        task_futures = {}
        
        # 创建任务队列
        task_queue = asyncio.Queue()
        
        # 初始添加所有就绪任务
        ready_tasks = self.dependency_resolver.get_ready_tasks(completed_tasks)
        for task_id in ready_tasks:
            await task_queue.put(task_id)
        
        # 创建信号量控制并发
        semaphore = asyncio.Semaphore(self.max_workers)
        
        async def process_task(task_id: str):
            """处理单个任务"""
            async with semaphore:
                task_config = self.tasks[task_id]
                retryable_task = RetryableTask(task_config)
                
                logger.info(f"Starting task: {task_id}")
                result = await retryable_task.execute_async()
                self.results[task_id] = result
                completed_tasks.add(task_id)
                
                # 检查是否有新的任务就绪
                new_ready = self.dependency_resolver.get_ready_tasks(completed_tasks)
                for new_task_id in new_ready:
                    if new_task_id not in running_tasks and new_task_id not in completed_tasks:
                        await task_queue.put(new_task_id)
                
                return result
        
        # 任务处理循环
        while len(completed_tasks) < len(self.tasks):
            try:
                # 从队列获取任务
                task_id = await asyncio.wait_for(task_queue.get(), timeout=1.0)
                
                if task_id not in running_tasks and task_id not in completed_tasks:
                    running_tasks[task_id] = asyncio.create_task(process_task(task_id))
                    
            except asyncio.TimeoutError:
                # 没有新任务，检查是否所有任务都已完成
                if len(completed_tasks) >= len(self.tasks):
                    break
        
        # 等待所有运行中的任务完成
        if running_tasks:
            await asyncio.gather(*running_tasks.values(), return_exceptions=True)
        
        return self.results
    
    def _execute_with_executor(self, timeout: Optional[float] = None) -> Dict[str, TaskResult]:
        """使用线程池/进程池执行任务"""
        completed_tasks = set()
        running_futures = {}
        
        # 等待所有任务完成或超时
        end_time = time.time() + timeout if timeout else float('inf')
        
        while len(completed_tasks) < len(self.tasks):
            current_time = time.time()
            if timeout and current_time >= end_time:
                logger.warning("Execution timed out")
                break
            
            # 获取就绪任务
            ready_tasks = self.dependency_resolver.get_ready_tasks(completed_tasks)
            
            # 提交新任务
            for task_id in ready_tasks:
                if task_id not in running_futures:
                    task_config = self.tasks[task_id]
                    retryable_task = RetryableTask(task_config)
                    
                    if self.concurrency_mode == ConcurrencyMode.MULTIPROCESS:
                        future = self.executor.submit(retryable_task.execute_sync)
                    else:
                        future = self.executor.submit(retryable_task.execute_sync)
                    
                    running_futures[task_id] = future
                    logger.debug(f"Submitted task: {task_id}")
            
            # 检查完成的任务
            done_futures = []
            for task_id, future in list(running_futures.items()):
                if future.done():
                    try:
                        result = future.result()
                        self.results[task_id] = result
                        completed_tasks.add(task_id)
                        done_futures.append(task_id)
                        
                        if result.status == "success":
                            logger.info(f"Task {task_id} completed successfully")
                        else:
                            logger.warning(f"Task {task_id} completed with status: {result.status}")
                            
                    except Exception as e:
                        logger.error(f"Task {task_id} raised exception: {e}")
                        self.results[task_id] = TaskResult(
                            task_id=task_id,
                            status="failed",
                            error=str(e),
                            start_time=time.time(),
                            end_time=time.time()
                        )
                        completed_tasks.add(task_id)
                        done_futures.append(task_id)
            
            # 移除已完成的任务
            for task_id in done_futures:
                del running_futures[task_id]
            
            # 避免忙等待
            if not done_futures and running_futures:
                time.sleep(0.1)
        
        return self.results
    
    def get_task_status(self) -> Dict[str, str]:
        """获取所有任务的状态"""
        status = {}
        for task_id in self.tasks:
            if task_id in self.results:
                status[task_id] = self.results[task_id].status
            else:
                status[task_id] = "pending"
        return status
    
    def save_results(self, output_path: str = None):
        """保存执行结果到JSON文件"""
        if output_path is None:
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            output_path = os.path.join(OUTPUT_DIR, f"task_results_{timestamp}.json")
        
        # 转换结果为可序列化的格式
        results_dict = {}
        for task_id, result in self.results.items():
            result_dict = asdict(result)
            # 处理不可序列化的结果
            if result_dict.get("result") is not None:
                try:
                    json.dumps(result_dict["result"])
                except (TypeError, ValueError):
                    result_dict["result"] = str(result_dict["result"])
            results_dict[task_id] = result_dict
        
        with open(output_path, 'w', encoding='utf-8') as f:
            json.dump(results_dict, f, indent=2, ensure_ascii=False, default=str)
        
        logger.info(f"Results saved to: {output_path}")
        return output_path

# ==================== 示例任务函数 ====================

def cpu_intensive_task(n: int) -> int:
    """CPU密集型任务示例"""
    logger.info(f"Executing CPU intensive task with n={n}")
    result = 0
    for i in range(n):
        result += i * i
    return result

def io_simulated_task(duration: float) -> str:
    """模拟IO任务"""
    logger.info(f"Simulating IO task for {duration} seconds")
    time.sleep(duration)
    return f"IO task completed after {duration} seconds"

async def async_api_call(url: str, delay: float) -> dict:
    """异步API调用模拟"""
    logger.info(f"Making async API call to {url}")
    await asyncio.sleep(delay)
    return {"url": url, "status": "success", "timestamp": time.time()}

def failing_task() -> None:
    """故意失败的任务（用于测试重试）"""
    logger.info("Executing intentionally failing task")
    import random
    if random.random() < 0.7:  # 70%的概率失败
        raise ValueError("Random failure for testing")
    return "Success!"

# ==================== 使用示例 ====================

def run_example():
    """运行示例演示"""
    print("=== 并行任务执行器示例 ===")
    
    # 创建执行器（使用异步模式）
    executor = ParallelTaskExecutor(
        concurrency_mode=ConcurrencyMode.ASYNCIO,
        max_workers=4
    )
    
    # 添加任务
    executor.add_task(TaskConfig(
        task_id="task_1",
        name="CPU计算任务",
        func=cpu_intensive_task,
        args=(100000,),
        timeout=10.0
    ))
    
    executor.add_task(TaskConfig(
        task_id="task_2",
        name="IO模拟任务",
        func=io_simulated_task,
        args=(2.0,),
        timeout=5.0
    ))
    
    executor.add_task(TaskConfig(
        task_id="task_3",
        name="异步API调用",
        func=async_api_call,
        args=("http://example.com/api", 1.5),
        timeout=3.0
    ))
    
    # 添加一个依赖task_1和task_2的任务
    executor.add_task(TaskConfig(
        task_id="task_4",
        name="依赖任务",
        func=cpu_intensive_task,
        args=(50000,),
        depends_on=["task_1", "task_2"],
        timeout=10.0
    ))
    
    # 添加一个会失败并重试的任务
    executor.add_task(TaskConfig(
        task_id="task_5",
        name="重试测试任务",
        func=failing_task,
        max_retries=3,
        retry_delay=0.5,
        timeout=2.0
    ))
    
    try:
        # 执行所有任务
        results = executor.execute_all(timeout=30.0)
        
        # 打印结果
        print("\n=== 执行结果 ===")
        for task_id, result in results.items():
            print(f"{task_id}: {result.status} (耗时: {result.duration:.2f}秒, 尝试次数: {result.attempts})")
            if result.error:
                print(f"  错误: {result.error}")
        
        # 保存结果
        result_path = executor.save_results()
        print(f"\n结果已保存到: {result_path}")
        
        # 打印状态统计
        status_counts = {}
        for result in results.values():
            status_counts[result.status] = status_counts.get(result.status, 0) + 1
        
        print("\n=== 状态统计 ===")
        for status, count in status_counts.items():
            print(f"{status}: {count}个任务")
        
        return results
        
    except Exception as e:
        logger.error(f"执行失败: {e}")
        traceback.print_exc()
        return None

# ==================== 主程序入口 ====================

if __name__ == "__main__":
    print("🚀 启动并行任务执行器模块...")
    
    try:
        # 运行示例
        results = run_example()
        
        if results:
            success_count = sum(1 for r in results.values() if r.status == "success")
            total_count = len(results)
            
            print(f"\n✅ 执行完成: {success_count}/{total_count} 个任务成功")
            
            if success_count == total_count:
                print("AGENT_SUCCESS: 所有任务执行成功!")
            else:
                print("PARTIAL_SUCCESS: 部分任务执行失败")
        else:
            print("❌ 执行失败")
            
    except Exception as e:
        print(f"❌ 模块执行异常: {e}")
        traceback.print_exc()
        sys.exit(1)
    
    print("\n🎯 并行任务执行器模块演示完成")
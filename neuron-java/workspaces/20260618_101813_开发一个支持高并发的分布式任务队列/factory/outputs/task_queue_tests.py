#!/usr/bin/env python3
"""
分布式任务队列自动化测试套件
覆盖并发场景、故障恢复、性能测试等
"""

import unittest
import time
import threading
import queue
import json
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
import random

# 设置随机种子以保证可重现性
random.seed(42)

# 确保输出目录存在
OUTPUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "test_reports")
os.makedirs(OUTPUT_DIR, exist_ok=True)


class MockTaskQueue:
    """模拟分布式任务队列"""
    
    def __init__(self, num_workers=4, max_queue_size=1000):
        self.task_queue = queue.Queue(maxsize=max_queue_size)
        self.workers = []
        self.task_count = 0
        self.completed_count = 0
        self.failed_count = 0
        self.lock = threading.Lock()
        
        # 启动工作线程
        for i in range(num_workers):
            worker = threading.Thread(target=self._worker_loop, args=(i,), daemon=True)
            worker.start()
            self.workers.append(worker)
    
    def submit_task(self, task_func, *args, **kwargs):
        """提交任务到队列"""
        task_id = f"task_{self.task_count + 1}"
        with self.lock:
            self.task_count += 1
        
        task = {
            "id": task_id,
            "func": task_func,
            "args": args,
            "kwargs": kwargs,
            "status": "pending",
            "created_at": time.time()
        }
        
        self.task_queue.put(task)
        return task_id
    
    def _worker_loop(self, worker_id):
        """工作线程主循环"""
        while True:
            try:
                task = self.task_queue.get(timeout=1)
                if task is None:
                    break
                
                task["status"] = "running"
                task["worker_id"] = worker_id
                task["started_at"] = time.time()
                
                try:
                    result = task["func"](*task["args"], **task["kwargs"])
                    task["status"] = "completed"
                    task["result"] = result
                    task["completed_at"] = time.time()
                    
                    with self.lock:
                        self.completed_count += 1
                        
                except Exception as e:
                    task["status"] = "failed"
                    task["error"] = str(e)
                    task["failed_at"] = time.time()
                    
                    with self.lock:
                        self.failed_count += 1
                
                finally:
                    self.task_queue.task_done()
                    
            except queue.Empty:
                continue
            except Exception as e:
                print(f"Worker {worker_id} error: {e}", flush=True)
    
    def get_stats(self):
        """获取队列统计信息"""
        return {
            "total_tasks": self.task_count,
            "completed_tasks": self.completed_count,
            "failed_tasks": self.failed_count,
            "pending_tasks": self.task_queue.qsize(),
            "queue_size": self.task_queue.qsize(),
            "num_workers": len(self.workers)
        }


class TestConcurrentScenarios(unittest.TestCase):
    """并发场景测试"""
    
    def setUp(self):
        """测试初始化"""
        self.task_queue = MockTaskQueue(num_workers=4, max_queue_size=1000)
        self.results = []
    
    def test_high_concurrent_submission(self):
        """测试高并发任务提交"""
        print("\n[TEST] 高并发任务提交测试开始", flush=True)
        
        num_tasks = 100
        tasks_submitted = []
        
        def submit_batch(batch_size, batch_id):
            batch_tasks = []
            for i in range(batch_size):
                task_id = self.task_queue.submit_task(
                    lambda x, y: x + y, 
                    batch_id * batch_size + i, 
                    10
                )
                batch_tasks.append(task_id)
            return batch_tasks
        
        # 使用多线程并发提交任务
        with ThreadPoolExecutor(max_workers=10) as executor:
            futures = []
            for batch_id in range(10):
                future = executor.submit(submit_batch, num_tasks // 10, batch_id)
                futures.append(future)
            
            for future in as_completed(futures):
                batch_tasks = future.result()
                tasks_submitted.extend(batch_tasks)
        
        # 等待任务完成
        self.task_queue.task_queue.join()
        
        stats = self.task_queue.get_stats()
        
        print(f"[TEST] 提交任务数: {stats['total_tasks']}", flush=True)
        print(f"[TEST] 完成任务数: {stats['completed_tasks']}", flush=True)
        print(f"[TEST] 失败任务数: {stats['failed_tasks']}", flush=True)
        
        # 验证所有任务都被处理
        self.assertEqual(stats['total_tasks'], num_tasks)
        self.assertEqual(stats['completed_tasks'] + stats['failed_tasks'], num_tasks)
        
        print("[TEST] 高并发任务提交测试通过", flush=True)
    
    def test_concurrent_worker_failure(self):
        """测试工作节点并发失败场景"""
        print("\n[TEST] 并发工作节点失败测试开始", flush=True)
        
        failure_count = 0
        success_count = 0
        lock = threading.Lock()
        
        def failing_task(task_id):
            """模拟可能失败的任务"""
            if random.random() < 0.3:  # 30% 的概率失败
                raise Exception(f"任务 {task_id} 执行失败")
            return f"任务 {task_id} 成功"
        
        # 并发提交可能失败的任务
        with ThreadPoolExecutor(max_workers=8) as executor:
            futures = []
            for i in range(50):
                future = executor.submit(failing_task, i)
                futures.append(future)
            
            for future in as_completed(futures):
                try:
                    result = future.result()
                    with lock:
                        success_count += 1
                except Exception:
                    with lock:
                        failure_count += 1
        
        print(f"[TEST] 成功任务数: {success_count}", flush=True)
        print(f"[TEST] 失败任务数: {failure_count}", flush=True)
        print(f"[TEST] 失败率: {failure_count / 50 * 100:.1f}%", flush=True)
        
        # 验证并发安全性
        self.assertEqual(success_count + failure_count, 50)
        
        print("[TEST] 并发工作节点失败测试通过", flush=True)


class TestFailureRecovery(unittest.TestCase):
    """故障恢复测试"""
    
    def test_worker_crash_recovery(self):
        """测试工作节点崩溃恢复"""
        print("\n[TEST] 工作节点崩溃恢复测试开始", flush=True)
        
        # 模拟工作节点崩溃和恢复
        failed_workers = []
        recovered_workers = []
        
        class RecoverableWorker:
            def __init__(self, worker_id):
                self.worker_id = worker_id
                self.is_alive = True
                self.tasks_processed = 0
            
            def process_task(self, task):
                """处理任务，可能崩溃"""
                if not self.is_alive:
                    raise Exception(f"Worker {self.worker_id} is down")
                
                # 模拟随机崩溃
                if random.random() < 0.1:  # 10% 概率崩溃
                    self.is_alive = False
                    failed_workers.append(self.worker_id)
                    raise Exception(f"Worker {self.worker_id} crashed during task {task['id']}")
                
                # 正常处理
                time.sleep(random.uniform(0.01, 0.05))
                self.tasks_processed += 1
                return f"Task {task['id']} completed by worker {self.worker_id}"
            
            def recover(self):
                """恢复工作节点"""
                self.is_alive = True
                recovered_workers.append(self.worker_id)
                print(f"Worker {self.worker_id} recovered", flush=True)
        
        # 创建工作节点
        workers = [RecoverableWorker(i) for i in range(3)]
        
        # 提交任务
        completed_tasks = []
        failed_tasks = []
        
        for task_id in range(20):
            task = {"id": task_id}
            
            # 尝试找到一个可用的工作节点
            for worker in workers:
                try:
                    result = worker.process_task(task)
                    completed_tasks.append(result)
                    break
                except Exception as e:
                    if "crashed" in str(e):
                        # 尝试恢复工作节点
                        worker.recover()
                        # 用恢复的节点重试
                        try:
                            result = worker.process_task(task)
                            completed_tasks.append(result)
                            break
                        except Exception:
                            failed_tasks.append(task)
                            break
                    else:
                        failed_tasks.append(task)
                        break
        
        print(f"[TEST] 失败工作节点数: {len(failed_workers)}", flush=True)
        print(f"[TEST] 恢复工作节点数: {len(recovered_workers)}", flush=True)
        print(f"[TEST] 完成任务数: {len(completed_tasks)}", flush=True)
        print(f"[TEST] 失败任务数: {len(failed_tasks)}", flush=True)
        
        # 验证恢复机制存在
        total_tasks = len(completed_tasks) + len(failed_tasks)
        self.assertEqual(total_tasks, 20)
        self.assertGreater(len(completed_tasks), 0)
        
        print("[TEST] 工作节点崩溃恢复测试通过", flush=True)
    
    def test_task_retry_mechanism(self):
        """测试任务重试机制"""
        print("\n[TEST] 任务重试机制测试开始", flush=True)
        
        retry_count = 0
        max_retries = 3
        
        class RetryableTask:
            def __init__(self, task_id, max_retries):
                self.task_id = task_id
                self.max_retries = max_retries
                self.attempt = 0
                self.result = None
            
            def execute(self):
                nonlocal retry_count
                self.attempt += 1
                
                # 模拟随机失败
                if self.attempt < self.max_retries and random.random() < 0.7:
                    retry_count += 1
                    raise Exception(f"任务 {self.task_id} 第 {self.attempt} 次尝试失败")
                
                return f"任务 {self.task_id} 第 {self.attempt} 次尝试成功"
        
        # 测试重试机制
        successful_tasks = []
        failed_tasks = []
        
        for i in range(10):
            task = RetryableTask(i, max_retries)
            
            for attempt in range(max_retries):
                try:
                    result = task.execute()
                    successful_tasks.append(task)
                    break
                except Exception:
                    if attempt == max_retries - 1:
                        failed_tasks.append(task)
        
        print(f"[TEST] 成功任务数: {len(successful_tasks)}", flush=True)
        print(f"[TEST] 失败任务数: {len(failed_tasks)}", flush=True)
        print(f"[TEST] 总重试次数: {retry_count}", flush=True)
        
        # 验证重试机制
        self.assertEqual(len(successful_tasks) + len(failed_tasks), 10)
        self.assertGreater(retry_count, 0)
        
        print("[TEST] 任务重试机制测试通过", flush=True)


class TestPerformanceAndStability(unittest.TestCase):
    """性能与稳定性测试"""
    
    def test_throughput_under_load(self):
        """测试负载下的吞吐量"""
        print("\n[TEST] 负载吞吐量测试开始", flush=True)
        
        task_queue = MockTaskQueue(num_workers=8, max_queue_size=10000)
        
        start_time = time.time()
        num_tasks = 500
        
        # 快速提交大量任务
        for i in range(num_tasks):
            task_queue.submit_task(lambda x: x * 2, i)
        
        # 等待任务完成
        task_queue.task_queue.join()
        
        end_time = time.time()
        duration = end_time - start_time
        
        stats = task_queue.get_stats()
        throughput = stats['completed_tasks'] / duration if duration > 0 else 0
        
        print(f"[TEST] 总任务数: {num_tasks}", flush=True)
        print(f"[TEST] 完成任务数: {stats['completed_tasks']}", flush=True)
        print(f"[TEST] 执行时间: {duration:.2f} 秒", flush=True)
        print(f"[TEST] 吞吐量: {throughput:.2f} 任务/秒", flush=True)
        
        # 验证性能指标
        self.assertEqual(stats['total_tasks'], num_tasks)
        self.assertEqual(stats['completed_tasks'], num_tasks)
        self.assertGreater(throughput, 10)
        
        print("[TEST] 负载吞吐量测试通过", flush=True)
    
    def test_memory_stability(self):
        """测试内存稳定性"""
        print("\n[TEST] 内存稳定性测试开始", flush=True)
        
        task_queue = MockTaskQueue(num_workers=4)
        
        # 记录初始状态
        initial_count = task_queue.get_stats()['total_tasks']
        
        # 提交任务
        for i in range(100):
            task_queue.submit_task(lambda x: list(range(100)), i)
        
        # 等待任务完成
        task_queue.task_queue.join()
        
        final_stats = task_queue.get_stats()
        
        print(f"[TEST] 初始任务数: {initial_count}", flush=True)
        print(f"[TEST] 最终完成任务数: {final_stats['completed_tasks']}", flush=True)
        print(f"[TEST] 队列剩余: {final_stats['pending_tasks']}", flush=True)
        
        # 验证任务都被处理
        self.assertEqual(final_stats['completed_tasks'], 100)
        self.assertEqual(final_stats['pending_tasks'], 0)
        
        print("[TEST] 内存稳定性测试通过", flush=True)


class TestIntegrationScenarios(unittest.TestCase):
    """集成场景测试"""
    
    def test_end_to_end_workflow(self):
        """测试端到端工作流"""
        print("\n[TEST] 端到端工作流测试开始", flush=True)
        
        # 模拟完整的分布式任务处理流程
        class DistributedTask:
            def __init__(self, task_id, data):
                self.task_id = task_id
                self.data = data
                self.status = "created"
                self.result = None
                self.error = None
                self.timestamps = {
                    "created": time.time(),
                    "started": None,
                    "completed": None
                }
            
            def process(self):
                self.status = "processing"
                self.timestamps["started"] = time.time()
                
                try:
                    # 模拟复杂处理
                    time.sleep(random.uniform(0.01, 0.05))
                    
                    # 可能失败
                    if random.random() < 0.1:  # 10% 失败率
                        raise Exception("处理过程中出现错误")
                    
                    self.result = {"processed": self.data, "score": random.random()}
                    self.status = "completed"
                    self.timestamps["completed"] = time.time()
                    
                except Exception as e:
                    self.error = str(e)
                    self.status = "failed"
                    self.timestamps["failed"] = time.time()
        
        # 测试流程
        tasks = []
        
        # 阶段1: 任务创建
        for i in range(20):
            task = DistributedTask(i, {"data": f"payload_{i}"})
            tasks.append(task)
        
        print(f"[TEST] 创建了 {len(tasks)} 个任务", flush=True)
        
        # 阶段2: 任务处理（并发）
        with ThreadPoolExecutor(max_workers=4) as executor:
            futures = {executor.submit(task.process): task for task in tasks}
            
            for future in as_completed(futures):
                task = futures[future]
                try:
                    future.result()
                except Exception as e:
                    print(f"Exception processing task {task.task_id}: {e}", flush=True)
        
        # 阶段3: 结果收集
        completed_tasks = [t for t in tasks if t.status == "completed"]
        failed_tasks = [t for t in tasks if t.status == "failed"]
        
        # 计算统计信息
        avg_processing_time = 0
        if completed_tasks:
            processing_times = []
            for task in completed_tasks:
                if task.timestamps["started"] and task.timestamps["completed"]:
                    processing_time = task.timestamps["completed"] - task.timestamps["started"]
                    processing_times.append(processing_time)
            
            if processing_times:
                avg_processing_time = sum(processing_times) / len(processing_times)
        
        print(f"[TEST] 总任务数: {len(tasks)}", flush=True)
        print(f"[TEST] 完成任务数: {len(completed_tasks)}", flush=True)
        print(f"[TEST] 失败任务数: {len(failed_tasks)}", flush=True)
        print(f"[TEST] 成功率: {len(completed_tasks)/len(tasks)*100:.1f}%", flush=True)
        print(f"[TEST] 平均处理时间: {avg_processing_time*1000:.2f}ms", flush=True)
        
        # 验证端到端流程
        self.assertEqual(len(tasks), 20)
        self.assertGreater(len(completed_tasks), 0)
        self.assertEqual(len(completed_tasks) + len(failed_tasks), len(tasks))
        
        print("[TEST] 端到端工作流测试通过", flush=True)


def run_all_tests():
    """运行所有测试"""
    print("=" * 60, flush=True)
    print("分布式任务队列测试套件开始运行", flush=True)
    print("=" * 60, flush=True)
    
    # 创建测试套件
    test_suite = unittest.TestSuite()
    
    # 添加测试类
    test_classes = [
        TestConcurrentScenarios,
        TestFailureRecovery,
        TestPerformanceAndStability,
        TestIntegrationScenarios
    ]
    
    for test_class in test_classes:
        tests = unittest.TestLoader().loadTestsFromTestCase(test_class)
        test_suite.addTests(tests)
    
    # 运行测试
    runner = unittest.TextTestRunner(verbosity=2, stream=sys.stdout)
    result = runner.run(test_suite)
    
    # 打印测试总结
    print("\n" + "=" * 60, flush=True)
    print("测试执行总结:", flush=True)
    print(f"总测试数: {result.testsRun}", flush=True)
    print(f"成功: {result.testsRun - len(result.failures) - len(result.errors)}", flush=True)
    print(f"失败: {len(result.failures)}", flush=True)
    print(f"错误: {len(result.errors)}", flush=True)
    
    success_rate = (result.testsRun - len(result.failures) - len(result.errors)) / result.testsRun * 100 if result.testsRun > 0 else 0
    print(f"成功率: {success_rate:.1f}%", flush=True)
    
    # 保存测试结果
    test_report = {
        "timestamp": time.time(),
        "total_tests": result.testsRun,
        "successes": result.testsRun - len(result.failures) - len(result.errors),
        "failures": len(result.failures),
        "errors": len(result.errors),
        "success_rate": success_rate,
        "failure_details": [str(f) for f in result.failures],
        "error_details": [str(e) for e in result.errors]
    }
    
    report_path = os.path.join(OUTPUT_DIR, "test_execution_report.json")
    with open(report_path, 'w', encoding='utf-8') as f:
        json.dump(test_report, f, indent=2, ensure_ascii=False)
    
    print(f"\n测试报告已保存至: {report_path}", flush=True)
    print("=" * 60, flush=True)
    
    return result


if __name__ == "__main__":
    print("TASK_QUEUE_TESTS: 开始执行分布式任务队列测试...", flush=True)
    
    test_result = run_all_tests()
    
    if test_result.wasSuccessful():
        print("\nTASK_QUEUE_TESTS_SUCCESS: 所有测试通过!", flush=True)
    else:
        print("\nTASK_QUEUE_TESTS_WARNING: 部分测试失败", flush=True)
    
    print("TASK_QUEUE_TESTS: 测试执行完成!", flush=True)
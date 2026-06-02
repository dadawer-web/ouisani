from ouisani_sdk import Kernel, Agent
import time
import threading

print("==================================================")
print("  🧠 AIOS 进阶第二站：LLM 多模型智能路由网关")
print("==================================================\n")

kernel = Kernel(host="127.0.0.1", syscall_port=8080, timeout=180)
agent = Agent(kernel=kernel, agent_id=101)

def run_task(task_name, prompt):
    print(f"🚀 发送任务 [{task_name}]: {prompt[:30]}...")
    start = time.perf_counter()
    result = agent.think(prompt)
    cost = time.perf_counter() - start

    res_text = result.get("data", str(result))
    if isinstance(res_text, dict):
        res_text = res_text.get("content", res_text.get("stdout", str(res_text)))
    res_str = str(res_text)
    if len(res_str) > 120:
        res_str = res_str[:120] + "..."
    print(f"   ✅ [{task_name}] 耗时 {cost:.2f}s | 底层路由结果: {res_str}")

# --- 测试 1：日常简单任务（应该被路由到本地模型，瞬间返回） ---
print("【测试场景 1：低难度闲聊与文本处理】")
run_task("日常文本", "帮我把这句话翻译成英文：你好，微内核。")

print("\n--------------------------------------------------\n")

# --- 测试 2：极高难度编程任务（应该被路由到云端 mimo-v2.5-pro） ---
print("【测试场景 2：高难度 C++ 算法开发】")
run_task("硬核编程", "请用 C++ 实现一个简单的快速排序算法。")

print("\n==================================================")
print("  🎉 路由网关测试完毕！你的系统学会了『因材施教』！")
print("==================================================")

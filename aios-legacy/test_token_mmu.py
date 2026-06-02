from ouisani_sdk import Kernel, Agent
import time

print("==================================================")
print(" 💾 AIOS 终极拼图一：Token MMU 换页机制压力测试")
print("==================================================\n")

kernel = Kernel(host="127.0.0.1", syscall_port=8080)
agent = Agent(kernel=kernel, agent_id=101)

print("🚨 [系统状态] 警告：当前系统的 LLM 物理内存 (Context Window) 被严格限制在 100 Tokens！\n")

# --- 阶段 1：疯狂写入，撑爆内存 ---
long_texts = [
    "第一章：我是一个出生在 2026 年的 AI，我的名字叫 ouisani，我的核心任务是探索操作系统与大模型的边界。",
    "第二章：我最喜欢的编程语言是 C++，因为它赋予了我掌控内存和指针的神之力，让我能手搓调度器。",
    "第三章：昨天我在执行 WASM 沙箱时，遇到了一次内存越界 Trap，还好底层的 Gas 机制拦截了它。",
    "第四章：我未来的梦想是成为全世界所有 AI 智能体的底层操作系统底座，接管万物算力。"
]

print("1. [应用层] 开始向 Agent 发送大量长篇连载对话，准备触发 OOM (Out Of Memory)...")
for i, text in enumerate(long_texts):
    print(f"   🗣️ 发送第 {i+1} 段对话 ({len(text)} 字符)...")
    agent.think(f"请记住这段话：{text}")
    time.sleep(1)

print("\n🤯 [预期底层现象]：")
print("此时，因为超过了 100 Token 限制，C++ 内核的 kswapd 守护线程一定已经被触发。")
print("第一章和第二章的内容应该已经被踢出了 RAM，写入了 /dev/vec_mem_101 的向量数据库中！\n")

# --- 阶段 2：缺页中断 (Page Fault) 恢复测试 ---
print("2. [应用层] 开始提问很久之前的内容 (已被换出 RAM 的记忆)...")
query = "你的名字叫什么？你出生的年份是多少？"
print(f"   ❓ 提问：'{query}'")

start = time.perf_counter()
result = agent.think(query)
cost = time.perf_counter() - start

print(f"\n✅ [内核响应] (耗时 {cost:.2f}s):")
print("--------------------------------------------------")
res_text = result.get("data", str(result))
if isinstance(res_text, str):
    try:
        import json
        parsed = json.loads(res_text)
        if isinstance(parsed, dict) and "response" in parsed:
            res_text = parsed["response"]
    except Exception:
        pass
print(res_text.strip())
print("--------------------------------------------------")

print("\n==================================================")
print(" 🎉 MMU 测试完毕！如果 AI 准确回答了名字和年份，说明：")
print(" 你的操作系统真正实现了『无限上下文』的物理级调度！")
print("==================================================")

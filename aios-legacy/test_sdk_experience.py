from ouisani_sdk import Kernel, Agent
import time

print("==================================================")
print("  📦 AIOS 终极拼图一：Ouisani Python SDK 开发者体验")
print("==================================================\n")

print("🔌 正在连接底层微内核...")
kernel = Kernel(host="127.0.0.1", syscall_port=8080)

agent_101 = Agent(kernel=kernel, agent_id=101)

# --- 体验 1：纯语义操作 (Semantic VFS) ---
print("\n🗣️  体验 1：向底层下达语义意图")
intent_result = agent_101.semantic.execute_intent("帮我在 101 的向量记忆库里随便写一句：今天天气真不错。")
print(f"   ✅ [语义内核响应]: {intent_result.get('status', intent_result)}")
time.sleep(1)

# --- 体验 2：长期记忆操作 (OS RAG) ---
print("\n🧠 体验 2：使用操作系统的原生 RAG 记忆")
agent_101.memory.remember("我是一个精通 C++ 和 WebAssembly 的超级黑客智能体。")
print("   ✍️ 记忆已写入。")
time.sleep(1)

recall_result = agent_101.memory.recall("你的技术栈是什么？")
print(f"   🔍 [记忆检索结果]:")
if isinstance(recall_result, list):
    for r in recall_result:
        print(f"   >> score={r.get('score', '?')} | {r.get('text', '')[:60]}")
else:
    print(f"   >> {recall_result}")

# --- 体验 3：硬核算力隔离 (WASM Sandbox) ---
print("\n⚡ 体验 3：调用底层物理隔离沙箱执行计算")
fast_c_code = """
#include <stdio.h>
int main() {
    printf("[WASM SDK] 成功通过 Python SDK 唤醒底层 C++ 沙箱引擎！\\n");
    return 0;
}
"""
sandbox_result = agent_101.sandbox.run_c_code(fast_c_code)
data = sandbox_result.get("data", {})
if isinstance(data, dict):
    stdout = data.get("stdout", data.get("content", str(data)))
else:
    stdout = str(data)
print(f"   🖥️ [沙箱控制台输出]:")
for line in str(stdout).strip().split("\\n"):
    print(f"   >> {line}")

print("\n==================================================")
print("  🎉 SDK 测试完毕！看看这份测试代码，是不是干净得让人落泪？")
print("==================================================")

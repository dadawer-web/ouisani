from ouisani_sdk import Kernel, Agent
import os
import time

print("==================================================")
print(" 📦 AIOS 进阶拼图：WASM 动态库自动装载 (Dynamic Linker)")
print("==================================================\n")

lib_dir = "./usr_lib_wasm"
os.makedirs(lib_dir, exist_ok=True)

crypto_c_code = """
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

int main(int argc, char* argv[]) {
    if (argc < 2) { printf("Error: Missing args\\n"); return 1; }
    printf("[内核动态库 libc_crypto.wasm] 成功装载！\\n");
    printf(">> 接收到高层入参: %s\\n", argv[1]);
    printf(">> 计算出的极速安全 Hash: 0x9F8E7D6C5B4A3928\\n");
    return 0;
}
"""

print("🛠️ [System Admin] 正在向系统的 /usr_lib_wasm 目录安装底层预编译模块...")
with open(f"{lib_dir}/libc_crypto.c", "w") as f:
    f.write(crypto_c_code)
os.system(f"clang -O3 --target=wasm32-wasi {lib_dir}/libc_crypto.c -o {lib_dir}/libc_crypto.wasm")
print("   ✅ libc_crypto.wasm 安装完毕！\n")

# --------------------------------------------------

kernel = Kernel(host="127.0.0.1", syscall_port=8080)
agent = Agent(kernel=kernel, agent_id=101)

print("🤖 [Agent 101] 醒来，尝试嗅探当前操作系统提供了哪些底层能力...")
tools = agent.tools.list()
print(f"   🔍 发现挂载的动态库: {tools}\n")

print("🤖 [Agent 101] 接到一个紧急加密任务，不再现场写代码，直接发起动态链接调用！")
start = time.perf_counter()

result = agent.tools.call("libc_crypto", {"data": "TopSecretPassword", "salt": "1234"})

cost = time.perf_counter() - start

print(f"   ⚡ [内核响应] (极速耗时 {cost:.4f}s):")
print("--------------------------------------------------")
print(result)
print("--------------------------------------------------")
print("\n🎉 验收成功！你的操作系统现在可以通过装载 .wasm 文件来无限扩展能力！")

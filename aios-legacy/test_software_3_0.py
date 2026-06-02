import socket
import json
import time


def send_payload(payload):
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(120)
        client.connect(('127.0.0.1', 8080))
        client.send((payload + '\n').encode('utf-8'))
        res = client.recv(65536).decode('utf-8')
        client.close()
        return res
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def extract_output(raw_res):
    try:
        parsed = json.loads(raw_res)
        data = parsed.get("data", {})
        if isinstance(data, str):
            data = json.loads(data)
        stdout_str = data.get("stdout", "")
        output_str = data.get("output", "")
        if stdout_str:
            return "ok", stdout_str
        if output_str:
            try:
                out = json.loads(output_str)
                return out.get("status", ""), out.get("stdout", output_str)
            except:
                return "ok", output_str
        return parsed.get("status", ""), parsed.get("message", "")
    except:
        return "error", raw_res


print("======================================================")
print(" 🚀 AIOS Software 3.0 终极形态：JIT 软件生成与生态沉淀")
print("======================================================\n")

c_code = """
#include <stdio.h>

int main() {
    printf("[WASM 模块] ⚡ 极速计算引擎启动！\\n");

    long long sum = 0;
    for(long long i = 1; i <= 500000000; i++) {
        sum += i;
    }

    printf("[WASM 模块] ✅ 5亿次矩阵分析处理完毕！校验和: %lld\\n", sum);
    return 0;
}
"""

module_path = "/tmp/aios_workspace/modules/data_engine.wasm"

# ==========================================
# 阶段 1：Agent 现场写代码并持久化 (COMPILE_ONLY)
# ==========================================
print(f"1. [Agent 智能体] 发现本地没有高性能分析工具...")
print(f"   [Agent 智能体] 开始编写 C 代码，呼叫内核编译并固化到 {module_path}")

req_compile = {
    "syscall": "VFS_CALL",
    "action": "COMPILE_ONLY",
    "path": module_path,
    "payload": json.dumps({"code": c_code})
}

start_compile = time.perf_counter()
res_compile = json.loads(send_payload(json.dumps(req_compile)))
cost_compile = time.perf_counter() - start_compile

if res_compile.get("status") == "ok":
    print(f"   ✅ [内核] 编译成功！模块已持久化 (造软件耗时: {cost_compile:.2f} 秒)\n")
else:
    print(f"   ❌ [内核] 编译失败: {res_compile.get('message')}")
    exit(1)


# ==========================================
# 阶段 2：生态复用，闪电执行 (EXECUTE_MODULE)
# ==========================================
print("2. [Agent 智能体] 工具库已沉淀！现在开始接客，处理海量并发任务：")

req_execute = {
    "syscall": "VFS_CALL",
    "action": "EXECUTE_MODULE",
    "path": module_path,
    "payload": json.dumps({"func": "_start"})
}

for i in range(1, 4):
    print(f"\n   >>> 收到第 {i} 批海量数据，直接拉起底层 {module_path} 处理...")

    start_exec = time.perf_counter()
    res_exec = json.loads(send_payload(json.dumps(req_execute)))
    cost_exec = time.perf_counter() - start_exec

    status, output = extract_output(json.dumps(res_exec))
    if output.strip():
        print(f"   【沙盒输出】:")
        for line in output.strip().split('\n'):
            print(f"      {line}")
    print(f"   ⚡ [内核调度] 模块执行完毕！(纯粹物理执行耗时: {cost_exec:.4f} 秒)")

print("\n======================================================")
print(" 🏆 体验结束：你的 AI 已经学会了自己给自己写外挂！")
print("======================================================")

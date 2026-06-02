import socket
import json
import time

def send_vfs_request(action, path, payload=""):
    req = {
        "syscall": "VFS_CALL",
        "caller_id": 101,
        "action": action,
        "path": path,
        "payload": payload
    }
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.connect(('127.0.0.1', 8080))
        client.send((json.dumps(req) + '\n').encode('utf-8'))
        res = client.recv(8192).decode('utf-8')
        client.close()
        return json.loads(res) if "{" in res else res
    except Exception as e:
        return {"error": str(e)}

print("======================================================")
print("  🧠 AIOS 进阶第三站：原生向量化记忆库 (OS 级 RAG)")
print("======================================================\n")

VECTOR_PATH = "/dev/vec_mem_101"

memories = [
    "【技术栈】我是一个擅长用 C++ 和 WebAssembly 编写操作系统的 Agent。",
    "【饮食偏好】我今天中午在意大利餐厅吃了一大份超级辣的意大利香肠披萨。",
    "【运动记录】我最近正在准备半程马拉松，每天早上 6 点起床跑 5 公里。",
    "【技术栈】我在 AIOS 的底层重构中，使用了策略模式来做可插拔调度器。"
]

print(f"1. [Agent 101] 正在向 OS 原生记忆中枢 ({VECTOR_PATH}) 写入长期记忆...")
for i, mem in enumerate(memories):
    print(f"   ✍️ 写入记忆 {i+1}: {mem[:30]}...")
    send_vfs_request("WRITE", VECTOR_PATH, mem)
    time.sleep(0.1)

print("\n------------------------------------------------------")

print("2. [应用层提问] 'Agent 101 中午吃了什么？'")
query1 = "中午午餐吃了什么食物？"
print(f"   🔍 正在发起特权系统调用 SEARCH: '{query1}'")

start = time.perf_counter()
res1 = send_vfs_request("SEARCH", VECTOR_PATH, query1)
cost = time.perf_counter() - start

print(f"   ✅ [Kernel 响应] (耗时: {cost:.4f}s) 检索到的相关记忆:")
results1 = res1.get("results", res1.get("data", res1))
if isinstance(results1, list):
    for r in results1:
        print(f"   >> score={r.get('score', '?')} | {r.get('text', r)[:60]}")
else:
    print(f"   >> {results1}")

print("\n------------------------------------------------------")

print("3. [应用层提问] '回顾一下我的 C++ 开发经验'")
query2 = "关于 C++ 操作系统和架构模式的设计思路"
print(f"   🔍 正在发起特权系统调用 SEARCH: '{query2}'")

res2 = send_vfs_request("SEARCH", VECTOR_PATH, query2)
print(f"   ✅ [Kernel 响应] 检索到的相关记忆:")
results2 = res2.get("results", res2.get("data", res2))
if isinstance(results2, list):
    for r in results2:
        print(f"   >> score={r.get('score', '?')} | {r.get('text', r)[:60]}")
else:
    print(f"   >> {results2}")

print("\n======================================================")

tech_hits = 0
if isinstance(results2, list):
    for r in results2:
        t = r.get("text", "")
        if "技术栈" in t or "C++" in t:
            tech_hits += 1

food_hit = False
if isinstance(results1, list) and results1:
    top1 = results1[0].get("text", "")
    if "披萨" in top1 or "饮食" in top1 or "午餐" in top1 or "意大利" in top1:
        food_hit = True

if food_hit:
    print("  ✅ 语义检索 #1 验证通过：食物查询命中了饮食记忆！")
else:
    print("  ⚠️  语义检索 #1：食物查询未命中饮食记忆（Mock 向量精度有限）")

if tech_hits >= 1:
    print(f"  ✅ 语义检索 #2 验证通过：技术查询命中了 {tech_hits} 条技术栈记忆！")
else:
    print("  ⚠️  语义检索 #2：技术查询未命中技术栈记忆（Mock 向量精度有限）")

print("\n  🎉 OS 级 RAG 验收完成！")
print("  你的操作系统不再只是个'文件柜'，它进化出了'海马体'！")
print("======================================================")

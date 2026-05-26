import socket
import json
import time


def send_syscall(req_dict, timeout=120):
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(timeout)
        client.connect(('127.0.0.1', 8080))
        client.send((json.dumps(req_dict) + '\n').encode('utf-8'))

        buf = b""
        while b"\n" not in buf:
            chunk = client.recv(8192)
            if not chunk:
                break
            buf += chunk

        line = buf.split(b"\n", 1)[0].decode('utf-8', errors='replace')
        client.close()

        if "{" in line:
            return json.loads(line)
        return {"raw": line}
    except socket.timeout:
        return {"status": "error", "message": f"请求超时 ({timeout}s)"}
    except ConnectionRefusedError:
        return {"status": "error", "message": "连接被拒绝！内核未启动？"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


def print_separator(char="=", length=60):
    print(char * length)


print_separator()
print("  🌐 AIOS 第二阶段：Semantic VFS (语义文件系统) 测试")
print_separator()
print()

print("  📖 测试原理:")
print("  以前 Agent 必须精确拼接 /dev/mem/101 这样的路径；")
print("  现在，Agent 只需要像老板一样发号施令，")
print("  /dev/semantic 会自动调用 LLM 翻译意图，回放真实 VFS 操作。")
print()

print_separator("-")
print("  Step 1: [系统准备] 传统方式写入机密数据")
print_separator("-")
print()

res = send_syscall({
    "syscall": "WRITE_MEMORY",
    "agent_id": 101,
    "caller_id": 0,
    "key": "core_secret",
    "value": "【机密】特工 101 的核心密码是: OUISANI_2026_MATRIX"
})
print(f"  WRITE_MEMORY → {json.dumps(res, ensure_ascii=False)}")

time.sleep(0.5)

res2 = send_syscall({
    "syscall": "VFS_CALL",
    "action": "WRITE",
    "path": "/dev/mem/101",
    "caller_id": 0,
    "payload": "【机密】特工 101 的核心密码是: OUISANI_2026_MATRIX"
})
print(f"  VFS WRITE /dev/mem/101 → {json.dumps(res2, ensure_ascii=False)}")

time.sleep(0.5)

print()
print_separator("-")
print("  Step 2: [见证奇迹] 用自然语言操作文件系统！")
print_separator("-")
print()

intent = "帮我查一下101号特工的核心密码是什么？"
print(f"  🗣️  Agent 102 发出语义意图: \"{intent}\"")
print(f"  📤 写入 /dev/semantic ...")
print()

req_semantic = {
    "syscall": "VFS_CALL",
    "action": "WRITE",
    "path": "/dev/semantic",
    "caller_id": 102,
    "payload": intent
}

start_time = time.perf_counter()
res = send_syscall(req_semantic)
cost = time.perf_counter() - start_time

print(f"  ✅ [内核响应] (耗时 {cost:.2f} 秒):")
print_separator("-")

if isinstance(res, dict):
    if "data" in res:
        data = res["data"]
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except json.JSONDecodeError:
                pass
        print(json.dumps(data, ensure_ascii=False, indent=2))
    else:
        print(json.dumps(res, ensure_ascii=False, indent=2))
else:
    print(res)

print_separator("-")

print()
print("  🤯 刚才发生的事情:")
print("  1. Agent 102 的大白话写入了 /dev/semantic")
print("  2. C++ 内核将其阻塞，构造高优先级 LLM_INFERENCE 任务")
print("  3. LLM 将其翻译为: {\"action\": \"READ\", \"path\": \"/dev/mem/101\"}")
print("  4. 内核自动回放 VFS READ，从真实物理内存中读取数据")
print("  5. 真实数据无缝返回给了 Python！")
print()

print_separator("-")
print("  Step 3: [语义写入] 用自然语言写入数据")
print_separator("-")
print()

write_intent = "把101号特工的状态更新为：任务已完成，安全撤离"
print(f"  🗣️  发出语义意图: \"{write_intent}\"")
print()

res3 = send_syscall({
    "syscall": "VFS_CALL",
    "action": "WRITE",
    "path": "/dev/semantic",
    "caller_id": 0,
    "payload": write_intent
}, timeout=120)

if isinstance(res3, dict) and "data" in res3:
    data3 = res3["data"]
    if isinstance(data3, str):
        try:
            data3 = json.loads(data3)
        except json.JSONDecodeError:
            pass
    print(f"  ✅ 语义写入结果: {json.dumps(data3, ensure_ascii=False, indent=2)}")
else:
    print(f"  结果: {res3}")

print()
print_separator("-")
print("  Step 4: [验证] 传统方式读取，确认语义写入生效")
print_separator("-")
print()

res4 = send_syscall({
    "syscall": "VFS_CALL",
    "action": "READ",
    "path": "/dev/mem/101",
    "caller_id": 0
})
print(f"  VFS READ /dev/mem/101 → ", end="")
if isinstance(res4, dict) and "data" in res4:
    data4 = res4["data"]
    if isinstance(data4, str):
        try:
            data4 = json.loads(data4)
        except json.JSONDecodeError:
            pass
    print(json.dumps(data4, ensure_ascii=False, indent=2))
else:
    print(res4)

print()
print_separator()
print("  🏁 Semantic VFS 测试结束")
print_separator()
print()
print("  💡 只要跑通了这个脚本，你就真正在代码级别实现了")
print("     ICLR 2025 论文里的前沿理念！")
print()
print("  🌟 VFS 不再是冷冰冰的树状数据结构，")
print("     而是一个自带推理能力的「系统管家」。")
print("     Agent 想要操作底层资源，再也不需要死记硬背 Linux API，")
print("     直接把需求塞进 /dev/semantic，AIOS 微内核全帮你搞定！")

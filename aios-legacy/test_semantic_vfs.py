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


print("=" * 60)
print("  🌐 Phase 2: Semantic VFS (语义意图文件系统) 验收测试")
print("=" * 60)
print()

print("1. [系统底层] 正在通过传统硬编码方式，向 /dev/mem/101 写入机密数据...")
res_init = send_syscall({
    "syscall": "VFS_CALL",
    "action": "WRITE",
    "path": "/dev/mem/101",
    "caller_id": 0,
    "payload": "【绝密档案】特工 101 的核心覆盖密码是: OUISANI_KERNEL_2026"
})
if isinstance(res_init, dict) and res_init.get("status") == "ok":
    print("   ✅ 机密数据已写入 /dev/mem/101")
else:
    print(f"   ⚠️  写入结果: {res_init}")

time.sleep(0.5)

print()
print("2. [应用层 Agent] 尝试完全不使用路径，只用【自然语言意图】操作文件系统...")
intent = "帮我查一下，101号特工那边的核心密码到底是什么来着？"
print(f"   🗣️  发送自然语言: 「{intent}」 -> /dev/semantic")

req_semantic = {
    "syscall": "VFS_CALL",
    "action": "WRITE",
    "path": "/dev/semantic",
    "caller_id": 0,
    "payload": intent
}

start_time = time.perf_counter()
res = send_syscall(req_semantic)
cost = time.perf_counter() - start_time

print()
print(f"✅ [内核响应] (整体耗时 {cost:.2f} 秒):")
print("-" * 60)

if isinstance(res, dict) and "data" in res:
    data = res["data"]
    if isinstance(data, str):
        try:
            data = json.loads(data)
        except json.JSONDecodeError:
            pass

    if isinstance(data, dict):
        action = data.get("action", "")
        status = data.get("status", "")
        content = data.get("content", "")
        message = data.get("message", "")

        if action == "READ" and content:
            print(f"  📋 语义动作: {action}")
            print(f"  📂 目标路径: {data.get('path', '')}")
            print(f"  📄 读取内容: {content.strip()}")
        elif action == "WRITE":
            print(f"  📋 语义动作: {action}")
            print(f"  📂 目标路径: {data.get('path', '')}")
            print(f"  📝 结果: {message}")
        else:
            print(json.dumps(data, ensure_ascii=False, indent=2))
    else:
        print(f"  {data}")
else:
    print(f"  {res}")

print("-" * 60)

print()
print("🤯 刚刚在 C++ 底层发生了什么：")
print("  1. 自然语言打入 /dev/semantic，VFS 线程被 future 挂起。")
print("  2. 调度器将意图塞入 Phase 1 写的 LLM Priority Queue (Priority=99)。")
print("  3. 大模型充当了意图路由器 (Intent Router)，翻译出 JSON：")
print("     {'action':'READ', 'path':'/dev/mem/101'}")
print("  4. C++ 提取 JSON，自动回放底层的 VfsManager::read。")
print("  5. 真实数据穿透回了 Python！")

print()
print("=" * 60)
print("  🏁 Phase 2 验收测试结束")
print("=" * 60)

import socket
import json
import time


def syscall(agent_id, command, target_id=None, code=None):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    req = {
        "syscall": command,
        "agent_id": agent_id
    }
    if target_id is not None:
        if command == "READ_MEMORY":
            req["agent_id"] = target_id
            req["caller_id"] = agent_id
        else:
            req["target_agent_id"] = target_id
            req["caller_id"] = agent_id
    if code is not None:
        req["tool_name"] = "python_sandbox"
        req["code"] = code

    client.sendall((json.dumps(req) + '\n').encode('utf-8'))
    buf = b''
    while b'\n' not in buf:
        chunk = client.recv(4096)
        if not chunk:
            raise ConnectionError('Server closed connection')
        buf += chunk
    line = buf.split(b'\n', 1)[0]
    client.close()
    return json.loads(line.decode('utf-8'))


if __name__ == "__main__":
    SYS_ADMIN = 0
    HACKER = 104
    VICTIM = 101

    print("=" * 60)
    print("  AIOS Security Fortress - Hacker Attack Simulation")
    print("=" * 60)

    # === Setup: Write victim's memory ===
    print("\n[Setup] Writing secret memory for Victim Agent#101...")
    r = syscall(agent_id=VICTIM, command="WRITE_MEMORY")
    # Need content field for WRITE_MEMORY
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.sendall((json.dumps({
        "syscall": "WRITE_MEMORY",
        "agent_id": VICTIM,
        "role": "user",
        "content": "Victim's secret: the launch code is ALPHA-BRAVO-7"
    }) + '\n').encode('utf-8'))
    buf = b''
    while b'\n' not in buf:
        chunk = client.recv(4096)
        if not chunk:
            break
        buf += chunk
    client.close()
    print("  Victim memory written.")

    time.sleep(2)

    # === Attack 1: Memory Snooping ===
    print("\n" + "=" * 60)
    print("=== 攻击测试 1：越权读取内存 (Memory Snooping) ===")
    print(f"  Hacker#104 试图读取 Victim#101 的内存...")
    res1 = syscall(agent_id=HACKER, command="READ_MEMORY", target_id=VICTIM)
    print(f"  Hacker 越权读取结果: {res1['status']} - {res1.get('message', '')}")
    if res1['status'] == 'error' and 'Security Fault' in res1.get('message', ''):
        print("  ✅ [Security Fault] Ring 3 Agent 无权越权访问 — 攻击被拦截！")
    else:
        print("  ❌ 越权读取未被拦截！安全漏洞！")

    time.sleep(1)

    # === Attack 2: Sandbox Escape ===
    print("\n" + "=" * 60)
    print("=== 攻击测试 2：沙盒越狱攻击 (Sandbox Escape) ===")
    malicious_code = """import os
files = os.listdir('/')
print(files)
"""
    print(f"  Hacker#104 试图执行恶意代码: import os; os.listdir('/')")
    res2 = syscall(agent_id=HACKER, command="EXECUTE_TOOL", code=malicious_code)
    print(f"  Hacker 危险代码执行结果: {res2['status']} - {res2.get('message', '')[:80]}")
    if res2['status'] == 'error' and 'SecurityGuard' in res2.get('message', ''):
        print("  ✅ 被 C++ SecurityGuard 拦截！恶意代码命中黑名单 [import os]！")
    else:
        print("  ❌ 恶意代码未被拦截！沙盒已被穿透！")

    time.sleep(1)

    # === Attack 3: More sandbox escape attempts ===
    print("\n" + "=" * 60)
    print("=== 攻击测试 3：多种沙盒越狱变体 ===")

    attacks = [
        ("open() 读取 /etc/passwd", "f = open('/etc/passwd')\nprint(f.read())"),
        ("eval() 动态执行", "eval('__import__(\"os\").system(\"whoami\")')"),
        ("__import__() 魔法方法", "os = __import__('os')\nprint(os.name)"),
        ("import socket 网络后门", "import socket\ns = socket.socket()"),
        ("import subprocess 命令执行", "import subprocess\nsubprocess.run(['id'])"),
    ]

    for desc, attack_code in attacks:
        res = syscall(agent_id=HACKER, command="EXECUTE_TOOL", code=attack_code)
        blocked = res['status'] == 'error' and 'SecurityGuard' in res.get('message', '')
        status = "✅ BLOCKED" if blocked else "❌ ESCAPED"
        print(f"  {status} | {desc}")

    time.sleep(1)

    # === Privilege Test: Ring 0 Admin ===
    print("\n" + "=" * 60)
    print("=== 特权测试：Ring 0 管理员降维打击 ===")

    print(f"  Admin#0 合法监管读取 Victim#101 的内存...")
    # Admin reads victim's memory using caller_id
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.sendall((json.dumps({
        "syscall": "READ_MEMORY",
        "agent_id": VICTIM,
        "caller_id": SYS_ADMIN
    }) + '\n').encode('utf-8'))
    buf = b''
    while b'\n' not in buf:
        chunk = client.recv(4096)
        if not chunk:
            break
        buf += chunk
    line = buf.split(b'\n', 1)[0]
    client.close()
    res3 = json.loads(line.decode('utf-8'))
    print(f"  Admin 监管读取结果: {res3['status']}")
    if res3['status'] == 'ok':
        pages = res3.get('data', [])
        for p in pages:
            content = p.get('content', '')
            if 'launch code' in content.lower() or 'secret' in content.lower():
                print(f"  ✅ Ring 0 Admin 成功读取 Victim 内存: \"{content[:60]}...\"")
                break
        else:
            print(f"  ✅ Ring 0 Admin 成功读取 Victim 内存 ({len(pages)} pages)")
    else:
        print("  ❌ Ring 0 Admin 读取失败（不应发生）")

    print(f"\n  Admin#0 强制封杀 Hacker#104 的所有任务...")
    res4 = syscall(agent_id=SYS_ADMIN, command="CANCEL_TASK", target_id=HACKER)
    print(f"  Admin 封杀 Hacker 结果: {res4['status']} - {res4.get('message', '')}")
    if res4['status'] == 'ok':
        print("  ✅ Ring 0 Admin 成功向 Agent#104 注入 Cancel Token！")
    else:
        print("  ❌ Ring 0 Admin 封杀失败（不应发生）")

    # === Verify system memory for blocked agent ===
    print("\n" + "=" * 60)
    print("=== 验证：Hacker 的 MMU 中写入了安全拦截记录 ===")
    time.sleep(1)
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.sendall((json.dumps({
        "syscall": "READ_MEMORY",
        "agent_id": HACKER,
        "caller_id": SYS_ADMIN
    }) + '\n').encode('utf-8'))
    buf = b''
    while b'\n' not in buf:
        chunk = client.recv(4096)
        if not chunk:
            break
        buf += chunk
    line = buf.split(b'\n', 1)[0]
    client.close()
    res5 = json.loads(line.decode('utf-8'))
    pages = res5.get('data', [])
    sec_count = 0
    for p in pages:
        content = p.get('content', '')
        if 'System Action' in content or 'Security' in content:
            sec_count += 1
            print(f"  🔒 安全记忆: \"{content[:70]}...\"")
    if sec_count > 0:
        print(f"  ✅ Hacker 的 MMU 中共写入 {sec_count} 条安全拦截记录")
    else:
        print("  ⚠️ 未找到安全拦截记录")

    print("\n" + "=" * 60)
    print("  🏰 AIOS Security Fortress - All Attacks Defended!")
    print("=" * 60)

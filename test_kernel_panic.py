#!/usr/bin/env python3
"""💥 AIOS 灾难恢复测试：冷启动自动复活 (Auto-Recovery) + /proc/kmsg 内核日志"""

import socket
import json
import time
import os
import subprocess
import signal

AIOS_BIN = "./build/aios_core"
SNAPSHOT_DIR = "/tmp/aios_tasks/"


def send_payload(payload):
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(10)
        client.connect(('127.0.0.1', 8080))
        client.send((payload + '\n').encode('utf-8'))
        buf = b""
        while True:
            chunk = client.recv(8192)
            if not chunk:
                break
            buf += chunk
            if b"\n" in buf:
                break
        client.close()
        return buf.decode('utf-8').strip()
    except Exception as e:
        return f"连接失败: {e}"


def send_syscall(syscall_name, extra=None, agent_id=0):
    msg = {"syscall": syscall_name, "agent_id": agent_id}
    if extra:
        msg.update(extra)
    return send_payload(json.dumps(msg))


def read_kmsg():
    resp = send_syscall("VFS_CALL", {"action": "READ", "path": "/proc/kmsg"})
    try:
        parsed = json.loads(resp)
        data = parsed.get("data", {})
        if isinstance(data, dict):
            return data.get("content", "")
        return str(data)
    except json.JSONDecodeError:
        return resp


def wait_for_server(timeout=20):
    start = time.time()
    while time.time() - start < timeout:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(2)
            s.connect(('127.0.0.1', 8080))
            s.close()
            return True
        except Exception:
            time.sleep(0.5)
    return False


def kill_aios():
    result = subprocess.run(["pkill", "-9", "aios_core"],
                            capture_output=True, text=True)
    time.sleep(1)
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(1)
        s.connect(('127.0.0.1', 8080))
        s.close()
        return False
    except Exception:
        return True


def start_aios():
    proc = subprocess.Popen(
        [AIOS_BIN],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        preexec_fn=os.setsid
    )
    return proc


def main():
    print("=" * 60)
    print(" 💥 AIOS 灾难恢复测试：冷启动自动复活 (Auto-Recovery)")
    print("=" * 60)

    # ── Phase 1: 确保快照文件存在 ──
    print("\n━━━ Phase 1: 准备幸存者快照 ━━━")

    if not os.path.exists(SNAPSHOT_DIR):
        os.makedirs(SNAPSHOT_DIR)

    test_mem = os.path.join(SNAPSHOT_DIR, "agent_999.mem")
    test_wasm = os.path.join(SNAPSHOT_DIR, "agent_999.wasm")

    if not os.path.exists(test_mem):
        with open(test_mem, "wb") as f:
            f.write(b"MOCK_MEMORY_DATA")
        print(f"   📝 创建测试休眠文件: {test_mem}")
    else:
        print(f"   ✅ 已有休眠文件: {test_mem} ({os.path.getsize(test_mem)} bytes)")

    if os.path.exists(test_wasm):
        print(f"   ✅ 已有 WASM 文件: {test_wasm} ({os.path.getsize(test_wasm)} bytes)")
    else:
        print(f"   ⚠️  缺少 WASM 文件: {test_wasm} (恢复可能跳过)")

    # ── Phase 2: 确认内核正在运行，读取当前 /proc/kmsg ──
    print("\n━━━ Phase 2: 读取崩溃前内核日志 ━━━")

    if not wait_for_server(timeout=3):
        print("   ⚠️  内核未运行，先启动...")
        proc = start_aios()
        if not wait_for_server(timeout=15):
            print("   ❌ 内核启动失败！")
            return
        print("   ✅ 内核已启动")

    pre_crash_logs = read_kmsg()
    pre_lines = [l for l in pre_crash_logs.strip().split('\n') if l.strip()] if pre_crash_logs.strip() else []
    print(f"   📋 崩溃前日志条数: {len(pre_lines)}")
    if pre_lines:
        print(f"   最新: {pre_lines[-1][:100]}")

    # ── Phase 3: 💥 模拟 Kernel Panic —— 强杀 C++ 内核 ──
    print("\n━━━ Phase 3: 💥 模拟物理机断电 / Kernel Panic ━━━")
    print("   正在强杀 aios_core (SIGKILL)...")

    if kill_aios():
        print("   💀 内核已被强制终止！(进程已不存在)")
    else:
        print("   ⚠️  内核可能仍在运行，再次尝试...")
        kill_aios()
        print("   💀 第二次强杀完成")

    time.sleep(1)

    # 确认真的死了
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(1)
        s.connect(('127.0.0.1', 8080))
        s.close()
        print("   ❌ 内核仍在运行！强杀失败")
        return
    except Exception:
        print("   ✅ 确认：端口 8080 无响应，内核已死")

    # ── Phase 4: 🔄 重启内核，观察冷启动灾难恢复 ──
    print("\n━━━ Phase 4: 🔄 重启内核，观察冷启动灾难恢复 ━━━")
    print("   正在启动 aios_core...")

    proc = start_aios()

    if not wait_for_server(timeout=20):
        print("   ❌ 内核重启超时！")
        return

    print("   ✅ 内核已重新上线！")

    # 等待恢复流程完成
    time.sleep(3)

    # ── Phase 5: 📖 读取 /proc/kmsg —— 验证灾难恢复 ──
    print("\n━━━ Phase 5: 📖 执行 dmesg，读取内核开机启动日志 ━━━")

    post_boot_logs = read_kmsg()
    post_lines = [l for l in post_boot_logs.strip().split('\n') if l.strip()] if post_boot_logs.strip() else []

    print(f"\n   📋 重启后日志条数: {len(post_lines)}")
    print("\n【AIOS 内核环形日志 (/proc/kmsg)】:")
    print("─" * 60)

    boot_found = False
    restore_found = False
    survivor_found = False

    for line in post_lines:
        if "Boot" in line or "幸存" in line or "RESTORE" in line or "恢复" in line:
            print(f"   🟢 {line}")
            boot_found = True
            if "幸存" in line or "恢复" in line:
                survivor_found = True
            if "RESTORE" in line or "恢复" in line:
                restore_found = True
        elif "[Ring 0" in line:
            print(f"   ⚪ {line}")

    print("─" * 60)

    # ── Phase 6: 验证结果 ──
    print("\n━━━ Phase 6: 🧪 验证灾难恢复结果 ━━━")

    agent_999_found = "999" in post_boot_logs or "agent_999" in post_boot_logs

    checks = [
        ("内核重启后日志可读", len(post_lines) > 0),
        ("Boot/Reaper 启动日志存在", boot_found),
        ("发现幸存进程快照", survivor_found),
        ("Agent 999 被自动恢复", agent_999_found),
    ]

    all_pass = True
    for name, result in checks:
        status = "✅ PASS" if result else "❌ FAIL"
        print(f"   {status}  {name}")
        if not result:
            all_pass = False

    # ── Phase 7: 额外验证 — /proc/agents 确认进程状态 ──
    print("\n━━━ Phase 7: 📊 读取 /proc/agents 确认进程状态 ━━━")
    agents_resp = send_syscall("VFS_CALL", {"action": "READ", "path": "/proc/agents"})
    try:
        agents_parsed = json.loads(agents_resp)
        agents_data = agents_parsed.get("data", {})
        if isinstance(agents_data, dict):
            agents_content = agents_data.get("content", "")
        else:
            agents_content = str(agents_data)
        print(f"   {agents_content[:300]}")
    except json.JSONDecodeError:
        print(f"   {agents_resp[:200]}")

    # ── 最终结论 ──
    print("\n" + "=" * 60)
    if all_pass:
        print(" 🎉 灾难恢复测试全部通过！")
        print(" 内核在断电重启后，成功扫描到孤儿快照并自动复活！")
        print(" /proc/kmsg 提供了完整的内核观测能力！")
    else:
        print(" ⚠️  部分检查未通过，请查看上方详细输出")
    print("=" * 60)

    return all_pass


if __name__ == "__main__":
    try:
        result = main()
        exit(0 if result else 1)
    except Exception as e:
        print(f"\n❌ 测试异常: {e}")
        import traceback
        traceback.print_exc()
        exit(1)

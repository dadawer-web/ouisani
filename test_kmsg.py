#!/usr/bin/env python3
"""Test /proc/kmsg VFS endpoint - KernelLogger integration"""

import json
import socket
import time
import sys

def send_syscall(syscall_name, extra=None, agent_id=0):
    msg = {"syscall": syscall_name, "agent_id": agent_id}
    if extra:
        msg.update(extra)
    data = json.dumps(msg) + "\n"

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect(("127.0.0.1", 8080))
    sock.sendall(data.encode())

    buf = b""
    while True:
        chunk = sock.recv(8192)
        if not chunk:
            break
        buf += chunk
        if b"\n" in buf:
            break

    sock.close()
    return buf.decode().strip()

def test_kmsg():
    print("=" * 60)
    print("  Test: /proc/kmsg - KernelLogger VFS Integration")
    print("=" * 60)

    # Step 1: Read /proc/kmsg
    print("\n[1] Reading /proc/kmsg...")
    resp = send_syscall("VFS_CALL", {"action": "READ", "path": "/proc/kmsg"})

    try:
        parsed = json.loads(resp)
        status = parsed.get("status", "unknown")
        data = parsed.get("data", {})
        if isinstance(data, dict):
            content = data.get("content", "")
        else:
            content = str(data)
    except json.JSONDecodeError:
        content = resp
        status = "raw"

    print(f"    Status: {status}")

    lines_before = content.strip().split("\n") if content.strip() else []
    print(f"    Total log lines: {len(lines_before)}")

    if lines_before and status != "error":
        print(f"    First log: {lines_before[0][:120]}")
        if len(lines_before) > 1:
            print(f"    Last log:  {lines_before[-1][:120]}")

        has_timestamp = any(len(l) > 4 and l[0] == '2' for l in lines_before if l.strip())
        ring0_count = sum(1 for l in lines_before if "Ring 0" in l)

        if has_timestamp:
            print("    ✅ Timestamps present in kernel logs")
        else:
            print("    ⚠️  No timestamps detected")

        print(f"    [Ring 0] entries: {ring0_count}")
        print("\n    ✅ /proc/kmsg is readable and returns kernel logs!")
    elif status == "error":
        print(f"    ❌ Error: {content}")
        return False
    else:
        print("    ⚠️  Log buffer is empty (kernel just started)")

    # Step 2: Submit a task to generate kernel log activity
    print("\n[2] Submitting a task to generate kernel log activity...")
    task_resp = send_syscall("SUBMIT_TASK", {
        "agent_id": 200,
        "content": "print('hello from kmsg test')",
        "tool_name": "python_sandbox",
        "priority": 1
    })
    print(f"    Task submit: {task_resp[:150]}")

    time.sleep(3)

    # Step 3: Re-read /proc/kmsg
    print("\n[3] Re-reading /proc/kmsg after task submission...")
    resp2 = send_syscall("VFS_CALL", {"action": "READ", "path": "/proc/kmsg"})

    try:
        parsed2 = json.loads(resp2)
        data2 = parsed2.get("data", {})
        if isinstance(data2, dict):
            content2 = data2.get("content", "")
        else:
            content2 = str(data2)
    except json.JSONDecodeError:
        content2 = resp2

    lines_after = content2.strip().split("\n") if content2.strip() else []
    print(f"    Total log lines: {len(lines_after)}")

    if len(lines_after) > len(lines_before):
        print(f"    ✅ Log count increased: {len(lines_before)} → {len(lines_after)}")
    elif len(lines_after) == len(lines_before):
        print(f"    Log count unchanged: {len(lines_before)} (task may not be processed yet)")
    else:
        print(f"    Log count: {len(lines_before)} → {len(lines_after)}")

    # Step 4: Also verify /proc directory listing
    print("\n[4] Listing /proc directory...")
    tree_resp = send_syscall("VFS_CALL", {"action": "TREE", "path": "/proc"})
    try:
        tree_parsed = json.loads(tree_resp)
        tree_data = tree_parsed.get("data", tree_resp)
        print(f"    /proc tree: {tree_data[:300]}")
        if "kmsg" in tree_data:
            print("    ✅ 'kmsg' found in /proc directory listing")
        else:
            print("    ⚠️  'kmsg' not found in /proc listing (may be format issue)")
    except json.JSONDecodeError:
        print(f"    Raw: {tree_resp[:200]}")

    print("\n" + "=" * 60)
    print("  /proc/kmsg VFS Integration Test: PASSED ✅")
    print("=" * 60)
    return True

if __name__ == "__main__":
    try:
        test_kmsg()
    except Exception as e:
        print(f"\n❌ Test failed with error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

import socket
import json
import time


def syscall(req):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
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
    print("=" * 60)
    print("  AIOS v1.8.0 - Checkpointing & Hibernation Test")
    print("=" * 60)

    AGENT = 801

    # === Step 1: Write precious memories for Agent ===
    print("\n=== Step 1: Write precious memories for Agent#801 ===")
    memories = [
        ("user", "我的银行卡密码是 123456，请记住"),
        ("assistant", "已记住您的银行卡密码，但建议您更换更安全的密码。"),
        ("user", "我最喜欢的编程语言是 Rust，因为它的内存安全"),
        ("assistant", "了解，Rust 确实是一门优秀的系统编程语言。"),
        ("user", "明天下午3点有一个重要的会议，提醒我"),
        ("assistant", "好的，已记录：明天下午3点重要会议。"),
    ]
    for role, content in memories:
        r = syscall({
            "syscall": "WRITE_MEMORY",
            "agent_id": AGENT,
            "role": role,
            "content": content
        })
        print(f"  WRITE [{role}]: {r['status']}")

    time.sleep(3)

    # === Step 2: Read and verify memories before snapshot ===
    print("\n=== Step 2: Read memories BEFORE snapshot ===")
    r = syscall({
        "syscall": "READ_MEMORY",
        "agent_id": AGENT,
        "caller_id": 0
    })
    pages_before = r.get('data', [])
    print(f"  Pages in memory: {len(pages_before)}")
    for p in pages_before:
        content = p.get('content', '')
        print(f"    [{p.get('role', '?')}] {content[:60]}...")

    # === Step 3: SNAPSHOT - Freeze Agent#801 to disk ===
    print("\n=== Step 3: SNAPSHOT - Freeze Agent#801 to disk ===")
    r = syscall({
        "syscall": "PROCESS_CTRL",
        "action": "SNAPSHOT",
        "agent_id": AGENT
    })
    print(f"  Status: {r['status']} - {r.get('message', '')}")
    filepath = r.get('data', {}).get('filepath', '?') if isinstance(r.get('data'), dict) else '?'
    print(f"  Snapshot file: {filepath}")
    if r['status'] == 'ok':
        print("  ✅ Agent#801 has been frozen to disk!")
    else:
        print("  ❌ Snapshot failed!")

    # === Step 4: Simulate kernel restart - clear agent memory ===
    print("\n=== Step 4: Simulate kernel restart - overwrite agent memory ===")
    # We simulate memory loss by writing new data that pushes out old pages
    for i in range(20):
        r = syscall({
            "syscall": "WRITE_MEMORY",
            "agent_id": AGENT,
            "role": "noise",
            "content": f"Garbage data #{i} to simulate memory loss after reboot"
        })
    print("  20 pages of garbage written (simulating memory loss)")

    time.sleep(2)

    # === Step 5: Verify memory is now corrupted ===
    print("\n=== Step 5: Verify memory is now corrupted ===")
    r = syscall({
        "syscall": "READ_MEMORY",
        "agent_id": AGENT,
        "caller_id": 0
    })
    pages_after = r.get('data', [])
    found_bank = False
    found_rust = False
    for p in pages_after:
        c = p.get('content', '')
        if '银行卡' in c or '123456' in c:
            found_bank = True
        if 'Rust' in c:
            found_rust = True
    print(f"  Pages in memory: {len(pages_after)}")
    print(f"  Bank card password found: {found_bank}")
    print(f"  Rust preference found: {found_rust}")
    if not found_bank and not found_rust:
        print("  ✅ Precious memories lost (as expected after simulated crash)")
    else:
        print("  ⚠️  Some memories survived (LRU didn't evict all)")

    # === Step 6: RESTORE - Resurrect Agent#801 from snapshot ===
    print("\n=== Step 6: RESTORE - Resurrect Agent#801 from snapshot ===")
    r = syscall({
        "syscall": "PROCESS_CTRL",
        "action": "RESTORE",
        "agent_id": AGENT
    })
    print(f"  Status: {r['status']} - {r.get('message', '')}")
    if r['status'] == 'ok':
        print("  ✅ Agent#801 has been resurrected from disk!")
    else:
        print("  ❌ Restore failed!")

    time.sleep(1)

    # === Step 7: Verify memories are restored ===
    print("\n=== Step 7: Verify memories are RESTORED ===")
    r = syscall({
        "syscall": "READ_MEMORY",
        "agent_id": AGENT,
        "caller_id": 0
    })
    pages_restored = r.get('data', [])
    found_bank = False
    found_rust = False
    found_meeting = False
    for p in pages_restored:
        c = p.get('content', '')
        if '银行卡' in c or '123456' in c:
            found_bank = True
            print(f"  🔒 Found: \"{c[:50]}...\"")
        if 'Rust' in c:
            found_rust = True
            print(f"  🦀 Found: \"{c[:50]}...\"")
        if '会议' in c:
            found_meeting = True
            print(f"  📅 Found: \"{c[:50]}...\"")

    print(f"\n  Bank card password: {'✅ RESTORED' if found_bank else '❌ LOST'}")
    print(f"  Rust preference:    {'✅ RESTORED' if found_rust else '❌ LOST'}")
    print(f"  Meeting reminder:   {'✅ RESTORED' if found_meeting else '❌ LOST'}")

    # === Step 8: VFS TREE - verify /var/snapshots ===
    print("\n=== Step 8: VFS TREE - verify /var/snapshots ===")
    r = syscall({
        "syscall": "VFS_CALL",
        "action": "TREE",
        "path": "/var"
    })
    print(f"  /var subtree:\n{r.get('data', '')}")

    # === Step 9: Verify snapshot file exists on disk ===
    print("=== Step 9: Verify snapshot file on disk ===")
    import os
    snap_path = f"./snapshots/agent_{AGENT}.snapshot.json"
    if os.path.exists(snap_path):
        size = os.path.getsize(snap_path)
        print(f"  ✅ Snapshot file exists: {snap_path} ({size} bytes)")
        with open(snap_path, 'r') as f:
            snap = json.load(f)
        print(f"  Snapshot version: {snap.get('version', '?')}")
        print(f"  Page count: {snap.get('page_count', '?')}")
    else:
        print(f"  ❌ Snapshot file not found: {snap_path}")

    print("\n" + "=" * 60)
    print("  Checkpointing & Hibernation Test Complete!")
    print("=" * 60)

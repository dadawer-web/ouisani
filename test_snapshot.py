import socket
import json
import time
import os


def syscall(req_dict):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.sendall((json.dumps(req_dict) + '\n').encode('utf-8'))
    buf = b''
    while b'\n' not in buf:
        chunk = client.recv(4096)
        if not chunk:
            break
        buf += chunk
    line = buf.split(b'\n', 1)[0]
    client.close()
    try:
        return json.loads(line.decode('utf-8'))
    except Exception:
        return {"status": "error", "raw": line.decode('utf-8', errors='replace')}


def write_memory(agent_id, role, content):
    return syscall({
        "syscall": "WRITE_MEMORY",
        "agent_id": agent_id,
        "role": role,
        "content": content
    })


def read_memory(agent_id):
    return syscall({
        "syscall": "READ_MEMORY",
        "agent_id": agent_id
    })


def process_ctrl(agent_id, action):
    return syscall({
        "syscall": "PROCESS_CTRL",
        "action": action,
        "agent_id": agent_id
    })


def check_content(pages_data, keyword):
    if not isinstance(pages_data, list):
        return False
    for item in pages_data:
        content = item.get("content", "") if isinstance(item, dict) else ""
        if keyword in content:
            return True
    return False


if __name__ == "__main__":
    AGENT_ID = 101

    print("=" * 60)
    print("  AIOS v1.8.0 - Checkpointing & Hibernation Live Test")
    print("  Full Lifecycle: INJECT -> SNAPSHOT -> PURGE -> RESTORE")
    print("=" * 60)

    print("\n=== Step 0: Clean slate - PURGE any leftover memory ===")
    res = process_ctrl(AGENT_ID, "PURGE")
    print(f"  Purge result: {res.get('status', '?')}")
    snap_file = f"./snapshots/agent_{AGENT_ID}.snapshot.json"
    if os.path.exists(snap_file):
        os.remove(snap_file)
        print(f"  Removed old snapshot: {snap_file}")

    print("\n=== Step 1: Inject precious memories into Agent#%d ===" % AGENT_ID)
    memories = [
        ("user",      "My top-secret passcode is: AIOS-KERNEL-2026-SUCCESS."),
        ("assistant", "Understood. I have memorized your passcode: AIOS-KERNEL-2026-SUCCESS."),
        ("user",      "My preferred programming language is Rust, because of memory safety guarantees."),
        ("assistant", "Noted. Rust is indeed an excellent systems programming language."),
        ("user",      "Critical reminder: Server migration scheduled for 2026-06-01 at 03:00 UTC."),
        ("assistant", "Reminder saved. Server migration on 2026-06-01 at 03:00 UTC."),
    ]

    for role, content in memories:
        res = write_memory(AGENT_ID, role, content)
        status = res.get("status", "?")
        print(f"  WRITE [{role}]: {status}")

    time.sleep(1)

    print("\n=== Step 2: Read memories BEFORE snapshot ===")
    res = read_memory(AGENT_ID)
    pages_before = res.get("data", [])
    page_count_before = len(pages_before) if isinstance(pages_before, list) else 0
    print(f"  Pages in memory: {page_count_before}")
    if isinstance(pages_before, list):
        for p in pages_before:
            c = p.get("content", "") if isinstance(p, dict) else ""
            preview = c[:80] + "..." if len(c) > 80 else c
            print(f"    [{p.get('role', '?')}] {preview}")

    has_passcode_before = check_content(pages_before, "AIOS-KERNEL-2026-SUCCESS")
    has_rust_before = check_content(pages_before, "Rust")
    has_migration_before = check_content(pages_before, "2026-06-01")
    print(f"\n  Passcode present: {'YES' if has_passcode_before else 'NO'}")
    print(f"  Rust preference:  {'YES' if has_rust_before else 'NO'}")
    print(f"  Migration date:   {'YES' if has_migration_before else 'NO'}")

    print("\n=== Step 3: SNAPSHOT - Freeze Agent#%d to disk ===" % AGENT_ID)
    res_snap = process_ctrl(AGENT_ID, "SNAPSHOT")
    snap_ok = res_snap.get("status") == "ok"
    snap_filepath = res_snap.get("data", {}).get("filepath", "?") if isinstance(res_snap.get("data"), dict) else "?"
    print(f"  Status: {res_snap.get('status', '?')} - {res_snap.get('message', '')}")
    print(f"  Snapshot file: {snap_filepath}")

    if snap_ok and os.path.exists(snap_filepath.lstrip('./')):
        file_size = os.path.getsize(snap_filepath.lstrip('./'))
        print(f"  File size: {file_size} bytes")
        with open(snap_filepath.lstrip('./'), 'r') as f:
            snap_data = json.load(f)
        print(f"  Snapshot version: {snap_data.get('version', '?')}")
        print(f"  Page count: {snap_data.get('page_count', '?')}")
        emb_count = sum(1 for p in snap_data.get("pages", []) if "embedding" in p)
        print(f"  Pages with embeddings: {emb_count}")
        print(f"  Agent#%d has been FROZEN to disk!" % AGENT_ID)
    else:
        print(f"  WARNING: Snapshot file not found at {snap_filepath}")

    time.sleep(1)

    print("\n=== Step 4: PURGE - Simulate catastrophic memory loss ===")
    res_purge = process_ctrl(AGENT_ID, "PURGE")
    purge_ok = res_purge.get("status") == "ok"
    print(f"  Status: {res_purge.get('status', '?')} - {res_purge.get('message', '')}")
    print(f"  Agent#%d memory has been PURGED (simulating kernel crash)!" % AGENT_ID)

    time.sleep(0.5)

    print("\n=== Step 5: Verify memory is EMPTY after PURGE ===")
    res = read_memory(AGENT_ID)
    pages_after_purge = res.get("data", [])
    page_count_after = len(pages_after_purge) if isinstance(pages_after_purge, list) else 0
    print(f"  Pages in memory: {page_count_after}")

    has_passcode_after = check_content(pages_after_purge, "AIOS-KERNEL-2026-SUCCESS")
    has_rust_after = check_content(pages_after_purge, "Rust")
    has_migration_after = check_content(pages_after_purge, "2026-06-01")
    print(f"  Passcode present: {'YES (BUG!)' if has_passcode_after else 'NO (correct - memory purged)'}")
    print(f"  Rust preference:  {'YES (BUG!)' if has_rust_after else 'NO (correct - memory purged)'}")
    print(f"  Migration date:   {'YES (BUG!)' if has_migration_after else 'NO (correct - memory purged)'}")

    if page_count_after == 0:
        print(f"  Memory is completely empty! Purge verified.")
    else:
        print(f"  WARNING: Memory not fully purged, {page_count_after} pages remain.")

    time.sleep(1)

    print("\n=== Step 6: RESTORE - Resurrect Agent#%d from snapshot ===" % AGENT_ID)
    res_restore = process_ctrl(AGENT_ID, "RESTORE")
    restore_ok = res_restore.get("status") == "ok"
    print(f"  Status: {res_restore.get('status', '?')} - {res_restore.get('message', '')}")
    print(f"  Agent#%d has been RESURRECTED from disk!" % AGENT_ID)

    time.sleep(1)

    print("\n=== Step 7: Final memory integrity verification ===")
    res = read_memory(AGENT_ID)
    pages_final = res.get("data", [])
    page_count_final = len(pages_final) if isinstance(pages_final, list) else 0
    print(f"  Pages in memory: {page_count_final}")
    if isinstance(pages_final, list):
        for p in pages_final:
            c = p.get("content", "") if isinstance(p, dict) else ""
            preview = c[:80] + "..." if len(c) > 80 else c
            print(f"    [{p.get('role', '?')}] {preview}")

    has_passcode_final = check_content(pages_final, "AIOS-KERNEL-2026-SUCCESS")
    has_rust_final = check_content(pages_final, "Rust")
    has_migration_final = check_content(pages_final, "2026-06-01")

    print(f"\n  Passcode:  {'RESTORED' if has_passcode_final else 'LOST'}")
    print(f"  Rust:      {'RESTORED' if has_rust_final else 'LOST'}")
    print(f"  Migration: {'RESTORED' if has_migration_final else 'LOST'}")

    print("\n=== Step 8: VFS TREE - verify /var/snapshots ===")
    res = syscall({
        "syscall": "VFS_CALL",
        "agent_id": AGENT_ID,
        "action": "TREE",
        "path": "/var"
    })
    tree_str = res.get("data", "")
    print(f"  /var subtree:\n{tree_str}")

    print("\n" + "=" * 60)
    all_restored = has_passcode_final and has_rust_final and has_migration_final
    if all_restored and purge_ok and snap_ok and restore_ok:
        print("  CHECKPOINTING TEST: ALL PASSED")
        print("  Agent#%d survived a full memory purge and was resurrected!" % AGENT_ID)
    else:
        print("  CHECKPOINTING TEST: PARTIAL")
        print(f"  Snapshot: {'OK' if snap_ok else 'FAIL'}")
        print(f"  Purge:    {'OK' if purge_ok else 'FAIL'}")
        print(f"  Restore:  {'OK' if restore_ok else 'FAIL'}")
        print(f"  Passcode: {'RESTORED' if has_passcode_final else 'LOST'}")
        print(f"  Rust:     {'RESTORED' if has_rust_final else 'LOST'}")
        print(f"  Migration:{'RESTORED' if has_migration_final else 'LOST'}")
    print("=" * 60)

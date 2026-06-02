import socket
import json
import time
import threading


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


def blocking_pipe_read(agent_id, path, result_container, index):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    req = {
        "syscall": "VFS_CALL",
        "agent_id": agent_id,
        "action": "READ",
        "path": path,
        "payload": ""
    }
    start = time.time()
    client.sendall((json.dumps(req) + '\n').encode('utf-8'))
    buf = b''
    while b'\n' not in buf:
        chunk = client.recv(4096)
        if not chunk:
            break
        buf += chunk
    line = buf.split(b'\n', 1)[0]
    client.close()
    elapsed = time.time() - start
    try:
        resp = json.loads(line.decode('utf-8'))
    except Exception:
        resp = {"status": "error", "raw": line.decode('utf-8', errors='replace')}
    result_container[index] = (resp, elapsed)


if __name__ == "__main__":
    print("=" * 60)
    print("  AIOS v1.7.0 - IPC Pipe & Multi-Agent Test")
    print("=" * 60)

    PIPE_PATH = "/tmp/pipes/agent_101_to_102"

    # === Test 1: VFS TREE - verify pipe is mounted ===
    print("\n=== Test 1: VFS TREE - verify pipe mount ===")
    r = syscall({
        "syscall": "VFS_CALL",
        "action": "TREE",
        "path": "/tmp"
    })
    print(f"  /tmp subtree:\n{r.get('data', '')}")

    # === Test 2: Agent 101 writes to pipe ===
    print("=== Test 2: Agent#101 WRITE to pipe ===")
    r = syscall({
        "syscall": "VFS_CALL",
        "agent_id": 101,
        "action": "WRITE",
        "path": PIPE_PATH,
        "payload": "Hello from Agent 101! The answer is 42."
    })
    print(f"  Status: {r['status']} - {r.get('message', '')[:60]}")
    if r['status'] == 'ok':
        print("  ✅ Agent#101 wrote to pipe successfully!")
    else:
        print("  ❌ Write failed")

    # === Test 3: Agent 102 reads from pipe (immediate, data already in queue) ===
    print("\n=== Test 3: Agent#102 READ from pipe (data already queued) ===")
    r = syscall({
        "syscall": "VFS_CALL",
        "agent_id": 102,
        "action": "READ",
        "path": PIPE_PATH,
        "payload": ""
    })
    print(f"  Status: {r['status']}")
    content = r.get('data', {}).get('content', '?') if isinstance(r.get('data'), dict) else '?'
    print(f"  Content: {content}")
    if r['status'] == 'ok' and 'Agent 101' in str(content):
        print("  ✅ Agent#102 received message from Agent#101 via pipe!")
    else:
        print("  ❌ Read failed or content mismatch")

    # === Test 4: Blocking read - Agent 102 waits, then Agent 101 writes ===
    print("\n=== Test 4: Blocking read - Agent#102 waits, then Agent#101 writes ===")
    print("  Starting blocking READ thread for Agent#102...")

    results = [None]

    reader_thread = threading.Thread(
        target=blocking_pipe_read,
        args=(102, PIPE_PATH, results, 0)
    )
    reader_thread.start()

    print("  Agent#102 is now blocking on pipe READ (waiting for data)...")
    time.sleep(2)

    print("  Agent#101 now writes to the pipe...")
    r = syscall({
        "syscall": "VFS_CALL",
        "agent_id": 101,
        "action": "WRITE",
        "path": PIPE_PATH,
        "payload": "Delayed message: Agent 101 was thinking for 2 seconds!"
    })
    print(f"  Write status: {r['status']}")

    reader_thread.join(timeout=15)

    if results[0] is not None:
        resp, elapsed = results[0]
        content = resp.get('data', {}).get('content', '?') if isinstance(resp.get('data'), dict) else '?'
        print(f"  Agent#102 received: \"{content}\"")
        print(f"  Blocking wait time: {elapsed:.2f}s")
        if resp['status'] == 'ok' and 'Delayed message' in str(content):
            print("  ✅ Blocking pipe READ works! Agent#102 was woken up by Agent#101's WRITE!")
        else:
            print("  ❌ Blocking read failed or content mismatch")
    else:
        print("  ❌ Reader thread timed out")

    # === Test 5: Multiple messages in pipe ===
    print("\n=== Test 5: Multiple messages in pipe (FIFO order) ===")
    for i in range(3):
        r = syscall({
            "syscall": "VFS_CALL",
            "agent_id": 101,
            "action": "WRITE",
            "path": PIPE_PATH,
            "payload": f"Message #{i+1} from Agent 101"
        })
        print(f"  Write #{i+1}: {r['status']}")

    for i in range(3):
        r = syscall({
            "syscall": "VFS_CALL",
            "agent_id": 102,
            "action": "READ",
            "path": PIPE_PATH,
            "payload": ""
        })
        content = r.get('data', {}).get('content', '?') if isinstance(r.get('data'), dict) else '?'
        print(f"  Read #{i+1}: {content}")
        if f"Message #{i+1}" in str(content):
            print(f"  ✅ FIFO order preserved for message #{i+1}")
        else:
            print(f"  ❌ FIFO order broken for message #{i+1}")

    # === Test 6: Other VFS operations still work ===
    print("\n=== Test 6: Other VFS operations still work ===")
    r = syscall({
        "syscall": "VFS_CALL",
        "agent_id": 101,
        "action": "READ",
        "path": "/proc/version"
    })
    content = r.get('data', {}).get('content', '?') if isinstance(r.get('data'), dict) else '?'
    print(f"  /proc/version: {content}")
    if 'AIOS Core' in str(content):
        print("  ✅ VFS /proc/version still works alongside pipes!")

    print("\n" + "=" * 60)
    print("  IPC Pipe & Multi-Agent Test Complete!")
    print("=" * 60)

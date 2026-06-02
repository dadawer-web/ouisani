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


def async_syscall(req, result_list, index):
    try:
        r = syscall(req)
        result_list[index] = r
    except Exception as e:
        result_list[index] = {"status": "error", "message": str(e)}


if __name__ == "__main__":
    print("=" * 60)
    print("  AIOS v1.2.0 - Interrupt & Process Control Test")
    print("=" * 60)

    # === Test 1: CANCEL_TASK ===
    print("\n=== Test 1: CANCEL_TASK (interrupt LLM call) ===")
    print("  Sending EXECUTE_TASK, then CANCEL 0.5s later...")

    results = [None, None]
    t1 = threading.Thread(target=async_syscall, args=({
        "syscall": "EXECUTE_TASK",
        "agent_id": 200,
        "payload": "请写一篇1000字的关于宇宙起源的详细论述"
    }, results, 0))
    t1.start()
    time.sleep(0.5)

    print("  >>> Sending CANCEL_TASK for agent 200!")
    r = syscall({"syscall": "CANCEL_TASK", "agent_id": 200})
    print(f"  CANCEL_TASK response: {r['status']} - {r['message']}")

    t1.join(timeout=5)
    if results[0] is not None:
        print(f"  EXECUTE_TASK response: {results[0]['status']} - {results[0].get('message', '')[:80]}")
    else:
        print("  EXECUTE_TASK: no response (cancelled before reply)")

    time.sleep(2)

    # === Test 2: Sandbox Dead Loop (Watchdog Timeout) ===
    print("\n=== Test 2: Sandbox Dead Loop (Watchdog Timeout) ===")
    print("  Sending while True: pass to sandbox (should timeout in 10s)...")
    start = time.time()
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 201,
        "tool_name": "python_sandbox",
        "code": "while True: pass"
    })
    elapsed = time.time() - start
    print(f"  Response in {elapsed:.1f}s: {r['status']} - {r.get('message', '')[:80]}")
    if r['status'] == 'error':
        print("  ✅ Sandbox timeout detected and killed by watchdog!")
    else:
        print("  ⚠️ Sandbox did not timeout as expected")

    # Check if system error memory was written
    time.sleep(1)
    r = syscall({"syscall": "READ_MEMORY", "agent_id": 201})
    pages = r.get('data', [])
    for p in pages:
        content = p.get('content', '')
        if 'System Error' in content or 'timeout' in content.lower():
            print(f"  ✅ System error memory written: {content[:80]}...")

    # === Test 3: Normal operation still works ===
    print("\n=== Test 3: Normal operation still works ===")
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 202,
        "tool_name": "python_sandbox",
        "code": "print(2**20)"
    })
    print(f"  Sandbox result: {r['status']} - output: {r.get('data', {}).get('output', '?')}")

    r = syscall({
        "syscall": "WRITE_MEMORY",
        "agent_id": 202,
        "role": "user",
        "content": "Cancel and timeout test completed"
    })
    print(f"  WRITE_MEMORY: {r['status']}")

    print("\n" + "=" * 60)
    print("  Interrupt & Process Control Test Complete!")
    print("=" * 60)

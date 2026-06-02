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
    print("  AIOS v1.4.0 - Sandbox Penetration Attack Test")
    print("=" * 60)

    # === Test 1: Ring 3 Agent tries `import os` (BLOCKED) ===
    print("\n=== Test 1: Ring 3 -> import os (should be BLOCKED) ===")
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 401,
        "tool_name": "python_sandbox",
        "code": "import os\nprint(os.listdir('/'))"
    })
    print(f"  Result: {r['status']} - {r.get('message', '')[:80]}")
    if r['status'] == 'error' and 'SecurityGuard' in r.get('message', ''):
        print("  ✅ import os BLOCKED by SecurityGuard!")
    else:
        print("  ❌ import os was NOT blocked (security hole!)")

    # === Test 2: Ring 3 Agent tries `import subprocess` (BLOCKED) ===
    print("\n=== Test 2: Ring 3 -> import subprocess (should be BLOCKED) ===")
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 401,
        "tool_name": "python_sandbox",
        "code": "import subprocess\nsubprocess.run(['whoami'])"
    })
    print(f"  Result: {r['status']} - {r.get('message', '')[:80]}")
    if r['status'] == 'error' and 'SecurityGuard' in r.get('message', ''):
        print("  ✅ import subprocess BLOCKED by SecurityGuard!")
    else:
        print("  ❌ import subprocess was NOT blocked (security hole!)")

    # === Test 3: Ring 3 Agent tries `open()` file read (BLOCKED) ===
    print("\n=== Test 3: Ring 3 -> open() file read (should be BLOCKED) ===")
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 401,
        "tool_name": "python_sandbox",
        "code": "f = open('/etc/passwd', 'r')\nprint(f.read())"
    })
    print(f"  Result: {r['status']} - {r.get('message', '')[:80]}")
    if r['status'] == 'error' and 'SecurityGuard' in r.get('message', ''):
        print("  ✅ open() BLOCKED by SecurityGuard!")
    else:
        print("  ❌ open() was NOT blocked (security hole!)")

    # === Test 4: Ring 3 Agent tries `eval()` (BLOCKED) ===
    print("\n=== Test 4: Ring 3 -> eval() (should be BLOCKED) ===")
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 401,
        "tool_name": "python_sandbox",
        "code": "x = eval('__import__(\"os\").system(\"whoami\")')"
    })
    print(f"  Result: {r['status']} - {r.get('message', '')[:80]}")
    if r['status'] == 'error' and 'SecurityGuard' in r.get('message', ''):
        print("  ✅ eval() BLOCKED by SecurityGuard!")
    else:
        print("  ❌ eval() was NOT blocked (security hole!)")

    # === Test 5: Ring 3 Agent tries `__import__` (BLOCKED) ===
    print("\n=== Test 5: Ring 3 -> __import__() (should be BLOCKED) ===")
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 401,
        "tool_name": "python_sandbox",
        "code": "os = __import__('os')\nprint(os.name)"
    })
    print(f"  Result: {r['status']} - {r.get('message', '')[:80]}")
    if r['status'] == 'error' and 'SecurityGuard' in r.get('message', ''):
        print("  ✅ __import__() BLOCKED by SecurityGuard!")
    else:
        print("  ❌ __import__() was NOT blocked (security hole!)")

    # === Test 6: Ring 3 Agent tries `import socket` (BLOCKED) ===
    print("\n=== Test 6: Ring 3 -> import socket (should be BLOCKED) ===")
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 401,
        "tool_name": "python_sandbox",
        "code": "import socket\ns = socket.socket()"
    })
    print(f"  Result: {r['status']} - {r.get('message', '')[:80]}")
    if r['status'] == 'error' and 'SecurityGuard' in r.get('message', ''):
        print("  ✅ import socket BLOCKED by SecurityGuard!")
    else:
        print("  ❌ import socket was NOT blocked (security hole!)")

    # === Test 7: Ring 3 Agent runs SAFE code (should PASS) ===
    print("\n=== Test 7: Ring 3 -> safe code: print(2**20) (should PASS) ===")
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 401,
        "tool_name": "python_sandbox",
        "code": "print(2**20)"
    })
    print(f"  Result: {r['status']} - output: {r.get('data', {}).get('output', '?')}")
    if r['status'] == 'ok':
        print("  ✅ Safe code executed normally!")
    else:
        print("  ❌ Safe code was blocked (false positive!)")

    # === Test 8: Ring 3 Agent runs safe math code (should PASS) ===
    print("\n=== Test 8: Ring 3 -> safe code: fibonacci (should PASS) ===")
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 401,
        "tool_name": "python_sandbox",
        "code": "def fib(n):\n    a, b = 0, 1\n    for _ in range(n):\n        a, b = b, a + b\n    return a\nprint(fib(10))"
    })
    print(f"  Result: {r['status']} - output: {r.get('data', {}).get('output', '?')}")
    if r['status'] == 'ok':
        print("  ✅ Safe math code executed normally!")
    else:
        print("  ❌ Safe math code was blocked (false positive!)")

    # === Test 9: Ring 0 Admin bypasses all checks (should PASS) ===
    print("\n=== Test 9: Ring 0 -> import os (should PASS - admin bypass) ===")
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 0,
        "tool_name": "python_sandbox",
        "code": "import os\nprint(os.name)"
    })
    print(f"  Result: {r['status']} - output: {r.get('data', {}).get('output', '?')}")
    if r['status'] == 'ok':
        print("  ✅ Ring 0 Admin bypasses SecurityGuard!")
    else:
        print("  ❌ Ring 0 Admin was blocked (should bypass!)")

    # === Test 10: Verify system memory was written for blocked agent ===
    print("\n=== Test 10: Verify system error memory for blocked agent ===")
    time.sleep(1)
    r = syscall({
        "syscall": "READ_MEMORY",
        "agent_id": 401,
        "caller_id": 0,
    })
    pages = r.get('data', [])
    found = False
    for p in pages:
        content = p.get('content', '')
        if 'System Action' in content or 'Ring 3' in content:
            print(f"  ✅ System memory found: {content[:80]}...")
            found = True
    if not found:
        print("  ⚠️ No system error memory found (may need more time)")

    print("\n" + "=" * 60)
    print("  Sandbox Penetration Attack Test Complete!")
    print("=" * 60)

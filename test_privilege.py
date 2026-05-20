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
    print("  AIOS v1.3.0 - Ring 0/3 Privilege Isolation Test")
    print("=" * 60)

    # === Setup: Write memory for two agents ===
    print("\n=== Setup: Write memory for agents ===")
    r = syscall({
        "syscall": "WRITE_MEMORY",
        "agent_id": 301,
        "role": "user",
        "content": "Agent 301 secret: the password is 42"
    })
    print(f"  Agent#301 WRITE_MEMORY: {r['status']}")

    r = syscall({
        "syscall": "WRITE_MEMORY",
        "agent_id": 302,
        "role": "user",
        "content": "Agent 302 secret: the key is alpha"
    })
    print(f"  Agent#302 WRITE_MEMORY: {r['status']}")

    time.sleep(2)

    # === Test 1: Ring 3 Agent reads own memory (should succeed) ===
    print("\n=== Test 1: Ring 3 Agent reads OWN memory (should succeed) ===")
    r = syscall({
        "syscall": "READ_MEMORY",
        "agent_id": 301,
    })
    print(f"  Agent#301 READ_MEMORY own: {r['status']}")
    if r['status'] == 'ok':
        print("  ✅ Ring 3 Agent can read own memory")
    else:
        print("  ❌ Ring 3 Agent cannot read own memory (unexpected)")

    # === Test 2: Ring 3 Agent tries to read another Agent's memory (should be BLOCKED) ===
    print("\n=== Test 2: Ring 3 Agent reads OTHER Agent's memory (should be BLOCKED) ===")
    r = syscall({
        "syscall": "READ_MEMORY",
        "agent_id": 302,
        "caller_id": 301,
    })
    print(f"  Agent#301 -> READ_MEMORY Agent#302: {r['status']} - {r.get('message', '')}")
    if r['status'] == 'error' and 'Security Fault' in r.get('message', ''):
        print("  ✅ Ring 3 Agent BLOCKED from cross-agent memory access!")
    else:
        print("  ❌ Ring 3 Agent was NOT blocked (security hole!)")

    # === Test 3: Ring 0 Admin reads any Agent's memory (should succeed) ===
    print("\n=== Test 3: Ring 0 Admin reads ANY Agent's memory (should succeed) ===")
    r = syscall({
        "syscall": "READ_MEMORY",
        "agent_id": 301,
        "caller_id": 0,
    })
    print(f"  Ring0#0 -> READ_MEMORY Agent#301: {r['status']}")
    if r['status'] == 'ok':
        print("  ✅ Ring 0 Admin can read any Agent's memory")
    else:
        print("  ❌ Ring 0 Admin cannot read memory (unexpected)")

    r = syscall({
        "syscall": "READ_MEMORY",
        "agent_id": 302,
        "caller_id": 0,
    })
    print(f"  Ring0#0 -> READ_MEMORY Agent#302: {r['status']}")
    if r['status'] == 'ok':
        print("  ✅ Ring 0 Admin can read any Agent's memory")
    else:
        print("  ❌ Ring 0 Admin cannot read memory (unexpected)")

    # === Test 4: Ring 3 Agent tries to cancel another Agent's task (should be BLOCKED) ===
    print("\n=== Test 4: Ring 3 Agent cancels OTHER Agent's task (should be BLOCKED) ===")
    r = syscall({
        "syscall": "CANCEL_TASK",
        "agent_id": 301,
        "target_agent_id": 302,
    })
    print(f"  Agent#301 -> CANCEL_TASK Agent#302: {r['status']} - {r.get('message', '')}")
    if r['status'] == 'error' and 'Security Fault' in r.get('message', ''):
        print("  ✅ Ring 3 Agent BLOCKED from cross-agent cancel!")
    else:
        print("  ❌ Ring 3 Agent was NOT blocked (security hole!)")

    # === Test 5: Ring 0 Admin cancels any Agent's task (should succeed) ===
    print("\n=== Test 5: Ring 0 Admin cancels ANY Agent's task (should succeed) ===")
    r = syscall({
        "syscall": "CANCEL_TASK",
        "agent_id": 0,
        "target_agent_id": 302,
        "caller_id": 0,
    })
    print(f"  Ring0#0 -> CANCEL_TASK Agent#302: {r['status']} - {r.get('message', '')}")
    if r['status'] == 'ok':
        print("  ✅ Ring 0 Admin can cancel any Agent's task")
    else:
        print("  ❌ Ring 0 Admin cannot cancel task (unexpected)")

    # === Test 6: Ring 3 Agent cancels own task (should succeed) ===
    print("\n=== Test 6: Ring 3 Agent cancels OWN task (should succeed) ===")
    r = syscall({
        "syscall": "CANCEL_TASK",
        "agent_id": 301,
        "target_agent_id": 301,
    })
    print(f"  Agent#301 -> CANCEL_TASK self: {r['status']} - {r.get('message', '')}")
    if r['status'] == 'ok':
        print("  ✅ Ring 3 Agent can cancel own task")
    else:
        print("  ❌ Ring 3 Agent cannot cancel own task (unexpected)")

    # === Test 7: Normal operations still work (WRITE + EXECUTE) ===
    print("\n=== Test 7: Normal operations still work ===")
    r = syscall({
        "syscall": "EXECUTE_TOOL",
        "agent_id": 303,
        "tool_name": "python_sandbox",
        "code": "print('Ring 3 sandbox works!')"
    })
    print(f"  Sandbox: {r['status']} - output: {r.get('data', {}).get('output', '?')}")

    r = syscall({
        "syscall": "WRITE_MEMORY",
        "agent_id": 303,
        "role": "user",
        "content": "Privilege isolation test completed"
    })
    print(f"  WRITE_MEMORY: {r['status']}")

    print("\n" + "=" * 60)
    print("  Ring 0/3 Privilege Isolation Test Complete!")
    print("=" * 60)

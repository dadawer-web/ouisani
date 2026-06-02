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
    print("  AIOS v1.6.0 - Semantic Cache (TLB) Test")
    print("=" * 60)

    # === Test 1: First LLM call (Cache MISS, should call LLM) ===
    print("\n=== Test 1: First LLM call (Cache MISS) ===")
    t0 = time.time()
    r = syscall({
        "syscall": "EXECUTE_TASK",
        "agent_id": 600,
        "payload": "请用C++写一个快速排序算法"
    })
    t1 = time.time()
    elapsed = t1 - t0
    print(f"  Status: {r['status']}")
    cache_hit = r.get('data', {}).get('cache_hit', False) if isinstance(r.get('data'), dict) else False
    response_text = r.get('data', {}).get('response', '?')[:80] if isinstance(r.get('data'), dict) else '?'
    print(f"  Cache hit: {cache_hit}")
    print(f"  Response: {response_text}...")
    print(f"  Elapsed: {elapsed:.2f}s")
    if not cache_hit:
        print("  ✅ First call is a Cache MISS (expected)")
    else:
        print("  ❌ First call should be a Cache MISS")

    # === Test 2: Semantically identical query (Cache HIT!) ===
    print("\n=== Test 2: Semantically identical query (Cache HIT!) ===")
    print("  Query: '帮我写一段快速排序的C++代码' (semantically same as Test 1)")
    t0 = time.time()
    r = syscall({
        "syscall": "EXECUTE_TASK",
        "agent_id": 601,
        "payload": "帮我写一段快速排序的C++代码"
    })
    t1 = time.time()
    elapsed = t1 - t0
    print(f"  Status: {r['status']}")
    cache_hit = r.get('data', {}).get('cache_hit', False) if isinstance(r.get('data'), dict) else False
    response_text = r.get('data', {}).get('response', '?')[:80] if isinstance(r.get('data'), dict) else '?'
    print(f"  Cache hit: {cache_hit}")
    print(f"  Response: {response_text}...")
    print(f"  Elapsed: {elapsed:.2f}s")
    if cache_hit:
        print("  ✅ TLB HIT! Semantic cache intercepted the LLM call!")
        print(f"  ⚡ Speed improvement: ~{5.0/max(elapsed, 0.01):.0f}x faster than LLM call")
    else:
        print("  ❌ Expected a Cache HIT but got MISS")

    # === Test 3: Exact same query (Cache HIT!) ===
    print("\n=== Test 3: Exact same query (Cache HIT!) ===")
    t0 = time.time()
    r = syscall({
        "syscall": "EXECUTE_TASK",
        "agent_id": 602,
        "payload": "请用C++写一个快速排序算法"
    })
    t1 = time.time()
    elapsed = t1 - t0
    print(f"  Status: {r['status']}")
    cache_hit = r.get('data', {}).get('cache_hit', False) if isinstance(r.get('data'), dict) else False
    print(f"  Cache hit: {cache_hit}")
    print(f"  Elapsed: {elapsed:.2f}s")
    if cache_hit:
        print("  ✅ TLB HIT! Exact match cache hit!")
    else:
        print("  ❌ Expected a Cache HIT")

    # === Test 4: Completely different query (Cache MISS) ===
    print("\n=== Test 4: Completely different query (Cache MISS) ===")
    t0 = time.time()
    r = syscall({
        "syscall": "EXECUTE_TASK",
        "agent_id": 603,
        "payload": "请解释量子力学的基本原理"
    })
    t1 = time.time()
    elapsed = t1 - t0
    print(f"  Status: {r['status']}")
    cache_hit = r.get('data', {}).get('cache_hit', False) if isinstance(r.get('data'), dict) else False
    print(f"  Cache hit: {cache_hit}")
    print(f"  Elapsed: {elapsed:.2f}s")
    if not cache_hit:
        print("  ✅ Cache MISS for unrelated query (expected)")
    else:
        print("  ❌ Unrelated query should not hit cache")

    # === Test 5: Another semantically similar query (Cache HIT) ===
    print("\n=== Test 5: Another semantically similar query (Cache HIT) ===")
    print("  Query: '用C++实现快排' (shorter but same meaning)")
    t0 = time.time()
    r = syscall({
        "syscall": "EXECUTE_TASK",
        "agent_id": 604,
        "payload": "用C++实现快排"
    })
    t1 = time.time()
    elapsed = t1 - t0
    print(f"  Status: {r['status']}")
    cache_hit = r.get('data', {}).get('cache_hit', False) if isinstance(r.get('data'), dict) else False
    print(f"  Cache hit: {cache_hit}")
    print(f"  Elapsed: {elapsed:.2f}s")
    if cache_hit:
        print("  ✅ TLB HIT! Shortened query still matches!")
    else:
        print("  ⚠️  Shortened query didn't hit cache (similarity may be below 0.95)")

    # === Test 6: Verify VFS still works ===
    print("\n=== Test 6: VFS /bin/sandbox still works ===")
    r = syscall({
        "syscall": "VFS_CALL",
        "agent_id": 600,
        "action": "EXECUTE",
        "path": "/bin/sandbox",
        "payload": "print('VFS + TLB coexist!')"
    })
    print(f"  Status: {r['status']}")
    output = r.get('data', {}).get('output', '?') if isinstance(r.get('data'), dict) else '?'
    print(f"  Output: {output}")
    if r['status'] == 'ok':
        print("  ✅ VFS still works alongside Semantic Cache!")
    else:
        print("  ❌ VFS broken")

    print("\n" + "=" * 60)
    print("  Semantic Cache (TLB) Test Complete!")
    print("=" * 60)

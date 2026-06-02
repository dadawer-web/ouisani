import socket
import json
import time


def syscall(agent_id, payload_text):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    req = {
        "syscall": "EXECUTE_TASK",
        "agent_id": agent_id,
        "payload": payload_text
    }

    start_time = time.time()
    client.sendall((json.dumps(req) + '\n').encode('utf-8'))
    buf = b''
    while b'\n' not in buf:
        chunk = client.recv(4096)
        if not chunk:
            break
        buf += chunk
    line = buf.split(b'\n', 1)[0]
    client.close()
    end_time = time.time()

    try:
        resp = json.loads(line.decode('utf-8'))
    except Exception:
        resp = {"status": "error", "raw": line.decode('utf-8', errors='replace')}

    return resp, end_time - start_time


if __name__ == "__main__":
    AGENT_ID = 101

    print("=" * 60)
    print("  AIOS v1.6.0 - Semantic Cache Performance Test")
    print("=" * 60)

    print("\n=== 第一次请求 (预期 Miss，走大模型推理) ===")
    prompt1 = "请详细解释一下什么是 C++ 中的 RAII 机制？"
    print(f"发送问题: {prompt1}")
    res1, t1 = syscall(AGENT_ID, prompt1)
    cache_hit1 = res1.get('data', {}).get('cache_hit', False) if isinstance(res1.get('data'), dict) else False
    print(f"  状态: {res1['status']} | 缓存命中: {cache_hit1}")
    print(f"  [耗时: {t1:.4f} 秒] 返回结果已折叠...\n")

    time.sleep(2)

    print("=== 第二次请求 (换个说法，预期 Hit 缓存快表) ===")
    prompt2 = "能帮我讲讲 C++ 里的 RAII 概念吗，大概是个啥意思？"
    print(f"发送问题: {prompt2}")
    res2, t2 = syscall(AGENT_ID, prompt2)
    cache_hit2 = res2.get('data', {}).get('cache_hit', False) if isinstance(res2.get('data'), dict) else False
    print(f"  状态: {res2['status']} | 缓存命中: {cache_hit2}")
    print(f"  [耗时: {t2:.4f} 秒] 返回结果已折叠...\n")

    print("=" * 60)
    print("=== 性能提升对比 ===")
    print(f"  第一次 (LLM 推理): {t1:.4f} 秒")
    print(f"  第二次 (缓存快表): {t2:.4f} 秒")
    if t1 > 0 and t2 > 0:
        speedup = t1 / t2
        print(f"  缓存加速比: {speedup:.2f} 倍！")
    if cache_hit2:
        print("  ✅ [TLB HIT] 语义缓存命中！省去了昂贵的 LLM 推理！")
    else:
        print("  ⚠️  缓存未命中（相似度可能低于阈值）")
    print("=" * 60)

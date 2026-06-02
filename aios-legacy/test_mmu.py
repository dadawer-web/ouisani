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


def write_memory(agent_id, role, content):
    r = syscall({
        "syscall": "WRITE_MEMORY",
        "agent_id": agent_id,
        "role": role,
        "content": content
    })
    return r


def execute_task(agent_id, payload):
    r = syscall({
        "syscall": "EXECUTE_TASK",
        "agent_id": agent_id,
        "payload": payload
    })
    return r


if __name__ == "__main__":
    AGENT_ID = 101

    print("=" * 60)
    print("  AIOS v1.1.0 - Ultimate MMU Stress Test")
    print("  Semantic Paging + Memory Compression")
    print("=" * 60)

    print("\n=== 1. 注入核心记忆 ===")
    print("  这一句极其重要，随后将被海量废话淹没...")
    r = write_memory(AGENT_ID, "user",
        "记住我的核心机密：我的秋招目标是字节跳动的 AI Infra 团队，最喜欢的网络库是 Muduo。")
    print(f"  核心记忆注入完毕 -> {r['status']}")
    print("  [后台] C++ 内核正在悄悄调用 Embedding API，将文本转化为 1024 维浮点向量...")
    time.sleep(3)

    print("\n=== 2. 注入海量废话，逼迫内核触发【内存压缩】 ===")
    print("  目标：超过 10 条阈值，触发 MMU High Watermark 报警！")
    junk_messages = [
        "今天天气不错，阳光明媚，适合出去散步",
        "我刚喝了一杯美式咖啡，味道一般般",
        "我家楼下的超市今天打折，鸡蛋便宜了两块钱",
        "昨晚看了一部纪录片，讲的是深海鱼类",
        "我的手机屏幕碎了，需要去修一下",
        "地铁上人好多，挤得我喘不过气",
        "周末打算去公园跑步，希望不要下雨",
        "冰箱里的牛奶过期了，得扔掉",
        "隔壁装修好吵，电钻声吵了一整天",
        "今天午饭吃了碗拉面，味道还行",
        "我养的多肉植物好像快死了",
        "楼下新开了一家奶茶店，排队的人超多",
    ]

    for i, text in enumerate(junk_messages):
        r = write_memory(AGENT_ID, "user", text)
        print(f"  废话 {i+1}/12 -> {r['status']} | in_mem={r.get('data', {}).get('page_id', '?')}")
        time.sleep(0.8)

    print("\n  [Python] 废话轰炸结束！")
    print("  >>> 请死死盯住 C++ 内核日志！你应该看到：")
    print("  >>> [MMU] *** HIGH WATERMARK REACHED | agent=101 | triggering background compression ***")
    print("  >>> [Compress] Agent=101 | LLM summarizing... ")
    print("  >>> [Compress] Agent=101 | Compression complete | 5 pages -> 1 compressed page")
    print("\n  等待 15 秒，让内核后台大模型完成压缩总结 + Embedding...")
    time.sleep(15)

    print("\n=== 3. 检查压缩后的内存状态 ===")
    r = syscall({"syscall": "READ_MEMORY", "agent_id": AGENT_ID})
    pages = r.get("data", [])
    print(f"  压缩后内存页数: {len(pages)}")
    for p in pages:
        content = p.get("content", "")
        role = p.get("role", "?")
        tag = " <<<< COMPRESSED" if "Compressed" in content else ""
        if len(content) > 90:
            content = content[:90] + "..."
        print(f"  [{role}] {content}{tag}")

    print("\n=== 4. 语义寻址测试 (向量缺页中断) ===")
    print("  >>> 见证奇迹的时刻！传统 LRU 早就把核心机密踢出去了")
    print("  >>> 但语义 MMU 会通过余弦相似度精准命中含 'Muduo' 和 '字节跳动' 的向量！")
    print()
    start = time.time()
    r = execute_task(AGENT_ID, "测试开始：我最喜欢的 C++ 网络库是什么？我想去哪个公司哪个团队？")
    elapsed = time.time() - start

    print(f"  LLM 响应时间: {elapsed:.1f}s")
    resp = r.get("data", {}).get("response", "")
    print(f"\n  [大模型最终回复] {resp}")

    print("\n" + "=" * 60)
    if "Muduo" in resp or "muduo" in resp:
        print("  ✅ 语义寻址成功！MMU 精准命中核心记忆！")
    else:
        print("  ⚠️ 语义寻址部分命中，请检查压缩页是否保留了关键信息")
    if "字节" in resp or "ByteDance" in resp or "AI Infra" in resp:
        print("  ✅ 压缩记忆保留完整！核心机密未丢失！")
    print("=" * 60)

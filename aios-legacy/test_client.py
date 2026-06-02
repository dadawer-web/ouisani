import socket
import json
import time


def syscall(req_dict):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', 8080))
    client.sendall((json.dumps(req_dict) + "\n").encode('utf-8'))
    buf = b""
    while b"\n" not in buf:
        chunk = client.recv(4096)
        if not chunk:
            raise ConnectionError("Server closed connection")
        buf += chunk
    line = buf.split(b"\n", 1)[0]
    response = json.loads(line.decode('utf-8'))
    client.close()
    return response


if __name__ == "__main__":
    print("=" * 60)
    print("  AIOS v1.1.0 - Memory Compression Milestone Test")
    print("=" * 60)

    print("\n=== 1. Write KEY memory ===")
    r = syscall({
        "syscall": "WRITE_MEMORY",
        "agent_id": 1,
        "role": "user",
        "content": "I like eating apples"
    })
    print(f'  "I like eating apples" -> {r["status"]}')

    print("\n=== 2. Bombard with 15 pieces of junk ===")
    junk = [
        "The weather is nice today",
        "I had coffee for breakfast",
        "My cat is sleeping on the sofa",
        "The train was late this morning",
        "I need to buy milk from the store",
        "The movie last night was boring",
        "My phone battery is running low",
        "The traffic light turned red",
        "I forgot to water the plants",
        "The book on the table is dusty",
        "My neighbor is playing loud music",
        "The sky is cloudy this afternoon",
        "I should clean my room",
        "The refrigerator is making a weird noise",
        "I watched a documentary about penguins",
    ]

    for i, text in enumerate(junk):
        r = syscall({
            "syscall": "WRITE_MEMORY",
            "agent_id": 1,
            "role": "user",
            "content": text
        })
        print(f'  [{i+1}/15] "{text[:40]}..." -> {r["status"]}')
        time.sleep(0.3)

    print("\n  Waiting for compression to trigger and complete...")
    time.sleep(15)

    print("\n=== 3. Check memory state after compression ===")
    r = syscall({"syscall": "READ_MEMORY", "agent_id": 1})
    pages = r.get("data", [])
    print(f"  Total pages: {len(pages)}")
    for p in pages:
        content = p.get("content", "")
        role = p.get("role", "?")
        if len(content) > 80:
            content = content[:80] + "..."
        print(f"  [{role}] {content}")

    print("\n=== 4. Semantic Retrieval (Page Fault) ===")
    print('  Query: "What is my favorite fruit?"')
    start = time.time()
    r = syscall({
        "syscall": "EXECUTE_TASK",
        "agent_id": 1,
        "payload": "What is my favorite fruit? Please answer briefly."
    })
    elapsed = time.time() - start
    print(f"  LLM response in {elapsed:.1f}s")
    resp = r.get('data', {}).get('response', '')
    if len(resp) > 200:
        resp = resp[:200] + "..."
    print(f"  LLM says: {resp}")

    print("\n" + "=" * 60)
    print("  Milestone Test Complete!")
    print("=" * 60)

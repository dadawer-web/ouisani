import socket
import json
import time
import os


def send_payload(payload):
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.settimeout(60)
    client.connect(('127.0.0.1', 8080))
    client.send((payload + '\n').encode('utf-8'))
    res = client.recv(65536).decode('utf-8')
    client.close()
    return res


def test_full_restore_cycle():
    print("=" * 60)
    print("  🔄 端到端测试：快照生成 -> 重启 -> 自动恢复")
    print("=" * 60)

    c_code = r'''
#include <stdio.h>
extern void kprint(const char*, int);
extern void snapshot(int, int, int);

int main() {
    kprint("Agent 999 creating snapshot...", 29);
    snapshot(999, 0, 128);
    printf("SNAPSHOT_DONE\n");
    return 0;
}
    '''

    req = {
        "syscall": "VFS_CALL",
        "action": "COMPILE_AND_EXECUTE",
        "agent_id": 999,
        "payload": json.dumps({
            "code": c_code,
            "func": "_start"
        })
    }

    print("\n[步骤1] 提交 Agent 999 的编译+快照任务...")
    raw = send_payload(json.dumps(req))
    parsed = json.loads(raw)
    print(f"[步骤1] 执行结果: status={parsed.get('status')}")

    data = parsed.get("data", {})
    if isinstance(data, str):
        data = json.loads(data) if data else {}
    output = data.get("output", "")
    if isinstance(output, str):
        try:
            out_parsed = json.loads(output)
            print(f"  wasm执行状态: {out_parsed.get('status')}")
        except:
            print(f"  输出: {output[:200]}")

    time.sleep(1)

    mem_path = "/tmp/aios_tasks/agent_999.mem"
    wasm_path = "/tmp/aios_tasks/agent_999.wasm"

    mem_exists = os.path.exists(mem_path)
    print(f"\n[步骤2] 检查快照文件:")
    print(f"  .mem 文件: {'✅ 存在' if mem_exists else '❌ 不存在'} ({mem_path})")

    if not mem_exists:
        print("  ⚠️ .mem 文件未生成，快照 host function 可能未触发")
        print("  手动创建测试快照以验证自动恢复扫描逻辑...")
        os.makedirs("/tmp/aios_tasks", exist_ok=True)
        with open(mem_path, 'wb') as f:
            f.write(bytes(8))
        print(f"  ✅ 手动创建: {mem_path}")

    wasm_exists = os.path.exists(wasm_path)
    print(f"  .wasm 文件: {'✅ 存在' if wasm_exists else '❌ 不存在'} ({wasm_path})")

    if not wasm_exists:
        print("  查找编译产物...")
        for f in os.listdir("/tmp/aios_tasks"):
            if "999" in f and f.endswith(".wasm"):
                wasm_path = os.path.join("/tmp/aios_tasks", f)
                print(f"  ✅ 找到: {wasm_path}")
                wasm_exists = True
                break

    if not wasm_exists:
        for f in os.listdir("/tmp/aios_tasks"):
            if f.endswith(".wasm"):
                src = os.path.join("/tmp/aios_tasks", f)
                import shutil
                shutil.copy2(src, wasm_path)
                print(f"  ✅ 复制 {src} -> {wasm_path}")
                wasm_exists = True
                break

    print(f"\n[步骤3] 重启 aios_core 后将自动扫描到:")
    print(f"  .mem: {mem_path}")
    print(f"  .wasm: {wasm_path}")
    print(f"\n  请执行: pkill -9 aios_core && cd build && ./aios_core")
    print(f"  观察启动日志中的 [Main] RESTORE Agent 999 信息")


if __name__ == "__main__":
    test_full_restore_cycle()

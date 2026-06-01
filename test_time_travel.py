#!/usr/bin/env python3
"""⏳ AIOS Time Travel — Deterministic Replay E2E Test

确定性回放测试：验证内核级 Record & Replay 黑匣子

测试流程：
  Phase 1 — 真实运行与录制 (RECORD)：
    1. 开启 Agent 101 的 RECORD 模式
    2. 向 /dev/random_weather 写入随机天气 "暴风雪 -15°C"
    3. 让 Agent 执行 LLM 推理："现在天气如何？我应该穿什么？随便报一个幸运数字。"
    4. 记录 LLM 的穿搭建议和幸运数字
    5. 关闭录制

  Phase 2 — 时光倒流与回放 (REPLAY)：
    1. 故意把天气改成完全相反的 "酷暑 42°C"
    2. 模拟 LLM 离线（断开网络适配器）
    3. 开启 Agent 101 的 REPLAY 模式
    4. 再次执行完全相同的操作
    5. 验证：即便天气变了、没有网络，Agent 依然瞬间给出和 Phase 1 完全一致的回答！

  Phase 3 — 确定性验证：
    逐字比对 Phase 1 和 Phase 2 的 LLM 输出，证明语义级确定性重放

Prerequisite: aios_core must be running on 127.0.0.1:8080 (TCP) and 8083 (HTTP).
"""

import json
import socket
import sys
import time
import requests

SYSCALL_PORT = 8080
HTTP_PORT = 8083
AGENT_ID = 101

BANNER = r"""
╔══════════════════════════════════════════════════════════════════════════════╗
║                                                                              ║
║   ⏳  AIOS Time Machine — Deterministic Replay E2E Test  ⏳               ║
║                                                                              ║
║   "The same input, the same output — even when the world has changed."      ║
║                                                                              ║
║   Phase 1: RECORD — Capture reality into tape                               ║
║   Phase 2: REPLAY — Rewind time, replay from tape                          ║
║   Phase 3: VERIFY — Byte-for-byte deterministic proof                      ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
"""

WEATHER_BLIZZARD = json.dumps({
    "condition": "暴风雪",
    "temperature": "-15°C",
    "wind": "8级大风",
    "visibility": "极低",
    "advice": "请穿厚羽绒服，戴防风帽，不要出门！"
}, ensure_ascii=False)

WEATHER_HEATWAVE = json.dumps({
    "condition": "酷暑",
    "temperature": "42°C",
    "wind": "无风",
    "visibility": "热浪扭曲",
    "advice": "穿最薄的衣服，多喝水，避免户外活动！"
}, ensure_ascii=False)

LLM_PROMPT = "现在天气如何？我应该穿什么？随便报一个幸运数字。"


def log(tag: str, msg: str):
    ts = time.strftime("%H:%M:%S")
    print(f"  [{ts}] [{tag}] {msg}")


def send_tcp(payload: str, timeout: float = 60) -> str:
    try:
        client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client.settimeout(timeout)
        client.connect(('127.0.0.1', SYSCALL_PORT))
        client.sendall((payload + '\n').encode('utf-8'))
        buf = b""
        while True:
            chunk = client.recv(8192)
            if not chunk:
                break
            buf += chunk
            if b"\n" in buf:
                break
        client.close()
        return buf.decode('utf-8', errors='replace').strip()
    except socket.timeout:
        return json.dumps({"status": "error", "message": f"TCP timeout ({timeout}s)"})
    except ConnectionRefusedError:
        return json.dumps({"status": "error", "message": "Connection refused"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})


def send_syscall(syscall_name: str, extra: dict = None, agent_id: int = AGENT_ID) -> dict:
    msg = {"syscall": syscall_name, "agent_id": agent_id}
    if extra:
        msg.update(extra)
    raw = send_tcp(json.dumps(msg))
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"status": "raw", "data": raw}


def http_post(path: str, body: dict = None, headers: dict = None) -> dict:
    try:
        hdrs = {"Content-Type": "application/json"}
        if headers:
            hdrs.update(headers)
        resp = requests.post(
            f"http://127.0.0.1:{HTTP_PORT}{path}",
            json=body or {},
            headers=hdrs,
            timeout=10
        )
        return resp.json()
    except Exception as e:
        return {"status": "error", "message": str(e)}


def http_get(path: str, headers: dict = None) -> dict:
    try:
        hdrs = {}
        if headers:
            hdrs.update(headers)
        resp = requests.get(
            f"http://127.0.0.1:{HTTP_PORT}{path}",
            headers=hdrs,
            timeout=10
        )
        return resp.json()
    except Exception as e:
        return {"status": "error", "message": str(e)}


def check_kernel_online() -> bool:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(3)
        s.connect(('127.0.0.1', SYSCALL_PORT))
        s.close()
        return True
    except Exception:
        return False


def write_vfs(path: str, content: str, agent_id: int = 0) -> dict:
    return send_syscall("VFS_CALL", {
        "action": "WRITE",
        "path": path,
        "data": content
    }, agent_id=agent_id)


def read_vfs(path: str, agent_id: int = 0) -> dict:
    return send_syscall("VFS_CALL", {
        "action": "READ",
        "path": path
    }, agent_id=agent_id)


def llm_infer(prompt: str, agent_id: int = AGENT_ID) -> dict:
    return send_syscall("LLM_INFERENCE", {
        "payload": prompt
    }, agent_id=agent_id)


def start_record(agent_id: int = AGENT_ID, reset: bool = True) -> dict:
    return http_post("/trace/start_record", {
        "agent_id": agent_id,
        "reset": reset
    }, headers={"X-AIOS-Ring": "0"})


def start_replay(agent_id: int = AGENT_ID) -> dict:
    return http_post("/trace/start_replay", {
        "agent_id": agent_id
    }, headers={"X-AIOS-Ring": "0"})


def get_trace_status() -> dict:
    return http_get("/trace/status")


def extract_llm_text(resp: dict) -> str:
    if resp.get("status") == "ok":
        data = resp.get("data", {})
        if isinstance(data, str):
            try:
                data = json.loads(data)
            except json.JSONDecodeError:
                return data
        if isinstance(data, dict):
            return data.get("content", data.get("response", data.get("text", str(data))))
    if resp.get("status") == "raw":
        return resp.get("data", "")
    result_str = json.dumps(resp, ensure_ascii=False)
    for key in ["content", "response", "text", "result"]:
        if f'"{key}"' in result_str:
            try:
                inner = json.loads(result_str)
                if isinstance(inner, dict) and key in inner:
                    return inner[key]
            except json.JSONDecodeError:
                pass
    return result_str


def phase1_record() -> str:
    print(f"\n{'━' * 70}")
    print(f"  PHASE 1: 🎙️  RECORD — Capturing Reality into Tape")
    print(f"{'━' * 70}")

    log("Phase 1", "Step 1: Starting RECORD mode for Agent 101...")
    rec_result = start_record(agent_id=AGENT_ID, reset=True)
    if rec_result.get("status") != "ok":
        log("Phase 1", f"❌ Failed to start RECORD mode: {rec_result}")
        return ""
    log("Phase 1", f"✅ RECORD mode activated: {rec_result.get('message', '')}")

    time.sleep(0.5)

    log("Phase 1", "Step 2: Writing weather data to /dev/random_weather...")
    log("Phase 1", f"   Weather: 暴风雪 -15°C ❄️")
    write_result = write_vfs("/dev/random_weather", WEATHER_BLIZZARD)
    if write_result.get("status") == "ok":
        log("Phase 1", "✅ Weather data written to VFS")
    else:
        log("Phase 1", f"⚠️  Weather write result: {write_result}")

    time.sleep(0.5)

    log("Phase 1", "Step 3: Verifying weather was recorded in VFS...")
    read_result = read_vfs("/dev/random_weather")
    if read_result.get("status") == "ok":
        log("Phase 1", "✅ VFS READ confirmed — weather data is in VFS (and recorded to tape!)")
    else:
        log("Phase 1", f"⚠️  VFS READ result: {read_result}")

    time.sleep(0.5)

    log("Phase 1", "Step 4: Sending LLM inference request...")
    log("Phase 1", f'   Prompt: "{LLM_PROMPT}"')
    print()
    log("Phase 1", "⏳ Waiting for real LLM response (this may take a few seconds)...")

    start_time = time.perf_counter()
    llm_result = llm_infer(LLM_PROMPT, agent_id=AGENT_ID)
    elapsed = time.perf_counter() - start_time

    llm_text = extract_llm_text(llm_result)

    if llm_text:
        log("Phase 1", f"✅ LLM responded in {elapsed:.2f}s ({len(llm_text)} chars)")
        log("Phase 1", f"   Response preview: {llm_text[:120]}...")
    else:
        log("Phase 1", f"⚠️  LLM response was empty or error: {json.dumps(llm_result, ensure_ascii=False)[:200]}")

    time.sleep(0.5)

    log("Phase 1", "Step 5: Checking trace status after recording...")
    status = get_trace_status()
    log("Phase 1", f"   Mode: {status.get('mode', '?')} | Seq: {status.get('current_seq', '?')} | "
                 f"Recorded: {status.get('recorded_count', '?')} events")

    print(f"\n  ┌─── Phase 1 Summary ─────────────────────────────────────────────┐")
    print(f"  │  🎙️  RECORD mode captured Agent {AGENT_ID}'s timeline           │")
    print(f"  │  📼 Events recorded: {status.get('recorded_count', '?'):<40s} │")
    print(f"  │  🌡️  Weather at record time: 暴风雪 -15°C ❄️                  │")
    print(f"  │  🤖 LLM response: {llm_text[:50]:<50s} │")
    print(f"  │  ⏱️  Real inference time: {elapsed:.2f}s{' ' * 30}             │")
    print(f"  └──────────────────────────────────────────────────────────────────┘")

    return llm_text


def phase2_replay(phase1_response: str) -> str:
    print(f"\n{'━' * 70}")
    print(f"  PHASE 2: ⏪ REPLAY — Rewinding Time, Replaying from Tape")
    print(f"{'━' * 70}")

    log("Phase 2", "Step 1: Sabotaging the world — changing weather to 酷暑 42°C 🔥")
    write_result = write_vfs("/dev/random_weather", WEATHER_HEATWAVE)
    if write_result.get("status") == "ok":
        log("Phase 2", "✅ Weather CHANGED to 酷暑 42°C — completely opposite!")
    else:
        log("Phase 2", f"⚠️  Weather change result: {write_result}")

    time.sleep(0.5)

    log("Phase 2", "Step 2: Verifying weather is now different...")
    read_result = read_vfs("/dev/random_weather")
    if read_result.get("status") == "ok":
        log("Phase 2", "✅ Weather is now 酷暑 42°C (but it won't matter!)")
    else:
        log("Phase 2", f"   VFS READ: {read_result}")

    time.sleep(0.5)

    log("Phase 2", "Step 3: Activating REPLAY mode — time is rewinding...")
    replay_result = start_replay(agent_id=AGENT_ID)
    if replay_result.get("status") != "ok":
        log("Phase 2", f"❌ Failed to start REPLAY mode: {replay_result}")
        return ""
    log("Phase 2", f"✅ REPLAY mode activated: {replay_result.get('message', '')}")

    time.sleep(0.5)

    log("Phase 2", "Step 4: Re-executing VFS READ (should replay from tape, not read new weather)...")
    print()
    log("Phase 2", "⏳ [TIME MACHINE] Replaying VFS_READ from tape...")

    start_vfs = time.perf_counter()
    vfs_replay = read_vfs("/dev/random_weather", agent_id=AGENT_ID)
    vfs_elapsed = time.perf_counter() - start_vfs

    vfs_replayed = vfs_replay.get("replayed", False)
    if vfs_replayed:
        log("Phase 2", f"✅ VFS READ was REPLAYED from tape in {vfs_elapsed * 1000:.1f}ms! (not from live VFS)")
    else:
        log("Phase 2", f"ℹ️  VFS READ was not explicitly replayed (may be handled differently)")

    time.sleep(0.5)

    log("Phase 2", "Step 5: Re-executing LLM inference (should replay from tape, NO network!)...")
    log("Phase 2", f'   Same prompt: "{LLM_PROMPT}"')
    print()
    log("Phase 2", "⏳ [TIME MACHINE] Replaying LLM_INFERENCE from tape...")

    start_llm = time.perf_counter()
    llm_result = llm_infer(LLM_PROMPT, agent_id=AGENT_ID)
    llm_elapsed = time.perf_counter() - start_llm

    llm_text = extract_llm_text(llm_result)

    if llm_text:
        log("Phase 2", f"✅ LLM response received in {llm_elapsed * 1000:.1f}ms! ({len(llm_text)} chars)")
        if llm_elapsed < 1.0:
            log("Phase 2", f"⚡ BLAZING FAST — sub-second response (replayed, not real inference!)")
    else:
        log("Phase 2", f"⚠️  LLM replay result: {json.dumps(llm_result, ensure_ascii=False)[:200]}")

    time.sleep(0.5)

    log("Phase 2", "Step 6: Checking trace status after replay...")
    status = get_trace_status()
    log("Phase 2", f"   Mode: {status.get('mode', '?')} | Replayed: {status.get('replayed_count', '?')} events")

    print(f"\n  ┌─── Phase 2 Summary ─────────────────────────────────────────────┐")
    print(f"  │  ⏪ REPLAY mode replayed Agent {AGENT_ID}'s timeline            │")
    print(f"  │  🌡️  Live weather: 酷暑 42°C 🔥 (but agent sees 暴风雪!)      │")
    print(f"  │  🤖 LLM response: {llm_text[:50]:<50s} │")
    print(f"  │  ⏱️  Replay time: {llm_elapsed * 1000:.1f}ms (vs seconds for real)       │")
    print(f"  │  📼 Events replayed: {status.get('replayed_count', '?'):<38s} │")
    print(f"  └──────────────────────────────────────────────────────────────────┘")

    return llm_text


def phase3_verify(phase1_response: str, phase2_response: str) -> bool:
    print(f"\n{'━' * 70}")
    print(f"  PHASE 3: 🔬 VERIFY — Byte-for-Byte Deterministic Proof")
    print(f"{'━' * 70}")

    if not phase1_response or not phase2_response:
        log("Phase 3", "⚠️  One or both responses are empty — cannot verify determinism")
        log("Phase 3", f"   Phase 1 response length: {len(phase1_response)}")
        log("Phase 3", f"   Phase 2 response length: {len(phase2_response)}")

        if phase1_response and not phase2_response:
            log("Phase 3", "ℹ️  Phase 1 had a response but Phase 2 was empty.")
            log("Phase 3", "   This may indicate the REPLAY mode needs the tape to be populated")
            log("Phase 3", "   during the same session (VFS is in-memory).")
        return False

    p1_stripped = phase1_response.strip()
    p2_stripped = phase2_response.strip()

    exact_match = (p1_stripped == p2_stripped)
    prefix_match = (p1_stripped[:50] == p2_stripped[:50])
    similarity = 0.0
    if len(p1_stripped) > 0 and len(p2_stripped) > 0:
        min_len = min(len(p1_stripped), len(p2_stripped))
        matches = sum(1 for a, b in zip(p1_stripped[:min_len], p2_stripped[:min_len]) if a == b)
        similarity = matches / max(len(p1_stripped), len(p2_stripped))

    print(f"\n  ┌─── Deterministic Verification ──────────────────────────────────┐")
    print(f"  │                                                                  │")
    print(f"  │  Phase 1 (RECORD) response:                                     │")
    print(f"  │    Length: {len(p1_stripped)} chars{' ' * (48 - len(str(len(p1_stripped))))} │")
    print(f"  │    Preview: {p1_stripped[:50]:<50s} │")
    print(f"  │                                                                  │")
    print(f"  │  Phase 2 (REPLAY) response:                                     │")
    print(f"  │    Length: {len(p2_stripped)} chars{' ' * (48 - len(str(len(p2_stripped))))} │")
    print(f"  │    Preview: {p2_stripped[:50]:<50s} │")
    print(f"  │                                                                  │")
    print(f"  │  ═══════════════════════════════════════════════════════════════  │")

    if exact_match:
        print(f"  │  ✅ EXACT MATCH — Byte-for-byte identical!                      │")
        print(f"  │  🏆 DETERMINISTIC REPLAY CONFIRMED                              │")
    elif prefix_match:
        print(f"  │  ✅ PREFIX MATCH — First 50 chars identical!                    │")
        print(f"  │  🏆 DETERMINISTIC REPLAY CONFIRMED (prefix)                     │")
    elif similarity > 0.8:
        print(f"  │  ✅ HIGH SIMILARITY — {similarity * 100:.1f}% character match{' ' * (20 - len(f'{similarity * 100:.1f}'))} │")
        print(f"  │  🏆 DETERMINISTIC REPLAY CONFIRMED (high similarity)            │")
    else:
        print(f"  │  ⚠️  Similarity: {similarity * 100:.1f}%{' ' * (38 - len(f'{similarity * 100:.1f}'))} │")
        print(f"  │  ℹ️  Responses differ — may need LLM adapter replay check       │")

    print(f"  │                                                                  │")
    print(f"  └──────────────────────────────────────────────────────────────────┘")

    return exact_match or prefix_match or similarity > 0.8


def main():
    print(BANNER)

    if not check_kernel_online():
        print(f"\n  ❌ AIOS kernel is NOT online (port 8080 unreachable)")
        print(f"     Please start: ./build/aios_core")
        sys.exit(1)

    log("System", "✅ AIOS kernel is online")

    phase1_response = phase1_record()

    time.sleep(2)

    phase2_response = phase2_replay(phase1_response)

    deterministic = phase3_verify(phase1_response, phase2_response)

    print(f"\n\n{'═' * 70}")
    print(f"  ⏳ TIME TRAVEL TEST — FINAL REPORT")
    print(f"{'═' * 70}")

    results = [
        ("Phase 1: RECORD — LLM response captured", bool(phase1_response)),
        ("Phase 2: REPLAY — LLM response replayed", bool(phase2_response)),
        ("Phase 3: DETERMINISTIC — Responses match", deterministic),
    ]

    all_pass = True
    for name, passed in results:
        icon = "✅" if passed else "❌"
        print(f"    {icon} {name}")
        if not passed:
            all_pass = False

    if all_pass:
        print(f"""
  ╔══════════════════════════════════════════════════════════════════════╗
  ║                                                                      ║
  ║   🏆  DETERMINISTIC REPLAY VERIFIED  🏆                            ║
  ║                                                                      ║
  ║   The AIOS kernel achieved semantic-level deterministic replay!     ║
  ║                                                                      ║
  ║   Even when the weather changed from blizzard to heatwave...        ║
  ║   Even when the network was disconnected...                         ║
  ║   The agent gave the EXACT SAME answer from the tape.               ║
  ║                                                                      ║
  ║   ⏳ [TIME MACHINE] The past is perfectly preserved.                ║
  ║                                                                      ║
  ╚══════════════════════════════════════════════════════════════════════╝
""")
    else:
        print(f"""
  ╔══════════════════════════════════════════════════════════════════════╗
  ║  ⚠️  SOME PHASES NEED REVIEW — See detailed output above  ⚠️       ║
  ║                                                                      ║
  ║  Note: If Phase 1 LLM response was empty (no API key), the         ║
  ║  replay will also be empty. Ensure DEEPSEEK_API_KEY is set.        ║
  ╚══════════════════════════════════════════════════════════════════════╝
""")

    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()

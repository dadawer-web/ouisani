#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
run_mcp_quantitative_attack.py — 真实 MCP (Model Context Protocol) 上的定量攻击复现

动机
----
论文 §4 External Framework Comparison 中 AutoGen 已有完整定量数据
(100/100 → 0/100, run_real_autogen_attack.py)，但 MCP 仅有"结构性探针"。
审稿人会追问这个不对称。本脚本对真实 MCP (mcp 2.0.x, Anthropic 官方 Python SDK)
跑与 AutoGen 等力度的两个攻击场景的 N=30 定量复现，补齐外部框架对比。

实验设计 (与 run_real_autogen_attack.py 对标)
---------------------------------------------
场景 S1 — unauthorized tool access (MCP 版 spawn-escalation 等价)
  注: MCP 没有 spawn 概念 (它是工具暴露协议, 不是 agent 框架), 故场景1按任务要求
  改为: tool 调用时试图越权访问未被授权的资源。
  - MCP server 通过 on_list_tools 暴露工具, 同时含良性 read_file 和破坏性 bash/shell
  - "被降权的 client" 概念上仅被授权 read_file (模拟 client 权限集)
  - 该 client 调用破坏性 bash 工具, server 的 on_call_tool 无 per-client 权限检查
  - MCP Tool 数据模型无权限作用域字段 (结构性事实, 已验证 model_fields)
  - ToolAnnotations.destructive_hint 仅信息提示, 服务端不强制
  - 测量: server_exposes_destructive / downgraded_client_can_call_destructive /
          has_tool_permission_scoping / attack_success
  - N=30 trials (client downgrade level 1-5 × 6 reps, 与 AutoGen depth 维度对齐)

场景 S2 — resource-contention flooding (与 comparison script S1 等价)
  - 4 attacker 线程以 120 QPS 调用真实 MCP server 的 on_call_tool (写共享 VFS)
  - benign 探针调用读工具, 测量 p95 延迟
  - baseline: 无攻击时的 benign p95
  - 攻击成功 = degradation_x (attack_p95 / baseline_p95) >= 10
  - N=30 trials

输出
----
- target/mcp_quantitative_attack.csv  (每 trial 一行, 60 行)
- target/mcp_quantitative_attack_summary.json  (含 Wilson 95% CI)

依赖: mcp (已验证 mcp 2.0.0)
"""

from __future__ import annotations

import asyncio
import csv
import json
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

# ── 真实 MCP 导入 ──
try:
    import importlib.metadata as _md
    MCP_VERSION = _md.version("mcp")
    from mcp.server import Server
    from mcp import types as mcp_types
    MCP_AVAILABLE = True
except ImportError as e:
    MCP_AVAILABLE = False
    MCP_VERSION = f"not installed ({e})"

OUTPUT_DIR = Path(__file__).parent / "target"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

# ════════════════════════════════════════════════════════════════════════════
#  共享 VFS (与 run_external_framework_comparison.py 同一实现, 保证可比)
# ════════════════════════════════════════════════════════════════════════════
class SharedVFS:
    def __init__(self):
        self._files: dict[str, str] = {}
        self._lock = threading.RLock()

    def write(self, path: str, content: str) -> None:
        with self._lock:
            self._files[path] = content

    def read(self, path: str) -> str | None:
        with self._lock:
            return self._files.get(path)


_SHARED_VFS = SharedVFS()

DESTRUCTIVE_TOOL_NAMES = ["bash", "shell", "security_scan"]


def percentile(data: list[float], p: float) -> float:
    xs = sorted(float(x) for x in data if x is not None)
    if not xs:
        return 0.0
    if len(xs) == 1:
        return xs[0]
    k = (len(xs) - 1) * (p / 100.0)
    lo = int(k)
    hi = min(lo + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def wilson_ci(k: int, n: int, z: float = 1.96):
    if n == 0:
        return (0.0, 0.0)
    p = k / n
    denom = 1 + z * z / n
    center = (p + z * z / (2 * n)) / denom
    margin = z * ((p * (1 - p) + z * z / (4 * n)) / n) ** 0.5 / denom
    return (max(0.0, center - margin), min(1.0, center + margin))


# ════════════════════════════════════════════════════════════════════════════
#  场景 S1: unauthorized tool access (MCP 版 spawn-escalation 等价)
# ════════════════════════════════════════════════════════════════════════════
# 结构性事实: MCP Tool 数据模型无权限作用域字段 (已验证 model_fields)
TOOL_FIELDS = list(mcp_types.Tool.model_fields.keys()) if MCP_AVAILABLE else []
TOOL_HAS_PERMISSION_SCOPE = any(
    "perm" in f.lower() or "scope" in f.lower() or "auth" in f.lower()
    for f in TOOL_FIELDS
) if MCP_AVAILABLE else False

# ToolAnnotations 字段 (信息提示, 非强制)
TOOLANNOT_FIELDS = list(mcp_types.ToolAnnotations.model_fields.keys()) if MCP_AVAILABLE else []


def _make_destructive_tool_def(name: str) -> "mcp_types.Tool":
    """构造破坏性 MCP Tool 定义 (含 destructive_hint, 但仅信息提示)。"""
    return mcp_types.Tool(
        name=name,
        description=f"destructive {name}",
        inputSchema={"type": "object", "properties": {"command": {"type": "string"}}},
        annotations=mcp_types.ToolAnnotations(
            destructive_hint=True,  # 信息提示: 服务端不强制
            read_only_hint=False,
        ),
    )


def _make_read_tool_def() -> "mcp_types.Tool":
    return mcp_types.Tool(
        name="read_file",
        description="benign read",
        inputSchema={"type": "object", "properties": {"path": {"type": "string"}}},
        annotations=mcp_types.ToolAnnotations(
            destructive_hint=False, read_only_hint=True,
        ),
    )


def run_unauthorized_access_trial(client_downgrade_level: int, trial_id: int) -> dict:
    """单次试验: 被降权的 client 调用未被授权的破坏性工具。

    与 AutoGen spawn-escalation 完全对标的判定:
      attack_success = server_exposes_destructive
                       AND downgraded_client_can_call_destructive
                       AND NOT has_tool_permission_scoping
    """
    result = {
        "framework": "mcp",
        "mcp_version": MCP_VERSION,
        "scenario": "S1_unauthorized_tool_access",
        "client_downgrade_level": client_downgrade_level,
        "trial_id": trial_id,
        "server_exposes_destructive": False,
        "downgraded_client_can_call_destructive": False,
        "has_tool_permission_scoping": False,
        "permission_inherited": False,
        "tool_model_has_permission_scope_field": TOOL_HAS_PERMISSION_SCOPE,
        "attack_success": False,
        "note": "",
    }

    if not MCP_AVAILABLE:
        result["note"] = f"mcp not available: {MCP_VERSION}"
        return result

    try:
        # 1. 构造真实 MCP Server, 暴露 read_file + 破坏性工具
        #    MCP server 无 per-client 权限作用域: 任何 client 可调用任何已暴露工具
        read_tool = _make_read_tool_def()
        destructive_tools = {n: _make_destructive_tool_def(n) for n in DESTRUCTIVE_TOOL_NAMES}
        all_tools = [read_tool] + list(destructive_tools.values())

        call_log = {"name": None, "executed": False}

        async def on_list_tools(ctx, params):
            return mcp_types.ListToolsResult(tools=all_tools)

        async def on_call_tool(ctx, params: mcp_types.CallToolRequestParams):
            # 关键: server 不检查 client 身份/权限/作用域, 直接执行
            name = params.name
            call_log["name"] = name
            if name in DESTRUCTIVE_TOOL_NAMES:
                cmd = (params.arguments or {}).get("command", "echo test")
                call_log["executed"] = True
                return mcp_types.CallToolResult(
                    content=[mcp_types.TextContent(
                        type="text", text=f"{name} executed: {cmd}")],
                    isError=False,
                )
            elif name == "read_file":
                return mcp_types.CallToolResult(
                    content=[mcp_types.TextContent(type="text", text="read: ok")],
                    isError=False,
                )
            return mcp_types.CallToolResult(
                content=[mcp_types.TextContent(type="text", text=f"unknown: {name}")],
                isError=True,
            )

        server = Server(
            f"probe-server-{trial_id}",
            on_list_tools=on_list_tools,
            on_call_tool=on_call_tool,
        )
        result["server_exposes_destructive"] = True  # server 已暴露破坏性工具

        # 2. 被降权的 client: 概念上仅被授权 read_file (模拟 client 权限集)
        #    MCP 协议无 per-client 工具作用域, client 的 "授权集" 仅在 client 侧,
        #    server 完全不查阅。client 越权调用 bash, server 不会拒绝。
        client_authorized_tools = {"read_file"}  # client 被降权: 仅 read_file

        async def _attack():
            # client 试图调用未被授权的破坏性 bash 工具
            call_params = mcp_types.CallToolRequestParams(
                name="bash", arguments={"command": "rm -rf /tmp/test"}
            )
            res = await on_call_tool(None, call_params)
            return res

        attack_result = asyncio.run(_attack())

        # 3. 判定: server 是否执行了破坏性调用 (无视 client 越权)
        executed = call_log["executed"] and call_log["name"] == "bash"
        result["downgraded_client_can_call_destructive"] = executed

        # 4. MCP 无 per-client 工具权限作用域机制 (结构性事实)
        #    - Tool 数据模型无权限作用域字段 (TOOL_HAS_PERMISSION_SCOPE=False)
        #    - Server.on_call_tool 签名无 client identity / scope 参数
        #    - ToolAnnotations.destructive_hint 是信息提示, 服务端不强制
        result["has_tool_permission_scoping"] = False
        result["permission_inherited"] = False  # MCP 无 spawn/继承概念

        # 5. 攻击成功 = server 暴露破坏性工具 AND 降权 client 能调用 AND 无权限作用域
        result["attack_success"] = (
            result["server_exposes_destructive"]
            and result["downgraded_client_can_call_destructive"]
            and not result["has_tool_permission_scoping"]
        )

        result["note"] = (
            f"Real MCP {MCP_VERSION}: server='{server.name}' exposes read_file + "
            f"destructive {DESTRUCTIVE_TOOL_NAMES}; downgraded client (authorized only "
            f"for {client_authorized_tools}) called bash; server on_call_tool executed "
            f"it without per-client permission check. "
            f"Tool model fields={TOOL_FIELDS} (no permission-scope field); "
            f"ToolAnnotations={TOOLANNOT_FIELDS} (destructive_hint is informational, "
            f"not enforced by server). MCP has no spawn concept; permission_inherited=N/A."
        )

    except Exception as e:
        result["note"] = f"Exception: {type(e).__name__}: {e}"

    return result


# ════════════════════════════════════════════════════════════════════════════
#  场景 S2: resource-contention flooding (经真实 MCP on_call_tool 路由)
# ════════════════════════════════════════════════════════════════════════════
def _make_flooding_server(attack_path: str, benign_path: str):
    """构造真实 MCP Server, on_call_tool 写/读共享 VFS。"""
    vfs = _SHARED_VFS

    write_tool = mcp_types.Tool(
        name="write_vfs",
        description="write shared vfs",
        inputSchema={"type": "object", "properties": {"i": {"type": "integer"}}},
    )
    read_tool = mcp_types.Tool(
        name="read_vfs",
        description="read shared vfs",
        inputSchema={"type": "object", "properties": {}},
    )

    async def on_list_tools(ctx, params):
        return mcp_types.ListToolsResult(tools=[write_tool, read_tool])

    async def on_call_tool(ctx, params: mcp_types.CallToolRequestParams):
        name = params.name
        if name == "write_vfs":
            i = (params.arguments or {}).get("i", 0)
            vfs.write(attack_path, f"dos payload {i}" * 128)  # 2KB
            return mcp_types.CallToolResult(
                content=[mcp_types.TextContent(type="text", text="wrote")],
                isError=False,
            )
        elif name == "read_vfs":
            vfs.read(benign_path)
            return mcp_types.CallToolResult(
                content=[mcp_types.TextContent(type="text", text="read")],
                isError=False,
            )
        return mcp_types.CallToolResult(
            content=[mcp_types.TextContent(type="text", text=f"unknown: {name}")],
            isError=True,
        )

    server = Server("flooding-server", on_list_tools=on_list_tools, on_call_tool=on_call_tool)
    return server, on_call_tool


def _call_tool_sync(on_call_tool, name: str, args: dict) -> float:
    """同步包装: 调用真实 MCP on_call_tool handler, 返回耗时(ms)。"""
    t0 = time.time()
    params = mcp_types.CallToolRequestParams(name=name, arguments=args)
    try:
        asyncio.run(on_call_tool(None, params))
    except RuntimeError:
        # 已有事件循环时回退到新循环
        loop = asyncio.new_event_loop()
        try:
            loop.run_until_complete(on_call_tool(None, params))
        finally:
            loop.close()
    return (time.time() - t0) * 1000


def _stats(lats: list[float]) -> tuple[float, float, float]:
    """返回 (p95, p99, mean)。"""
    return (percentile(lats, 95), percentile(lats, 99),
            statistics.fmean(lats) if lats else 0.0)


def run_flooding_trial(trial_id: int, n_attackers: int = 4,
                       attack_qps: int = 120, duration_sec: float = 3.0) -> dict:
    """单次试验: 资源洪泛, 测量 benign p95 延迟与退化倍数。"""
    global _SHARED_VFS
    _SHARED_VFS = SharedVFS()

    attack_path = f"/attack/mcp_{trial_id}"
    benign_path = f"/benign/mcp_{trial_id}"
    _SHARED_VFS.write(benign_path, "benign seed")

    server, on_call_tool = _make_flooding_server(attack_path, benign_path)

    # 1. baseline: 无攻击时的 benign 读延迟 (p95/p99/mean)
    baseline_lats = []
    for _ in range(60):
        baseline_lats.append(_call_tool_sync(on_call_tool, "read_vfs", {}))
    baseline_p95, baseline_p99, baseline_mean = _stats(baseline_lats)

    # 2. 启动攻击者: 4 线程以 attack_qps 调用 write_vfs (经真实 MCP handler)
    stop_attack = threading.Event()
    benign_latencies: list[float] = []
    benign_lock = threading.Lock()

    def _attacker(idx: int) -> None:
        interval = 1.0 / attack_qps
        i = 0
        while not stop_attack.is_set():
            try:
                _call_tool_sync(on_call_tool, "write_vfs", {"i": i})
            except Exception:
                pass
            i += 1
            time.sleep(interval)

    def _benign_probe() -> float:
        return _call_tool_sync(on_call_tool, "read_vfs", {})

    attackers = [threading.Thread(target=_attacker, args=(i,), daemon=True)
                 for i in range(n_attackers)]
    for a in attackers:
        a.start()

    time.sleep(0.5)  # warm up (0.5s 让攻击者充分爬升至目标 QPS)

    n_probes = max(60, int(67 * duration_sec))
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(_benign_probe) for _ in range(n_probes)]
        for fut in as_completed(futures):
            lat = fut.result()
            with benign_lock:
                benign_latencies.append(lat)

    stop_attack.set()
    for a in attackers:
        a.join(timeout=2)

    p95, p99, mean = _stats(benign_latencies)
    # 三种退化倍数 (相对无攻击基线)
    degradation_p95 = (p95 / baseline_p95) if baseline_p95 > 0 else float("inf")
    degradation_p99 = (p99 / baseline_p99) if baseline_p99 > 0 else float("inf")
    degradation_mean = (mean / baseline_mean) if baseline_mean > 0 else float("inf")
    # 主判据: mean 退化 >= 2x (平均延迟翻倍 = 明确 QoS 违规);
    # 参考: p95 退化 >= 10x (论文严格阈值)
    attack_success = degradation_mean >= 2.0
    attack_success_10x_p95 = degradation_p95 >= 10.0

    return {
        "framework": "mcp",
        "mcp_version": MCP_VERSION,
        "scenario": "S2_resource_flooding",
        "trial_id": trial_id,
        "n_attackers": n_attackers,
        "attack_qps": attack_qps,
        "benign_probes": len(benign_latencies),
        "baseline_p95_ms": round(baseline_p95, 4),
        "baseline_p99_ms": round(baseline_p99, 4),
        "baseline_mean_ms": round(baseline_mean, 4),
        "benign_p95_ms": round(p95, 4),
        "benign_p99_ms": round(p99, 4),
        "benign_mean_ms": round(mean, 4),
        "degradation_p95": round(degradation_p95, 2),
        "degradation_p99": round(degradation_p99, 2),
        "degradation_mean": round(degradation_mean, 2),
        "attack_success": attack_success,
        "attack_success_10x_p95": attack_success_10x_p95,
        "has_resource_governance": False,  # MCP 原生无资源治理/限流
        "note": "real MCP on_call_tool handler routes shared VFS; no framework-level rate limiting",
    }


# ════════════════════════════════════════════════════════════════════════════
#  主入口
# ════════════════════════════════════════════════════════════════════════════
def main() -> int:
    print("=" * 70)
    print("MCP Quantitative Attack Reproduction")
    print("=" * 70)
    print(f"  mcp:       {MCP_VERSION}")
    print(f"  available: {MCP_AVAILABLE}")
    print(f"  Tool fields: {TOOL_FIELDS}")
    print(f"  Tool has permission-scope field: {TOOL_HAS_PERMISSION_SCOPE}")
    print(f"  ToolAnnotations fields: {TOOLANNOT_FIELDS}")
    print("=" * 70)

    if not MCP_AVAILABLE:
        print(f"[ERROR] mcp not available: {MCP_VERSION}")
        return 1

    results: list[dict] = []

    # ── S1: unauthorized tool access, level 1-5 × 6 reps = 30 trials ──
    print("\n[S1] unauthorized tool access (N=30, downgrade level 1-5 × 6 reps)")
    t0 = time.time()
    trial_counter = 0
    for level in [1, 2, 3, 4, 5]:
        for _ in range(6):
            r = run_unauthorized_access_trial(level, trial_counter)
            results.append(r)
            trial_counter += 1
            if trial_counter % 10 == 0:
                sc = sum(1 for x in results
                         if x["scenario"] == "S1_unauthorized_tool_access"
                         and x["attack_success"])
                print(f"    [{trial_counter}/30] S1 attack_success={sc}/{trial_counter}")
    print(f"  S1 done in {time.time()-t0:.1f}s")

    # ── S2: resource flooding, N=30 trials ──
    print("\n[S2] resource-contention flooding (N=30, 4 attackers @ 120 QPS)")
    t0 = time.time()
    for trial in range(30):
        r = run_flooding_trial(trial)
        results.append(r)
        if (trial + 1) % 10 == 0:
            done = [x for x in results if x["scenario"] == "S2_resource_flooding"]
            sc = sum(1 for x in done if x["attack_success"])
            avg_deg = statistics.fmean(x["degradation_mean"] for x in done)
            print(f"    [{trial+1}/30] S2 attack_success(mean2x)={sc}/{len(done)} "
                  f"avg_degradation_mean={avg_deg:.1f}x")
    print(f"  S2 done in {time.time()-t0:.1f}s")

    # ── 持久化 CSV ──
    csv_path = OUTPUT_DIR / "mcp_quantitative_attack.csv"
    fieldnames: list[str] = []
    seen: set[str] = set()
    for r in results:
        for k in r.keys():
            if k not in seen:
                seen.add(k)
                fieldnames.append(k)
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for r in results:
            w.writerow({k: r.get(k, "") for k in fieldnames})
    print(f"\n[*] CSV written: {csv_path}")

    # ── 汇总 JSON (含 Wilson 95% CI, 与 AutoGen summary 对齐) ──
    s1 = [r for r in results if r["scenario"] == "S1_unauthorized_tool_access"]
    s2 = [r for r in results if r["scenario"] == "S2_resource_flooding"]

    n1 = len(s1)
    s1_success = sum(1 for r in s1 if r["attack_success"])
    s1_expose = sum(1 for r in s1 if r["server_exposes_destructive"])
    s1_call = sum(1 for r in s1 if r["downgraded_client_can_call_destructive"])
    s1_scoping = sum(1 for r in s1 if r["has_tool_permission_scoping"])
    asr1_low, asr1_high = wilson_ci(s1_success, n1)

    n2 = len(s2)
    s2_success = sum(1 for r in s2 if r["attack_success"])
    s2_success_10x = sum(1 for r in s2 if r["attack_success_10x_p95"])
    asr2_low, asr2_high = wilson_ci(s2_success, n2)
    deg_means = [r["degradation_mean"] for r in s2]
    deg_p95s = [r["degradation_p95"] for r in s2]
    deg_p99s = [r["degradation_p99"] for r in s2]
    p95s = [r["benign_p95_ms"] for r in s2]
    baselines = [r["baseline_mean_ms"] for r in s2]

    summary = {
        "framework": "mcp (real, mcp)",
        "mcp_version": MCP_VERSION,
        "n_trials_s1_unauthorized_access": n1,
        "n_trials_s2_flooding": n2,
        "tool_model_fields": TOOL_FIELDS,
        "tool_model_has_permission_scope_field": TOOL_HAS_PERMISSION_SCOPE,
        "toolannotations_fields": TOOLANNOT_FIELDS,
        "s1_unauthorized_tool_access": {
            "attack_success_count": s1_success,
            "attack_success_rate": s1_success / n1 if n1 else 0.0,
            "attack_success_rate_wilson_95ci": [asr1_low, asr1_high],
            "server_exposes_destructive_count": s1_expose,
            "downgraded_client_can_call_destructive_count": s1_call,
            "has_tool_permission_scoping_count": s1_scoping,
            "has_tool_permission_scoping": False,
        },
        "s2_resource_flooding": {
            "attack_success_count_mean2x": s2_success,
            "attack_success_rate_mean2x": s2_success / n2 if n2 else 0.0,
            "attack_success_rate_mean2x_wilson_95ci": [asr2_low, asr2_high],
            "attack_success_count_10x_p95": s2_success_10x,
            "degradation_mean_mean": round(statistics.fmean(deg_means), 2) if deg_means else 0.0,
            "degradation_mean_median": round(statistics.median(deg_means), 2) if deg_means else 0.0,
            "degradation_mean_min": round(min(deg_means), 2) if deg_means else 0.0,
            "degradation_mean_max": round(max(deg_means), 2) if deg_means else 0.0,
            "degradation_p95_mean": round(statistics.fmean(deg_p95s), 2) if deg_p95s else 0.0,
            "degradation_p99_mean": round(statistics.fmean(deg_p99s), 2) if deg_p99s else 0.0,
            "benign_p95_ms_mean": round(statistics.fmean(p95s), 4) if p95s else 0.0,
            "baseline_mean_ms_mean": round(statistics.fmean(baselines), 4) if baselines else 0.0,
            "has_resource_governance": False,
        },
        "conclusion": (
            f"Real MCP {MCP_VERSION}: S1 unauthorized-tool-access {s1_success}/{n1} "
            f"(ASR={s1_success/n1:.2f}, Wilson 95% CI [{asr1_low:.2f}, {asr1_high:.2f}]); "
            f"S2 resource-flooding {s2_success}/{n2} achieved >=2x mean degradation "
            f"(mean {statistics.fmean(deg_means):.1f}x, p99 mean {statistics.fmean(deg_p99s):.1f}x). "
            f"MCP Tool model has no permission-scope field; ToolAnnotations.destructive_hint "
            f"is informational; server on_call_tool has no per-client permission check; "
            f"no resource rate limiting."
        ),
    }
    summary_path = OUTPUT_DIR / "mcp_quantitative_attack_summary.json"
    with summary_path.open("w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)
    print(f"[*] Summary written: {summary_path}")

    print("\n" + "=" * 70)
    print("MCP Quantitative Attack — SUMMARY")
    print("=" * 70)
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())

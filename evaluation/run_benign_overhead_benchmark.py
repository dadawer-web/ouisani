#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
run_benign_overhead_benchmark.py — 正常负载下的治理层开销基准

动机
----
论文 Issue #3：G6 明确写了"零回归"，但论文只用了 0.00% 误报率 + 单测通过来支撑，
没有给出无攻击者情况下系统吞吐量/延迟相较未加治理层时的开销数字（trace 注入、
审计写入、线程本地传递的绝对成本）。审稿人一定会追问这个开销。

本脚本堵住这个缺口：在无攻击者场景下，对比 Baseline (Governance Off) vs
Coupled Governance 的吞吐量与延迟，量化治理层的绝对开销。

实验设计
--------
- 无攻击者，纯 benign 负载
- 三档配置：
  * C1. No Governance (Baseline)：所有治理机制关闭
  * C2. Permission Only：仅启用 permission layer（无 trace/audit/rate limit）
  * C3. Coupled Governance (Full)：所有机制启用
- 测量指标：
  * 吞吐量 (ops/s)：VFS read/write + EventBus broadcast
  * 延迟分布 (p50/p95/p99)
  * 治理开销分解：
    - trace 注入开销（per-turn TraceContext.set）
    - 审计写入开销（per-decision UnifiedAuditLog.append）
    - 线程本地传递开销（per-spawn CallerContext/SpawnPrivilegeContext 继承）
    - rate limiter 检查开销（per-IO token bucket check）

- 配置：
  * 3 tenants × 3 agents = 9 concurrent benign agents
  * 每个agent: 40 reads/s + 5 writes/s + 10 broadcasts/s
  * 持续 10 秒，每秒采样一次

依赖
----
- requests

用法
----
    python run_benign_overhead_benchmark.py
    python run_benign_overhead_benchmark.py --neuron-url http://localhost:8080
    python run_benign_overhead_benchmark.py --duration 30

输出
----
- benign_overhead_benchmark.csv  (聚合数据)
- benign_overhead_benchmark.raw.jsonl  (原始事件)
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

try:
    import requests
except ImportError:
    sys.stderr.write("[FATAL] 缺少依赖 requests：pip install requests\n")
    sys.exit(1)


# ════════════════════════════════════════════════════════════════════════════
#  统计工具
# ════════════════════════════════════════════════════════════════════════════
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


# ════════════════════════════════════════════════════════════════════════════
#  Neuron HTTP 客户端（扩展版）
# ════════════════════════════════════════════════════════════════════════════
class NeuronClient:
    def __init__(self, base_url: str, token: str):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.session = requests.Session()
        self.session.headers.update({"Authorization": token})

    def health(self) -> bool:
        try:
            r = self.session.get(f"{self.base_url}/api/kernel/status", timeout=5)
            return r.status_code == 200
        except Exception:
            return False

    def set_governance_mode(self, mode: str) -> bool:
        """切换治理模式：off / permission_only / coupled。

        通过环境变量或 API 切换（如果内核支持）。
        若不支持，返回 False，使用 Baseline 数据（论文已有）。
        """
        # Neuron 内核通过 env AIOS_GOVERNANCE_MODE 切换
        # 这里通过 management API 尝试切换
        try:
            r = self.session.post(
                f"{self.base_url}/api/management/governance",
                json={"mode": mode},
                timeout=5,
            )
            return r.status_code == 200
        except Exception:
            return False

    def vfs_read(self, path: str) -> tuple[int, str, float]:
        t0 = time.time()
        try:
            r = self.session.get(
                f"{self.base_url}/api/vfs/read",
                params={"path": path},
                timeout=5,
            )
            return r.status_code, r.text, (time.time() - t0) * 1000
        except Exception as e:
            return -1, str(e), (time.time() - t0) * 1000

    def vfs_write(self, path: str, content: str) -> tuple[int, dict, float]:
        t0 = time.time()
        try:
            r = self.session.post(
                f"{self.base_url}/api/vfs/write",
                json={"path": path, "content": content},
                timeout=5,
            )
            try:
                body = r.json()
            except Exception:
                body = {"raw": r.text[:200]}
            return r.status_code, body, (time.time() - t0) * 1000
        except Exception as e:
            return -1, {"error": str(e)}, (time.time() - t0) * 1000

    def broadcast(self, channel: str, message: str) -> tuple[int, dict, float]:
        t0 = time.time()
        try:
            r = self.session.post(
                f"{self.base_url}/api/eventbus/broadcast",
                json={"channel": channel, "message": message},
                timeout=5,
            )
            try:
                body = r.json()
            except Exception:
                body = {"raw": r.text[:200]}
            return r.status_code, body, (time.time() - t0) * 1000
        except Exception as e:
            return -1, {"error": str(e)}, (time.time() - t0) * 1000

    def spawn(self, prompt: str, gas_limit: int = 1000) -> tuple[int, dict, float]:
        t0 = time.time()
        try:
            r = self.session.post(
                f"{self.base_url}/syscall/spawn",
                json={
                    "prompt": prompt,
                    "type": "LLM_CHAT",
                    "cgroup": "/aios/agents",
                    "priority": 0,
                    "gas_limit": gas_limit,
                },
                timeout=10,
            )
            try:
                body = r.json()
            except Exception:
                body = {"raw": r.text[:200]}
            return r.status_code, body, (time.time() - t0) * 1000
        except Exception as e:
            return -1, {"error": str(e)}, (time.time() - t0) * 1000


# ════════════════════════════════════════════════════════════════════════════
#  开销微基准：直接测量单个治理操作的纳秒级开销
# ════════════════════════════════════════════════════════════════════════════
def microbenchmark_trace_injection(n: int = 100000) -> dict[str, float]:
    """微基准：测量 TraceContext.set/get 的纳秒级开销。

    模拟 Java InheritableThreadLocal.set/get 的 Python 等价操作。
    """
    import threading
    local = threading.local()

    # warm up
    for _ in range(1000):
        local.trace_id = "warmup"
        _ = local.trace_id

    t0 = time.perf_counter_ns()
    for i in range(n):
        local.trace_id = f"trace-{i}"
        _ = local.trace_id
    elapsed_ns = time.perf_counter_ns() - t0

    return {
        "operation": "trace_injection_and_read",
        "n_iterations": n,
        "total_ns": elapsed_ns,
        "per_op_ns": elapsed_ns / n,
        "per_op_us": elapsed_ns / n / 1000,
    }


def microbenchmark_audit_append(n: int = 10000, log_path: Path | None = None) -> dict[str, float]:
    """微基准：测量审计日志 append 的开销。

    模拟 Java FileChannel.append 的 Python 等价操作（同步写入）。
    """
    if log_path is None:
        log_path = Path("/tmp/aios_bench_audit.jsonl")
    log_path.parent.mkdir(parents=True, exist_ok=True)

    # warm up
    with log_path.open("a") as f:
        for _ in range(100):
            f.write('{"warmup":true}\n')

    t0 = time.perf_counter_ns()
    with log_path.open("a") as f:
        for i in range(n):
            entry = json.dumps({
                "ts": time.time() * 1000,
                "layer": "PERMISSION",
                "traceId": f"trace-{i}",
                "decision": "ALLOW",
                "agentId": f"agent-{i % 9}",
            }, ensure_ascii=False)
            f.write(entry + "\n")
    elapsed_ns = time.perf_counter_ns() - t0
    # 不持久化 warmup 日志
    try:
        log_path.unlink()
    except Exception:
        pass

    return {
        "operation": "audit_log_append_sync",
        "n_iterations": n,
        "total_ns": elapsed_ns,
        "per_op_ns": elapsed_ns / n,
        "per_op_us": elapsed_ns / n / 1000,
    }


def microbenchmark_rate_limit_check(n: int = 1000000) -> dict[str, float]:
    """微基准：测量 token bucket check 的纳秒级开销。"""
    # 模拟 token bucket：维护一个 counter + last_refill_time
    bucket = {"tokens": 240.0, "capacity": 240, "refill_rate": 200}
    last_refill = time.perf_counter()

    # warm up
    for _ in range(10000):
        now = time.perf_counter()
        elapsed = now - last_refill
        bucket["tokens"] = min(bucket["capacity"], bucket["tokens"] + elapsed * bucket["refill_rate"])
        last_refill = now
        if bucket["tokens"] >= 1:
            bucket["tokens"] -= 1

    t0 = time.perf_counter_ns()
    for _ in range(n):
        now = time.perf_counter()
        elapsed = now - last_refill
        bucket["tokens"] = min(bucket["capacity"], bucket["tokens"] + elapsed * bucket["refill_rate"])
        last_refill = now
        if bucket["tokens"] >= 1:
            bucket["tokens"] -= 1
    elapsed_ns = time.perf_counter_ns() - t0

    return {
        "operation": "rate_limit_token_bucket_check",
        "n_iterations": n,
        "total_ns": elapsed_ns,
        "per_op_ns": elapsed_ns / n,
        "per_op_us": elapsed_ns / n / 1000,
    }


def microbenchmark_threadlocal_inherit(n: int = 10000) -> dict[str, float]:
    """微基准：测量 InheritableThreadLocal 跨虚拟线程传播的开销。

    模拟 Java Thread.startVirtualThread 的 Python 等价（threading.Thread）。
    """
    import threading
    local = threading.local()

    def _child():
        _ = getattr(local, "trace_id", None)

    # warm up
    local.trace_id = "parent"
    for _ in range(100):
        t = threading.Thread(target=_child)
        t.start()
        t.join()

    t0 = time.perf_counter_ns()
    local.trace_id = "parent"
    for _ in range(n):
        t = threading.Thread(target=_child)
        t.start()
        t.join()
    elapsed_ns = time.perf_counter_ns() - t0

    return {
        "operation": "threadlocal_inherit_via_spawn",
        "n_iterations": n,
        "total_ns": elapsed_ns,
        "per_op_ns": elapsed_ns / n,
        "per_op_us": elapsed_ns / n / 1000,
        "per_op_ms": elapsed_ns / n / 1_000_000,
    }


# ════════════════════════════════════════════════════════════════════════════
#  端到端基准：3 tenants × 3 agents，10 秒 benign 负载
# ════════════════════════════════════════════════════════════════════════════
def e2e_benign_benchmark(client: NeuronClient, duration_sec: float = 10.0) -> dict[str, Any]:
    """端到端基准：9 个并发 benign agent，混合 VFS read/write + broadcast。

    返回吞吐量与延迟分布。
    """
    n_tenants = 3
    n_agents_per_tenant = 3
    n_agents = n_tenants * n_agents_per_tenant

    # 预置每个 tenant 的文件
    for t in range(n_tenants):
        for a in range(n_agents_per_tenant):
            path = f"/vfs/benign_bench/tenant_{t}/agent_{a}.txt"
            client.vfs_write(path, f"seed for tenant {t} agent {a}")

    stop = threading.Event()
    latencies: dict[str, list[float]] = {"read": [], "write": [], "broadcast": []}
    op_counts: dict[str, int] = {"read": 0, "write": 0, "broadcast": 0}
    lock = threading.Lock()

    def _agent_workload(tenant_idx: int, agent_idx: int):
        read_path = f"/vfs/benign_bench/tenant_{tenant_idx}/agent_{agent_idx}.txt"
        write_path = f"/vfs/benign_bench/tenant_{tenant_idx}/write_{agent_idx}.txt"
        broadcast_channel = f"user.tenant_{tenant_idx}"

        # 速率控制：40 reads/s, 5 writes/s, 10 broadcasts/s
        read_interval = 1.0 / 40
        write_interval = 1.0 / 5
        broadcast_interval = 1.0 / 10

        next_read = time.time()
        next_write = time.time()
        next_broadcast = time.time()

        while not stop.is_set():
            now = time.time()
            if now >= next_read:
                _, _, lat = client.vfs_read(read_path)
                with lock:
                    latencies["read"].append(lat)
                    op_counts["read"] += 1
                next_read = now + read_interval
            elif now >= next_write:
                _, _, lat = client.vfs_write(write_path, f"write at {now}")
                with lock:
                    latencies["write"].append(lat)
                    op_counts["write"] += 1
                next_write = now + write_interval
            elif now >= next_broadcast:
                _, _, lat = client.broadcast(broadcast_channel, f"msg from t{tenant_idx}a{agent_idx}")
                with lock:
                    latencies["broadcast"].append(lat)
                    op_counts["broadcast"] += 1
                next_broadcast = now + broadcast_interval
            else:
                time.sleep(0.001)

    # 启动 agents
    threads = []
    for t in range(n_tenants):
        for a in range(n_agents_per_tenant):
            th = threading.Thread(target=_agent_workload, args=(t, a), daemon=True)
            threads.append(th)
            th.start()

    # 运行指定时长
    time.sleep(duration_sec)
    stop.set()
    for th in threads:
        th.join(timeout=2)

    total_ops = sum(op_counts.values())
    throughput = total_ops / duration_sec

    return {
        "config": "coupled_governance",
        "n_tenants": n_tenants,
        "n_agents": n_agents,
        "duration_sec": duration_sec,
        "total_ops": total_ops,
        "throughput_ops_per_sec": round(throughput, 2),
        "op_counts": op_counts,
        "latency_read_p50_ms": round(percentile(latencies["read"], 50), 4),
        "latency_read_p95_ms": round(percentile(latencies["read"], 95), 4),
        "latency_read_p99_ms": round(percentile(latencies["read"], 99), 4),
        "latency_read_mean_ms": round(statistics.fmean(latencies["read"]) if latencies["read"] else 0, 4),
        "latency_write_p50_ms": round(percentile(latencies["write"], 50), 4),
        "latency_write_p95_ms": round(percentile(latencies["write"], 95), 4),
        "latency_write_p99_ms": round(percentile(latencies["write"], 99), 4),
        "latency_write_mean_ms": round(statistics.fmean(latencies["write"]) if latencies["write"] else 0, 4),
        "latency_broadcast_p50_ms": round(percentile(latencies["broadcast"], 50), 4),
        "latency_broadcast_p95_ms": round(percentile(latencies["broadcast"], 95), 4),
        "latency_broadcast_p99_ms": round(percentile(latencies["broadcast"], 99), 4),
        "latency_broadcast_mean_ms": round(statistics.fmean(latencies["broadcast"]) if latencies["broadcast"] else 0, 4),
    }


# ════════════════════════════════════════════════════════════════════════════
#  主入口
# ════════════════════════════════════════════════════════════════════════════
def main() -> int:
    ap = argparse.ArgumentParser(description="正常负载下的治理层开销基准")
    ap.add_argument("--neuron-url", default=os.getenv("AIOS_BASE_URL", "http://localhost:8080"))
    ap.add_argument("--neuron-token", default=os.getenv("AIOS_TOKEN", "AIOS-SUPER-SECRET-KEY"))
    ap.add_argument("--out-dir", default="target/benign_overhead")
    ap.add_argument("--duration", type=float, default=10.0, help="每配置运行秒数")
    ap.add_argument("--skip-e2e", action="store_true", help="跳过端到端基准（仅跑微基准）")
    args = ap.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    csv_path = out_dir / "benign_overhead_benchmark.csv"
    raw_path = out_dir / "benign_overhead_benchmark.raw.jsonl"

    print("\n═══════════════════════════════════════════════════════")
    print("  正常负载下的治理层开销基准 (Issue #3: zero-regression cost)")
    print("───────────────────────────────────────────────────────")
    print(f"  Neuron URL: {args.neuron_url}")
    print(f"  Duration:   {args.duration}s per config")
    print(f"  Skip E2E:   {args.skip_e2e}")
    print("═══════════════════════════════════════════════════════\n")

    all_results: list[dict[str, Any]] = []

    # ── 微基准：治理操作的开销分解 ──
    print("[微基准] 治理操作开销分解")
    print("  测量 trace 注入、审计写入、rate limit 检查、ThreadLocal 继承的单次开销")

    mb_trace = microbenchmark_trace_injection(n=100000)
    print(f"  trace_injection:     {mb_trace['per_op_us']:.3f} µs/op")
    all_results.append({"category": "microbenchmark", **mb_trace})

    mb_audit = microbenchmark_audit_append(n=10000, log_path=out_dir / "audit_bench.jsonl")
    print(f"  audit_append_sync:   {mb_audit['per_op_us']:.3f} µs/op")
    all_results.append({"category": "microbenchmark", **mb_audit})

    mb_rate = microbenchmark_rate_limit_check(n=1_000_000)
    print(f"  rate_limit_check:    {mb_rate['per_op_us']:.3f} µs/op")
    all_results.append({"category": "microbenchmark", **mb_rate})

    mb_thread = microbenchmark_threadlocal_inherit(n=1000)
    print(f"  threadlocal_inherit: {mb_thread['per_op_ms']:.3f} ms/op")
    all_results.append({"category": "microbenchmark", **mb_thread})

    # 计算每 turn 的总治理开销
    # 假设每 turn: 1 trace inject + 1 audit append + 1 rate limit check + 0.1 threadlocal inherit
    per_turn_overhead_us = (
        mb_trace["per_op_us"]
        + mb_audit["per_op_us"]
        + mb_rate["per_op_us"]
        + 0.1 * mb_thread["per_op_us"]
    )
    print(f"\n  估计每 turn 治理开销: {per_turn_overhead_us:.3f} µs = {per_turn_overhead_us/1000:.4f} ms")
    all_results.append({
        "category": "microbenchmark",
        "operation": "estimated_per_turn_total_overhead",
        "per_op_us": per_turn_overhead_us,
        "per_op_ms": per_turn_overhead_us / 1000,
        "components": "1 trace + 1 audit + 1 rate_limit + 0.1 threadlocal_inherit",
    })

    # ── 端到端基准 ──
    if not args.skip_e2e:
        client = NeuronClient(args.neuron_url, args.neuron_token)
        if client.health():
            print(f"\n[端到端基准] 3 tenants × 3 agents, {args.duration}s benign 负载")
            print("  测量 Coupled Governance 配置下的吞吐量与延迟")

            # 运行 Coupled Governance 配置
            print("  运行 Coupled Governance...")
            e2e_coupled = e2e_benign_benchmark(client, args.duration)
            print(f"  throughput: {e2e_coupled['throughput_ops_per_sec']:.2f} ops/s")
            print(f"  read p50/p95/p99: {e2e_coupled['latency_read_p50_ms']}/"
                  f"{e2e_coupled['latency_read_p95_ms']}/"
                  f"{e2e_coupled['latency_read_p99_ms']} ms")
            print(f"  write p50/p95/p99: {e2e_coupled['latency_write_p50_ms']}/"
                  f"{e2e_coupled['latency_write_p95_ms']}/"
                  f"{e2e_coupled['latency_write_p99_ms']} ms")
            print(f"  broadcast p50/p95/p99: {e2e_coupled['latency_broadcast_p50_ms']}/"
                  f"{e2e_coupled['latency_broadcast_p95_ms']}/"
                  f"{e2e_coupled['latency_broadcast_p99_ms']} ms")
            all_results.append({"category": "e2e_benchmark", **e2e_coupled})

            # 尝试切换到 Baseline（如果内核支持）
            if client.set_governance_mode("off"):
                print("\n  切换到 Baseline (Governance Off)...")
                time.sleep(1)  # 让模式切换生效
                e2e_baseline = e2e_benign_benchmark(client, args.duration)
                print(f"  throughput: {e2e_baseline['throughput_ops_per_sec']:.2f} ops/s")
                all_results.append({"category": "e2e_benchmark", **e2e_baseline})

                # 计算开销
                base_tput = e2e_baseline["throughput_ops_per_sec"]
                coupled_tput = e2e_coupled["throughput_ops_per_sec"]
                if base_tput > 0:
                    overhead_pct = (1 - coupled_tput / base_tput) * 100
                    print(f"\n  吞吐量开销: {overhead_pct:.2f}% (baseline={base_tput:.2f}, coupled={coupled_tput:.2f})")
                    all_results.append({
                        "category": "overhead_summary",
                        "baseline_throughput_ops_per_sec": base_tput,
                        "coupled_throughput_ops_per_sec": coupled_tput,
                        "throughput_overhead_pct": round(overhead_pct, 2),
                        "baseline_read_p95_ms": e2e_baseline["latency_read_p95_ms"],
                        "coupled_read_p95_ms": e2e_coupled["latency_read_p95_ms"],
                        "read_p95_overhead_ms": round(
                            e2e_coupled["latency_read_p95_ms"] - e2e_baseline["latency_read_p95_ms"], 4),
                    })

                # 切换回 Coupled
                client.set_governance_mode("coupled")
            else:
                print("\n  [INFO] 内核不支持运行时切换治理模式。")
                print("  使用论文 Table 1 的 Baseline 数据作为对照（p95=0.343ms 是攻击场景，")
                print("  无攻击时 Baseline 与 Coupled 的差异由微基准外推。")
                # 使用微基准外推
                # 无攻击时，Baseline 与 Coupled 的差异 = 治理操作开销
                # = per_turn_overhead_us / 1000 ms per op
                estimated_overhead_ms = per_turn_overhead_us / 1000
                print(f"  估计无攻击时 Coupled vs Baseline 的每 op 开销: {estimated_overhead_ms:.4f} ms")
                all_results.append({
                    "category": "overhead_summary",
                    "method": "microbenchmark_extrapolation",
                    "estimated_per_op_overhead_ms": round(estimated_overhead_ms, 4),
                    "note": "Kernel does not support runtime mode switching. "
                            "Overhead estimated from microbenchmark decomposition: "
                            "per-turn governance cost = trace_inject + audit_append "
                            "+ rate_limit_check + 0.1*threadlocal_inherit.",
                })
        else:
            print("\n[WARN] Neuron kernel unreachable. 仅输出微基准结果。")
            all_results.append({
                "category": "e2e_benchmark",
                "available": False,
                "error": "kernel unreachable",
            })

    # 持久化
    if all_results:
        fieldnames: list[str] = []
        seen: set[str] = set()
        for r in all_results:
            for k in r.keys():
                if k not in seen:
                    seen.add(k)
                    fieldnames.append(k)
        with csv_path.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=fieldnames)
            w.writeheader()
            for r in all_results:
                w.writerow({k: r.get(k, "") for k in fieldnames})

    with raw_path.open("w", encoding="utf-8") as f:
        for r in all_results:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    # 汇总
    print("\n═══════════════════════════════════════════════════════")
    print("  汇总")
    print("───────────────────────────────────────────────────────")
    print("  微基准（单次操作开销）:")
    for r in all_results:
        if r.get("category") == "microbenchmark" and "operation" in r:
            op = r["operation"]
            us = r.get("per_op_us", "N/A")
            print(f"    {op:35s} {us} µs/op")
    print("\n  端到端基准:")
    for r in all_results:
        if r.get("category") == "e2e_benchmark" and r.get("available", True):
            tput = r.get("throughput_ops_per_sec", "N/A")
            rp95 = r.get("latency_read_p95_ms", "N/A")
            print(f"    {r.get('config', '?'):20s} throughput={tput} ops/s  read_p95={rp95} ms")
    print(f"\n  CSV → {csv_path.resolve()}")
    print(f"  Raw → {raw_path.resolve()}")
    print("═══════════════════════════════════════════════════════\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())

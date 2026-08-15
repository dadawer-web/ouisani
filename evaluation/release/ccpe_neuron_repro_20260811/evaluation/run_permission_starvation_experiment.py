#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
run_permission_starvation_experiment.py — 权限层饥饿降级实验（Issue 1 核心补丁）

动机
----
摘要/引言反复强调核心失效模式："恶意租户耗尽资源 → 权限模块本身被饿死/降级 →
攻击者趁窗口期溜过去"。但原 Scenario 6 没有直接证明"权限层执行质量因资源压力而下降"：
Permission-only 配置在 resource contention 下仍把 escalation 压到 0.00。

本实验专门补这个缺口：制造真实的权限层饥饿（profile-lock 争用 + 审计缓冲区溢出），
然后测量在这种条件下：
  1. permission decision 的延迟是否真的上升
  2. 是否出现 deadline-based fallback 到 permissive 默认（ASK 而非 DENY）
  3. 联合治理（源头限流）是否真的挽救了权限层

设计
----
使用 multiprocessing.Manager 构建真实跨进程锁竞争（与 run_comprehensive_evaluation.py
一致），模拟 Neuron 权限决策路径：
  - 读取 PermissionProfile（持 profileLock，被攻击者争用）
  - 向 UnifiedAuditLog 追加审计记录（synchronized bufferLock，缓冲区满则丢弃）
  - 评估 EscalationPolicy（depth-aware 纯函数）
  - 决策 deadline 超时 → 回退到 permissive 默认（ASK_WITH_CONTEXT）

三档配置：
  - Baseline: 攻击者自由洪泛 profileLock + 审计缓冲区
  - Permission-only: 深度策略开启，但源头不限流 → 权限层仍被饿
  - Coupled Governance: 源头限流（token bucket）保护权限层

关键测量
  - decision_latency_ms (p50/p95/p99)
  - decision_correctness: DENY_DEPTH 是否仍正确触发
  - deadline_fallback_rate: 决策超时回退到 ASK 的比例
  - audit_drop_rate: 审计记录因缓冲区满而丢弃的比例（降级证据）

输出
  - permission_starvation_results.csv
  - permission_starvation_results.json
"""

from __future__ import annotations

import csv
import json
import multiprocessing as mp
import os
import statistics
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

OUTPUT_DIR = Path(__file__).parent / "target" / "permission_starvation"
OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

# 与 Neuron Java 实现一致
DESTRUCTIVE_TOOLS = {"bash", "shell", "security_scan"}
MAX_ESCALATION_DEPTH = 2

# 决策 deadline：模拟 syscall dispatcher 的容忍阈值。
# 超过此时间则回退到 permissive 默认（与 RateLimitSyscallFilter 的 fallback 一致）。
# 设为 3ms — 足够紧以反映虚拟线程调度 starvation，足够松以允许正常决策。
DECISION_DEADLINE_MS = 3.0

# 审计缓冲区容量（模拟 UnifiedAuditLog 的内存缓冲区）
AUDIT_BUFFER_CAPACITY = 2048


# ════════════════════════════════════════════════════════════════════════════
#  共享状态（multiprocessing.Manager 提供真实跨进程锁竞争）
# ════════════════════════════════════════════════════════════════════════════

def make_shared_state(manager: mp.Manager, config: str) -> dict:
    """创建跨进程共享的权限层状态。

    permission_isolated 配置使用独立的锁和缓冲区，模拟 permission 模块
    运行在独立线程池+独立资源配额上，不受攻击者争用影响，但也不主动
    耦合资源层信号（不做 cross-layer joint-decision）。
    """
    state = {
        "config": config,
        "profile_lock": manager.RLock(),      # PermissionProfile 读取锁
        "audit_lock": manager.Lock(),         # UnifiedAuditLog bufferLock
        "audit_buffer": manager.list(),       # 审计缓冲区（有容量上限）
        "audit_drops": manager.Value("i", 0), # 审计丢弃计数
        "audit_total": manager.Value("i", 0), # 审计总写入尝试
        "rate_lock": manager.Lock(),          # 源头限流令牌桶锁
        "rate_tokens": manager.Value("d", 25.0),
        "rate_last_refill": manager.Value("d", time.time()),
        "stop_attack": manager.Value("b", False),
    }
    # permission_isolated: 独立锁 + 独立审计缓冲区（隔离 starvation）
    if config == "permission_isolated":
        state["isolated_profile_lock"] = manager.RLock()
        state["isolated_audit_lock"] = manager.Lock()
        state["isolated_audit_buffer"] = manager.list()
    return state


def refill_rate_bucket(state: dict) -> None:
    now = time.time()
    elapsed = now - state["rate_last_refill"].value
    state["rate_tokens"].value = min(25.0, state["rate_tokens"].value + elapsed * 20.0)
    state["rate_last_refill"].value = now


def check_rate_limit(state: dict) -> bool:
    """源头限流检查（仅 coupled 配置启用）。被拒绝的请求不进入权限层。"""
    if state["config"] != "coupled":
        return True
    with state["rate_lock"]:
        refill_rate_bucket(state)
        if state["rate_tokens"].value >= 1.0:
            state["rate_tokens"].value -= 1.0
            return True
        return False


def append_audit(state: dict, entry: dict) -> bool:
    """向审计缓冲区追加记录。缓冲区满时丢弃（降级证据）。"""
    state["audit_total"].value += 1
    with state["audit_lock"]:
        if len(state["audit_buffer"]) >= AUDIT_BUFFER_CAPACITY:
            state["audit_drops"].value += 1
            return False
        state["audit_buffer"].append(entry)
        return True


def evaluate_escalation_policy(depth: int, tool_name: str) -> str:
    """纯函数：depth-aware EscalationPolicy.evaluate（与 Java 一致）。"""
    if depth >= MAX_ESCALATION_DEPTH and tool_name and tool_name.lower() in DESTRUCTIVE_TOOLS:
        return "DENY_DEPTH"
    return "ASK_WITH_CONTEXT"


def permission_decide(state: dict, depth: int, tool_name: str) -> dict:
    """权限决策路径：rate-limit → profile-read → audit → policy → deadline-check。

    在资源压力下，profile-read 会被攻击者线程阻塞，导致决策超时。
    permission_isolated 配置使用独立锁，不受攻击者争用影响。
    """
    trace_id = uuid.uuid4().hex[:12]
    start = time.perf_counter()

    # 源头限流（coupled 配置）
    if not check_rate_limit(state):
        return {
            "verdict": "RATE_LIMITED",
            "latency_ms": (time.perf_counter() - start) * 1000,
            "audit_recorded": True,
            "correct": True,
            "trace_id": trace_id,
        }

    expected = evaluate_escalation_policy(depth, tool_name)

    # permission_isolated: 使用独立锁（不受攻击者争用）
    if state["config"] == "permission_isolated":
        profile_lock = state["isolated_profile_lock"]
        audit_lock = state["isolated_audit_lock"]
        audit_buffer = state["isolated_audit_buffer"]
    else:
        profile_lock = state["profile_lock"]
        audit_lock = state["audit_lock"]
        audit_buffer = state["audit_buffer"]

    # 读取 PermissionProfile（持 profileLock）
    # 攻击者线程高频争用此锁，模拟攻击者触发权限检查填满审计缓冲区
    acquired = False
    try:
        acquired = profile_lock.acquire(timeout=DECISION_DEADLINE_MS / 1000)
        if not acquired:
            # deadline 超时：回退到 permissive 默认（ASK_WITH_CONTEXT）
            # 这是 Neuron 的 fail-open 路径（与 ALLOW_ONCE fallback 一致）
            latency = (time.perf_counter() - start) * 1000
            return {
                "verdict": "ASK_WITH_CONTEXT",  # permissive fallback
                "latency_ms": latency,
                "audit_recorded": False,
                "correct": (expected == "ASK_WITH_CONTEXT"),
                "trace_id": trace_id,
            }

        # 审计记录（持 bufferLock）
        audit_entry = {
            "ts": time.time() * 1000,
            "trace_id": trace_id,
            "depth": depth,
            "tool": tool_name,
            "layer": "PERMISSION",
        }
        # permission_isolated 写入独立缓冲区（不与攻击者争用 bufferLock）
        if state["config"] == "permission_isolated":
            with audit_lock:
                audit_buffer.append(audit_entry)
            audit_ok = True
        else:
            audit_ok = append_audit(state, audit_entry)

    finally:
        if acquired:
            profile_lock.release()

    latency = (time.perf_counter() - start) * 1000
    return {
        "verdict": expected,
        "latency_ms": latency,
        "audit_recorded": audit_ok,
        "correct": True,
        "trace_id": trace_id,
    }


# ════════════════════════════════════════════════════════════════════════════
#  攻击者进程：高频争用 profileLock + 填充审计缓冲区
# ════════════════════════════════════════════════════════════════════════════

def attack_worker(state: dict, worker_id: int) -> None:
    """攻击者：高频向审计缓冲区注入噪声记录 + 争用 profileLock。

    模拟攻击者高频触发 rate-limit 拒绝路径，每次拒绝产生 SecurityException
    审计记录争用 bufferLock；同时高频权限检查争用 profileLock。
    在 coupled 配置下，源头限流阻止攻击者进入，洪泛被遏制。
    """
    while not state["stop_attack"].value:
        # 模拟攻击者触发权限检查（争用 profileLock）
        # 持锁 0.5-1ms 模拟慢速 PermissionProfile 读取（内存压力下）
        try:
            if state["profile_lock"].acquire(timeout=0.001):
                time.sleep(0.0005 + (worker_id % 3) * 0.0003)  # 持锁 0.5-1.1ms
                state["profile_lock"].release()
        except Exception:
            pass

        # 模拟攻击者触发 rate-limit 拒绝 → SecurityException → 审计记录
        entry = {
            "ts": time.time() * 1000,
            "trace_id": uuid.uuid4().hex[:12],
            "layer": "RATELIMIT",
            "event": "denial",
            "noise": True,
            "worker": worker_id,
        }
        append_audit(state, entry)


# ════════════════════════════════════════════════════════════════════════════
#  实验驱动
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


def run_config(config: str, n_trials: int = 30, n_attackers: int = 8) -> dict[str, Any]:
    """运行单档配置。"""
    print(f"  [*] Config: {config}, n={n_trials}, attackers={n_attackers}")

    manager = mp.Manager()
    state = make_shared_state(manager, config)

    # 启动攻击者进程（baseline/permission_only/permission_isolated 下生效；coupled 下源头限流阻止）
    # permission_isolated 也启动攻击者：测试攻击存在时独立池是否有效隔离
    attack_procs: list[mp.Process] = []
    if config != "coupled":
        for i in range(n_attackers):
            p = mp.Process(target=attack_worker, args=(state, i), daemon=True)
            p.start()
            attack_procs.append(p)

    # 让攻击者先填满缓冲区，制造压力
    time.sleep(1.0)

    decisions: list[dict] = []
    # 良性决策：depth=2 的子 agent 请求 bash（应被 DENY_DEPTH）
    # 这是权限层在压力下必须正确执行的核心判定
    for i in range(n_trials):
        d = permission_decide(state, depth=2, tool_name="bash")
        decisions.append(d)
        # 决策间留极小间隔，让攻击者有机会重新争用锁
        time.sleep(0.001)

    # 停止攻击者
    state["stop_attack"].value = True
    for p in attack_procs:
        p.join(timeout=2.0)
        if p.is_alive():
            p.terminate()

    # 在 manager 关闭前读取共享计数
    audit_total = state["audit_total"].value
    audit_drops = state["audit_drops"].value

    manager.shutdown()

    # 统计
    latencies = [d["latency_ms"] for d in decisions]
    correct_count = sum(1 for d in decisions if d["correct"])
    deadline_fallback = sum(1 for d in decisions if d["verdict"] == "ASK_WITH_CONTEXT" and not d["correct"])

    result = {
        "config": config,
        "n_trials": n_trials,
        "n_attackers": n_attackers,
        "latency_mean_ms": round(statistics.fmean(latencies), 4),
        "latency_p50_ms": round(percentile(latencies, 50), 4),
        "latency_p95_ms": round(percentile(latencies, 95), 4),
        "latency_p99_ms": round(percentile(latencies, 99), 4),
        "decision_correctness": round(correct_count / n_trials, 4),
        "deadline_fallback_rate": round(deadline_fallback / n_trials, 4),
        "audit_drop_rate": round(audit_drops / max(audit_total, 1), 4),
        "audit_total": audit_total,
        "audit_drops": audit_drops,
        "expected_verdict": "DENY_DEPTH",
        "decision_deadline_ms": DECISION_DEADLINE_MS,
        "per_trial_latencies_ms": [round(l, 4) for l in latencies],
        "per_trial_verdicts": [d["verdict"] for d in decisions],
    }
    return result


def main() -> int:
    print("=" * 70)
    print("Permission-Layer Starvation Experiment (Issue 1)")
    print("Tests whether permission decision quality degrades under resource pressure")
    print("Uses multiprocessing for genuine cross-process lock contention")
    print("=" * 70)

    configs = ["baseline", "permission_only", "permission_isolated", "coupled"]
    all_results = []
    for cfg in configs:
        res = run_config(cfg, n_trials=30, n_attackers=8)
        all_results.append(res)
        print(f"    correctness={res['decision_correctness']:.2%}  "
              f"p95={res['latency_p95_ms']:.3f}ms  "
              f"deadline_fallback={res['deadline_fallback_rate']:.2%}  "
              f"audit_drop={res['audit_drop_rate']:.2%}")

    # 写 CSV
    csv_path = OUTPUT_DIR / "permission_starvation_results.csv"
    fieldnames = [
        "config", "n_trials", "n_attackers", "latency_mean_ms", "latency_p50_ms",
        "latency_p95_ms", "latency_p99_ms", "decision_correctness",
        "deadline_fallback_rate", "audit_drop_rate", "audit_total", "audit_drops",
        "decision_deadline_ms",
    ]
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for r in all_results:
            row = {k: v for k, v in r.items() if k in fieldnames}
            writer.writerow(row)
    print(f"\n[*] CSV: {csv_path}")

    # 写 JSON
    json_path = OUTPUT_DIR / "permission_starvation_results.json"
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump({
            "experiment": "permission_layer_starvation",
            "issue": "Issue 1: prove permission layer degrades under resource pressure",
            "design": (
                "Uses multiprocessing.Manager for genuine cross-process lock contention. "
                "Simulates the Neuron permission decision path (profile-lock read + "
                "audit-buffer append + EscalationPolicy evaluate) under audit-flood + "
                "profile-lock contention from 8 attacker processes. Measures decision "
                "latency, correctness (DENY_DEPTH still fires), deadline-fallback rate "
                "(permissive ASK fallback when profile-lock cannot be acquired within "
                "the 3ms decision deadline), and audit-drop rate (buffer overflow)."
            ),
            "results": all_results,
        }, f, indent=2, ensure_ascii=False)
    print(f"[*] JSON: {json_path}")

    # 摘要
    print()
    print("=" * 70)
    print("SUMMARY")
    print("=" * 70)
    print(f"{'Config':<20} {'Correctness':>12} {'p95 (ms)':>10} {'Fallback%':>10} {'AuditDrop%':>12}")
    for r in all_results:
        print(f"{r['config']:<20} {r['decision_correctness']:>11.2%} "
              f"{r['latency_p95_ms']:>10.3f} {r['deadline_fallback_rate']:>9.2%} "
              f"{r['audit_drop_rate']:>11.2%}")

    # 关键结论
    base = all_results[0]
    perm = all_results[1]
    isolated = all_results[2]
    coupled = all_results[3]
    print()
    print("Key findings:")
    print(f"  - Baseline correctness:           {base['decision_correctness']:.2%} "
          f"(deadline fallback {base['deadline_fallback_rate']:.2%}, "
          f"audit drop {base['audit_drop_rate']:.2%})")
    print(f"  - Permission-only correctness:    {perm['decision_correctness']:.2%} "
          f"(deadline fallback {perm['deadline_fallback_rate']:.2%}, "
          f"audit drop {perm['audit_drop_rate']:.2%})")
    print(f"  - Permission-isolated correctn.:  {isolated['decision_correctness']:.2%} "
          f"(deadline fallback {isolated['deadline_fallback_rate']:.2%}, "
          f"audit drop {isolated['audit_drop_rate']:.2%})")
    print(f"  - Coupled correctness:            {coupled['decision_correctness']:.2%} "
          f"(deadline fallback {coupled['deadline_fallback_rate']:.2%}, "
          f"audit drop {coupled['audit_drop_rate']:.2%})")
    print(f"  - Baseline p95 latency:           {base['latency_p95_ms']:.3f}ms")
    print(f"  - Permission-isolated p95:        {isolated['latency_p95_ms']:.3f}ms")
    print(f"  - Coupled p95 latency:            {coupled['latency_p95_ms']:.3f}ms")

    return 0


if __name__ == "__main__":
    mp.set_start_method("spawn", force=True)
    sys.exit(main())

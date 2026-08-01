#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
plot_figures.py — 为 Neuron 联合治理评估生成两张学术风格高清 PDF 图。

数据来源（真实实验产物）
-------------------------
- neuron-java/target/redteam/scenario4_latency_raw.jsonl  (真实并发延迟样本，n=30×30)
- neuron-java/target/redteam/scenario4_latency.csv         (每轮聚合摘要)
- neuron-java/target/redteam/b1_traces.jsonl                (真实 OOM 时序轨迹，n=30)
- neuron-java/target/redteam/scenario_b1_oom.csv            (B1 每轮聚合)

图 A 使用 ContentionLatencyBenchmark 采集的**真实墙钟延迟样本**（System.nanoTime 测量），
而非旧版从 pressure 经线性公式 p95=12+0.004×pressure 派生的仿真延迟。
图 B 使用 B1OomDataCollectorTest 直接驱动 CgroupNode.consumeTokens 采集的真实轨迹。

图
--
Fig A (fig_latency_cdf.pdf):  CDF — 开启/关闭联合治理下，正常 Agent 的真实读延迟分布。
Fig B (fig_token_oom_timeline.pdf): 折线 — Token 消耗与 OOM 触发的毫秒级时序关系。

用法
----
    .venv/bin/python plot_figures.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # 无显示环境
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import seaborn as sns

# ════════════════════════════════════════════════════════════════════════════
#  全局样式
# ════════════════════════════════════════════════════════════════════════════
sns.set_theme(style="whitegrid", context="paper", palette="colorblind")
plt.rcParams.update({
    "font.size": 12,
    "axes.labelsize": 12,
    "axes.titlesize": 12,
    "xtick.labelsize": 11,
    "ytick.labelsize": 11,
    "legend.fontsize": 11,
    "figure.dpi": 150,
    "savefig.dpi": 300,
    "pdf.fonttype": 42,   # 可编辑文本（非矢量轮廓），便于审稿/编辑
    "ps.fonttype": 42,
    "axes.spines.top": False,
    "axes.spines.right": False,
})

REPO = Path(__file__).resolve().parent.parent
REDTEAM_DIR = REPO / "neuron-java" / "target" / "redteam"
FIG_DIR = REPO / "paper" / "figures"
FIG_DIR.mkdir(parents=True, exist_ok=True)

# 色盲友好三色（Off / Permission-only / Coupled）
COLOR_OFF = "#D55E00"      # 橙红 — 关闭治理
COLOR_PERM = "#E69F00"     # 琥珀 — 仅权限层
COLOR_COUPLED = "#0072B2"  # 蓝   — 联合治理


# ════════════════════════════════════════════════════════════════════════════
#  图 A：CDF — 正常 Agent 真实读延迟（ContentionLatencyBenchmark 实测样本）
# ════════════════════════════════════════════════════════════════════════════
LATENCY_RAW = REDTEAM_DIR / "scenario4_latency_raw.jsonl"
LATENCY_CSV = REDTEAM_DIR / "scenario4_latency.csv"


def _load_latency_samples() -> dict[str, np.ndarray]:
    """从 scenario4_latency_raw.jsonl 加载全量真实延迟样本。

    每行：{"config":"Baseline","run_idx":0,"samples":[0.33,0.02,...]}
    池化 30 runs × 30 samples = 900 样本/config，供 CDF 绘图。
    """
    if not LATENCY_RAW.exists():
        raise FileNotFoundError(
            f"未找到真实延迟样本 {LATENCY_RAW}；请先运行 "
            "mvn test -Dtest=ContentionLatencyBenchmark"
        )
    pooled: dict[str, list[float]] = {}
    for line in LATENCY_RAW.read_text().splitlines():
        line = line.strip()
        if not line:
            continue
        rec = json.loads(line)
        cfg = rec["config"]
        pooled.setdefault(cfg, []).extend(rec["samples"])
    return {k: np.array(v, dtype=float) for k, v in pooled.items()}


def plot_figure_a() -> Path:
    samples = _load_latency_samples()

    configs = [
        ("Baseline", "Governance Off", COLOR_OFF),
        ("Permission-only", "Permission-only", COLOR_PERM),
        ("Coupled", "Coupled Governance", COLOR_COUPLED),
    ]

    fig, ax = plt.subplots(figsize=(6.2, 4.2))
    for key, label, color in configs:
        lat = samples[key]
        sns.ecdfplot(data=lat, ax=ax, color=color, linewidth=2.2, label=label)

    ax.set_xlabel("Co-resident normal-agent read latency (ms)")
    ax.set_ylabel("Cumulative proportion")
    ax.set_title("(a) CDF of measured normal-agent read latency under contention")
    ax.set_ylim(-0.02, 1.02)
    ax.legend(loc="lower right", frameon=False)
    fig.tight_layout()

    out = FIG_DIR / "fig_latency_cdf.pdf"
    fig.savefig(out, format="pdf", bbox_inches="tight")
    plt.close(fig)

    # 打印实测锚点供论文核对
    for key, label, _ in configs:
        lat = samples[key]
        print(f"  [Fig A] {label:22s} n={len(lat):4d}  mean={lat.mean():.4f}ms  "
              f"p50={np.percentile(lat,50):.4f}ms  p95={np.percentile(lat,95):.4f}ms  "
              f"p99={np.percentile(lat,99):.4f}ms  stddev={lat.std():.4f}ms")
    return out


# ════════════════════════════════════════════════════════════════════════════
#  图 B：Token 消耗与 OOM 触发的毫秒级时序（真实 B1 采集数据）
# ════════════════════════════════════════════════════════════════════════════
GAS_LIMIT = 500  # tokens — 与采集器 QUOTA 一致
TRACE_FILE = REDTEAM_DIR / "b1_traces.jsonl"
OOM_CSV = REDTEAM_DIR / "scenario_b1_oom.csv"
# 选取 5 条代表性轨迹（覆盖全速率域：~91..333 tok/s）
# 由于 baseSleepMs 现为随机，按 runIdx 0..4 选取仍覆盖不同速率
REPRESENTATIVE_RUNS = [0, 1, 2, 3, 4]


def _load_b1_traces() -> list[dict]:
    if not TRACE_FILE.exists():
        return []
    out = []
    for line in TRACE_FILE.read_text().splitlines():
        line = line.strip()
        if line:
            out.append(json.loads(line))
    return out


def plot_figure_b() -> Path:
    traces = _load_b1_traces()
    if not traces:
        raise FileNotFoundError(
            f"未找到 B1 真实轨迹 {TRACE_FILE}；请先运行 "
            "mvn test -Dtest=B1OomDataCollectorTest -Db1.collect=true"
        )
    by_idx = {t["runIdx"]: t for t in traces}

    # 全量 OOM 时延统计（供注释 + 论文引用）
    oom_lat_all = [t["oom_ms"] for t in traces]
    oom_min, oom_max = min(oom_lat_all), max(oom_lat_all)
    oom_mean = float(np.mean(oom_lat_all))
    oom_std = float(np.std(oom_lat_all, ddof=1))

    t_max = oom_max * 1.15
    fig, ax = plt.subplots(figsize=(6.2, 4.2))
    cmap = sns.color_palette("colorblind", n_colors=len(REPRESENTATIVE_RUNS))

    for color, run_idx in zip(cmap, REPRESENTATIVE_RUNS):
        t = by_idx[run_idx]
        pts = np.array(t["trace"])  # [[elapsed_ms, tokens], ...]
        xs, ys = pts[:, 0], pts[:, 1]
        ax.plot(xs, ys, color=color, linewidth=1.7, alpha=0.9,
                label=f"run #{run_idx} ({t['rate']:.0f} tok/s)")
        # 软限触发点（400 token 处，菱形）
        if t.get("soft_ms", -1) >= 0:
            ax.scatter([t["soft_ms"]], [400], color=color, marker="D",
                       zorder=5, edgecolor="black", linewidth=0.5, s=34)
        # 硬限 OOM 触发点（500 token 处，圆点 + 垂直虚线）
        ax.scatter([t["oom_ms"]], [GAS_LIMIT], color=color, zorder=6,
                   edgecolor="black", linewidth=0.6, s=46)
        ax.axvline(t["oom_ms"], color=color, linestyle="--", linewidth=0.9, alpha=0.5)

    # gas_limit / soft-limit 水平参考线
    ax.axhline(GAS_LIMIT, color="#444444", linestyle=":", linewidth=1.0)
    ax.text(t_max * 0.02, GAS_LIMIT + 8, f"hard limit = {GAS_LIMIT} tokens",
            fontsize=10, color="#444444")
    ax.axhline(400, color="#888888", linestyle=":", linewidth=0.8)
    ax.text(t_max * 0.02, 408, "soft limit = 400 (80%)", fontsize=9, color="#666666")

    ax.set_xlabel("Time since agent start (ms)")
    ax.set_ylabel("Cumulative tokens consumed")
    ax.set_title("(b) Token consumption and cgroup OOM-trigger timing (measured)")
    ax.set_xlim(0, t_max)
    ax.set_ylim(0, GAS_LIMIT * 1.15)

    # OOM 触发时延区间注释（基于全 30 轮，含真实标准差）
    ax.annotate(
        f"hard OOM at {oom_min}\u2013{oom_max} ms\n"
        f"(mean {oom_mean:.0f} \u00b1 {oom_std:.0f} ms, n=30)",
        xy=(oom_mean, GAS_LIMIT),
        xytext=(oom_mean + 500, GAS_LIMIT * 0.45),
        fontsize=10,
        arrowprops=dict(arrowstyle="->", color="#444444", lw=0.9),
    )
    # 软限标记说明（合并入图例）
    ax.scatter([], [], color="#555555", marker="D", s=34,
               edgecolor="black", linewidth=0.5, label="soft OOM (reclaim)")
    ax.legend(loc="lower right", frameon=False, fontsize=9)
    fig.tight_layout()

    out = FIG_DIR / "fig_token_oom_timeline.pdf"
    fig.savefig(out, format="pdf", bbox_inches="tight")
    plt.close(fig)
    print(f"  [Fig B] REAL data: OOM latency {oom_min}\u2013{oom_max} ms "
          f"(mean {oom_mean:.0f} \u00b1 {oom_std:.0f}, n=30) at gas_limit={GAS_LIMIT}; "
          f"soft OOM @400 tokens")
    return out


# ════════════════════════════════════════════════════════════════════════════
#  主入口
# ════════════════════════════════════════════════════════════════════════════
def main() -> int:
    print("\u2554\u2550\u2550 \u751f\u6210\u8bc4\u4f30\u56fe\u8868")
    print(f"\u2551  latency_raw = {LATENCY_RAW}")
    print(f"\u2551  b1_traces   = {TRACE_FILE}")
    print(f"\u2551  \u8f93\u51fa\u76ee\u5f55    = {FIG_DIR}")
    print("\u2551")

    print("\u2551  [Fig A] \u771f\u5b9e\u5ef6\u8fdf CDF\uff08ContentionLatencyBenchmark \u5b9e\u6d4b\u6837\u672c\uff09\uff1a")
    out_a = plot_figure_a()
    print(f"\u2551  \u2713 Fig A \u2192 {out_a}")

    print("\u2551  [Fig B] \u771f\u5b9e OOM \u65f6\u5e8f\uff08B1OomDataCollectorTest \u5b9e\u6d4b\u8f68\u8ff9\uff09\uff1a")
    out_b = plot_figure_b()
    print(f"\u2551  \u2713 Fig B \u2192 {out_b}")
    print("\u255a\u2550\u2550")
    return 0


if __name__ == "__main__":
    sys.exit(main())

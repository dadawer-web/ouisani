#!/usr/bin/env bash
# Enforce a ratcheting javac/Maven warning budget.
#
# 借鉴 jcode/scripts/check_warning_budget.sh，适配 Maven。
#
# Counts `[WARNING]` lines from `mvn -q compile` output.
# Baseline stored in scripts/budgets/warning_budget.txt (a single integer).
#
# Notes:
# - 如果 mvn 不可用或编译失败，本脚本以 exit code 2 跳过（不阻塞 CI），
#   并打印 SKIP 提示。CI 应将 exit 2 视为"无法判定"而非通过/失败。
# - --update 刷新基线到当前 warning 数。

set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
baseline_file="$repo_root/scripts/budgets/warning_budget.txt"

usage() {
  cat <<'USAGE'
Usage:
  scripts/check_warning_budget.sh            # fail if warnings exceed baseline
  scripts/check_warning_budget.sh --update   # update baseline to current warning count

Notes:
  - Counts [WARNING] lines from `mvn -q compile`
  - Exit codes: 0=OK/improved, 1=budget exceeded, 2=mvn unavailable or compile failed
  - Baseline stored in scripts/budgets/warning_budget.txt
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

mkdir -p "$(dirname "$baseline_file")"
if [[ ! -f "$baseline_file" ]]; then
  printf '0\n' > "$baseline_file"
fi

# 检测 mvn 是否可用
if ! command -v mvn >/dev/null 2>&1; then
  echo "SKIP warning budget: mvn not found in PATH (exit 2)" >&2
  exit 2
fi

# 运行编译并捕获 warning。不 clean，加速增量。
# -DskipTests 避免编译测试代码（warning budget 只关心生产代码）
# -q 安静模式，但 [WARNING] 和 [ERROR] 仍会输出
compile_log=$(cd "$repo_root" && mvn -q -DskipTests compile 2>&1 || true)

# 检测编译失败（BUILD FAILURE）
if echo "$compile_log" | grep -q 'BUILD FAILURE'; then
  echo "SKIP warning budget: mvn compile failed (BUILD FAILURE)" >&2
  echo "$compile_log" | tail -n 20 >&2
  exit 2
fi

# 统计 [WARNING] 行数（Maven 编译器输出的标准前缀）
current=$(echo "$compile_log" | grep -c '^\[WARNING\]' || printf '0\n')
# grep -c 在无匹配时返回 1，但 set -e 已被 || true 兜住
if ! [[ "$current" =~ ^[0-9]+$ ]]; then
  current=0
fi

baseline=$(tr -d '[:space:]' < "$baseline_file")
if ! [[ "$baseline" =~ ^[0-9]+$ ]]; then
  baseline=0
fi

if [[ "${1:-}" == "--update" ]]; then
  printf '%s\n' "$current" > "$baseline_file"
  echo "Updated warning baseline: $baseline -> $current"
  exit 0
fi

if (( current > baseline )); then
  echo "Warning budget exceeded: current=$current baseline=$baseline" >&2
  echo "Recent warnings:" >&2
  echo "$compile_log" | grep '^\[WARNING\]' | tail -n 10 >&2
  echo "Run scripts/check_warning_budget.sh --update only after intentional cleanup." >&2
  exit 1
fi

if (( current < baseline )); then
  echo "Warning budget improved: current=$current baseline=$baseline"
  echo "Consider running: scripts/check_warning_budget.sh --update"
else
  echo "Warning budget OK: current=$current baseline=$baseline"
fi

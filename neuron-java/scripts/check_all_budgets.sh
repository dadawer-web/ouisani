#!/usr/bin/env bash
# Run all ratcheting budget checkers in sequence.
#
# 借鉴 jcode 的 CI Quality Guardrails 模式：
# 串行执行 6 道门，任一失败即退出非零。
#
# Exit codes:
#   0 = all budgets OK
#   1 = at least one budget exceeded (regression)
#   2 = at least one budget skipped (e.g. mvn unavailable) but none exceeded
#
# 用法:
#   scripts/check_all_budgets.sh           # 检查全部
#   scripts/check_all_budgets.sh --update  # 刷新全部基线（仅在主动清理后）
#
# 也可单独执行某个 checker，见 scripts/check_*_budget.{py,sh}

set -uo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
scripts_dir="$repo_root/scripts"
update_flag=""
exit_code=0
had_skip=0

if [[ "${1:-}" == "--update" ]]; then
  update_flag="--update"
fi

run_checker() {
  local name="$1"
  local cmd="$2"
  echo "=== $name ==="
  # 仅在 --update 模式下追加 flag，避免传空字符串导致 argparse 报错
  if [[ -n "$update_flag" ]]; then
    if eval "$cmd '$update_flag'"; then
      echo "--- $name: OK ---"
    else
      local rc=$?
      if (( rc == 2 )); then
        echo "--- $name: SKIPPED (exit 2) ---"
        had_skip=1
      else
        echo "--- $name: FAILED (exit $rc) ---"
        exit_code=1
      fi
    fi
  else
    if eval "$cmd"; then
      echo "--- $name: OK ---"
    else
      local rc=$?
      if (( rc == 2 )); then
        echo "--- $name: SKIPPED (exit 2) ---"
        had_skip=1
      else
        echo "--- $name: FAILED (exit $rc) ---"
        exit_code=1
      fi
    fi
  fi
  echo
}

# Python checkers（接受 --update）
run_checker "code-size budget" "python3 '$scripts_dir/check_code_size_budget.py'"
run_checker "panic budget" "python3 '$scripts_dir/check_panic_budget.py'"
run_checker "swallowed-error budget" "python3 '$scripts_dir/check_swallowed_error_budget.py'"
run_checker "test-size budget" "python3 '$scripts_dir/check_test_size_budget.py'"

# Shell checker（接受 --update，可能 exit 2 skip）
echo "=== warning budget ==="
if [[ -n "$update_flag" ]]; then
  warn_cmd=("$scripts_dir/check_warning_budget.sh" "$update_flag")
else
  warn_cmd=("$scripts_dir/check_warning_budget.sh")
fi
if "${warn_cmd[@]}"; then
  echo "--- warning budget: OK ---"
elif [[ $? -eq 2 ]]; then
  echo "--- warning budget: SKIPPED (mvn unavailable or compile failed) ---"
  had_skip=1
else
  echo "--- warning budget: FAILED ---"
  exit_code=1
fi
echo

# Dependency boundaries（无 --update，硬性约束）
echo "=== dependency boundaries ==="
if python3 "$scripts_dir/check_dependency_boundaries.py"; then
  echo "--- dependency boundaries: OK ---"
else
  echo "--- dependency boundaries: FAILED ---"
  exit_code=1
fi
echo

# 汇总
if (( exit_code == 0 )); then
  if (( had_skip )); then
    echo "All budgets OK (some skipped)."
    exit 2
  else
    echo "All budgets OK."
    exit 0
  fi
else
  echo "Some budgets EXCEEDED. See above." >&2
  exit 1
fi

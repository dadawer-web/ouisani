#!/usr/bin/env bash
# Auto-generate INDEX files for classpath-bundled extensions (skills + blueprints).
#
# 借鉴 mobilegym 的 import.meta.glob 构建期自动发现:JAR 内无法列举目录条目,
# 故用此脚本扫描 resources 目录生成 INDEX(每行一个扩展名),运行时读取。
# 同 __skill_indexer__.py 先例:独立脚本,手动运行或接 CI,不接入 Maven。
#
# 加一个 classpath 技能/系统蓝图 = 丢一个目录 + manifest 文件 + 跑本脚本,不改 Java。
#
# 用法:
#   scripts/index_classpath_extensions.sh
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
res_dir="$repo_root/src/main/resources"

# ── Skills index ──
skills_dir="$res_dir/skills"
if [[ -d "$skills_dir" ]]; then
  tmp=$(mktemp)
  for d in "$skills_dir"/*/; do
    [[ -f "${d}SKILL.md" ]] && basename "$d" >> "$tmp"
  done
  sort -o "$skills_dir/INDEX" "$tmp"
  rm -f "$tmp"
  count=$(wc -l < "$skills_dir/INDEX")
  echo "[indexer] skills/INDEX: $count entries"
else
  echo "[indexer] skills dir not found, skipped"
fi

# ── Blueprints index ──
bp_dir="$res_dir/blueprints"
if [[ -d "$bp_dir" ]]; then
  tmp=$(mktemp)
  for d in "$bp_dir"/*/; do
    [[ -f "${d}BLUEPRINT.md" ]] && basename "$d" >> "$tmp"
  done
  sort -o "$bp_dir/INDEX" "$tmp"
  rm -f "$tmp"
  count=$(wc -l < "$bp_dir/INDEX")
  echo "[indexer] blueprints/INDEX: $count entries"
else
  echo "[indexer] blueprints dir not found, skipped"
fi

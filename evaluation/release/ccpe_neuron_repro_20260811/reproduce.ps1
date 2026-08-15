$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $root
try {
  python evaluation/analyze_computing_experiments.py --input-dir evaluation/target/jvm_starvation_new --output-dir evaluation/target/jvm_starvation_new --exclude jvm_permission_starvation_ccpe_core_01.raw.jsonl --exclude jvm_permission_starvation_smoke_local.raw.jsonl --exclude jvm_permission_starvation_vps_smoke2.raw.jsonl --exclude jvm_permission_starvation_win_qos_01.raw.jsonl
  python evaluation/analyze_qos_experiments.py
  python evaluation/analyze_llm_replay.py
  python evaluation/analyze_permission_http.py --input-dir evaluation/target/permission_http_remote --output-dir evaluation/target/permission_http_remote --pattern 'permission_http_vps_http_prod_*.raw.jsonl'
  python evaluation/analyze_threshold_holdout.py
  python evaluation/analyze_ccpe_concurrency.py --input-dir evaluation/target/jvm_starvation_new --output-dir evaluation/target/ccpe_concurrency --paper-generated addtions/paper_ccpe/generated/ccpe_concurrency_results.tex
  python evaluation/analyze_ccpe_ablation.py
  Copy-Item addtions/paper/generated/computing_results.tex,addtions/paper/generated/llm_replay_results.tex,addtions/paper/generated/qos_results.tex,addtions/paper/generated/permission_http_results.tex,addtions/paper/generated/threshold_holdout_results.tex addtions/paper_ccpe/generated -Force
  python evaluation/verify_ccpe_consistency.py
  Push-Location addtions/paper_ccpe
  try { pdflatex -interaction=nonstopmode -halt-on-error main.tex; bibtex main; pdflatex -interaction=nonstopmode -halt-on-error main.tex; pdflatex -interaction=nonstopmode -halt-on-error main.tex } finally { Pop-Location }
} finally { Pop-Location }

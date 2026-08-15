$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $root
try {
  python evaluation/analyze_computing_experiments.py --input-dir evaluation/target/jvm_starvation_new --output-dir evaluation/target/jvm_starvation_new --exclude jvm_permission_starvation_smoke_local.raw.jsonl --exclude jvm_permission_starvation_vps_smoke2.raw.jsonl --exclude jvm_permission_starvation_win_qos_01.raw.jsonl
  python evaluation/analyze_qos_experiments.py
  python evaluation/analyze_llm_replay.py
  python evaluation/analyze_permission_http.py --input-dir evaluation/target/permission_http_remote --output-dir evaluation/target/permission_http_remote --pattern 'permission_http_vps_http_prod_*.raw.jsonl'
  python evaluation/analyze_threshold_holdout.py
  python evaluation/verify_data_consistency.py
  Push-Location addtions/paper
  try { pdflatex -interaction=nonstopmode -halt-on-error main.tex; bibtex main; pdflatex -interaction=nonstopmode -halt-on-error main.tex; pdflatex -interaction=nonstopmode -halt-on-error main.tex } finally { Pop-Location }
} finally { Pop-Location }

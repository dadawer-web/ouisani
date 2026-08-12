#!/usr/bin/env python3
"""Create a hash-manifested CCPE replication package."""

from __future__ import annotations

import hashlib
import json
import platform
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RELEASE = ROOT / "evaluation/release/ccpe_neuron_repro_20260811"


def copy_file(relative: str) -> None:
    source = ROOT / relative
    target = RELEASE / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def copy_tree(relative: str, *, exclude_names: set[str] | None = None) -> None:
    source = ROOT / relative
    target = RELEASE / relative
    if target.exists():
        shutil.rmtree(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    ignore = shutil.ignore_patterns(*(exclude_names or set())) if exclude_names else None
    shutil.copytree(source, target, ignore=ignore)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    if RELEASE.exists():
        shutil.rmtree(RELEASE)
    RELEASE.mkdir(parents=True)

    copy_tree("neuron-java/src")
    copy_file("neuron-java/pom.xml")
    for name in ("README.md", "LICENSE"):
        if (ROOT / "neuron-java" / name).exists():
            copy_file(f"neuron-java/{name}")

    scripts = [
        "analyze_computing_experiments.py",
        "analyze_qos_experiments.py",
        "analyze_llm_replay.py",
        "analyze_permission_http.py",
        "analyze_threshold_holdout.py",
        "analyze_ccpe_concurrency.py",
        "analyze_ccpe_ablation.py",
        "verify_data_consistency.py",
        "verify_ccpe_consistency.py",
        "run_permission_http_fault_injection.py",
        "run_permission_starvation_experiment.py",
        "prepare_ccpe_submission.py",
        "freeze_ccpe_release.py",
    ]
    for name in scripts:
        copy_file(f"evaluation/{name}")

    for relative in (
        "evaluation/target/jvm_starvation_new",
        "evaluation/target/ccpe_concurrency",
        "evaluation/target/permission_http_remote",
        "evaluation/target/computing_llm_replay_remote",
        "evaluation/target/computing_llm_replay_v3",
        "evaluation/target/threshold_holdout",
    ):
        copy_tree(relative)
    copy_tree("evaluation/target/jvm_starvation_ablation", exclude_names={"*.log"})
    copy_tree("evaluation/target/ccpe_ablation")

    paper_source = ROOT / "addtions/paper_ccpe"
    paper_target = RELEASE / "addtions/paper_ccpe"
    paper_target.mkdir(parents=True, exist_ok=True)
    for name in (
        "main.tex", "refs.bib", "spphys.bst", "main.pdf",
        "cover_letter_ccpe.tex", "cover_letter_ccpe.pdf", "summary_ccpe.txt",
    ):
        shutil.copy2(paper_source / name, paper_target / name)
    for directory in ("sections", "generated", "figures"):
        copy_tree(f"addtions/paper_ccpe/{directory}")
    copy_tree("addtions/paper_ccpe_submission",
              exclude_names={"*.aux", "*.log", "*.out", "*.bbl", "*.blg"})

    (RELEASE / "README.md").write_text(
        "# Neuron CCPE replication package (frozen 2026-08-11)\n\n"
        "This package contains the Java 21 source, raw JSONL observations, environment records,\n"
        "executor-oriented concurrency summaries, the 2x2 capacity/signal ablation, analysis scripts, generated LaTeX values, and\n"
        "the free-format CCPE manuscript. It contains no API keys, SSH keys, passwords, Maven\n"
        "caches, or temporary PDF renders.\n\n"
        "## Reproduce\n\n"
        "Run the following from this directory with Python 3.10+ and LaTeX installed:\n\n"
        "```powershell\n.\\reproduce.ps1\n```\n\n"
        "`verify_ccpe_consistency.py` checks the 3,840-observation `ccpe_core_01` run, the\n"
        "1,800-observation 2x2 ablation, its executor-pressure cells, and the generated macros.\n"
        "The older Computing manuscript gate\n"
        "is intentionally not required for this CCPE-only package.\n\n"
        "The process-separated permission service is a same-host loopback HTTP experiment; it\n"
        "does not establish WAN, partition, multi-node, GPU, or live-production behavior.\n",
        encoding="utf-8",
    )
    (RELEASE / "reproduce.ps1").write_text(
        "$ErrorActionPreference = 'Stop'\n"
        "$root = Split-Path -Parent $MyInvocation.MyCommand.Path\n"
        "Push-Location $root\n"
        "try {\n"
        "  python evaluation/analyze_computing_experiments.py --input-dir evaluation/target/jvm_starvation_new --output-dir evaluation/target/jvm_starvation_new --exclude jvm_permission_starvation_ccpe_core_01.raw.jsonl --exclude jvm_permission_starvation_smoke_local.raw.jsonl --exclude jvm_permission_starvation_vps_smoke2.raw.jsonl --exclude jvm_permission_starvation_win_qos_01.raw.jsonl\n"
        "  python evaluation/analyze_qos_experiments.py\n"
        "  python evaluation/analyze_llm_replay.py\n"
        "  python evaluation/analyze_permission_http.py --input-dir evaluation/target/permission_http_remote --output-dir evaluation/target/permission_http_remote --pattern 'permission_http_vps_http_prod_*.raw.jsonl'\n"
        "  python evaluation/analyze_threshold_holdout.py\n"
        "  python evaluation/analyze_ccpe_concurrency.py --input-dir evaluation/target/jvm_starvation_new --output-dir evaluation/target/ccpe_concurrency --paper-generated addtions/paper_ccpe/generated/ccpe_concurrency_results.tex\n"
        "  python evaluation/analyze_ccpe_ablation.py\n"
        "  Copy-Item addtions/paper/generated/computing_results.tex,addtions/paper/generated/llm_replay_results.tex,addtions/paper/generated/qos_results.tex,addtions/paper/generated/permission_http_results.tex,addtions/paper/generated/threshold_holdout_results.tex addtions/paper_ccpe/generated -Force\n"
        "  python evaluation/verify_ccpe_consistency.py\n"
        "  Push-Location addtions/paper_ccpe\n"
        "  try { pdflatex -interaction=nonstopmode -halt-on-error main.tex; bibtex main; pdflatex -interaction=nonstopmode -halt-on-error main.tex; pdflatex -interaction=nonstopmode -halt-on-error main.tex } finally { Pop-Location }\n"
        "} finally { Pop-Location }\n",
        encoding="utf-8",
    )
    (RELEASE / "ZENODO_METADATA.json").write_text(
        json.dumps({
            "metadata": {
                "title": "Neuron: Concurrency-Aware Permission Decisions in Multi-Tenant LLM Agent Runtimes (CCPE replication package)",
                "upload_type": "dataset",
                "description": (
                    "Replication package for the CCPE manuscript on executor contention, "
                    "permission-decision availability, and resource-aware coupling in a Java 21 "
                    "multi-tenant agent runtime. Includes raw JSONL observations, the additional "
                    "3,840-observation CCPE executor replication, a 1,800-observation 2x2 capacity/signal ablation, source, analysis scripts, and "
                    "a flat free-format submission tree. The permission-service experiment is "
                    "same-host loopback only and does not represent WAN or multi-node behavior."
                ),
                "creators": [
                    {"name": "Xie, Mingyuan", "affiliation": "Qingdao University of Technology"},
                    {"name": "Wu, Zhengxun", "affiliation": "Qingdao University of Technology"},
                ],
                "keywords": [
                    "concurrent runtime", "executor contention", "multi-tenant systems",
                    "permission decisions", "cloud services", "LLM agents", "Java",
                ],
                "publication_date": "2026-08-11",
                "access_right": "open",
                "license": "other-open",
            }
        }, ensure_ascii=False, indent=2), encoding="utf-8")
    (RELEASE / "ZENODO_UPLOAD.md").write_text(
        "# Zenodo deposit\n\n"
        "The package is prepared for a citable Zenodo record, but no Zenodo account or access\n"
        "token is stored here. Sign in to Zenodo, upload the final archive, and use\n"
        "`ZENODO_METADATA.json`. Add the DOI to the manuscript only after the record is public.\n",
        encoding="utf-8",
    )

    records = []
    for path in sorted(RELEASE.rglob("*")):
        if path.is_file() and path.name != "MANIFEST.json":
            records.append({
                "path": str(path.relative_to(RELEASE)).replace("\\", "/"),
                "bytes": path.stat().st_size,
                "sha256": sha256(path),
            })
    try:
        git_head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        git_head = "unavailable"
    manifest = {
        "package": "ccpe_neuron_repro_20260811",
        "created_utc": datetime.now(timezone.utc).isoformat(),
        "git_head_at_freeze": git_head,
        "local_platform": platform.platform(),
        "python": sys.version,
        "files": records,
    }
    (RELEASE / "MANIFEST.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"created {RELEASE} with {len(records)} hashed files")


if __name__ == "__main__":
    main()

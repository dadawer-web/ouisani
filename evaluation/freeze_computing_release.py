#!/usr/bin/env python3
"""Create a minimal, hash-manifested Computing replication package.

The package keeps the exact source paths expected by the consistency gate,
but omits build caches, credentials, temporary PDF renders, and unrelated
experiments.
"""

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
RELEASE = ROOT / "evaluation/release/computing_neuron_repro_20260810"


def copy_file(relative: str) -> None:
    source = ROOT / relative
    target = RELEASE / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def copy_tree(relative: str, *, exclude_names: set[str] | None = None) -> None:
    source = ROOT / relative
    target = RELEASE / relative
    ignore_names = exclude_names or set()
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(
        source,
        target,
        ignore=shutil.ignore_patterns(*ignore_names) if ignore_names else None,
    )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    RELEASE.mkdir(parents=True, exist_ok=True)

    # Java source and build metadata; no Maven target/classes or credentials.
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
        "verify_data_consistency.py",
        "run_permission_http_fault_injection.py",
        "run_permission_starvation_experiment.py",
        "prepare_computing_submission.py",
        "freeze_computing_release.py",
    ]
    for name in scripts:
        copy_file(f"evaluation/{name}")

    # Raw observations and environment records used by the paper.
    copy_tree("evaluation/target/jvm_starvation_new")
    copy_tree("evaluation/target/permission_http_remote")
    copy_tree("evaluation/target/computing_llm_replay_remote")
    copy_tree("evaluation/target/computing_llm_replay_v3")
    copy_tree("evaluation/target/redteam")
    holdout_source = ROOT / "evaluation/target/threshold_holdout"
    holdout_target = RELEASE / "evaluation/target/threshold_holdout"
    holdout_target.mkdir(parents=True, exist_ok=True)
    for path in holdout_source.glob("*threshold_holdout_03*"):
        shutil.copy2(path, holdout_target / path.name)
    for name in ("threshold_holdout_report.json", "threshold_holdout_profile_summary.csv"):
        copy_file(f"evaluation/target/threshold_holdout/{name}")

    # Paper source, generated values, formal model, figures, and final PDF.
    paper_source = ROOT / "addtions/paper"
    paper_target = RELEASE / "addtions/paper"
    paper_target.mkdir(parents=True, exist_ok=True)
    for name in (
        "main.tex",
        "refs.bib",
        "build.sh",
        "main.pdf",
        "svjour3.cls",
        "svglov3.clo",
        "spphys.bst",
        "cover_letter_computing.tex",
        "cover_letter_computing.pdf",
    ):
        shutil.copy2(paper_source / name, paper_target / name)
    for directory in ("sections", "generated", "formal", "figures"):
        copy_tree(f"addtions/paper/{directory}")
    # Flat source tree for Springer online submission (no nested LaTeX inputs).
    flat_source = ROOT / "addtions/paper_computing_submission"
    if flat_source.exists():
        copy_tree(
            "addtions/paper_computing_submission",
            exclude_names={"*.aux", "*.log", "*.out", "*.bbl", "*.blg"},
        )

    readme = RELEASE / "README.md"
    readme.write_text(
        "# Neuron Computing replication package (frozen 2026-08-10)\n\n"
        "This package contains the Java-21 source, raw JSONL observations, environment records,\n"
        "analysis scripts, generated LaTeX macros, and the frozen manuscript PDF. It deliberately\n"
        "does not contain API keys, SSH keys, passwords, Maven caches, or temporary PDF renders.\n\n"
        "## One-click consistency check\n\n"
        "From this directory, with Python 3.10+ and Java/LaTeX installed:\n\n"
        "```powershell\n"
        ".\\reproduce.ps1\n"
        "```\n\n"
        "The script regenerates the summaries/macros from raw JSONL, runs the fail-fast\n"
        "consistency gate, and rebuilds `addtions/paper/main.pdf`. The captured VPS service\n"
        "experiment is a same-host loopback HTTP deployment; this artifact makes no WAN,\n"
        "partition, multi-node, GPU, or live-production-traffic claim.\n\n"
        "## Springer source\n\n"
        "`addtions/paper_computing_submission/` is a flat upload tree using the official\n"
        "`svjour3[smallextended]` class and `spphys` bibliography style. It includes the\n"
        "author metadata, declarations, and the cover letter source/PDF.\n\n"
        "## Captured holdout\n\n"
        "`evaluation/target/threshold_holdout/` contains the final Java holdout run\n"
        "(`vps_threshold_holdout_03`): 180 observations, six profiles, threshold fixed at 50.\n"
        "The first two pilot runs were not used in the paper and are intentionally omitted.\n\n"
        "## Remote rerun\n\n"
        "The package is self-contained for analysis. A fresh VPS rerun requires Java 21, Maven,\n"
        "the source under `neuron-java/`, and a separately configured SSH account; do not put\n"
        "credentials in this directory.\n",
        encoding="utf-8",
    )
    (RELEASE / "reproduce.ps1").write_text(
        "$ErrorActionPreference = 'Stop'\n"
        "$root = Split-Path -Parent $MyInvocation.MyCommand.Path\n"
        "Push-Location $root\n"
        "try {\n"
        "  python evaluation/analyze_computing_experiments.py --input-dir evaluation/target/jvm_starvation_new --output-dir evaluation/target/jvm_starvation_new --exclude jvm_permission_starvation_smoke_local.raw.jsonl --exclude jvm_permission_starvation_vps_smoke2.raw.jsonl --exclude jvm_permission_starvation_win_qos_01.raw.jsonl\n"
        "  python evaluation/analyze_qos_experiments.py\n"
        "  python evaluation/analyze_llm_replay.py\n"
        "  python evaluation/analyze_permission_http.py --input-dir evaluation/target/permission_http_remote --output-dir evaluation/target/permission_http_remote --pattern 'permission_http_vps_http_prod_*.raw.jsonl'\n"
        "  python evaluation/analyze_threshold_holdout.py\n"
        "  python evaluation/verify_data_consistency.py\n"
        "  Push-Location addtions/paper\n"
        "  try { pdflatex -interaction=nonstopmode -halt-on-error main.tex; bibtex main; pdflatex -interaction=nonstopmode -halt-on-error main.tex; pdflatex -interaction=nonstopmode -halt-on-error main.tex } finally { Pop-Location }\n"
        "} finally { Pop-Location }\n",
        encoding="utf-8",
    )
    (RELEASE / "ZENODO_METADATA.json").write_text(
        json.dumps(
            {
                "metadata": {
                    "title": "Neuron: Resource-Aware Permission Governance for Dependable Multi-Tenant LLM Agent Runtimes (Computing replication package)",
                    "upload_type": "dataset",
                    "description": (
                        "Replication package for the Computing manuscript on resource pressure, "
                        "permission-decision availability, and cross-layer coupling in a Java 21 "
                        "multi-tenant agent runtime. Includes source, raw JSONL observations, "
                        "environment records, analysis scripts, generated values, and a flat "
                        "Springer submission tree. The permission-service deployment is same-host "
                        "loopback only and does not represent WAN or multi-node behavior."
                    ),
                    "creators": [
                        {"name": "Xie, Mingyuan", "affiliation": "Qingdao University of Technology"},
                        {"name": "Wu, Zhengxun", "affiliation": "Qingdao University of Technology"},
                    ],
                    "keywords": [
                        "dependable computing",
                        "multi-tenant runtime",
                        "permission governance",
                        "resource-aware security",
                        "LLM agents",
                        "Java",
                    ],
                    "publication_date": "2026-08-10",
                    "access_right": "open",
                    "license": "other-open",
                }
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    (RELEASE / "ZENODO_UPLOAD.md").write_text(
        "# Zenodo deposit\n\n"
        "A public code-host copy is available at the Computing replication branch listed in the "
        "manuscript. The archive is also prepared for a citable Zenodo record, but no Zenodo "
        "account or access token is stored in this package. To create that record, sign in to "
        "Zenodo, create a new upload, add `computing_neuron_repro_20260810.tar.gz`, and use "
        "`ZENODO_METADATA.json`. A DOI can then be added to the manuscript. Never commit an access "
        "token.\n",
        encoding="utf-8",
    )

    records = []
    for path in sorted(RELEASE.rglob("*")):
        if path.is_file() and path.name not in {"MANIFEST.json"}:
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
        "package": "computing_neuron_repro_20260810",
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

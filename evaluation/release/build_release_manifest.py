#!/usr/bin/env python3
"""Copy selected evidence and build a hash manifest for release staging."""

from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "evaluation" / "release" / "emse_recovery_channel_v1"

FILES = [
    "addtions/paper_emse/current_results.md",
    "addtions/paper_emse/RESPONSIBLE_DISCLOSURE_DRAFT.md",
    "addtions/paper_emse/RESPONSIBLE_DISCLOSURE_DRAFT.eml",
    "addtions/paper_emse/generated_results.tex",
    "addtions/paper_emse/generated_reflexion_rows.tex",
    "addtions/paper_emse/generated_native_rows.tex",
    "addtions/paper_emse/generated_ablation_rows.tex",
    "addtions/paper_emse/generated_trustorigin_rows.tex",
    "evaluation/generate_emse_paper_numbers.py",
    "evaluation/audit_emse_protocol.py",
    "evaluation/reflexion_native_trustorigin.py",
    "evaluation/reflexion_ollama_qwen3_anchor.py",
    "evaluation/prepare_second_coder_packet.py",
    "evaluation/analyze_second_coder.py",
    "evaluation/results/emse_protocol_audit/provenance_manifest.json",
    "evaluation/results/emse_protocol_audit/model_snapshot_audit.json",
    "evaluation/results/emse_protocol_audit/power_analysis.json",
    "evaluation/results/emse_source_analysis/source_audit.json",
    "evaluation/results/emse_reflexion_trustorigin/kimi-k2.6_n50/native_trustorigin.json",
    "evaluation/results/emse_reflexion_trustorigin/kimi-k2.6_n50/native_trustorigin.csv",
    "evaluation/results/emse_source_analysis/second_coder/CODEBOOK.md",
    "evaluation/results/emse_source_analysis/second_coder/HANDOFF_TO_SECOND_CODER.md",
    "evaluation/results/emse_source_analysis/second_coder/second_coder_labels.csv",
    "evaluation/results/emse_reflexion_qwen3_ollama_anchor/QWEN3_BLOCKER.md",
    "evaluation/results/emse_reflexion_ollama_open_anchor/qwen7b_n5/README.md",
    "evaluation/results/emse_reflexion_ollama_open_anchor/qwen7b_n5/qwen7b_anchor.json",
    "evaluation/results/emse_reflexion_ollama_open_anchor/qwen7b_n5/qwen7b_anchor.raw.jsonl",
    "addtions/paper_emse/main.pdf",
    "addtions/paper_emse/cover_letter_emse.pdf",
    "addtions/paper_emse/title_page_emse.pdf",
]

PAPER_SOURCE_DIR = ROOT / "addtions" / "paper_emse"
FILES.extend(
    f"addtions/paper_emse/{path.name}"
    for path in sorted(PAPER_SOURCE_DIR.iterdir())
    if path.suffix.lower() in {".tex", ".bib", ".cls", ".clo"}
)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    manifest = {"status": "staging", "files": []}
    for rel in FILES:
        source = ROOT / rel
        if not source.is_file():
            manifest["files"].append({"path": rel, "status": "missing"})
            continue
        target = OUT / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        manifest["files"].append({"path": rel, "status": "present", "sha256": sha256(source), "bytes": source.stat().st_size})
    (OUT / "MANIFEST.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"package": str(OUT), "files": len(manifest["files"]), "missing": [x["path"] for x in manifest["files"] if x["status"] == "missing"]}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

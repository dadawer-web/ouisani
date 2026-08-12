#!/usr/bin/env python3
"""Reproducible source audit for the six-framework recovery-path cases.

The audit is deliberately source-only: it fetches files at fixed revisions and
checks the codebook predicates used in the paper.  It does not execute agent
code or infer a population prevalence estimate.  The output is written under
``evaluation/results/emse_source_analysis`` and is suitable for inclusion in
the replication package.
"""

from __future__ import annotations

import csv
import json
import re
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "evaluation" / "results" / "emse_source_analysis"
RETRIEVAL_DATE = "2026-08-09"


CASES: list[dict[str, Any]] = [
    {
        "framework": "Reflexion",
        "repository": "https://github.com/noahshinn/reflexion",
        "commit": "218cf0ef1df84b05ce379dd4a8e47f17766733a0",
        "revision_label": "pinned commit used by the native experiment",
        "files": [
            {
                "path": "programming_runs/generators/generator_utils.py",
                "required": [
                    r"\[unit test results from previous impl\]",
                    r"\[previous impl\]",
                ],
            }
        ],
        "source_origin_provenance": False,
        "error_status_metadata": False,
        "recovery_action_frame": True,
    },
    {
        "framework": "MetaGPT",
        "repository": "https://github.com/FoundationAgents/MetaGPT",
        "commit": "c036574507e7616c02512e7c8ad88dd847783afa",
        "revision_label": "v0.8.1",
        "files": [
            {
                "path": "metagpt/actions/debug_error.py",
                "required": [r"# Console logs", r"Role: You are a Development Engineer"],
            }
        ],
        "source_origin_provenance": False,
        "error_status_metadata": False,
        "recovery_action_frame": True,
    },
    {
        "framework": "Aider",
        "repository": "https://github.com/Aider-AI/aider",
        "commit": "253f0368b873ba30d8ee26e463718f0c03614ddf",
        "revision_label": "v0.86.2",
        "files": [
            {
                "path": "aider/coders/editblock_coder.py",
                "required": [r"Just reply with fixed versions of the"],
            }
        ],
        "source_origin_provenance": False,
        "error_status_metadata": False,
        "recovery_action_frame": True,
    },
    {
        "framework": "OpenHands",
        "repository": "https://github.com/All-Hands-AI/OpenHands",
        "commit": "41a78ca768afd21bb05e2e9c41a14b08739884f1",
        "revision_label": "0.48.0",
        "files": [
            {
                "path": "openhands/controller/agent_controller.py",
                "required": [r"ErrorObservation", r"state\.history"],
            },
            {
                "path": "openhands/memory/conversation_memory.py",
                "required": [r"ErrorObservation", r"\[Error occurred in processing last action\]"],
            },
        ],
        "source_origin_provenance": False,
        "error_status_metadata": True,
        "recovery_action_frame": False,
    },
    {
        "framework": "AutoGen",
        "repository": "https://github.com/microsoft/autogen",
        "commit": "b04775f3dc7a5c9a437c49f97b2cb42684b5b38e",
        "revision_label": "python-v0.4.7",
        "files": [
            {
                "path": "python/packages/autogen-agentchat/src/autogen_agentchat/agents/_assistant_agent.py",
                "required": [
                    r"FunctionExecutionResult\(content=f[\"']Error: \{e\}",
                    r"is_error=True",
                    r"FunctionExecutionResultMessage\(content=exec_results\)",
                ],
            }
        ],
        "source_origin_provenance": False,
        "error_status_metadata": True,
        "recovery_action_frame": True,
    },
    {
        "framework": "LangGraph",
        "repository": "https://github.com/langchain-ai/langgraph",
        "commit": "d56666f7fbf0d380ad84cdf0cbe5aa48ab0cc086",
        "revision_label": "repository HEAD fixed at retrieval",
        "files": [
            {
                "path": "libs/prebuilt/langgraph/prebuilt/tool_node.py",
                "required": [r"status=\"error\"", r"ToolMessage"],
            }
        ],
        "source_origin_provenance": False,
        "error_status_metadata": True,
        "recovery_action_frame": False,
    },
]


def fetch(repo: str, commit: str, path: str) -> str:
    url = f"https://raw.githubusercontent.com/{repo.split('github.com/', 1)[1]}/{commit}/{path}"
    request = urllib.request.Request(url, headers={"User-Agent": "emse-source-audit/1.0"})
    with urllib.request.urlopen(request, timeout=45) as response:
        return response.read().decode("utf-8", errors="replace")


def audit_case(case: dict[str, Any]) -> dict[str, Any]:
    fetched_files: list[dict[str, Any]] = []
    all_required_present = True
    errors: list[str] = []
    for spec in case["files"]:
        try:
            content = fetch(case["repository"], case["commit"], spec["path"])
            matches = {pattern: bool(re.search(pattern, content, re.IGNORECASE)) for pattern in spec["required"]}
            all_required_present = all_required_present and all(matches.values())
            fetched_files.append({"path": spec["path"], "required_matches": matches})
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError) as exc:
            all_required_present = False
            errors.append(f"{spec['path']}: {exc}")
            fetched_files.append({"path": spec["path"], "required_matches": {}, "error": str(exc)})

    signature = "yes" if (not case["source_origin_provenance"] and case["recovery_action_frame"]) else "no"
    return {
        "framework": case["framework"],
        "repository": case["repository"],
        "commit": case["commit"],
        "revision_label": case["revision_label"],
        "fetched": not errors,
        "required_patterns_present": all_required_present,
        "files": fetched_files,
        "source_origin_provenance": case["source_origin_provenance"],
        "error_status_metadata": case["error_status_metadata"],
        "recovery_action_frame": case["recovery_action_frame"],
        "trampoline_signature": signature if all_required_present else "unverified",
        "errors": errors,
    }


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    cases = [audit_case(case) for case in CASES]
    report = {
        "experiment": "Six-framework recovery-path source audit",
        "retrieval_date": RETRIEVAL_DATE,
        "unit_of_analysis": "failure-content capture, packaging, and re-injection code",
        "source_origin_definition": "A label that distinguishes externally originated content from internally generated diagnostics; error status alone does not count as source provenance.",
        "high_trust_frame_definition": "A recovery/action frame that instructs the model to use the failure payload for repair, or transports it in the same action-authorizing channel as successful tool results.",
        "cases": cases,
        "summary": {
            "cases": len(cases),
            "verified": sum(1 for case in cases if case["trampoline_signature"] != "unverified"),
            "source_origin_provenance_present": sum(1 for case in cases if case["source_origin_provenance"]),
            "error_status_only": sum(1 for case in cases if case["error_status_metadata"] and not case["source_origin_provenance"]),
            "trampoline_signature_yes": sum(1 for case in cases if case["trampoline_signature"] == "yes"),
        },
    }
    report_path = OUT / "source_audit.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    with (OUT / "source_audit.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=["framework", "commit", "revision_label", "fetched", "required_patterns_present", "source_origin_provenance", "error_status_metadata", "recovery_action_frame", "trampoline_signature"],
        )
        writer.writeheader()
        writer.writerows({key: case[key] for key in writer.fieldnames} for case in cases)
    print(json.dumps(report["summary"], indent=2))
    print(report_path)
    return 0 if report["summary"]["verified"] == len(CASES) else 1


if __name__ == "__main__":
    raise SystemExit(main())

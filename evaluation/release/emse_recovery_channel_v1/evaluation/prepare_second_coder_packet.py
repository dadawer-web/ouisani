#!/usr/bin/env python3
"""Prepare a blinded human second-coder packet for the six-framework audit.

The packet intentionally contains no primary labels.  A human second coder
should inspect the pinned source revisions and fill the four label columns in
``second_coder_labels.csv`` independently of the primary audit.
"""

from __future__ import annotations

import csv
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AUDIT = ROOT / "evaluation" / "results" / "emse_source_analysis" / "source_audit.json"
OUT = ROOT / "evaluation" / "results" / "emse_source_analysis" / "second_coder"

CODEBOOK = """# Independent second-coder packet

## Instructions

1. Inspect only the pinned repository revision and the listed source path(s).
2. Apply the operational definitions below independently; do not consult the
   primary labels or the paper's results while coding.
3. Enter exactly `yes` or `no` in each label column. Use `notes` only for a
   short evidence pointer (function/class/line range).
4. Return the completed `second_coder_labels.csv` to the study authors. Do not
   alter the framework, commit, or source-path fields.

## Codebook

- `source_provenance`: a field or type distinguishes externally originated
  content from internally generated diagnostics, and that distinction survives
  into the recovery representation. An error flag alone is not provenance.
- `error_status`: metadata indicates execution failed, such as `is_error` or
  `status=error`.
- `recovery_action_frame`: a recovery message instructs the model to use the
  failure payload for repair, or transports it in the same action-authorizing
  channel as successful tool results.
- `trampoline_signature`: `yes` only when both `source_provenance=no` and
  `recovery_action_frame=yes` for the same recovery path.

Code status text separately from source origin. If a case is ambiguous, record
the most defensible label and explain the exact evidence in `notes`.
"""


def main() -> int:
    audit = json.loads(AUDIT.read_text(encoding="utf-8"))
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "CODEBOOK.md").write_text(CODEBOOK, encoding="utf-8")
    rows = []
    for case in audit["cases"]:
        paths = "; ".join(item["path"] for item in case.get("files", []))
        rows.append(
            {
                "unit_id": case["framework"],
                "framework": case["framework"],
                "repository": case["repository"],
                "commit": case["commit"],
                "source_paths": paths,
                "source_provenance": "",
                "error_status": "",
                "recovery_action_frame": "",
                "trampoline_signature": "",
                "notes": "",
            }
        )
    fields = list(rows[0])
    with (OUT / "second_coder_labels.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    (OUT / "README.md").write_text(
        "# Second-coder packet\n\n"
        "This packet is intentionally blinded: label columns are blank. A human\n"
        "second coder must complete `second_coder_labels.csv` independently.\n"
        "Run `python evaluation/analyze_second_coder.py` after returning the\n"
        "completed CSV.\n",
        encoding="utf-8",
    )
    print(OUT / "CODEBOOK.md")
    print(OUT / "second_coder_labels.csv")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

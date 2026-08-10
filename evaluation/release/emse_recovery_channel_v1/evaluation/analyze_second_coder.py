#!/usr/bin/env python3
"""Compute agreement after a human second coder returns the blinded CSV."""

from __future__ import annotations

import csv
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AUDIT = ROOT / "evaluation" / "results" / "emse_source_analysis" / "source_audit.json"
PACKET = ROOT / "evaluation" / "results" / "emse_source_analysis" / "second_coder"
LABELS = PACKET / "second_coder_labels.csv"
OUT = PACKET / "agreement.json"
CODES = ("source_provenance", "error_status", "recovery_action_frame", "trampoline_signature")


def normalize(value: str) -> int:
    value = value.strip().lower()
    if value in {"yes", "y", "1", "true"}:
        return 1
    if value in {"no", "n", "0", "false"}:
        return 0
    raise ValueError(f"expected yes/no label, got {value!r}")


def kappa(primary: list[int], second: list[int]) -> tuple[float | None, float]:
    n = len(primary)
    agree = sum(a == b for a, b in zip(primary, second))
    po = agree / n if n else 0.0
    p1 = sum(primary) / n if n else 0.0
    p2 = sum(second) / n if n else 0.0
    pe = p1 * p2 + (1 - p1) * (1 - p2)
    return (None if abs(1 - pe) < 1e-12 else (po - pe) / (1 - pe), po)


def primary_labels(case: dict) -> dict[str, int]:
    return {
        "source_provenance": int(case["source_origin_provenance"]),
        "error_status": int(case["error_status_metadata"]),
        "recovery_action_frame": int(case["recovery_action_frame"]),
        "trampoline_signature": int(case["trampoline_signature"] == "yes"),
    }


def main() -> int:
    if not LABELS.exists():
        raise SystemExit(f"missing completed second-coder file: {LABELS}")
    audit = json.loads(AUDIT.read_text(encoding="utf-8"))
    primary = {case["framework"]: primary_labels(case) for case in audit["cases"]}
    with LABELS.open(encoding="utf-8", newline="") as handle:
        rows = {row["framework"]: row for row in csv.DictReader(handle)}
    if set(rows) != set(primary):
        raise SystemExit("second-coder rows do not match the six audited frameworks")
    result = {"n_units": len(primary), "codes": {}, "adjudication": "pending"}
    for code in CODES:
        p = [primary[name][code] for name in sorted(primary)]
        s = [normalize(rows[name][code]) for name in sorted(primary)]
        kap, agreement = kappa(p, s)
        result["codes"][code] = {
            "cohen_kappa": kap,
            "raw_agreement": agreement,
            "primary_positive": sum(p),
            "second_positive": sum(s),
            "note": "kappa undefined when expected agreement is 1; report raw agreement and a prevalence-aware statistic",
        }
    OUT.mkdir(parents=True, exist_ok=True)
    OUT.joinpath("agreement.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

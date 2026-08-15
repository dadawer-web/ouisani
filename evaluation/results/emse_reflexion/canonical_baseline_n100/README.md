# Canonical Reflexion baseline (n=100)

This directory freezes one complete result per model for downstream analysis.
Each selected model has 5 attack payloads × 100 trials and 5 matched benign
controls × 100 trials. Duplicate and partial runs remain under the parent
`evaluation/results/emse_reflexion/` directory but are excluded from pooling.

Use `canonical_manifest.json` for provenance and SHA-256 hashes, and
`canonical_summary.csv` for model-level statistics. The copied JSON/CSV/JSONL
artifacts under each model directory are the canonical inputs for subsequent
tables and plots.

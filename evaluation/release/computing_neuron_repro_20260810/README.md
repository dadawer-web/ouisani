# Neuron Computing replication package (frozen 2026-08-10)

This package contains the Java-21 source, raw JSONL observations, environment records,
analysis scripts, generated LaTeX macros, and the frozen manuscript PDF. It deliberately
does not contain API keys, SSH keys, passwords, Maven caches, or temporary PDF renders.

## One-click consistency check

From this directory, with Python 3.10+ and Java/LaTeX installed:

```powershell
.\reproduce.ps1
```

The script regenerates the summaries/macros from raw JSONL, runs the fail-fast
consistency gate, and rebuilds `addtions/paper/main.pdf`. The captured VPS service
experiment is a same-host loopback HTTP deployment; this artifact makes no WAN,
partition, multi-node, GPU, or live-production-traffic claim.

## Springer source

`addtions/paper_computing_submission/` is a flat upload tree using the official
`svjour3[smallextended]` class and `spphys` bibliography style. It includes the
author metadata, declarations, and the cover letter source/PDF.

## Captured holdout

`evaluation/target/threshold_holdout/` contains the final Java holdout run
(`vps_threshold_holdout_03`): 180 observations, six profiles, threshold fixed at 50.
The first two pilot runs were not used in the paper and are intentionally omitted.

## Remote rerun

The package is self-contained for analysis. A fresh VPS rerun requires Java 21, Maven,
the source under `neuron-java/`, and a separately configured SSH account; do not put
credentials in this directory.

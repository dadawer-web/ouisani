# Neuron CCPE replication package (frozen 2026-08-11)

This package contains the Java 21 source, raw JSONL observations, environment records,
executor-oriented concurrency summaries, the 2x2 capacity/signal ablation, analysis scripts, generated LaTeX values, and
the free-format CCPE manuscript. It contains no API keys, SSH keys, passwords, Maven
caches, or temporary PDF renders.

## Reproduce

Run the following from this directory with Python 3.10+ and LaTeX installed:

```powershell
.\reproduce.ps1
```

`verify_ccpe_consistency.py` checks the 3,840-observation `ccpe_core_01` run, the
1,800-observation 2x2 ablation, its executor-pressure cells, and the generated macros.
The older Computing manuscript gate
is intentionally not required for this CCPE-only package.

The process-separated permission service is a same-host loopback HTTP experiment; it
does not establish WAN, partition, multi-node, GPU, or live-production behavior.

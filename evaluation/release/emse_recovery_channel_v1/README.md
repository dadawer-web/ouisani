# Recovery-Channel Prompt Injection in Self-Healing LLM Agent Frameworks

Replication package for the EMSE manuscript by Mingyuan Xie and Zhengxun Wu.

## Scope

This package contains the fixed-commit source audit, native Reflexion,
AutoGen, and Aider experiment runners, derived trial summaries, raw logs used
for the reported counts, the numerical-table generator, and the blinded
second-coder packet. API credentials and provider response bodies are excluded.

The provider-mediated model results are alias-provenance evaluations: the
provider does not expose an immutable backend checkpoint. The local Ollama
Qwen3-8B anchor is tracked separately and must not be described as complete
until its model files, local digest, and run manifest are present.

## Reproduction modes

1. **Ledger reproduction:** regenerate paper tables from the frozen JSON/JSONL
   artifacts without calling a model endpoint.
2. **Framework replication:** install the pinned framework revisions and run a
   local or explicitly documented model endpoint. Record model revision,
   tokenizer, runtime, sampling parameters, seeds, and hardware.

## Safety and privacy

All payloads are synthetic canaries. Do not add API keys, personal data, user
content, or production credentials. Provider response bodies are not
redistributed.

## Status

This is a staging package (2026-08-10). A public GitHub release and Zenodo DOI
must only be created after the fixed local-model anchor and the human
second-coder labels are added, the manifest is regenerated, and the authors
approve the release. The cached `qwen:7b` was used only for a harness smoke
test and is excluded from the formal Qwen3-8B evidence because its Ollama
metadata identifies it as Qwen2-family. A formal `qwen3:8b` pull is currently
blocked by the local registry/DNS path.

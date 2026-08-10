# Recovery-Channel Prompt Injection in Self-Healing LLM Agent Frameworks

Replication package for the EMSE manuscript by Mingyuan Xie and Zhengxun Wu.

## Scope

This package contains the fixed-commit source audit, native Reflexion,
AutoGen, and Aider experiment runners, derived trial summaries, raw logs used
for the reported counts, the numerical-table generator, and the blinded
second-coder packet. API credentials and provider response bodies are excluded.

The provider-mediated model results are alias-provenance evaluations: the
provider does not expose an immutable backend checkpoint. The fixed local
Ollama `qwen:7b` sensitivity anchor is included with its digest, manifest, and
raw log. It is a bounded exploratory open-model result, not a Qwen3-8B result
and not a replacement for the provider-alias matrix.

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

This is a staging package (2026-08-10). A prerelease GitHub release has been
pushed for auditability; final GitHub publication and a Zenodo DOI require
author approval/login. No independent second-coder labels are claimed; RQ1 is
single-coder qualitative analysis with an explicit limitation. The attempted
`qwen3:8b` pull is retained only as an invalid blocker record.

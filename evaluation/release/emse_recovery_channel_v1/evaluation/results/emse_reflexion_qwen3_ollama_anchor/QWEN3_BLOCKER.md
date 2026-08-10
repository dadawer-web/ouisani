# Qwen3-8B anchor blocker

Date: 2026-08-10 (Asia/Shanghai)

The runner `evaluation/reflexion_ollama_qwen3_anchor.py` is ready for the
formal fixed-model anchor. The local Ollama service was restarted and the
cached `qwen:7b` completed a one-request inference after warm-up. That smoke
run is excluded from the paper because `ollama /api/tags` reports family
`qwen2`, even though the parameter-size field is 8B.

The formal command

```text
ollama pull qwen3:8b
```

failed before downloading a manifest:

```text
Get "https://registry.ollama.ai/v2/library/qwen3/manifests/8b":
dial tcp: lookup registry.ollama.ai: no such host
```

A later retry resolved the registry but stalled while downloading the
Cloudflare R2 blob, with TLS handshake timeouts and remote connection resets.
The retained 5,225,374,496-byte partial blob hashes to
`e632633eb48ff5422dd147eb39c5bb9eebf47b163b917026231eba12b077ba0a`, which
does not match the advertised blob digest `a3de86cd1c13...`; it is therefore
not usable as a local model layer.

Therefore no Qwen3-8B trial count, ASR, confidence interval, or model digest
is claimed. A subsequent 400-job invocation with the absent model name was
retained as an invalid audit artifact (`qwen3_anchor.json`): all 400 jobs
returned local API errors and the valid-trial counts are zero. It must not be
used as data. Retry the pull on a network with Ollama registry/DNS access,
verify that `ollama show qwen3:8b` succeeds, then run the documented 400-trial
command and regenerate the paper numbers.

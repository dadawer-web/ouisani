# Current evidence freeze (2026-08-10)

This document is the current working results ledger for the EMSE draft. It supersedes numerical claims in earlier draft sections when they conflict with the frozen artifacts under `evaluation/results/emse_*`. All values below are generated from the recorded JSON/JSONL artifacts; they are not hand-entered paper estimates.

## Main line

The paper is organized around one mechanism: recovery paths can re-inject externally influenced failure content into a subsequent LLM turn, creating a prompt-injection channel that is independent of the normal forward path. Provenance marking can reduce risk but is not a complete guarantee; structural reauthorization is a complementary control for privileged recovery actions.

## Canonical Reflexion baseline

The canonical matrix uses five models, five attack variants, five matched benign variants, and 100 trials per model/variant/arm (500 attack and 500 benign trials per model). Duplicate and incomplete runs are excluded by the canonical manifest.

| Model | Attack successes / 500 | ASR | Benign successes / 500 | FPR |
|---|---:|---:|---:|---:|
| GPT-5.6 Luna | 166 | 0.332 | 0 | 0.000 |
| GLM-5.2 | 215 | 0.430 | 0 | 0.000 |
| Kimi K2.6 | 398 | 0.796 | 0 | 0.000 |
| DeepSeek V4 Flash | 204 | 0.408 | 0 | 0.000 |
| MiMo-V2.5 | 87 | 0.174 | 0 | 0.000 |

Primary artifact: `evaluation/results/emse_reflexion/canonical_baseline_n100/canonical_manifest.json`.

## Native framework evidence

### AutoGen

AutoGen 0.4.7 was executed through `AssistantAgent`, `FunctionTool`, a native tool-error event, and the same agent's recovery turn. The pilot uses 50 trials per variant and arm (250 attack and 250 benign trials per model).

| Model | Attack ASR | Native path rate | Benign FPR |
|---|---:|---:|---:|
| GLM-5.2 | 0.000 (0/250) | 1.000 | 0.000 |
| GPT-5.6 Luna | 0.000 (0/250) | 1.000 | 0.000 |
| Kimi K2.6 | 0.372 (93/250) | 0.984 | 0.000 |

The AutoGen result is native framework evidence, but its cross-model sample size is smaller than the canonical Reflexion matrix.

### Aider

Aider 0.86.2 was executed through `Coder.run_one`, its native `cmd_test` non-zero output, and the same Coder recovery loop. GPT-5.6 Luna yielded 114/250 attack successes (ASR 0.456), 248/250 native paths executed (0.992), and 0/250 benign successes.

### Native Reflexion TrustOrigin port

At Reflexion commit `218cf0ef1df84b05ce379dd4a8e47f17766733a0`, the shipped
`PyGenerator.func_impl` recovery generator was run with Kimi K2.6 and five
payload variants. Each treatment/arm cell contains 250 trials (50 per
payload), for 1,000 total trials and zero API errors.

| Treatment | Attack successes / 250 | ASR | Matched benign FPR |
|---|---:|---:|---:|
| Raw captured feedback | 194 | 0.776 | 0.000 |
| TrustOrigin-tagged feedback | 137 | 0.548 | 0.000 |

The raw-versus-tagged attack contrast has Fisher's exact `p = 9.78e-8` and
prospective two-proportion planning power approximately 0.9998 for the
observed effect. This is the primary RQ3 provenance result: single-model
native-framework evidence that the provenance frame can reduce (but does not
eliminate) recovery-path obedience. It is not a cross-model defense-efficacy
estimate. The formal artifacts are under
`evaluation/results/emse_reflexion_trustorigin/kimi-k2.6_n50/`.

## Reflexion causal mechanism screening

Kimi K2.6 was evaluated with five payload variants and 20 trials per variant in each cell. The pooled attack results were:

| Contrast | Attack ASR | Benign FPR | Interpretation |
|---|---:|---:|---|
| Recovery, raw user tail | 0.780 | 0.000 | screening cell |
| Recovery, tagged user tail | 0.770 | 0.000 | provenance marker effect is small in this sample |
| Forward, raw user tail | 0.840 | 0.000 | channel contrast |
| Recovery, raw system tail | 0.770 | 0.000 | role screening |
| Recovery, raw user head | 0.770 | 0.000 | position screening |

These are canonical message-level mechanism screens, not claims that every framework exposes the same native event loop. The provenance raw/tagged contrast is too small for a confirmatory claim.

## Defense-aware supplementary experiment

The author-constructed structural recovery-node experiment used five payload classes and 20 trials per class/configuration (100 trials per pooled configuration) with GPT-5.6 Luna.

- Undefended: 93/100, ASR 0.930, Wilson 95% CI [0.863, 0.966].
- Defended: 86/100, ASR 0.860, Wilson 95% CI [0.779, 0.915].
- Fisher exact p = 0.1652.
- No API errors and no forward blocks were recorded.

This is supplementary structural evidence and does not support a claim of statistically significant defense efficacy.

## Legitimate recovery screening

The deterministic task generator covered arithmetic, assertion failure, compile error, external-content error, format error, permission denial, and tool timeout. GPT-5.6 Luna completed 90/100 tasks successfully, with 0/100 false positives. This is a screening result; the pre-specified 0.80 planning null requires approximately 108 tasks under the current normal-approximation calculation.

## Endpoint and snapshot provenance

The supplied OpenCode Go catalog lists the experiment aliases and `owned_by: opencode`. Every catalog entry shares `created = 1786282996`, corresponding to `2026-08-09T13:43:16Z`; this is treated as catalog publication time, not an immutable checkpoint identifier.

The supplied service documentation specifies `/responses` for GPT-5.6 Luna, while historical experiment artifacts use the OpenAI-compatible `/chat/completions` protocol. A current one-request probe received HTTP 403 with edge error code 1010 from both endpoints, so it cannot establish protocol equivalence or disprove the documented route. Historical artifacts are therefore not relabeled.

The three official public snapshot candidates are `zai-org/GLM-5.2`, `deepseek-ai/DeepSeek-V4-Flash`, and `moonshotai/Kimi-K2.6`; the OpenCode alias-to-revision mapping remains undisclosed.

## Fixed local-model anchor attempt

The fixed-model runner is `evaluation/reflexion_ollama_qwen3_anchor.py` and
targets Ollama `qwen3:8b` for 400 trials. The cached local `qwen:7b` was used
only for a harness smoke test and is excluded because Ollama identifies it as
Qwen2-family. The formal `qwen3:8b` pull failed before manifest download with
`registry.ollama.ai: no such host`; the resulting blocker record is
`evaluation/results/emse_reflexion_qwen3_ollama_anchor/QWEN3_BLOCKER.md`.
Consequently, this ledger and the paper contain no Qwen3-8B numerical claim.

## Exploratory cached local anchor (not Qwen3)

The cached Ollama `qwen:7b` model (Ollama family `qwen2`) completed a separate
100-trial sensitivity run: raw attack 10/25 (0.400), TrustOrigin-tagged attack
6/25 (0.240), matched benign false-positive rates 0/25 in both arms, and
Fisher's exact `p=0.3635`. This result is retained as an open local-model
exploration, not as the requested Qwen3-8B anchor and not as a confirmatory
mitigation result.

## Reporting rule

The paper may make the following claims from the frozen evidence:

1. Recovery-channel injection is observed across three native framework paths (Reflexion, AutoGen, and Aider), with matched benign controls.
2. Attack success varies strongly by model and framework; pooled rates must not replace framework/model/payload strata.
3. The provenance and position results are mechanism screening evidence.
4. The defense-aware and legitimate-recovery results are supplementary/screening evidence.
5. Exact provider checkpoint reproducibility remains a documented limitation.

The paper must not claim that OpenCode aliases are immutable official snapshots, that the defense is statistically effective based on p = 0.1652, or that the small causal-ablation contrasts are confirmatory.

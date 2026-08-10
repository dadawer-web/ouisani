# Reproduction runbook

## Ledger-only rebuild

Run from the repository root:

```powershell
python evaluation/generate_emse_paper_numbers.py
python evaluation/audit_emse_protocol.py
```

## Optional second coder

The blinded packet is archived in
`evaluation/results/emse_source_analysis/second_coder/`. No second-coder
result is claimed in the paper. If an independent coder becomes available in
future, they may fill `second_coder_labels.csv` and then run:

```powershell
python evaluation/analyze_second_coder.py
```

## Fixed local open-model anchor

The accepted sensitivity anchor is the cached local Ollama model `qwen:7b`.
The exact Ollama digest/Modelfile and the 100-trial raw log are already
included. To reproduce the bounded run, confirm the model is present:

```powershell
ollama list
ollama show qwen:7b --modelfile
$env:OLLAMA_MODEL='qwen:7b'
$env:OLLAMA_TRIALS_PER_PAYLOAD='5'
$env:OLLAMA_CONCURRENCY='1'
python evaluation/reflexion_ollama_qwen3_anchor.py
```

The run produces 100 trials (five payloads, raw/tagged treatment, attack and
benign arms). It is exploratory sensitivity evidence and must not be pooled
with the provider-alias matrix. The failed `qwen3:8b` pull is documented but
not a completion claim.

## Paper build

From `addtions/paper_emse`:

```powershell
pdflatex -interaction=nonstopmode -halt-on-error main.tex
bibtex main
pdflatex -interaction=nonstopmode -halt-on-error main.tex
pdflatex -interaction=nonstopmode -halt-on-error main.tex
```

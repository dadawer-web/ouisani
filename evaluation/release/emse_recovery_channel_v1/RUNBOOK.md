# Reproduction runbook

## Ledger-only rebuild

Run from the repository root:

```powershell
python evaluation/generate_emse_paper_numbers.py
python evaluation/audit_emse_protocol.py
```

## Human second coder

The blinded packet is in
`evaluation/results/emse_source_analysis/second_coder/`. A human coder must
fill `second_coder_labels.csv` independently and return it before running:

```powershell
python evaluation/analyze_second_coder.py
```

## Local Qwen3 anchor

The intended anchor is the local Ollama model `qwen3:8b`. Before running, the
authors must record the exact Ollama model digest/Modelfile and confirm the
model is present:

```powershell
ollama list
ollama show qwen3:8b --modelfile
$env:OLLAMA_TRIALS_PER_PAYLOAD='20'
$env:OLLAMA_CONCURRENCY='1'
python evaluation/reflexion_ollama_qwen3_anchor.py
```

The run produces 400 trials (five payloads, raw/tagged treatment, attack and
benign arms). It must not be labeled Qwen3-8B evidence if the model is absent
or if only a different Ollama tag is available.

## Paper build

From `addtions/paper_emse`:

```powershell
pdflatex -interaction=nonstopmode -halt-on-error main.tex
bibtex main
pdflatex -interaction=nonstopmode -halt-on-error main.tex
pdflatex -interaction=nonstopmode -halt-on-error main.tex
```

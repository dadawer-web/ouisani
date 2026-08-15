# Handoff to the independent second coder

To: Zhengxun Wu

Please complete `second_coder_labels.csv` independently of the primary
analysis. Inspect only the listed fixed repository commit and source paths,
using `CODEBOOK.md` for the yes/no definitions. Do not consult the primary
labels, paper tables, or source-audit classifications before submitting the
completed CSV. Add a short function/class/line-range pointer in `notes` for
each decision.

After the completed CSV is returned, the study author should run:

```text
python evaluation/analyze_second_coder.py
```

The script writes `agreement.json`, including raw agreement and Cohen's
kappa where it is defined. If a category has a degenerate prevalence and
kappa is undefined, report the raw agreement and the reason rather than
inventing a coefficient.

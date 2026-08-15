"""Prepare a flat, Wiley CCPE free-format LaTeX submission directory."""

from __future__ import annotations

import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PAPER = ROOT / "addtions" / "paper_ccpe"
OUT = ROOT / "addtions" / "paper_ccpe_submission"


def copy_file(name: str) -> None:
    src = PAPER / name
    if src.exists():
        shutil.copy2(src, OUT / Path(name).name)


def main() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)

    main_text = (PAPER / "main.tex").read_text(encoding="utf-8")
    main_text = main_text.replace("generated/", "")
    main_text = main_text.replace("sections/", "")
    main_text = main_text.replace("\\graphicspath{{figures/}}", "\\graphicspath{{}}")
    (OUT / "main.tex").write_text(main_text, encoding="utf-8")

    for subdir in ("sections", "generated"):
        for src in sorted((PAPER / subdir).glob("*.tex")):
            shutil.copy2(src, OUT / src.name)
    for src in sorted((PAPER / "figures").glob("*")):
        if src.is_file():
            shutil.copy2(src, OUT / src.name)

    for name in (
        "refs.bib",
        "spphys.bst",
        "cover_letter_ccpe.tex",
        "cover_letter_ccpe.pdf",
        "summary_ccpe.txt",
        "main.pdf",
    ):
        copy_file(name)

    (OUT / "build.sh").write_text(
        "#!/usr/bin/env bash\n"
        "set -euo pipefail\n"
        "pdflatex -interaction=nonstopmode -halt-on-error main.tex\n"
        "bibtex main\n"
        "pdflatex -interaction=nonstopmode -halt-on-error main.tex\n"
        "pdflatex -interaction=nonstopmode -halt-on-error main.tex\n",
        encoding="utf-8",
    )
    (OUT / "SUBMISSION_README.md").write_text(
        "# CCPE submission source\n\n"
        "This directory is flattened for Wiley's free-format submission. "
        "The manuscript is a one-column LaTeX article and may be uploaded "
        "with the PDF as the main document and the source files as supporting "
        "LaTeX files. The process-separated permission-service experiment is "
        "same-host loopback only; it is not evidence about WAN or multi-node "
        "deployments. Run `build.sh` to rebuild the PDF.\n",
        encoding="utf-8",
    )
    print(OUT)


if __name__ == "__main__":
    main()

"""Prepare a flat, Springer-Computing-ready LaTeX submission directory.

The Computing instructions request editable source files without nested LaTeX
subfolders.  This script copies the frozen manuscript sources, rewrites input
paths, and keeps the generated values and Springer class files beside main.tex.
It never changes the working manuscript directory.
"""
from __future__ import annotations

import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PAPER = ROOT / "addtions" / "paper"
OUT = ROOT / "addtions" / "paper_computing_submission"


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
        "svjour3.cls",
        "svglov3.clo",
        "spphys.bst",
        "build.sh",
        "cover_letter_computing.tex",
        "cover_letter_computing.pdf",
        "main.pdf",
    ):
        copy_file(name)

    # A local build script is easier to use after flattening than the manuscript
    # script, which assumes the original directory layout.
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
        "# Computing submission source\\n\\n"
        "This directory is flattened for Springer Computing's LaTeX upload. "
        "The manuscript uses the official `svjour3[smallextended]` class and "
        "the `spphys` bibliography style. Run `build.sh` (or the four commands "
        "listed there) to rebuild `main.pdf`. The process-separated permission "
        "service experiment is same-host loopback only; it is not evidence about "
        "WAN or multi-node deployments.\\n",
        encoding="utf-8",
    )
    print(OUT)


if __name__ == "__main__":
    main()

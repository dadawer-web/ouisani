# Third native framework decision

MetaGPT 0.8.1 was probed first, but its declared `faiss-cpu==1.7.4`
dependency has no compatible wheel for the available Python 3.12 runtime.
The full native runtime therefore could not be installed reproducibly.

Aider was selected instead. The experiment pins `aider-chat==0.86.2` and
drives `aider.coders.base_coder.Coder.run_one` with `PatchCoder`, Aider's own
non-zero `/test` command, and the same Coder recovery loop. The n=50 pilot is
in `results/emse_aider_native/pilot_gpt_n50_split_20260809T`.

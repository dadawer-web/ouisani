# Independent second-coder packet

## Instructions

1. Inspect only the pinned repository revision and the listed source path(s).
2. Apply the operational definitions below independently; do not consult the
   primary labels or the paper's results while coding.
3. Enter exactly `yes` or `no` in each label column. Use `notes` only for a
   short evidence pointer (function/class/line range).
4. Return the completed `second_coder_labels.csv` to the study authors. Do not
   alter the framework, commit, or source-path fields.

## Codebook

- `source_provenance`: a field or type distinguishes externally originated
  content from internally generated diagnostics, and that distinction survives
  into the recovery representation. An error flag alone is not provenance.
- `error_status`: metadata indicates execution failed, such as `is_error` or
  `status=error`.
- `recovery_action_frame`: a recovery message instructs the model to use the
  failure payload for repair, or transports it in the same action-authorizing
  channel as successful tool results.
- `trampoline_signature`: `yes` only when both `source_provenance=no` and
  `recovery_action_frame=yes` for the same recovery path.

Code status text separately from source origin. If a case is ambiguous, record
the most defensible label and explain the exact evidence in `notes`.

# Responsible disclosure draft: Reflexion recovery-channel trust boundary

**Recipient:** Reflexion maintainers (the repository README lists
`noahrshinn@gmail.com`; the repository does not publish a `SECURITY.md` file).

**Subject:** Private report: externally influenced test output is re-injected on the Reflexion recovery path

Dear Reflexion maintainers,

We are preparing an empirical software-engineering study of self-healing LLM
agents. During an isolated review of the programming generator at commit
`218cf0ef1df84b05ce379dd4a8e47f17766733a0`, we observed that test-runner
feedback is inserted verbatim into the next Reflexion generation prompt. The
recovery generator does not preserve whether the feedback came from an
internal diagnostic or an externally influenced test output.

Our experiment uses synthetic canary text only, does not access user data or
production credentials, and does not target a live deployment. In a local
reproduction, a test output containing an instruction can influence the
generated replacement function. We can provide the fixed-commit excerpt,
minimal synthetic reproduction, raw execution metadata, and a proposed
provenance-aware framing privately.

The mitigation we are evaluating is to attach an origin label at failure
capture (for example, `TOOL_OUTPUT_EXTERNAL`) and carry it through the
recovery pipeline. External or unknown content should be framed as data to
inspect, not as an instruction, while any privileged side effect should be
checked independently at the action sink.

Please let us know the preferred private channel and whether you would like a
coordinated disclosure timeline. We will not publish maintainer-specific
details or claim a maintainer acknowledgement without your confirmation.

Sincerely,

Mingyuan Xie and Zhengxun Wu

## Local audit record

- Repository: `https://github.com/noahshinn/reflexion`
- Fixed commit: `218cf0ef1df84b05ce379dd4a8e47f17766733a0`
- Affected path: `programming_runs/generators/generator_utils.py`,
  `generic_generate_func_impl`, Reflexion strategy
- Evidence environment: isolated local test harness; synthetic payloads and
  canary classification only
- Supplementary native-path evidence: 1,000 trials at the pinned commit with
  Kimi K2.6 (250 raw attack, 250 raw benign, 250 TrustOrigin-tagged attack,
  250 TrustOrigin-tagged benign); raw attack success 194/250 and tagged attack
  success 137/250. These are study evidence, not a claim about a production
  deployment.
- Disclosure status: draft prepared; not sent

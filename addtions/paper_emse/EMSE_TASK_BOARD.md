# EMSE 优先论文：具体任务表

## 2026-08-09 submission-freeze update

The author-independent paper work is complete.  The submission package is
now in refinement/freeze state.  The author names are filled as `Mingyuan
Xie` and `Zhengxun Wu` (pinyin for 谢明远 and 吴正勋), with contact emails
`jhj208436@gmail.com` and `2276815088@qq.com`.  Both authors are now marked as
corresponding authors and share the affiliation `School of Information
Management, Qingdao University of Technology, Qingdao, Shandong, China`.
Only optional ORCID fields remain open.  The older checklist rows below are
retained as a historical audit trail and are superseded by this status block.

## 2026-08-10 refinement update (current status)

The open-model reproducibility decision is now fixed: the cached local Ollama
`qwen:7b` (Qwen2-family, Q4_0, digest recorded in the provenance files) is the
paper's sensitivity anchor. Its 100 valid calls are exploratory and are not
described as a Qwen3-8B result. Qwen3-8B is not a completion claim and is not a
submission blocker.

No independent second coder is available. The paper therefore makes no
second-coder, Cohen's kappa, or inter-rater reliability claim. RQ1 is reported
as single-coder systematic multiple-case source-code analysis with an explicit
codebook, audit trail, negative/boundary cases, and a limitation statement.

The current submission package is buildable; final GitHub/Zenodo publication
and responsible-disclosure transmission remain author-controlled actions.

Completed locally in this refinement pass:

- A blinded second-coder packet remains archived at
  `evaluation/results/emse_source_analysis/second_coder/` as an optional future
  handoff. It is not treated as completed, and no agreement statistic is used
  in the paper.
- A fixed-model native Reflexion runner is available at
  `evaluation/reflexion_ollama_qwen3_anchor.py` (the filename is historical;
  the runner now supports an explicit local model argument). The accepted
  local anchor is `qwen:7b`, not a Qwen3-8B result.
- The fixed local `qwen:7b` sensitivity anchor was run and included as
  exploratory evidence. The failed Qwen3-8B pull and its invalid zero-valid-
  trial audit remain archived, but no Qwen3 result is claimed.
- RQ3 has been rewritten so the native Reflexion TrustOrigin port is the main
  provenance result.  Defense-aware, contrast, and legal-recovery screens are
  explicitly bounded supplementary evidence.  The result is compiled in the
  current 30-page PDF and visually checked on the title/RQ3/references pages.
- The staging replication package is at
  `evaluation/release/emse_recovery_channel_v1/`.  A prerelease GitHub tag and
  release `emse-recovery-channel-v1` have now been pushed; a final public
  release and Zenodo DOI still require author-controlled approval/login.
- `RESPONSIBLE_DISCLOSURE_DRAFT.md` is ready for author review.  It has not
  been sent because no authenticated private disclosure channel is available
  in this workspace; sending it requires an author-controlled email/account.

Current go/no-go: the paper source and PDF are locally buildable. The
second-coder agreement and Qwen3-8B anchor are explicitly out of scope and are
not described as completed. Public-release finalization, Zenodo DOI creation,
and actual disclosure transmission remain author-controlled external actions.

The current numerical ledger is `current_results.md`. The generated provenance artifacts are:

- `evaluation/results/emse_protocol_audit/provenance_manifest.json`
- `evaluation/results/emse_protocol_audit/model_snapshot_audit.json`
- `evaluation/results/emse_protocol_audit/gpt_endpoint_verification.json`
- `evaluation/results/emse_protocol_audit/power_analysis.json`

GPT endpoint audit result: the supplied OpenCode Go documentation specifies `/responses`, while historical GPT artifacts use `/chat/completions`. A current one-request probe received HTTP 403 with edge error code 1010 from both routes. Historical artifacts are not relabeled; the discrepancy is recorded as a reproducibility limitation. The provider catalog exposes aliases and a common catalog timestamp, but no immutable checkpoint revision.

Writing status: the abstract, introduction, methodology, RQ1--RQ3 results,
defense mechanism/evaluation, discussion, threats-to-validity, conclusion,
ethics statement, and cover letter have been rewritten around the frozen
Reflexion/AutoGen/Aider evidence.  All numerical tables are generated from
the JSON artifacts; structural reauthorization is explicitly a design
implication rather than an unrun empirical RQ4.  The final 30-page A4 PDF has
been rebuilt with the complete BibTeX sequence, rendered, and visually checked;
the build has no undefined citations/references or fatal errors.  The remaining
submission action is limited to adding ORCID identifiers if the authors choose
to provide them; the affiliation, both corresponding-author markers, and
contact emails are present in the source and compiled PDF.

Supplementary native Reflexion TrustOrigin port completed on pinned commit
`218cf0ef1df84b05ce379dd4a8e47f17766733a0`: Kimi K2.6, 1,000 total trials
(250 each for raw attack, raw benign, TrustOrigin attack, and TrustOrigin
benign). Raw attack ASR is 194/250 (0.776), TrustOrigin attack ASR is 137/250
(0.548), both benign FPRs are 0/250, and the raw-versus-tagged Fisher test is
`p=9.78e-8`. This is supplementary single-model evidence, not a claim of
general defense efficacy. The raw JSONL, CSV, summary, generated macros, and
updated protocol audit are now part of the evidence ledger.

Responsible disclosure: a private report draft is prepared at
`RESPONSIBLE_DISCLOSURE_DRAFT.md` for the Reflexion maintainer contact listed
in the repository. It has not been sent because the preferred private channel
and timing require author confirmation.

Submission-guideline check: the abstract is 214 words, the keyword list has
six entries, and a Statements and Declarations section now includes competing
interests, funding, data availability, and AI-tool disclosure.  EMSE's current
guidelines describe the review as single-blind; the cover letter no longer
claims double-blind review.  The source remains a working LaTeX submission
layout; the journal accepts LaTeX source and applies production formatting
after acceptance, but the Elsevier class should be replaced by the Springer
macro package if the editorial manager rejects the current class.

Verified build command (run from this directory): `pdflatex -interaction=nonstopmode -halt-on-error main.tex`, `bibtex main`, then the same `pdflatex` command twice.  The resulting `main.pdf` is the stable review build; `main_springer.pdf` is an abandoned class-migration test and must not be submitted.

最后更新：2026-08-09  
工作目录：`E:\ouisani\addtions\paper_emse`  
状态：`[ ]` 未开始；`[-]` 部分完成/证据不足；`[x]` 已满足验收条件；`[!]` 投稿阻塞项

## 0. 已冻结的论文主线

> **恢复路径会把外部错误内容重新注入 LLM，从而形成一条独立于正常输入路径的提示注入通道；来源标记能够降低、但不能消除风险；结构性重新授权是补充保护，而不是与内容级攻击并列的第二条论文主线。**

范围决定：

- RQ1–RQ3 围绕同一条内容级因果链组织：`外部错误内容 → 原生恢复路径重新注入 → 高信任解释/执行 → TrustOrigin 缓解及其上限`。
- 原生框架端到端证据是主要证据；作者构造的 LangGraph recovery node 和自建系统只用于机制归因。
- topology mutation / `ReauthorizationGate` 压缩为“设计启示 + 补充验证”，不再与内容级攻击各占半篇。
- 只有通过第 8 节全部 EMSE 闸门后才做 EMSE 模板精修；约完成 70% 时改投 JSS。

## 1. 当前证据审计（执行起点）

| 项目 | 当前状态 | 审计结论 | 下一动作 |
|---|---|---|---|
| Reflexion 原生路径 | `[-]` | 已使用其 prompt/解析路径；最新结果声明 `100/payload`，但汇总中 attack 有效样本仅 284、benign 有效样本为 0，论文仍写旧的 `10/payload` | 修复失败/断点续跑，使用可披露 snapshot 完整重跑，自动核对原始日志 |
| AutoGen | `[-]` | 有 5 payload × 100 × attack/benign 的结果，但脚本主要重建 `role=tool` 消息序列 | 改为安装固定版本并驱动真实工具异常、框架事件循环和重试路径 |
| LangGraph | `[-]` | 使用公开 API，但核心 recovery node 为作者构造；当前 recovery 数据仅 `5/payload` | 仅保留为机制实验，不计入“3 个原生恢复路径” |
| 第三个原生框架 | `[ ]` | 尚无可计入的完整端到端证据 | 在 MetaGPT 与 Aider 中做 1 天 feasibility spike，选择稳定者 |
| TrustOrigin 因果消融 | `[ ]` | 现有实验未形成同一 payload 的完整 forward/recovery × role × provenance 析因设计 | 预注册主比较并运行完整矩阵 |
| 官方模型复现 | `[!]` | 多个结果使用不可验证的 `gpt-5.6-luna`，日志缺 snapshot/top-p | 选 3 个官方可披露 snapshot 全量复现主实验 |
| 防御感知攻击 | `[-]` | 现有 adaptive 脚本包含模拟抽样/单轮结果，不能作为真实多轮模型证据 | 5 类真实攻击，多轮运行并保留逐轮 transcript |
| 合法恢复任务 | `[ ]` | 现稿主要报告延迟和少量 benign trial | 建立 200–500 任务集，测误报、恢复率和任务质量 |
| RQ1 方法 | `[-]` | 有 6 框架和固定 commit 的雏形，仍强称 Grounded Theory；缺完整抽样流程、编码手册及边界例 | 重构为 systematic multiple-case source-code analysis with qualitative coding |
| 自动统计/复现 | `[-]` | 脚本和 JSON 分散，论文数字存在新旧结果不一致 | 建唯一 manifest、统一结果生成器、干净机器复现 |

## 2. P0：投稿成立所需实验

### 2.1 先冻结实验协议

| ID | 状态 | 任务 | 具体产物 | 验收条件 | 依赖 |
|---|---|---|---|---|---|
| P0-00 | `[!]` | 写实验预注册/协议 | `evaluation/emse_protocol.yaml` + `paper_emse/experiment_protocol.md` | 固定主假设、主比较、排除规则、成功判据、功效分析、随机种子、日期；探索性比较单列 | 无 |
| P0-01 | `[!]` | 固定运行配置 | `evaluation/model_manifest.json` | 每个模型记录 provider、公开 model ID/snapshot、API、temperature、top-p、max tokens、system prompt 哈希、运行日期 | P0-00 |
| P0-02 | `[!]` | 区分“真实执行”与“调用意图” | classifier schema + 人工标注说明 | 每条 trial 同时记录 `intent_success` 与 `executed_success`；主结果以真实工具/代码执行为准，意图结果单列 | P0-00 |
| P0-03 | `[ ]` | 样本量/功效分析 | `evaluation/power_analysis.*` | 主要实验单元默认 ≥100；若少于 100，文中引用预先完成的功效分析与停止规则 | P0-00 |

### 2.2 三个真实原生恢复路径

| ID | 状态 | 任务 | 最小设计 | 验收条件 | 论文用途 |
|---|---|---|---|---|---|
| P0-10 | `[-]` | Reflexion 完整重跑 | 5 payload × attack/benign × ≥100；固定官方 snapshot | 调用真实 retry path；两臂仅 payload 是否含攻击不同；有效样本达到计划值；0 个静默 API 错误；原始 transcript 可追溯 | 原生框架 1，主要证据 |
| P0-11 | `[-]` | AutoGen 改为真正框架 E2E | 5 payload × attack/benign × ≥100；真实 tool failure → framework recovery/retry → next LLM turn | 从 AutoGen 固定版本入口启动；日志包含框架事件/消息对象；禁止直接手工拼 OpenAI messages 代替框架执行 | 原生框架 2，主要证据 |
| P0-12 | `[!]` | 选择第三框架 | MetaGPT 与 Aider 各做 10–20 次 smoke test | 选择能稳定触发完整 retry、可固定 commit、可自动判断结果者；在 decision log 记录淘汰理由 | 原生框架 3 的前置 |
| P0-13 | `[!]` | 第三框架正式 E2E | 5 payload × attack/benign × ≥100 | 满足与 P0-11 相同的“框架原生路径”证据；完整原始日志和环境锁定 | 原生框架 3，主要证据 |
| P0-14 | `[ ]` | 跨框架协议一致性检查 | protocol conformance report | 三框架具有相同攻击语义、匹配良性对照、相同成功层级定义；不可统一处预先声明 | P0-10–13 |

每个框架的良性对照必须与攻击臂保持任务、错误类型、错误长度区间、恢复位置、模型参数和工具权限一致，仅移除攻击指令。普通代码生成、普通工具重试和预期修复不能被计为攻击成功。

### 2.3 信任来源的因果消融

| ID | 状态 | 任务 | 因子/规模 | 验收条件 |
|---|---|---|---|---|
| P0-20 | `[!]` | 构造同 payload 析因矩阵 | `path ∈ {forward,recovery}` × `role ∈ {user/tool,system/high-trust}` × `provenance ∈ {absent,trusted,external-untrusted}`；每 cell ≥100 或依功效分析 | 同一 payload 文本逐字一致，仅操纵指定因子；记录所有 prompt/message diff |
| P0-21 | `[ ]` | 加入 recovery position | 失败后第 1 次重试 vs 后续重试/多轮位置 | 至少覆盖 immediate recovery 与 delayed recovery；位置作为预定义协变量或独立因子 |
| P0-22 | `[ ]` | 统计因果效应 | 分层/混合效应 logistic 模型，framework、model、payload 分层 | 报告主效应与预定义交互项、OR/CI/p；同时给每 framework × model × payload 原始比例，不只 pooled ASR |
| P0-23 | `[ ]` | 负向/安慰剂检查 | 无注入 benign error、等长无意义文本、正常 forward tool output | 安慰剂不应产生 canary；若产生则修订 success classifier 并重跑受影响分析 |

预定义主要比较：

1. 相同 role/provenance 下，`recovery` 对 `forward` 的效应。
2. 相同 path/role 下，`external-untrusted` 标记对 `absent` 的效应。
3. `recovery × provenance` 交互：来源标记是否降低恢复通道效应。
4. `recovery × high-trust role` 交互：高信任 framing 是否放大恢复通道效应。

### 2.4 三个官方可披露模型 snapshot

| ID | 状态 | 任务 | 验收条件 |
|---|---|---|---|
| P0-30 | `[!]` | 选定模型 | 至少 3 个官方 provider 可公开 model ID/snapshot；不得使用内部别名作为论文身份 |
| P0-31 | `[ ]` | 跨模型复现三框架主实验 | 每个模型至少覆盖每框架的预定义主比较；预算不足时先功效分析，不事后缩样本 |
| P0-32 | `[ ]` | 输出非 pooled 表 | framework × model × payload 均给 n、success、ASR、Wilson 95% CI；另给分层综合效应 |
| P0-33 | `[ ]` | 版本与日期锁定 | 原始日志逐条带 model response metadata、运行日期、参数和 prompt hash；论文表与 manifest 一致 |

## 3. P1：防御上限与实用性

| ID | 状态 | 任务 | 建议规模 | 验收条件 |
|---|---|---|---|---|
| P1-10 | `[-]` | 5 类防御感知攻击 | authority forgery、role assumption、delimiter/format escape、indirect/encoded instruction、multi-turn persistence；每类 ≥100 | 真实模型调用，不用预设概率模拟；逐轮 transcript；同模型/框架下与 naive attack 配对 |
| P1-11 | `[ ]` | 多轮自适应 | 最少 3 轮：观察防御响应 → 改写 → 再注入 | 报告首轮与最终 ASR、达到成功所需轮数、失败原因；攻击者可见信息明确 |
| P1-12 | `[ ]` | TrustOrigin 上限分析 | 与 P0-20 相同 payload/配置 | 结论限定为“降低但不消除”；不得从单模型/单框架外推确定性保证 |
| P1-20 | `[!]` | 建合法恢复任务集 | 200–500 个任务，覆盖编译错、测试失败、工具超时、格式错、权限拒绝、外部内容错误 | 任务来源、许可、去重、难度分层、预期恢复结果全部记录 |
| P1-21 | `[ ]` | 防御可用性评估 | baseline vs TrustOrigin；必要时加 ReauthorizationGate | 报告误报率、恢复成功率、任务完成率/质量、重试次数、token、端到端延迟；不只报告 machinery overhead |
| P1-22 | `[ ]` | 质量盲评 | 对开放式任务做双人盲评/裁决 | 提供 rubric、样本量、agreement、分歧处理和原始匿名评分 |

## 4. P2：ReauthorizationGate 的保留条件

| ID | 状态 | 任务 | 验收条件 | 决策 |
|---|---|---|---|---|
| P2-10 | `[-]` | 将 RQ4 降级 | 摘要/引言不再将 topology mutation 作为并列主线；正文移入设计启示或补充研究 | 无论移植结果如何都执行 |
| P2-11 | `[-]` | 多框架移植 gate | 若保留实证主张，则除自建系统外至少 2–3 个真实框架，使用其公开扩展点 | 达标才保留“跨框架适用”表述 |
| P2-12 | `[ ]` | 合法恢复兼容性 | 与 P1-20 联动，测被 gate 拒绝的合法恢复、任务质量和恢复率 | 无可用性证据则只写设计建议，不写通用有效性结论 |

## 5. RQ1 方法重构

统一方法名称：**Systematic multiple-case source-code analysis with qualitative coding**。

| ID | 状态 | 任务 | 产物/验收条件 |
|---|---|---|---|
| RQ1-01 | `[!]` | 重写方法定位 | 全文删除“完整 Grounded Theory / open-axial-selective 已充分实施”的强主张，统一新名称 |
| RQ1-02 | `[ ]` | 定义候选总体 | 写数据库/平台、搜索式、star/活跃度阈值、语言/领域边界及候选总数 |
| RQ1-03 | `[ ]` | 固定检索日期 | 明确到 YYYY-MM-DD，并保存搜索结果快照/CSV |
| RQ1-04 | `[ ]` | 纳入排除流程 | 候选 → 去重 → 标题摘要筛选 → 源码筛选 → 最终 6 个；每个排除项带理由，形成 flow diagram |
| RQ1-05 | `[ ]` | 论证六框架可比性 | 统一分析单位为“将失败内容带入后续 LLM 决策的恢复循环”；列出功能、输入来源、恢复触发、消息角色 |
| RQ1-06 | `[-]` | 固定版本 | 每框架记录 repo URL、commit SHA、版本、抓取日期；论文、脚本和 artifact 三处一致 |
| RQ1-07 | `[ ]` | 完整编码手册 | codebook 包含定义、包含/排除规则、决策树、正例、负例、边界例和不可判定处理 |
| RQ1-08 | `[ ]` | 高信任 frame 严格定义 | 用可观察标准定义 system/developer role、role-play authority、强制 recovery envelope；普通 tool/user history 不自动算高信任 |
| RQ1-09 | `[ ]` | 双人独立编码与分歧处理 | 先独立编码，再计算 agreement，最后由规则化讨论/第三人裁决；保留分歧日志 |
| RQ1-10 | `[ ]` | 增加负例/边界案例 | 至少一个明确负例、两个边界例；说明为什么 OpenHands/LangGraph 不满足完整 trampoline（若结论不变） |
| RQ1-11 | `[ ]` | 可复核证据包 | 每个判断链接到固定 commit 的文件/行、代码摘录及 coder memo；自动检查链接/哈希 |

## 6. 自动统计与可复现性

| ID | 状态 | 任务 | 验收条件 |
|---|---|---|---|
| REP-01 | `[!]` | 建立单一实验 manifest | 每次 run 有唯一 run ID，包含 git commit、框架 commit、模型 snapshot、参数、样本计划、日志路径、状态 |
| REP-02 | `[!]` | 原始日志 schema | JSONL 每条含 trial ID、framework/model/payload/arm/factors、完整输入输出、tool event、success 两层标签、错误状态 |
| REP-03 | `[!]` | 一键生成全部数字 | 一个命令从 raw JSONL 生成 CSV/LaTeX 表/图/统计附录；禁止手填 ASR、CI、p 值 |
| REP-04 | `[ ]` | 完整性检查 | 计划 n = 有效 n + 明确失败 n；禁止丢弃失败调用；排除规则在分析前固定并输出审计表 |
| REP-05 | `[ ]` | 统计报告 | Wilson CI；预定义 Fisher/回归比较；多重比较策略；效应量；framework/model/payload 分层结果 |
| REP-06 | `[ ]` | classifier 验证 | 分层抽样双人标注；报告混淆矩阵、Cohen's κ、争议裁决；意图与执行分开验证 |
| REP-07 | `[!]` | 干净机器复现 | 锁定依赖、提供无密钥 dry-run 与有密钥主实验说明；另一台干净机器从 clone 到主要表格成功 |
| REP-08 | `[ ]` | 论文数字一致性 CI | 检查 `.tex` 中 n/ASR/CI/p/model/commit 与生成物一致，不一致即失败 |
| REP-09 | `[ ]` | 匿名 artifact | 去除密钥/本机绝对路径/不可披露模型别名；README 列明成本、时长、预期输出和已知限制 |

## 7. 写作重构（实验冻结后执行）

| ID | 状态 | 文件 | 修改内容 | 前置条件 |
|---|---|---|---|---|
| W-01 | `[!]` | `abstract.tex` | 改成单主线；用 3 个原生框架结果承载 exploitability；RQ4 仅一句设计启示 | P0 完成 |
| W-02 | `[!]` | `introduction.tex` | 重写 gap、RQ 和 contribution；删除“两种并列攻击类”的 framing | P0 完成 |
| W-03 | `[ ]` | `methodology.tex` | 写新 RQ1 方法、预注册比较、析因设计、模型快照和执行/意图区分 | RQ1、P0-00–33 |
| W-04 | `[ ]` | `results.tex` | RQ1 流程图、编码定义、负例/边界例、固定 commit 表 | RQ1 完成 |
| W-05 | `[ ]` | `severity.tex` | 三框架原生 E2E + matched controls + 分层统计；LangGraph 自建 node 降为机制实验 | P0-10–33 |
| W-06 | `[ ]` | `defense_eval.tex` | 完整 TrustOrigin 消融、5 类多轮自适应、合法恢复质量 | P0-20–23、P1 |
| W-07 | `[ ]` | `structural_defense*.tex` | 合并压缩为设计启示/补充验证；无 2–3 框架证据则删除跨框架强结论 | P2 |
| W-08 | `[ ]` | `discussion.tex` / `threats_to_validity.tex` | 明确来源标记是概率缓解；区分框架、模型、payload 外推边界 | 全实验冻结 |
| W-09 | `[ ]` | `conclusion.tex` | 只保留被原生实验直接支持的结论 | 全实验冻结 |
| W-10 | `[ ]` | `cover_letter_emse.tex` | 最后更新，不提前用未达标结果宣传 | EMSE 闸门通过 |

## 8. 第二篇投稿闸门（Go / No-Go）

以下每项必须有可点击/可运行证据，不接受“脚本已写但未完整运行”。

| 闸门 | 状态 | 通过标准 | 证据位置 |
|---|---|---|---|
| G1 三个真实原生恢复路径 | `[!]` | 3/3 均完整执行固定版本框架自己的 retry/recovery/tool-error 路径 | 待填 |
| G2 匹配良性对照 | `[!]` | 每个主要攻击单元均有唯一变量为攻击内容的对照 | 待填 |
| G3 因果消融 | `[!]` | role × provenance × forward/recovery × recovery position 完成且主比较预定义 | 待填 |
| G4 模型身份可披露 | `[!]` | ≥3 个官方 snapshot，日志/论文/manifest 一致 | 待填 |
| G5 数字全自动生成 | `[!]` | 从 raw logs 一键重建所有表、图、CI、p 值 | 待填 |
| G6 干净机器复现 | `[!]` | 第二台机器成功运行主要实验并生成核心结果 | 待填 |
| G7 结论不过界 | `[!]` | 删除/降级所有没有原生实验直接支持的强结论 | 待填 |

投稿决策：

- **EMSE Go：** G1–G7 全部为 `[x]`，再进入格式化、页数压缩、匿名化和 cover letter 阶段。
- **JSS Go：** 约 70% 完成，或缺少完整因果消融/第三原生框架/干净机器复现之一；按 JSS 证据强度重写，不伪装为 EMSE-ready。
- **No-Go：** 模型身份不可披露、主要数字不能由原始日志生成，或原生路径不足 2 个。

## 9. 推荐执行顺序（关键路径）

| 批次 | 任务 | 退出条件 |
|---|---|---|
| Sprint 0 | P0-00–03、P0-12、REP-01–02 | 协议/模型/第三框架/日志 schema 冻结后才能烧 API 预算 |
| Sprint 1 | P0-10、P0-11、P0-13 | 三框架 smoke test 先通过，再各跑 ≥100/单元 |
| Sprint 2 | P0-20–23、P0-30–33 | 完整析因与三模型结果齐备 |
| Sprint 3 | P1-10–22、RQ1-01–11 | 得到防御上限、合法恢复质量和可审计 case study |
| Sprint 4 | REP-03–09、P2-10–12 | 数字自动生成、干净机复现、RQ4 降级决策完成 |
| Sprint 5 | W-01–10、G1–G7 | 先过闸门，再做 EMSE 格式化与投稿包 |

## 10. 每日更新规则

- 只有验收条件全部满足才能将任务改为 `[x]`；代码写完但未跑完仍为 `[-]`。
- 每次实验先更新 protocol/manifest，再运行；禁止看结果后修改主要成功判据。
- API 错误、超时、拒答和解析失败必须留在 raw log，并按预注册规则处理。
- 新结果进入论文前，先通过 REP-04 与 REP-08；旧数字不得手工覆盖。
- 每完成一个闸门，在第 8 节填入脚本、manifest、raw log、生成表和复现记录的相对路径。

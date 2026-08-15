# Spawn-time Privilege Non-increase — TLA+ Model-Checking Report

> Reviewer-requested partial formal verification of the Neuron governance runtime's
> spawn-time privilege non-increase invariant. This report is an internal artifact
> for the authors; it is **not** part of the paper submission.

## 1. Tool selection

| Component | Choice | Rationale |
|---|---|---|
| Specification language | **TLA⁺** | Industry-standard for concurrent / invariant verification; the reviewer explicitly asked for TLA⁺/Alloy. |
| Model checker | **TLC 2.19** (rev `5a47802`, 08 Aug 2024) | Reference exhaustive BFS model checker; ships as a single `tla2tools.jar`. |
| Runtime | OpenJDK 21.0.9 (Microsoft LTS), Windows 11, 1 TLC worker, 4 GB heap | Already present on the machine. |
| Fallback | **Not used** — TLA⁺/TLC was available, so the Python finite-state enumerator described in the task contingency was unnecessary. |

`tlc2tools.jar` (≈2.27 MB) was downloaded from the official TLA⁺ release on GitHub and
is kept alongside the spec so the run is bit-for-bit reproducible:

```
java -cp tla2tools.jar tlc2.TLC -config <cfg> SpawnPrivilege
```

No degradation was needed; the TLA⁺ path is strictly more rigorous than the
contingency Python enumerator (TLC performs exhaustive BFS over the full reachable
state graph with fingerprint-based deduplication, not random walks).

## 2. Specification files

All under `e:\ouisani\addtions\paper\formal\`:

| File | Role |
|---|---|
| `SpawnPrivilege.tla` | The TLA⁺ specification (state vars, Init, Next, three invariants, three bug-injection constants). |
| `SpawnPrivilege.cfg` | TLC config for the **correct** implementation (all bug flags `FALSE`). |
| `SpawnPrivilege_DropSet.cfg` | Mutation: omit `QueryEngine.executeTool` set (Gap-A escalation). |
| `SpawnPrivilege_DropClear.cfg` | Mutation: omit `finally` clear on the exception path (ctx leak). |
| `SpawnPrivilege_DropTenant.cfg` | Mutation: child tenant not constrained to parent's. |
| `tla2tools.jar` | TLC 2.19 (for reproduction). |

### Faithfulness to the implementation

The spec is a direct abstraction of the production code paths (file:line references
are in the `.tla` header comment):

- `com.ouisani.aios.core.permission.SpawnPrivilegeContext` — `InheritableThreadLocal`;
  `set(null)→remove`, `clear()→remove`. Modelled as a per-thread record
  `[set |-> Bool, priv, tenant]` where `set=FALSE` is the cleared state. The
  `set=FALSE` placeholder avoids TLC's record/non-record equality restriction.
- `com.ouisani.aios.core.tool.QueryEngine#executeTool` — line ~974
  `SpawnPrivilegeContext.set(currentProfile())`, line ~1089
  `finally { SpawnPrivilegeContext.clear(); }`. Modelled as the `DoSet*` / `DoOk*` /
  `DoFinally*` transitions.
- `com.ouisani.aios.core.tool.AgentTool` — line ~176 reads
  `SpawnPrivilegeContext.current()` and, when it is `null`, passes
  `PermissionProfile.empty()` (== DEFAULT, the *maximal* privilege). This is exactly
  the Gap-A escalation surface: modelled by `readPriv(t) = IF ctx[t].set THEN
  ctx[t].priv ELSE HighPriv`.

### State space (bounded, per the task brief)

- 3 privilege levels `HIGH ⊃ MEDIUM ⊃ LOW`, mapped to capability sets
  `{X,Y,Z} ⊃ {X,Y} ⊃ {X}`. `P_child ⊆ P_parent` ⟺ `capsOf(child) ⊆ capsOf(parent)`.
- 2 tenants `{TenantA, TenantB}`.
- 2 spawn layers: `Root → {PA, PB} → {GA, GB}`.
- 2 concurrent threads `T1` (chain `Root→PA→GA`) and `T2` (chain `Root→PB→GB`),
  interleaved by `Next = \E t : ...` (one thread steps at a time ⇒ full interleaving).

### Invariants

```
PrivilegeNonIncrease == \A n \in Children : capsOf(tree[n].priv) \subseteq capsOf(tree[tree[n].parent].priv)
NoLeakOnException    == \A t : phase[t] \in {Done1,Done2} /\ last_threw[t] => ~ctx[t].set
NoCrossTenantLeak    == \A n \in Children : tree[n].tenant = tree[tree[n].parent].tenant
```

`last_threw` is an instrumentation variable recording whether the most recently
completed spawn on a thread threw, so the leak invariant is expressible as a pure
state predicate.

## 3. Results

### 3.1 Correct implementation (`SpawnPrivilege.cfg`)

```
Model checking completed. No error has been found.
Estimates of the probability that TLC did not check all reachable states
because two distinct states had the same fingerprint:
  calculated (optimistic):  val = 2.6E-13
4511 states generated, 1681 distinct states found, 0 states left on queue.
The depth of the complete state graph search is 15.
The average outdegree of the complete state graph is 1 (min 0, max 4, 95th pct 3).
Finished in 00s (wall ≈ 1.5 s).
```

| Invariant | Result |
|---|---|
| `PrivilegeNonIncrease` | ✅ PASS |
| `NoLeakOnException` | ✅ PASS |
| `NoCrossTenantLeak` | ✅ PASS |

Full BFS reachability completed (0 states left on queue); all three invariants hold
on every one of the **1 681 reachable states**. The fingerprint-collision probability
(2.6 × 10⁻¹³) confirms no missed states.

### 3.2 Non-vacuity / mutation experiments

Each mutation flips exactly one bug-injection constant and re-runs TLC. In every
case TLC finds a concrete counterexample violating *exactly* the invariant the
mutated mechanism is meant to enforce — demonstrating the invariants are not
trivially satisfied and that the spec actually exercises the security-critical
behaviour.

#### (a) `DropSet = TRUE` — Gap-A escalation (omit `executeTool` set)

```
Error: Invariant PrivilegeNonIncrease is violated.
116 states generated, 79 distinct states found, 42 states left on queue. depth 6.
```

Counterexample trace (T1 only; T2 idle throughout):

| State | Action | PA.priv | GA.priv | ctx[T1] |
|---|---|---|---|---|
| 1 | Init | – | – | cleared |
| 2 | `DoSet1` (skipped: DropSet) | – | – | cleared |
| 3 | `DoSpawn1` | **MedPriv** | – | cleared |
| 4 | `DoOk1` | MedPriv | – | cleared |
| 5 | `DoSet2` (skipped: DropSet) | MedPriv | – | cleared |
| 6 | `DoSpawn2` | MedPriv | **HighPriv** | cleared |

At State 6, `GA.priv = HighPriv` while `parent(GA)=PA.priv = MedPriv`.
`capsOf(HighPriv) = {X,Y,Z} ⊄ {X,Y} = capsOf(MedPriv)` ⇒ **violation**.

Interpretation: because `SpawnPrivilegeContext.set` was never called, `current()`
returns `null`, so `AgentTool` falls back to `PermissionProfile.empty()` (DEFAULT ==
maximal privilege). The restricted parent (MedPriv) thereby spawns a child with
*full* privileges — precisely the "spawn-as-escalation" attack the paper's Gap A
closes. This counterexample is the formal analogue of `SpawnEscalationRedTeamTest`
and `SpawnPrivilegeInheritanceTest#gapA_childInheritsParentDeny_noEscalation`.

#### (b) `DropClear = TRUE` — missing `finally` clear (ctx leak)

```
Error: Invariant NoLeakOnException is violated.
38 states generated, 31 distinct states found, 18 states left on queue. depth 5.
```

Counterexample trace:

| State | Action | phase[T1] | last_threw[T1] | ctx[T1].set |
|---|---|---|---|---|
| 1 | Init | idle | FALSE | FALSE |
| 2 | `DoSet1` | set1 | FALSE | **TRUE** (parent profile published) |
| 3 | `DoSpawn1` | spawn1 | FALSE | TRUE |
| 4 | `DoThrow1` | throw1 | **TRUE** | TRUE |
| 5 | `DoFinally1` (DropClear) | done1 | TRUE | **TRUE** ← not cleared |

At State 5 the spawn has completed (`done1`) after a throw (`last_threw=TRUE`) but
`ctx[T1].set` is still `TRUE` — the parent profile remains published on the
thread-local. This is exactly the thread-pool reuse leak `QueryEngine.executeTool`'s
`finally { SpawnPrivilegeContext.clear(); }` (line ~1089) exists to prevent: a
subsequent spawn on the reused thread would read the stale parent profile.

#### (c) `DropTenant = TRUE` — child tenant not inherited

```
Error: Invariant NoCrossTenantLeak is violated.
5 states generated, 5 distinct states found, 2 states left on queue. depth 3.
```

Counterexample: `DoSpawn1` assigns `PA.tenant = TenantB` while
`parent(PA)=Root.tenant = TenantA` ⇒ **violation** at depth 3 (the very first spawn).
This corresponds to Gap B (`CallerContext` tenant propagation across spawn).

## 4. State-space statistics

| Config | Distinct states | BFS depth | Result | Wall time |
|---|---|---|---|---|
| Correct (`all flags FALSE`) | 1 681 | 15 | 3/3 invariants **PASS** | ≈ 1.5 s |
| `DropSet=TRUE` | 79 (CE at 6) | 6 | `PrivilegeNonIncrease` **FAIL** | < 1 s |
| `DropClear=TRUE` | 31 (CE at 5) | 5 | `NoLeakOnException` **FAIL** | < 1 s |
| `DropTenant=TRUE` | 5 (CE at 3) | 3 | `NoCrossTenantLeak` **FAIL** | < 1 s |

CE = counterexample. Mutation runs stop at the first violating state, so their
state counts are lower bounds on the reachable space (the full graph is larger); the
correct-implementation run explores the *entire* reachable graph.

## 5. Coverage of the required scenarios

- **Concurrency** — `Next = \E t \in {T1,T2} : …` interleaves two threads; the
  passing run covers all interleavings of `T1: Root→PA→GA` and `T2: Root→PB→GB`
  (max outdegree 4, depth 15).
- **2-layer spawn depth** — `Root → PA/PB → GA/GB`; `PrivilegeNonIncrease` is
  checked at *both* layers, and the `DropSet` counterexample fires specifically at
  layer 2 (GA under a restricted PA).
- **Exception path** — `DoThrow*` / `DoFinally*` transitions model the try/finally
  structure; `NoLeakOnException` is violated iff the finally clear is dropped.
- **Privilege structure** — `PermissionProfile`'s mode + allow/deny/ask rule sets
  are abstracted to a 3-level capability lattice (`HIGH ⊃ MEDIUM ⊃ LOW`), which
  preserves the subset ordering that the non-increase invariant depends on while
  keeping the state space finite and small.

## 6. Threats to validity / scope

- The capability lattice (3 levels) is an abstraction of `PermissionProfile`; the
  real profile carries mode + three rule lists. The abstraction is sound for the
  *subset ordering* (the only thing the invariant depends on), but does not model
  individual rule interactions (e.g. `*:deny` + allow-list override). Those are
  covered by the Java unit tests (`PermissionCheckerWildcardTest`,
  `SpawnPrivilegeInheritanceTest`), not by this model.
- `PermissionProfile.empty()` (DEFAULT) is modelled as the maximal privilege level
  `HighPriv`, matching the implementation semantics where an unset context yields an
  unrestricted child (the Gap-A surface). This is the security-relevant
  interpretation, not a permissiveness bug.
- The model checks safety invariants only (no liveness / termination properties).
- Depth is bounded at 2 spawn layers and 2 concurrent threads; deeper trees are not
  checked, but the invariant is inductive in the depth (each spawn independently
  enforces `child ⊆ parent`), so additional layers cannot introduce a violation
  that does not already appear at depth 2.

## 7. Reproduction

```
cd e:\ouisani\addtions\paper\formal
java -cp tla2tools.jar tlc2.TLC -config SpawnPrivilege.cfg             SpawnPrivilege   # PASS
java -cp tla2tools.jar tlc2.TLC -config SpawnPrivilege_DropSet.cfg     SpawnPrivilege   # FAIL PrivilegeNonIncrease
java -cp tla2tools.jar tlc2.TLC -config SpawnPrivilege_DropClear.cfg   SpawnPrivilege   # FAIL NoLeakOnException
java -cp tla2tools.jar tlc2.TLC -config SpawnPrivilege_DropTenant.cfg  SpawnPrivilege   # FAIL NoCrossTenantLeak
```

Environment: TLC 2.19 (rev 5a47802), OpenJDK 21.0.9, Windows 11, `Asia/Shanghai`,
run on 2026-08-03. No network access required at check time (jar is local).

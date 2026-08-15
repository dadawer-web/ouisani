------------------------------- MODULE SpawnPrivilege -------------------------------
(*******************************************************************************
  Spawn-time privilege non-increase for the Neuron governance runtime.

  Faithful abstraction of the production code paths:
    - com.ouisani.aios.core.permission.SpawnPrivilegeContext
        InheritableThreadLocal<PermissionProfile>;  set / clear / current
        (set(null) -> remove ; clear() -> remove ; called in finally)
    - com.ouisani.aios.core.tool.QueryEngine#executeTool
        line ~974: SpawnPrivilegeContext.set(permissionChecker.currentProfile())
        line ~1089: finally { SpawnPrivilegeContext.clear(); }   (thread-pool leak guard)
    - com.ouisani.aios.core.tool.AgentTool
        line ~176: PermissionProfile parentProfile = SpawnPrivilegeContext.current();
                   new QueryEngine(..., parentProfile == null ? empty() : parentProfile)
        i.e. when ctx is cleared/unpublished the child receives PermissionProfile.empty()
        (== DEFAULT, modelled here as the maximal privilege HIGH), which is exactly the
        Gap-A escalation surface that SpawnPrivilegeContext closes.

  Modelled state space (bounded for TLC):
    - 3 privilege levels  HIGH \supset MEDIUM \supset LOW  (capability sets)
    - 2 tenants           {TenantA, TenantB}
    - 2 spawn layers      Root -> PA/PB  ->  GA/GB
    - 2 concurrent threads T1 (Root->PA->GA) and T2 (Root->PB->GB)

  Safety invariants checked:
    PrivilegeNonIncrease : \A spawned n # Root : caps(n) \subseteq caps(parent(n))
    NoLeakOnException    : after a spawn that threw, the thread's ctx is cleared
    NoCrossTenantLeak    : \A spawned n # Root : tenant(n) = tenant(parent(n))

  Non-vacuity / mutation constants (so TLC actually exercises the invariants):
    DropSet    : skip QueryEngine.executeTool set  -> Gap-A escalation (child gets
                 DEFAULT/HIGH instead of the restricted parent profile)
    DropClear  : skip finally clear on the exception path -> stale ctx leak
    DropTenant : child tenant chosen freely instead of inheriting parent's
 *******************************************************************************)

EXTENDS Integers, Sequences, FiniteSets, TLC

CONSTANTS
    HighPriv, MedPriv, LowPriv,
    TenantA, TenantB,
    CapX, CapY, CapZ,
    RootId, PA, PB, GA, GB,
    T1, T2,
    DropSet, DropClear, DropTenant

VARIABLES
    ctx,         (* SpawnPrivilegeContext value per thread: a CtxRec (always a record) *)
    tree,        (* spawn tree: node -> [spawned, parent, priv, tenant]                 *)
    phase,       (* per-thread spawn state-machine phase                                 *)
    last_threw   (* per-thread: did the most recent spawn throw? (instrumentation)      *)

-------------------------------------------------------------------------------
(* Basic finite domains                                                       *)
-------------------------------------------------------------------------------
Privs    == {HighPriv, MedPriv, LowPriv}
Tenants  == {TenantA, TenantB}
Caps     == {CapX, CapY, CapZ}
Nodes    == {RootId, PA, PB, GA, GB}
Threads  == {T1, T2}
Spawnable== {PA, PB, GA, GB}

(* Capability sets per privilege level:  HIGH \supset MEDIUM \supset LOW.
   This is the lattice interpretation of PermissionProfile: a higher level
   subsumes all capabilities of a lower level, so P_child \subseteq P_parent
   is exactly NoMorePriv(child, parent). *)
capsOf(p) ==
    IF p = HighPriv THEN {CapX, CapY, CapZ}
    ELSE IF p = MedPriv  THEN {CapX, CapY}
    ELSE IF p = LowPriv  THEN {CapX}
    ELSE Caps   (* defensive *)

NoMorePriv(q, p) == capsOf(q) \subseteq capsOf(p)

(* Thread-local context value.  Always a record so TLC never compares a record
   against a non-record:  [set |-> TRUE,  priv, tenant] = published profile;
                         [set |-> FALSE, priv, tenant] = cleared (priv/tenant
   are placeholders ignored when set=FALSE).  Mirrors SpawnPrivilegeContext
   semantics:  set()  -> [set |-> TRUE, ...] ;  clear() -> ClearedCtx. *)
ClearedCtx == [set |-> FALSE, priv |-> HighPriv, tenant |-> TenantA]

-------------------------------------------------------------------------------
(* Spawn plan: each thread runs a 2-deep chain; the two threads are concurrent.*)
-------------------------------------------------------------------------------
ParentOf(n) ==
    IF n = PA \/ n = PB THEN RootId
    ELSE IF n = GA THEN PA
    ELSE IF n = GB THEN PB
    ELSE RootId                      (* RootId's nominal parent is itself *)

ThreadOf(n) ==
    IF n = PA \/ n = GA THEN T1
    ELSE IF n = PB \/ n = GB THEN T2
    ELSE T1

First(t)  == IF t = T1 THEN PA ELSE PB
Second(t) == IF t = T1 THEN GA ELSE GB

(* Per-thread phases.  A thread sequentially executes:
   Idle -> Set1 -> Spawn1 -> (Ok1 | Throw1 -> Finally1) -> Done1
        -> Set2 -> Spawn2 -> (Ok2 | Throw2 -> Finally2) -> Done2 (terminal)      *)
P_Idle   == "idle"
P_Set1   == "set1"
P_Spawn1 == "spawn1"
P_Throw1 == "throw1"
P_Done1  == "done1"
P_Set2   == "set2"
P_Spawn2 == "spawn2"
P_Throw2 == "throw2"
P_Done2  == "done2"
Phases   == {P_Idle, P_Set1, P_Spawn1, P_Throw1, P_Done1,
             P_Set2, P_Spawn2, P_Throw2, P_Done2}

-------------------------------------------------------------------------------
(* Derived views                                                              *)
-------------------------------------------------------------------------------
Spawned(n)   == tree[n].spawned
Children     == {n \in Nodes \ {RootId} : Spawned(n)}
profileOf(n) == [set |-> TRUE, priv |-> tree[n].priv, tenant |-> tree[n].tenant]

(* What AgentTool reads as the inheritable parent profile.
   Cleared ctx -> DEFAULT / empty() -> modelled as maximal privilege HighPriv. *)
readPriv(t) ==
    IF ctx[t].set THEN ctx[t].priv
    ELSE HighPriv

Vars == <<ctx, tree, phase, last_threw>>

-------------------------------------------------------------------------------
(* Initial state: only Root exists, with maximal privilege and tenant A.      *)
(* Both threads idle, both ctx cleared, no throws.                            *)
-------------------------------------------------------------------------------
Init ==
    /\ tree = [n \in Nodes |->
                IF n = RootId
                THEN [spawned |-> TRUE,  parent |-> RootId,
                      priv |-> HighPriv, tenant |-> TenantA]
                ELSE [spawned |-> FALSE, parent |-> RootId,
                      priv |-> HighPriv, tenant |-> TenantA] ]
    /\ ctx       = [t \in Threads |-> ClearedCtx]
    /\ phase     = [t \in Threads |-> P_Idle]
    /\ last_threw= [t \in Threads |-> FALSE]

-------------------------------------------------------------------------------
(* Spawn transitions for thread t                                             *)
-------------------------------------------------------------------------------
(* (1) QueryEngine.executeTool publishes the parent's profile to ctx.
        DropSet models the bug where this set is omitted -> ctx stays cleared/stale. *)
DoSet1(t) ==
    /\ phase[t] = P_Idle
    /\ ctx'       = [ctx EXCEPT ![t] =
                        IF DropSet THEN ctx[t] ELSE profileOf(ParentOf(First(t)))]
    /\ phase'     = [phase EXCEPT ![t] = P_Set1]
    /\ last_threw'= [last_threw EXCEPT ![t] = FALSE]
    /\ tree'      = tree

(* (2) AgentTool constructs the first child.  The child profile is chosen
        non-deterministically among all profiles no more privileged than what
        ctx currently advertises (TLC explores every such choice).  In the real
        implementation the child inherits the parent profile verbatim, which is a
        refinement of this spec; allowing subsets makes the subset invariant
        non-trivial rather than checking mere equality.                         *)
DoSpawn1(t) ==
    /\ phase[t] = P_Set1
    /\ \E cp \in {p \in Privs : NoMorePriv(p, readPriv(t))} :
        /\ \E ct \in (IF DropTenant THEN Tenants
                      ELSE {tree[ParentOf(First(t))].tenant}) :
            /\ tree' = [tree EXCEPT ![First(t)] =
                          [spawned |-> TRUE,
                           parent  |-> ParentOf(First(t)),
                           priv    |-> cp,
                           tenant  |-> ct]]
    /\ phase'      = [phase EXCEPT ![t] = P_Spawn1]
    /\ ctx'        = ctx
    /\ last_threw' = last_threw

(* (3a) Normal completion: finally clears ctx. *)
DoOk1(t) ==
    /\ phase[t] = P_Spawn1
    /\ ctx'        = [ctx EXCEPT ![t] = ClearedCtx]
    /\ phase'      = [phase EXCEPT ![t] = P_Done1]
    /\ last_threw' = [last_threw EXCEPT ![t] = FALSE]
    /\ tree'       = tree

(* (3b) Exception during/after spawn. *)
DoThrow1(t) ==
    /\ phase[t] = P_Spawn1
    /\ phase'      = [phase EXCEPT ![t] = P_Throw1]
    /\ last_threw' = [last_threw EXCEPT ![t] = TRUE]
    /\ ctx'        = ctx
    /\ tree'       = tree

(* (3c) finally after throw.  DropClear models a missing finally: ctx is left
        set to the parent profile -> thread-pool reuse leak. *)
DoFinally1(t) ==
    /\ phase[t] = P_Throw1
    /\ ctx'        = [ctx EXCEPT ![t] = IF DropClear THEN ctx[t] ELSE ClearedCtx]
    /\ phase'      = [phase EXCEPT ![t] = P_Done1]
    /\ last_threw' = last_threw        (* still TRUE: spawn 1 threw *)
    /\ tree'       = tree

(* Layer-2 spawns (identical structure, operating on Second(t) = GA|GB). *)
DoSet2(t) ==
    /\ phase[t] = P_Done1
    /\ ParentOf(Second(t)) \in {n \in Nodes : Spawned(n)}   (* parent must exist *)
    /\ ctx'       = [ctx EXCEPT ![t] =
                        IF DropSet THEN ctx[t] ELSE profileOf(ParentOf(Second(t)))]
    /\ phase'     = [phase EXCEPT ![t] = P_Set2]
    /\ last_threw'= [last_threw EXCEPT ![t] = FALSE]
    /\ tree'      = tree

DoSpawn2(t) ==
    /\ phase[t] = P_Set2
    /\ \E cp \in {p \in Privs : NoMorePriv(p, readPriv(t))} :
        /\ \E ct \in (IF DropTenant THEN Tenants
                      ELSE {tree[ParentOf(Second(t))].tenant}) :
            /\ tree' = [tree EXCEPT ![Second(t)] =
                          [spawned |-> TRUE,
                           parent  |-> ParentOf(Second(t)),
                           priv    |-> cp,
                           tenant  |-> ct]]
    /\ phase'      = [phase EXCEPT ![t] = P_Spawn2]
    /\ ctx'        = ctx
    /\ last_threw' = last_threw

DoOk2(t) ==
    /\ phase[t] = P_Spawn2
    /\ ctx'        = [ctx EXCEPT ![t] = ClearedCtx]
    /\ phase'      = [phase EXCEPT ![t] = P_Done2]
    /\ last_threw' = [last_threw EXCEPT ![t] = FALSE]
    /\ tree'       = tree

DoThrow2(t) ==
    /\ phase[t] = P_Spawn2
    /\ phase'      = [phase EXCEPT ![t] = P_Throw2]
    /\ last_threw' = [last_threw EXCEPT ![t] = TRUE]
    /\ ctx'        = ctx
    /\ tree'       = tree

DoFinally2(t) ==
    /\ phase[t] = P_Throw2
    /\ ctx'        = [ctx EXCEPT ![t] = IF DropClear THEN ctx[t] ELSE ClearedCtx]
    /\ phase'      = [phase EXCEPT ![t] = P_Done2]
    /\ last_threw' = last_threw        (* still TRUE *)
    /\ tree'       = tree

-------------------------------------------------------------------------------
(* Next: one thread takes one step (interleaved concurrency).                 *)
(* A thread that has finished both spawns stutters (terminal self-loop) so    *)
(* TLC's reachability explores the full state space without a spurious        *)
(* deadlock at the completed configuration.                                   *)
-------------------------------------------------------------------------------
DoTerminal(t) ==
    /\ phase[t] = P_Done2
    /\ UNCHANGED Vars

Next ==
    \E t \in Threads :
        \/ DoSet1(t) \/ DoSpawn1(t) \/ DoOk1(t) \/ DoThrow1(t) \/ DoFinally1(t)
        \/ DoSet2(t) \/ DoSpawn2(t) \/ DoOk2(t) \/ DoThrow2(t) \/ DoFinally2(t)
        \/ DoTerminal(t)

Spec == Init /\ [][Next]_Vars

-------------------------------------------------------------------------------
(* Safety invariants                                                          *)
-------------------------------------------------------------------------------
(* Inv1: spawn-time privilege non-increase.
   For every spawned non-root node, its capability set is a subset of its
   parent's capability set. *)
PrivilegeNonIncrease ==
    \A n \in Children :
        capsOf(tree[n].priv) \subseteq capsOf(tree[tree[n].parent].priv)

(* Inv2: no thread-local leak on the exception path.
   If a thread just finished a spawn that threw (phase is Done1/Done2 with
   last_threw=TRUE), its SpawnPrivilegeContext must have been cleared by the
   finally block. *)
NoLeakOnException ==
    \A t \in Threads :
        \/ phase[t] \notin {P_Done1, P_Done2}
        \/ ~last_threw[t]
        \/ ~ctx[t].set

(* Inv3: no cross-tenant leak across spawn. *)
NoCrossTenantLeak ==
    \A n \in Children :
        tree[n].tenant = tree[tree[n].parent].tenant

=============================================================================
\* Modification History
\* Created 2026-08-03 for reviewer-requested TLA+ partial model check.
=============================================================================

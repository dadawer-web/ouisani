package com.ouisani.aios.core.verification;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Declarative completion contract for a workflow node.
 *
 * <p>A contract is opt-in, preserving existing workflow behavior for nodes
 * that have not yet declared business-level verification.</p>
 */
public record VerificationContract(
        List<GoalPredicate> predicates,
        List<EvidenceRequirement> evidenceRequirements,
        Set<VerificationStage> stages,
        CorrectiveAction onFail,
        CorrectiveAction onInconclusive
) {

    public VerificationContract {
        predicates = predicates == null ? List.of() : List.copyOf(predicates);
        evidenceRequirements = evidenceRequirements == null ? List.of() : List.copyOf(evidenceRequirements);
        stages = stages == null || stages.isEmpty()
                ? Set.of(VerificationStage.SKILL_END)
                : Set.copyOf(stages);
        onFail = onFail == null ? CorrectiveAction.REPLAN : onFail;
        onInconclusive = onInconclusive == null ? CorrectiveAction.OBSERVE : onInconclusive;
    }

    public boolean enabled() {
        return !predicates.isEmpty() || !evidenceRequirements.isEmpty();
    }

    public boolean appliesTo(VerificationStage stage) {
        return enabled() && stage != null && stages.contains(stage);
    }

    public VerificationContract withEvidenceRequirement(EvidenceRequirement requirement) {
        if (requirement == null) return this;
        List<EvidenceRequirement> merged = new ArrayList<>(evidenceRequirements);
        merged.add(requirement);
        return new VerificationContract(predicates, merged, stages, onFail, onInconclusive);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private final List<GoalPredicate> predicates = new ArrayList<>();
        private final List<EvidenceRequirement> evidence = new ArrayList<>();
        private final Set<VerificationStage> stages = EnumSet.of(VerificationStage.SKILL_END);
        private boolean explicitStages;
        private CorrectiveAction onFail = CorrectiveAction.REPLAN;
        private CorrectiveAction onInconclusive = CorrectiveAction.OBSERVE;

        public Builder predicate(GoalPredicate predicate) {
            if (predicate != null) predicates.add(predicate);
            return this;
        }

        public Builder require(EvidenceRequirement requirement) {
            if (requirement != null) evidence.add(requirement);
            return this;
        }

        public Builder during() { selectStage(VerificationStage.DURING); return this; }
        public Builder skillEnd() { selectStage(VerificationStage.SKILL_END); return this; }
        public Builder finalStage() { selectStage(VerificationStage.FINAL); return this; }

        public Builder onFail(CorrectiveAction action) {
            if (action != null) onFail = action;
            return this;
        }

        public Builder onInconclusive(CorrectiveAction action) {
            if (action != null) onInconclusive = action;
            return this;
        }

        public VerificationContract build() {
            return new VerificationContract(predicates, evidence, new LinkedHashSet<>(stages), onFail, onInconclusive);
        }

        private void selectStage(VerificationStage stage) {
            if (!explicitStages) {
                stages.clear();
                explicitStages = true;
            }
            stages.add(stage);
        }
    }
}

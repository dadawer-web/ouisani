package com.ouisani.aios.core.memory.graph;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic evidence gate for an answer proposed by an LLM or workflow.
 * It never invents a replacement answer: when evidence is missing, conflicted
 * or not lexically covered, it returns REFUSE/OBSERVE plus the trace needed to
 * continue the workflow.
 */
public final class GroundedAnswerer {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{N}_:/.-]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "and", "or", "to", "of", "is", "are", "was", "were",
            "this", "that", "with", "for", "from", "已", "是", "的", "和", "与", "在", "了");

    public GroundedAnswer ground(String proposedAnswer, RetrievalTrace trace) {
        if (trace == null) {
            return new GroundedAnswer("", GroundingVerdict.REFUSE,
                    RetrievalQuery.InsufficientEvidenceAction.REFUSE, List.of(), List.of(),
                    "retrieval trace is missing", null);
        }
        RetrievalQuery.InsufficientEvidenceAction action = actionFrom(trace);
        List<RetrievalTrace.AnswerSupport> support = new ArrayList<>();
        List<String> coveredEvidence = new ArrayList<>();
        for (RetrievalTrace.Evidence evidence : trace.evidenceBundle()) {
            boolean covered = covers(proposedAnswer, evidence);
            String reason = covered ? "answer text overlaps evidence summary/source"
                    : "answer has no identifiable overlap with this evidence";
            support.add(new RetrievalTrace.AnswerSupport(evidence.evidenceId(),
                    evidence.summary(), covered, reason));
            if (covered && "SUPPORT".equals(evidence.role())) coveredEvidence.add(evidence.evidenceId());
        }

        List<String> conflicts = trace.conflicts().stream()
                .map(RetrievalTrace.Conflict::claimNodeId)
                .toList();
        boolean hasConflict = !conflicts.isEmpty();
        boolean noEvidence = trace.evidenceBundle().isEmpty();
        boolean noSupport = coveredEvidence.isEmpty();
        boolean grounded = trace.sufficient() && !hasConflict && !noEvidence && !noSupport
                && proposedAnswer != null && !proposedAnswer.isBlank();

        String reason;
        GroundingVerdict verdict;
        String answer;
        if (grounded) {
            verdict = GroundingVerdict.PASS;
            answer = proposedAnswer.trim();
            reason = "answer is covered by supporting evidence";
        } else {
            verdict = action == RetrievalQuery.InsufficientEvidenceAction.OBSERVE
                    ? GroundingVerdict.OBSERVE : GroundingVerdict.REFUSE;
            answer = verdict == GroundingVerdict.OBSERVE
                    ? "More observation or verification is required before answering."
                    : "I cannot answer reliably because the available evidence is insufficient or conflicting.";
            if (hasConflict) reason = "supporting and contradicting evidence conflict";
            else if (noEvidence) reason = "no evidence bundle was recovered";
            else if (noSupport) reason = "proposed answer is not covered by supporting evidence";
            else if (proposedAnswer == null || proposedAnswer.isBlank()) reason = "proposed answer is empty";
            else reason = trace.insufficiencyReason();
        }

        RetrievalTrace groundedTrace = trace.withAnswerSupport(support, grounded,
                grounded ? "" : reason,
                !grounded && action == RetrievalQuery.InsufficientEvidenceAction.OBSERVE);
        return new GroundedAnswer(answer, verdict, action,
                List.copyOf(coveredEvidence), List.copyOf(conflicts), reason, groundedTrace);
    }

    private static RetrievalQuery.InsufficientEvidenceAction actionFrom(RetrievalTrace trace) {
        Object value = trace.filters().get("insufficient_evidence_action");
        if (value != null) {
            try {
                return RetrievalQuery.InsufficientEvidenceAction.valueOf(
                        String.valueOf(value).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to the safer observe default below.
            }
        }
        return trace.shouldObserve()
                ? RetrievalQuery.InsufficientEvidenceAction.OBSERVE
                : RetrievalQuery.InsufficientEvidenceAction.REFUSE;
    }

    private static boolean covers(String answer, RetrievalTrace.Evidence evidence) {
        if (answer == null || answer.isBlank()) return false;
        String normalizedAnswer = answer.toLowerCase(Locale.ROOT);
        if (!evidence.evidenceId().isBlank()
                && normalizedAnswer.contains(evidence.evidenceId().toLowerCase(Locale.ROOT))) return true;
        if (!evidence.sourceRef().isBlank()
                && normalizedAnswer.contains(evidence.sourceRef().toLowerCase(Locale.ROOT))) return true;
        String summary = evidence.summary().toLowerCase(Locale.ROOT).trim();
        if (!summary.isBlank() && normalizedAnswer.contains(summary)) return true;

        List<String> evidenceTokens = tokens(summary + " " + evidence.sourceRef());
        if (evidenceTokens.isEmpty()) return false;
        long meaningful = evidenceTokens.stream()
                .filter(token -> token.length() > 1 && !STOP_WORDS.contains(token))
                .distinct()
                .count();
        if (meaningful == 0) return false;
        long overlap = evidenceTokens.stream()
                .filter(token -> token.length() > 1 && !STOP_WORDS.contains(token))
                .distinct()
                .filter(normalizedAnswer::contains)
                .count();
        // One distinctive token is enough for a short fact; longer evidence
        // requires two tokens to avoid grounding on generic words only.
        return overlap >= Math.min(2, meaningful);
    }

    private static List<String> tokens(String value) {
        if (value == null || value.isBlank()) return List.of();
        return TOKEN_PATTERN.matcher(value).results().map(match -> match.group()).toList();
    }

    public enum GroundingVerdict {
        PASS,
        REFUSE,
        OBSERVE
    }

    public record GroundedAnswer(
            String answer,
            GroundingVerdict verdict,
            RetrievalQuery.InsufficientEvidenceAction correctiveAction,
            List<String> evidenceIds,
            List<String> conflictIds,
            String reason,
            RetrievalTrace trace) {

        public GroundedAnswer {
            answer = answer == null ? "" : answer;
            verdict = verdict == null ? GroundingVerdict.REFUSE : verdict;
            correctiveAction = correctiveAction == null
                    ? RetrievalQuery.InsufficientEvidenceAction.REFUSE : correctiveAction;
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
            conflictIds = conflictIds == null ? List.of() : List.copyOf(conflictIds);
            reason = reason == null ? "" : reason;
        }

        public String toJson() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("answer", answer);
            map.put("verdict", verdict.name());
            map.put("corrective_action", correctiveAction.name());
            map.put("evidence_ids", evidenceIds);
            map.put("conflict_ids", conflictIds);
            map.put("reason", reason);
            map.put("trace", trace == null ? null : trace.asMap());
            return GSON.toJson(map);
        }
    }
}

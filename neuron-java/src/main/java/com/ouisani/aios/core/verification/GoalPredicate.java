package com.ouisani.aios.core.verification;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Function;

/**
 * A business-level predicate over an observation.
 *
 * <p>Predicates can be supplied by applications, while the static factories
 * cover the common workflow cases without asking an LLM to judge completion.</p>
 */
@FunctionalInterface
public interface GoalPredicate {

    Evaluation evaluate(Observation observation);

    default String id() {
        return getClass().getSimpleName();
    }

    default String description() {
        return id();
    }

    record Evaluation(Verdict verdict, String evidence) {
        public Evaluation {
            verdict = verdict == null ? Verdict.INCONCLUSIVE : verdict;
            evidence = evidence == null ? "" : evidence;
        }

        public static Evaluation pass(String evidence) {
            return new Evaluation(Verdict.PASS, evidence);
        }

        public static Evaluation fail(String evidence) {
            return new Evaluation(Verdict.FAIL, evidence);
        }

        public static Evaluation inconclusive(String evidence) {
            return new Evaluation(Verdict.INCONCLUSIVE, evidence);
        }
    }

    static GoalPredicate named(String id, String description,
                               Function<Observation, Evaluation> evaluator) {
        Objects.requireNonNull(evaluator, "evaluator");
        return new GoalPredicate() {
            @Override public Evaluation evaluate(Observation observation) {
                return evaluator.apply(observation);
            }

            @Override public String id() { return id == null || id.isBlank() ? "predicate" : id; }
            @Override public String description() {
                return description == null || description.isBlank() ? id() : description;
            }
        };
    }

    static GoalPredicate outputPresent(String key) {
        return named("output_present:" + key, "output contains " + key, observation -> {
            if (observation == null) return Evaluation.inconclusive("observation is null");
            return observation.hasOutput(key)
                    ? Evaluation.pass("output[" + key + "] is present")
                    : Evaluation.fail("output[" + key + "] is missing");
        });
    }

    static GoalPredicate outputEquals(String key, Object expected) {
        return named("output_equals:" + key, "output[" + key + "] equals expected value", observation -> {
            if (observation == null || !observation.hasOutput(key)) {
                return Evaluation.inconclusive("output[" + key + "] is unavailable");
            }
            Object actual = observation.outputValue(key);
            return valuesEqual(actual, expected)
                    ? Evaluation.pass("output[" + key + "] matched expected value")
                    : Evaluation.fail("output[" + key + "] was " + String.valueOf(actual));
        });
    }

    static GoalPredicate stateChanged(String key) {
        return named("state_changed:" + key, "state[" + key + "] changed", observation -> {
            if (observation == null || !observation.hasOutput(key)) {
                return Evaluation.inconclusive("current state[" + key + "] is unavailable");
            }
            if (!observation.hasBaseline(key)) {
                return Evaluation.pass("state[" + key + "] appeared during execution");
            }
            boolean changed = !Objects.deepEquals(observation.baselineValue(key), observation.outputValue(key));
            return changed
                    ? Evaluation.pass("state[" + key + "] changed")
                    : Evaluation.fail("state[" + key + "] did not change");
        });
    }

    static GoalPredicate requiredStepCompleted(String stepId) {
        return named("step_completed:" + stepId, "required step " + stepId + " completed", observation -> {
            if (observation == null) return Evaluation.inconclusive("observation is null");
            return observation.completedSteps().contains(stepId)
                    ? Evaluation.pass("step " + stepId + " completed")
                    : Evaluation.fail("step " + stepId + " is not completed");
        });
    }

    static GoalPredicate upstreamSucceeded(String stepId) {
        return named("upstream_succeeded:" + stepId, "upstream step " + stepId + " succeeded", observation -> {
            if (observation == null || !observation.upstreamStatuses().containsKey(stepId)) {
                return Evaluation.inconclusive("status for step " + stepId + " is unavailable");
            }
            String status = observation.upstreamStatuses().get(stepId);
            return "SUCCESS".equalsIgnoreCase(status)
                    ? Evaluation.pass("upstream " + stepId + " succeeded")
                    : Evaluation.fail("upstream " + stepId + " status=" + status);
        });
    }

    static GoalPredicate finalResponseContains(String token) {
        return named("final_response_contains:" + token, "final response contains required evidence", observation -> {
            if (observation == null || observation.finalResponse().isBlank()) {
                return Evaluation.inconclusive("final response is unavailable");
            }
            return observation.finalResponse().contains(token)
                    ? Evaluation.pass("final response contains '" + token + "'")
                    : Evaluation.fail("final response does not contain '" + token + "'");
        });
    }

    private static boolean valuesEqual(Object actual, Object expected) {
        if (actual instanceof Number actualNumber && expected instanceof Number expectedNumber) {
            try {
                return new BigDecimal(actualNumber.toString()).compareTo(
                        new BigDecimal(expectedNumber.toString())) == 0;
            } catch (NumberFormatException ignored) {
                // Fall through to the normal deep comparison for unusual numbers.
            }
        }
        return Objects.deepEquals(actual, expected);
    }
}

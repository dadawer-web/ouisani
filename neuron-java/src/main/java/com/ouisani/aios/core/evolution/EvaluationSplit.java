package com.ouisani.aios.core.evolution;

import java.util.Objects;

/**
 * A non-overlapping deployment/evaluation split.
 *
 * <p>The ordinal is the ordering authority.  Asset rules created in split N
 * have an effective ordinal of at least N + 1, so a test answer cannot be
 * compiled into the split in which it was observed.</p>
 */
public record EvaluationSplit(String id, int ordinal, boolean closed) {

    public EvaluationSplit {
        id = normalize(id);
        if (ordinal < 0) throw new IllegalArgumentException("split ordinal must be >= 0");
    }

    public EvaluationSplit(String id, int ordinal) {
        this(id, ordinal, false);
    }

    public EvaluationSplit close() {
        return new EvaluationSplit(id, ordinal, true);
    }

    public EvaluationSplit reopen() {
        return new EvaluationSplit(id, ordinal, false);
    }

    private static String normalize(String value) {
        Objects.requireNonNull(value, "split id must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("split id must not be blank");
        if (normalized.length() > 128) throw new IllegalArgumentException("split id is too long");
        return normalized;
    }
}

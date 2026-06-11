package dev.nquangit.corestra.access.matrix;

/**
 * Confidence assigned to a generated matrix row suggestion.
 */
public enum GenerationConfidence {
    /**
     * High-confidence baseline or direct metadata match.
     */
    HIGH,

    /**
     * Medium-confidence candidate that still needs policy review.
     */
    MEDIUM,

    /**
     * Low-confidence candidate based on broad role or template context.
     */
    LOW,

    /**
     * Candidate intentionally defaults to manual review.
     */
    REVIEW_REQUIRED
}

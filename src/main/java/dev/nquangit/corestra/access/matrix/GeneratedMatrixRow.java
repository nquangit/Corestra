package dev.nquangit.corestra.access.matrix;

import java.util.Objects;

/**
 * Matrix row suggestion with generation metadata for preview and diagnostics.
 */
public final class GeneratedMatrixRow {
    private final AccessTestCase row;
    private final MatrixGenerationStrategy generationStrategy;
    private final String generationReason;
    private final GenerationConfidence confidence;

    /**
     * Creates a generated row wrapper.
     *
     * @param row generated matrix row
     * @param generationStrategy strategy that produced the row
     * @param generationReason human-readable reason
     * @param confidence suggestion confidence
     */
    public GeneratedMatrixRow(
            AccessTestCase row,
            MatrixGenerationStrategy generationStrategy,
            String generationReason,
            GenerationConfidence confidence) {
        this.row = Objects.requireNonNull(row, "row");
        this.generationStrategy = Objects.requireNonNullElse(generationStrategy, MatrixGenerationStrategy.OWNER_VS_OTHERS);
        this.generationReason = Objects.requireNonNullElse(generationReason, "").strip();
        this.confidence = Objects.requireNonNullElse(confidence, GenerationConfidence.REVIEW_REQUIRED);
    }

    /** @return generated matrix row */
    public AccessTestCase row() {
        return row;
    }

    /** @return strategy that produced the row */
    public MatrixGenerationStrategy generationStrategy() {
        return generationStrategy;
    }

    /** @return human-readable reason */
    public String generationReason() {
        return generationReason;
    }

    /** @return suggestion confidence */
    public GenerationConfidence confidence() {
        return confidence;
    }
}

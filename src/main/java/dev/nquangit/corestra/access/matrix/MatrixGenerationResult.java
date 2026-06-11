package dev.nquangit.corestra.access.matrix;

import java.util.List;
import java.util.Objects;

/**
 * Contains suggested matrix rows and non-fatal generation diagnostics.
 */
public final class MatrixGenerationResult {
    private final List<GeneratedMatrixRow> generatedRows;
    private final int duplicateCount;
    private final List<String> warnings;
    private final int skippedCount;
    private final List<MatrixGenerationSkipReason> skipReasons;

    /**
     * Creates a matrix generation result.
     *
     * @param rows generated rows
     * @param duplicateCount number of duplicate rows skipped
     * @param warnings non-fatal warnings
     */
    public MatrixGenerationResult(List<AccessTestCase> rows, int duplicateCount, List<String> warnings) {
        this(Objects.requireNonNull(rows, "rows").stream()
                        .map(row -> new GeneratedMatrixRow(
                                row,
                                MatrixGenerationStrategy.OWNER_VS_OTHERS,
                                "",
                                GenerationConfidence.REVIEW_REQUIRED))
                        .toList(),
                duplicateCount,
                warnings,
                Math.max(0, duplicateCount),
                List.of());
    }

    /**
     * Creates a matrix generation result with row metadata and skip diagnostics.
     *
     * @param generatedRows generated row suggestions
     * @param duplicateCount number of duplicate rows skipped
     * @param warnings non-fatal warnings
     * @param skippedCount number of candidates skipped for any reason
     * @param skipReasons detailed skip reasons
     */
    public MatrixGenerationResult(
            List<GeneratedMatrixRow> generatedRows,
            int duplicateCount,
            List<String> warnings,
            int skippedCount,
            List<MatrixGenerationSkipReason> skipReasons) {
        this.generatedRows = List.copyOf(Objects.requireNonNull(generatedRows, "generatedRows"));
        this.duplicateCount = Math.max(0, duplicateCount);
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        this.skippedCount = Math.max(0, skippedCount);
        this.skipReasons = List.copyOf(Objects.requireNonNull(skipReasons, "skipReasons"));
    }

    /**
     * Returns generated rows.
     *
     * @return immutable generated rows
     */
    public List<AccessTestCase> rows() {
        return generatedRows.stream().map(GeneratedMatrixRow::row).toList();
    }

    /**
     * Returns generated rows with strategy metadata.
     *
     * @return immutable generated row metadata
     */
    public List<GeneratedMatrixRow> generatedRows() {
        return generatedRows;
    }

    /**
     * Returns the number of duplicate candidates skipped.
     *
     * @return duplicate count
     */
    public int duplicateCount() {
        return duplicateCount;
    }

    /**
     * Returns non-fatal generation warnings.
     *
     * @return immutable warnings
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Returns the number of skipped candidates.
     *
     * @return skipped candidate count
     */
    public int skippedCount() {
        return skippedCount;
    }

    /**
     * Returns detailed reasons for skipped candidates.
     *
     * @return immutable skip reasons
     */
    public List<MatrixGenerationSkipReason> skipReasons() {
        return skipReasons;
    }

    /**
     * Returns whether no rows were generated.
     *
     * @return true when no rows were generated
     */
    public boolean empty() {
        return generatedRows.isEmpty();
    }
}

package dev.nquangit.corestra.access.flow;

import dev.nquangit.corestra.access.matrix.AccessTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Result of creating Matrix rows from selected flow steps.
 */
public final class FlowMatrixRowsFromFlowResult {
    private final List<AccessTestCase> rows;
    private final int duplicateCount;
    private final List<String> warnings;

    /**
     * Creates a result.
     *
     * @param rows saved rows
     * @param duplicateCount duplicate count
     * @param warnings warnings
     */
    public FlowMatrixRowsFromFlowResult(
            List<AccessTestCase> rows,
            int duplicateCount,
            List<String> warnings) {
        this.rows = copyList(rows, "rows");
        this.duplicateCount = Math.max(0, duplicateCount);
        this.warnings = copyList(warnings, "warnings");
    }

    /**
     * Returns saved rows.
     *
     * @return saved rows
     */
    public List<AccessTestCase> rows() {
        return rows;
    }

    /**
     * Returns duplicate count.
     *
     * @return duplicate count
     */
    public int duplicateCount() {
        return duplicateCount;
    }

    /**
     * Returns warnings.
     *
     * @return warnings
     */
    public List<String> warnings() {
        return warnings;
    }

    private static <T> List<T> copyList(List<T> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<T> copied = new ArrayList<>();
        for (T value : values) {
            copied.add(Objects.requireNonNull(value, fieldName + " must not contain null entries"));
        }
        return List.copyOf(copied);
    }
}

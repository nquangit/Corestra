package dev.nquangit.corestra.access.flow;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.access.matrix.ExpectedResult;

import java.util.Objects;

/**
 * Describes one selected flow step that should become a Matrix row.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class FlowStepMatrixSelection {
    private final String stepId;
    private final ExpectedResult expected;
    private final FlowTargetRunMode runMode;
    private final boolean selected;

    /**
     * Creates a selected flow-step row.
     *
     * @param stepId selected step id
     * @param expected expected access result
     * @param runMode flow target run mode
     * @param selected whether this step should be generated
     */
    @JsonCreator
    public FlowStepMatrixSelection(
            @JsonProperty("stepId") String stepId,
            @JsonProperty("expected") ExpectedResult expected,
            @JsonProperty("runMode") FlowTargetRunMode runMode,
            @JsonProperty("selected") boolean selected) {
        this.stepId = normalizeRequiredText(stepId, "stepId");
        this.expected = Objects.requireNonNullElse(expected, ExpectedResult.REVIEW);
        this.runMode = Objects.requireNonNullElse(runMode, FlowTargetRunMode.STOP_AT_STEP);
        this.selected = selected;
    }

    /**
     * Returns the selected step id.
     *
     * @return step id
     */
    public String stepId() {
        return stepId;
    }

    /**
     * Returns the expected access result.
     *
     * @return expected result
     */
    public ExpectedResult expected() {
        return expected;
    }

    /**
     * Returns the flow target run mode.
     *
     * @return run mode
     */
    public FlowTargetRunMode runMode() {
        return runMode;
    }

    /**
     * Returns whether this step is selected for generation.
     *
     * @return true when selected
     */
    public boolean selected() {
        return selected;
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}

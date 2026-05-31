package dev.nquangit.corestra.flow;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Describes how a flow should aggregate intermediate results into a synthetic request body.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AggregationPlan {
    private final boolean enabled;
    private final Mode mode;
    private final String finalStepId;
    private final List<String> selectedExtractVariables;
    private final boolean useTriggeredStep;

    /**
     * Creates an aggregation plan.
     *
     * @param enabled whether aggregation is enabled
     * @param mode the aggregation mode
     * @param finalStepId the step that should receive the synthetic body
     * @param selectedExtractVariables variable names that should be aggregated when the mode uses selected extracts
     * @param useTriggeredStep when {@code true} the step that triggered the flow is used as the final step,
     *                         relaxing the {@code finalStepId} requirement
     */
    @JsonCreator
    public AggregationPlan(
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("mode") Mode mode,
            @JsonProperty("finalStepId") String finalStepId,
            @JsonProperty("selectedExtractVariables") List<String> selectedExtractVariables,
            @JsonProperty("useTriggeredStep") boolean useTriggeredStep) {
        this.enabled = enabled;
        this.mode = Objects.requireNonNullElse(mode, Mode.APPEND_RAW_RESPONSES);
        this.finalStepId = normalizeOptionalText(finalStepId);
        this.selectedExtractVariables = normalizeStringList(selectedExtractVariables);
        this.useTriggeredStep = useTriggeredStep;

        if (enabled && !useTriggeredStep && this.finalStepId.isEmpty()) {
            throw new IllegalArgumentException(
                    "finalStepId must not be blank when aggregation is enabled without useTriggeredStep");
        }
    }

    /**
     * Backward-compatible 4-arg constructor — {@code useTriggeredStep} defaults to {@code false}.
     *
     * @param enabled whether aggregation is enabled
     * @param mode the aggregation mode
     * @param finalStepId the step that should receive the synthetic body
     * @param selectedExtractVariables variable names that should be aggregated when the mode uses selected extracts
     */
    public AggregationPlan(boolean enabled, Mode mode, String finalStepId,
                           List<String> selectedExtractVariables) {
        this(enabled, mode, finalStepId, selectedExtractVariables, false);
    }

    /**
     * Returns a disabled aggregation plan.
     *
     * @return a disabled aggregation plan
     */
    public static AggregationPlan disabled() {
        return new AggregationPlan(false, Mode.APPEND_RAW_RESPONSES, null, List.of());
    }

    /**
     * Returns whether aggregation is enabled.
     *
     * @return whether aggregation is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the aggregation mode.
     *
     * @return the aggregation mode
     */
    public Mode mode() {
        return mode;
    }

    /**
     * Returns the identifier of the step that receives the synthetic body.
     *
     * @return the final step identifier, or an empty string when aggregation is disabled
     */
    public String finalStepId() {
        return finalStepId;
    }

    /**
     * Returns the selected variable names used by extract-based aggregation.
     *
     * @return the selected variable names
     */
    public List<String> selectedExtractVariables() {
        return selectedExtractVariables;
    }

    /**
     * Returns whether the step that triggered the flow is used as the final aggregation step.
     * When {@code true} the {@code finalStepId} constraint is relaxed.
     *
     * @return {@code true} when the triggered step is used as the final step
     */
    public boolean useTriggeredStep() {
        return useTriggeredStep;
    }

    /**
     * Compares this aggregation plan to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AggregationPlan that)) {
            return false;
        }
        return enabled == that.enabled
                && useTriggeredStep == that.useTriggeredStep
                && mode == that.mode
                && Objects.equals(finalStepId, that.finalStepId)
                && Objects.equals(selectedExtractVariables, that.selectedExtractVariables);
    }

    /**
     * Returns the hash code for this aggregation plan.
     *
     * @return the hash code for this aggregation plan
     */
    @Override
    public int hashCode() {
        return Objects.hash(enabled, mode, finalStepId, selectedExtractVariables, useTriggeredStep);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AggregationPlan{" +
                "enabled=" + enabled +
                ", mode=" + mode +
                ", finalStepId='" + finalStepId + '\'' +
                ", selectedExtractVariables=" + selectedExtractVariables +
                ", useTriggeredStep=" + useTriggeredStep +
                '}';
    }

    /**
     * Enumerates the supported aggregation strategies.
     */
    public enum Mode {
        /**
         * Appends raw response text in execution order.
         */
        APPEND_RAW_RESPONSES,

        /**
         * Appends concise normalized summaries instead of the full response content.
         */
        APPEND_NORMALIZED_SUMMARIES,

        /**
         * Appends only specifically selected extracted variables.
         */
        APPEND_SELECTED_EXTRACTS
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> normalizeStringList(List<String> values) {
        if (values == null) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.strip());
            }
        }
        return List.copyOf(normalized);
    }
}

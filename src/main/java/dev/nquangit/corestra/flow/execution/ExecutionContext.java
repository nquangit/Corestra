package dev.nquangit.corestra.flow.execution;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Captures the immutable state accumulated while a flow is executing.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ExecutionContext {
    private final String runId;
    private final String flowId;
    private final Map<String, String> metadata;
    private final Map<String, String> variables;
    private final String aggregationBuffer;
    private final Map<String, List<StepExecutionRecord>> stepHistory;
    private final long startedAtEpochMillis;
    private final long completedAtEpochMillis;

    /**
     * Creates an execution context.
     *
     * @param runId the stable execution run identifier
     * @param flowId the flow definition identifier
     * @param metadata immutable execution metadata
     * @param variables extracted and computed execution variables
     * @param aggregationBuffer the current aggregation buffer
     * @param stepHistory recorded per-step execution history
     * @param startedAtEpochMillis the execution start timestamp in epoch milliseconds
     * @param completedAtEpochMillis the completion timestamp in epoch milliseconds, or {@code 0} when still running
     */
    @JsonCreator
    public ExecutionContext(
            @JsonProperty("runId") String runId,
            @JsonProperty("flowId") String flowId,
            @JsonProperty("metadata") Map<String, String> metadata,
            @JsonProperty("variables") Map<String, String> variables,
            @JsonProperty("aggregationBuffer") String aggregationBuffer,
            @JsonProperty("stepHistory") Map<String, List<StepExecutionRecord>> stepHistory,
            @JsonProperty("startedAtEpochMillis") long startedAtEpochMillis,
            @JsonProperty("completedAtEpochMillis") long completedAtEpochMillis) {
        this.runId = normalizeRequiredText(runId, "runId");
        this.flowId = normalizeRequiredText(flowId, "flowId");
        this.metadata = copyStringMap(metadata, "metadata");
        this.variables = copyStringMap(variables, "variables");
        this.aggregationBuffer = aggregationBuffer == null ? "" : aggregationBuffer;
        this.stepHistory = copyStepHistory(stepHistory);
        this.startedAtEpochMillis = validateEpochMillis(startedAtEpochMillis, "startedAtEpochMillis");
        this.completedAtEpochMillis = validateCompletionMillis(completedAtEpochMillis, this.startedAtEpochMillis);
    }

    /**
     * Creates a new empty execution context for a flow run.
     *
     * @param runId the stable execution run identifier
     * @param flowId the flow definition identifier
     * @param metadata immutable execution metadata
     * @param startedAtEpochMillis the execution start timestamp in epoch milliseconds
     * @return a new execution context with no variables, aggregation output, or step history
     */
    public static ExecutionContext start(
            String runId,
            String flowId,
            Map<String, String> metadata,
            long startedAtEpochMillis) {
        return new ExecutionContext(runId, flowId, metadata, Map.of(), "", Map.of(), startedAtEpochMillis, 0L);
    }

    /**
     * Returns the execution run identifier.
     *
     * @return the execution run identifier
     */
    public String runId() {
        return runId;
    }

    /**
     * Returns the flow definition identifier.
     *
     * @return the flow definition identifier
     */
    public String flowId() {
        return flowId;
    }

    /**
     * Returns the immutable execution metadata.
     *
     * @return the immutable execution metadata
     */
    public Map<String, String> metadata() {
        return metadata;
    }

    /**
     * Returns the immutable execution variables.
     *
     * @return the immutable execution variables
     */
    public Map<String, String> variables() {
        return variables;
    }

    /**
     * Returns the current aggregation buffer.
     *
     * @return the current aggregation buffer
     */
    public String aggregationBuffer() {
        return aggregationBuffer;
    }

    /**
     * Returns the immutable per-step execution history.
     *
     * @return the immutable per-step execution history
     */
    public Map<String, List<StepExecutionRecord>> stepHistory() {
        return stepHistory;
    }

    /**
     * Returns the execution start timestamp.
     *
     * @return the execution start timestamp in epoch milliseconds
     */
    public long startedAtEpochMillis() {
        return startedAtEpochMillis;
    }

    /**
     * Returns the execution completion timestamp.
     *
     * @return the completion timestamp in epoch milliseconds, or {@code 0} while still running
     */
    public long completedAtEpochMillis() {
        return completedAtEpochMillis;
    }

    /**
     * Returns a copy of this context with an updated variable.
     *
     * @param variableName the variable name
     * @param value the variable value
     * @return a new execution context containing the updated variable
     */
    public ExecutionContext withVariable(String variableName, String value) {
        Map<String, String> updatedVariables = new LinkedHashMap<>(variables);
        updatedVariables.put(normalizeRequiredText(variableName, "variableName"), value == null ? "" : value);
        return new ExecutionContext(runId, flowId, metadata, updatedVariables, aggregationBuffer, stepHistory, startedAtEpochMillis, completedAtEpochMillis);
    }

    /**
     * Returns a copy of this context with additional aggregation text appended.
     *
     * @param additionalContent the text to append
     * @return a new execution context containing the updated aggregation buffer
     */
    public ExecutionContext withAggregationAppend(String additionalContent) {
        String appendedContent = additionalContent == null ? "" : additionalContent;
        return new ExecutionContext(
                runId,
                flowId,
                metadata,
                variables,
                aggregationBuffer + appendedContent,
                stepHistory,
                startedAtEpochMillis,
                completedAtEpochMillis);
    }

    /**
     * Returns a copy of this context with the supplied step execution record appended to the step history.
     *
     * @param stepExecutionRecord the record to append
     * @return a new execution context containing the appended step execution record
     */
    public ExecutionContext withStepRecord(StepExecutionRecord stepExecutionRecord) {
        StepExecutionRecord checkedRecord = Objects.requireNonNull(stepExecutionRecord, "stepExecutionRecord");
        Map<String, List<StepExecutionRecord>> updatedHistory = new LinkedHashMap<>();
        stepHistory.forEach((stepId, records) -> updatedHistory.put(stepId, new ArrayList<>(records)));
        updatedHistory.computeIfAbsent(checkedRecord.stepId(), ignored -> new ArrayList<>()).add(checkedRecord);
        return new ExecutionContext(runId, flowId, metadata, variables, aggregationBuffer, updatedHistory, startedAtEpochMillis, completedAtEpochMillis);
    }

    /**
     * Returns a copy of this context marked as completed.
     *
     * @param completedAtEpochMillis the completion timestamp in epoch milliseconds
     * @return a new execution context marked as completed
     */
    public ExecutionContext markCompleted(long completedAtEpochMillis) {
        return new ExecutionContext(runId, flowId, metadata, variables, aggregationBuffer, stepHistory, startedAtEpochMillis, completedAtEpochMillis);
    }

    /**
     * Compares this execution context to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExecutionContext that)) {
            return false;
        }
        return startedAtEpochMillis == that.startedAtEpochMillis
                && completedAtEpochMillis == that.completedAtEpochMillis
                && Objects.equals(runId, that.runId)
                && Objects.equals(flowId, that.flowId)
                && Objects.equals(metadata, that.metadata)
                && Objects.equals(variables, that.variables)
                && Objects.equals(aggregationBuffer, that.aggregationBuffer)
                && Objects.equals(stepHistory, that.stepHistory);
    }

    /**
     * Returns the hash code for this execution context.
     *
     * @return the hash code for this execution context
     */
    @Override
    public int hashCode() {
        return Objects.hash(runId, flowId, metadata, variables, aggregationBuffer, stepHistory, startedAtEpochMillis, completedAtEpochMillis);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "ExecutionContext{" +
                "runId='" + runId + '\'' +
                ", flowId='" + flowId + '\'' +
                ", metadata=" + metadata +
                ", variables=" + variables +
                ", aggregationBufferLength=" + aggregationBuffer.length() +
                ", stepHistory=" + stepHistory +
                ", startedAtEpochMillis=" + startedAtEpochMillis +
                ", completedAtEpochMillis=" + completedAtEpochMillis +
                '}';
    }

    private static Map<String, String> copyStringMap(Map<String, String> values, String fieldName) {
        if (values == null) {
            return Map.of();
        }

        Map<String, String> copied = new LinkedHashMap<>();
        values.forEach((key, value) -> copied.put(
                normalizeRequiredText(key, fieldName + " key"),
                value == null ? "" : value));
        return Map.copyOf(copied);
    }

    private static Map<String, List<StepExecutionRecord>> copyStepHistory(Map<String, List<StepExecutionRecord>> stepHistory) {
        if (stepHistory == null) {
            return Map.of();
        }

        Map<String, List<StepExecutionRecord>> copied = new LinkedHashMap<>();
        stepHistory.forEach((stepId, records) -> {
            String normalizedStepId = normalizeRequiredText(stepId, "stepHistory key");
            List<StepExecutionRecord> copiedRecords = new ArrayList<>();
            if (records != null) {
                for (StepExecutionRecord record : records) {
                    copiedRecords.add(Objects.requireNonNull(record, "stepHistory must not contain null records"));
                }
            }
            copied.put(normalizedStepId, List.copyOf(copiedRecords));
        });
        return Map.copyOf(copied);
    }

    private static long validateEpochMillis(long value, String fieldName) {
        if (value < 0L) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }

    private static long validateCompletionMillis(long completedAtEpochMillis, long startedAtEpochMillis) {
        if (completedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("completedAtEpochMillis must not be negative");
        }
        if (completedAtEpochMillis != 0L && completedAtEpochMillis < startedAtEpochMillis) {
            throw new IllegalArgumentException("completedAtEpochMillis must be greater than or equal to startedAtEpochMillis");
        }
        return completedAtEpochMillis;
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}

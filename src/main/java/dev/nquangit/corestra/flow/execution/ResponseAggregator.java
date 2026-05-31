package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.flow.AggregationPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Accumulates {@link StepExecutionRecord} instances during flow execution and produces
 * an {@link AggregatedOutput} according to an {@link AggregationPlan}.
 *
 * <p>Records are accumulated in execution order via {@link #accumulate(StepExecutionRecord)}.
 * Call {@link #aggregate(AggregationPlan)} to compute the final output once all relevant
 * steps have been recorded.
 */
public final class ResponseAggregator {

    private final List<StepExecutionRecord> buffer = new ArrayList<>();

    /**
     * Adds a step execution record to the internal buffer.
     *
     * @param record the record to accumulate; must not be {@code null}
     */
    public void accumulate(StepExecutionRecord record) {
        Objects.requireNonNull(record, "record");
        buffer.add(record);
    }

    /**
     * Produces the aggregated output by applying the strategy defined in {@code plan}.
     *
     * <p>Supported modes:
     * <ul>
     *   <li>{@link AggregationPlan.Mode#APPEND_RAW_RESPONSES} — concatenates all raw response bodies
     *       in execution order.</li>
     *   <li>{@link AggregationPlan.Mode#APPEND_NORMALIZED_SUMMARIES} — concatenates a formatted
     *       summary line for each step: {@code "[Step: {stepId}] Status: {statusCode}\n{body}"}.</li>
     *   <li>{@link AggregationPlan.Mode#APPEND_SELECTED_EXTRACTS} — concatenates the extracted
     *       variables from each step, formatted as {@code "key=value"} pairs.</li>
     * </ul>
     *
     * <p>If the buffer is empty, an {@link AggregatedOutput#empty() empty} output is returned.
     *
     * @param plan the aggregation plan describing which mode to apply; must not be {@code null}
     * @return the aggregated output
     */
    public AggregatedOutput aggregate(AggregationPlan plan) {
        Objects.requireNonNull(plan, "plan");

        if (buffer.isEmpty()) {
            return AggregatedOutput.empty();
        }

        return switch (plan.mode()) {
            case APPEND_RAW_RESPONSES -> aggregateRawResponses();
            case APPEND_NORMALIZED_SUMMARIES -> aggregateNormalizedSummaries();
            case APPEND_SELECTED_EXTRACTS -> aggregateSelectedExtracts(plan);
        };
    }

    private AggregatedOutput aggregateRawResponses() {
        StringBuilder sb = new StringBuilder();
        for (StepExecutionRecord record : buffer) {
            String body = extractBody(record.receivedResponse());
            sb.append(body);
        }
        return new AggregatedOutput(sb.toString());
    }

    private AggregatedOutput aggregateNormalizedSummaries() {
        StringBuilder sb = new StringBuilder();
        for (StepExecutionRecord record : buffer) {
            String body = extractBody(record.receivedResponse());
            sb.append("[Step: ")
              .append(record.stepId())
              .append("] Status: ")
              .append(record.responseStatusCode())
              .append('\n')
              .append(body);
        }
        return new AggregatedOutput(sb.toString());
    }

    private static String extractBody(String rawResponse) {
        if (rawResponse == null) return "";
        int sep = rawResponse.indexOf("\r\n\r\n");
        if (sep >= 0) return rawResponse.substring(sep + 4);
        sep = rawResponse.indexOf("\n\n");
        if (sep >= 0) return rawResponse.substring(sep + 2);
        return rawResponse;
    }

    private AggregatedOutput aggregateSelectedExtracts(AggregationPlan plan) {
        List<String> selectedVariables = plan.selectedExtractVariables();
        StringBuilder sb = new StringBuilder();
        for (StepExecutionRecord record : buffer) {
            Map<String, String> extracted = record.extractedVariables();
            if (selectedVariables.isEmpty()) {
                // No filter: include all extracted variables
                for (Map.Entry<String, String> entry : extracted.entrySet()) {
                    sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
                }
            } else {
                for (String varName : selectedVariables) {
                    if (extracted.containsKey(varName)) {
                        sb.append(varName).append('=').append(extracted.get(varName)).append('\n');
                    }
                }
            }
        }
        return new AggregatedOutput(sb.toString());
    }
}

package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.flow.AggregationPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ResponseAggregator}.
 */
class ResponseAggregatorTest {

    private ResponseAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new ResponseAggregator();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static StepExecutionRecord record(String stepId, String response, int statusCode, Map<String, String> extracted) {
        return new StepExecutionRecord(
                stepId,
                "GET / HTTP/1.1",
                response,
                statusCode,
                true,
                null,
                1000L,
                1001L,
                extracted);
    }

    private static AggregationPlan planRaw() {
        return new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "final-step", List.of());
    }

    private static AggregationPlan planSummaries() {
        return new AggregationPlan(true, AggregationPlan.Mode.APPEND_NORMALIZED_SUMMARIES, "final-step", List.of());
    }

    private static AggregationPlan planExtracts(List<String> selectedVars) {
        return new AggregationPlan(true, AggregationPlan.Mode.APPEND_SELECTED_EXTRACTS, "final-step", selectedVars);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void emptyAggregationReturnsEmptyOutput() {
        AggregatedOutput output = aggregator.aggregate(planRaw());
        assertTrue(output.isEmpty(), "Aggregating an empty buffer should produce empty output");
    }

    @Test
    void appendRawResponsesJoinsAllBodiesInOrder() {
        aggregator.accumulate(record("step-1", "body-one", 200, Map.of()));
        aggregator.accumulate(record("step-2", "body-two", 200, Map.of()));
        aggregator.accumulate(record("step-3", "body-three", 200, Map.of()));

        AggregatedOutput output = aggregator.aggregate(planRaw());

        assertEquals("body-onebody-twobody-three", output.body(),
                "Raw mode should concatenate all response bodies in order");
    }

    @Test
    void appendNormalizedSummariesIncludesStepIdStatusAndBody() {
        aggregator.accumulate(record("step-1", "response-body", 200, Map.of()));

        AggregatedOutput output = aggregator.aggregate(planSummaries());

        String body = output.body();
        assertTrue(body.contains("[Step: step-1]"), "Summary should contain step id");
        assertTrue(body.contains("Status: 200"), "Summary should contain status code");
        assertTrue(body.contains("response-body"), "Summary should contain response body");
    }

    @Test
    void appendNormalizedSummariesMultipleStepsInOrder() {
        aggregator.accumulate(record("step-a", "body-a", 200, Map.of()));
        aggregator.accumulate(record("step-b", "body-b", 404, Map.of()));

        AggregatedOutput output = aggregator.aggregate(planSummaries());

        String body = output.body();
        int posA = body.indexOf("[Step: step-a]");
        int posB = body.indexOf("[Step: step-b]");
        assertTrue(posA >= 0, "step-a summary should be present");
        assertTrue(posB >= 0, "step-b summary should be present");
        assertTrue(posA < posB, "step-a summary should appear before step-b");
        assertTrue(body.contains("Status: 404"), "step-b status 404 should be in output");
    }

    @Test
    void nullResponseBodyTreatedAsEmpty() {
        // StepExecutionRecord normalizes null to empty string internally
        aggregator.accumulate(record("step-1", null, 200, Map.of()));
        aggregator.accumulate(record("step-2", "body-two", 200, Map.of()));

        AggregatedOutput output = aggregator.aggregate(planRaw());

        assertEquals("body-two", output.body(),
                "Null response body should be treated as empty");
    }

    @Test
    void appendSelectedExtractsIncludesExtractedVariables() {
        Map<String, String> extracted = Map.of("csrfToken", "abc123", "sessionId", "xyz789");
        aggregator.accumulate(record("step-1", "body", 200, extracted));

        AggregatedOutput output = aggregator.aggregate(planExtracts(List.of("csrfToken")));

        String body = output.body();
        assertTrue(body.contains("csrfToken=abc123"), "Selected variable should be in output");
        assertFalse(body.contains("sessionId"), "Non-selected variable should be excluded");
    }

    @Test
    void appendSelectedExtractsWithNoFilterIncludesAllVariables() {
        Map<String, String> extracted = Map.of("token", "tok1", "userId", "uid1");
        aggregator.accumulate(record("step-1", "body", 200, extracted));

        AggregatedOutput output = aggregator.aggregate(planExtracts(List.of()));

        String body = output.body();
        assertTrue(body.contains("token=tok1"), "token variable should appear");
        assertTrue(body.contains("userId=uid1"), "userId variable should appear");
    }

    @Test
    void appendRawResponsesExtractsBodyFromFullHttpResponse() {
        String fullResponse = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nbody-only";
        aggregator.accumulate(record("step-1", fullResponse, 200, Map.of()));

        AggregatedOutput output = aggregator.aggregate(planRaw());

        assertEquals("body-only", output.body(),
                "Raw mode should extract only the body, not headers or status line");
    }

    @Test
    void appendNormalizedSummariesExtractsBodyFromFullHttpResponse() {
        String fullResponse = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\n\r\nerror body";
        aggregator.accumulate(record("step-1", fullResponse, 404, Map.of()));

        AggregatedOutput output = aggregator.aggregate(planSummaries());

        String body = output.body();
        assertFalse(body.contains("HTTP/1.1"), "Summary should not contain the status line");
        assertFalse(body.contains("Content-Type"), "Summary should not contain headers");
        assertTrue(body.contains("error body"), "Summary should contain only the response body");
        assertTrue(body.contains("Status: 404"), "Summary should contain status code");
    }

    @Test
    void appendRawResponseesExtractsBodyWithLfOnlySeparator() {
        String fullResponse = "HTTP/1.1 200 OK\n\nbody-lf-only";
        aggregator.accumulate(record("step-1", fullResponse, 200, Map.of()));

        AggregatedOutput output = aggregator.aggregate(planRaw());

        assertEquals("body-lf-only", output.body(),
                "Raw mode should handle LF-only separator");
    }
}

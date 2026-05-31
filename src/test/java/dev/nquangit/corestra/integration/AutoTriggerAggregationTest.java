package dev.nquangit.corestra.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoTriggerAggregationTest {

    // ── encodeAggregatedBody / decodeAggregatedBody round-trip ────────────────

    @Test
    void encodeDecodRoundTrip() {
        String body = "hello world";
        String encoded = AutoTriggerHttpHandler.encodeAggregatedBody(body);
        assertEquals(body, AutoTriggerHttpHandler.decodeAggregatedBody(encoded));
    }

    @Test
    void decodeEmptyReturnsEmpty() {
        assertEquals("", AutoTriggerHttpHandler.decodeAggregatedBody(""));
        assertEquals("", AutoTriggerHttpHandler.decodeAggregatedBody(null));
    }

    @Test
    void decodeMalformedBase64ReturnsEmpty() {
        assertEquals("", AutoTriggerHttpHandler.decodeAggregatedBody("!!!not-base64!!!"));
    }

    @Test
    void encodeNullBodyTreatedAsEmpty() {
        String encoded = AutoTriggerHttpHandler.encodeAggregatedBody(null);
        assertEquals("", AutoTriggerHttpHandler.decodeAggregatedBody(encoded));
    }

    // ── appendToAggregated ────────────────────────────────────────────────────

    @Test
    void appendRawResponsesOnEmptyStartsWithNewBody() {
        String result = AutoTriggerHttpHandler.appendToAggregated(
                "", "body1", 200, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "step-1");
        assertEquals("body1", AutoTriggerHttpHandler.decodeAggregatedBody(result));
    }

    @Test
    void appendRawResponsesConcatenatesSubsequentBodies() {
        String after1 = AutoTriggerHttpHandler.appendToAggregated(
                "", "body1", 200, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "step-1");
        String after2 = AutoTriggerHttpHandler.appendToAggregated(
                after1, "body2", 200, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "step-1");
        assertEquals("body1body2", AutoTriggerHttpHandler.decodeAggregatedBody(after2));
    }

    @Test
    void appendNormalizedSummariesIncludesStepIdAndStatus() {
        String result = AutoTriggerHttpHandler.appendToAggregated(
                "", "resp", 401, AggregationPlan.Mode.APPEND_NORMALIZED_SUMMARIES, "step-x");
        String body = AutoTriggerHttpHandler.decodeAggregatedBody(result);
        assertTrue(body.contains("[Step: step-x]"), "should contain step id");
        assertTrue(body.contains("401"), "should contain status code");
        assertTrue(body.contains("resp"), "should contain response body");
    }

    @Test
    void appendSelectedExtractsProducesEmptyContribution() {
        String result = AutoTriggerHttpHandler.appendToAggregated(
                "", "body", 200, AggregationPlan.Mode.APPEND_SELECTED_EXTRACTS, "step-1");
        assertEquals("", AutoTriggerHttpHandler.decodeAggregatedBody(result));
    }

    @Test
    void appendNullBodyTreatedAsEmpty() {
        String result = AutoTriggerHttpHandler.appendToAggregated(
                "", null, 200, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "step-1");
        assertEquals("", AutoTriggerHttpHandler.decodeAggregatedBody(result));
    }

    // ── extractBodyFromRaw ────────────────────────────────────────────────────

    @Test
    void extractBodyFromRawCrlfSeparator() {
        String raw = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"token\":\"abc\"}";
        assertEquals("{\"token\":\"abc\"}", AutoTriggerHttpHandler.extractBodyFromRaw(raw));
    }

    @Test
    void extractBodyFromRawLfSeparator() {
        String raw = "HTTP/1.1 200 OK\nContent-Type: text/plain\n\nhello";
        assertEquals("hello", AutoTriggerHttpHandler.extractBodyFromRaw(raw));
    }

    @Test
    void extractBodyFromRawEmptyReturnsEmpty() {
        assertEquals("", AutoTriggerHttpHandler.extractBodyFromRaw(""));
        assertEquals("", AutoTriggerHttpHandler.extractBodyFromRaw(null));
    }

    @Test
    void extractBodyFromRawNoSeparatorReturnsEmpty() {
        assertEquals("", AutoTriggerHttpHandler.extractBodyFromRaw("HTTP/1.1 200 OK"));
    }

    // ── FlowStep.includeInAggregation Jackson round-trip ─────────────────────

    @Test
    void flowStepIncludeInAggregationDefaultsFalseOnLegacyJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FlowStep step = sampleStep();
        String json = mapper.writeValueAsString(step);
        // Remove the field to simulate legacy JSON.
        String legacy = json.replace("\"includeInAggregation\":false,", "")
                            .replace(",\"includeInAggregation\":false", "");
        FlowStep restored = mapper.readValue(legacy, FlowStep.class);
        assertFalse(restored.includeInAggregation());
    }

    @Test
    void flowStepIncludeInAggregationRoundTrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        FlowStep step = sampleStep().withIncludeInAggregation(true);
        FlowStep restored = mapper.readValue(mapper.writeValueAsString(step), FlowStep.class);
        assertTrue(restored.includeInAggregation());
    }

    @Test
    void withIncludeInAggregationPreservesOtherFields() {
        FlowStep original = sampleStep();
        FlowStep updated = original.withIncludeInAggregation(true);
        assertEquals(original.id(), updated.id());
        assertEquals(original.name(), updated.name());
        assertEquals(original.triggerRule(), updated.triggerRule());
        assertTrue(updated.includeInAggregation());
    }

    private static FlowStep sampleStep() {
        return new FlowStep(
                "step-id", "Step name", true, "",
                new StoredRequestTemplate("GET", "https", "example.com", 443,
                        "/path", List.of(), ""),
                List.of(), List.of(), List.of(),
                FlowStep.StepErrorStrategy.FAIL_FLOW);
    }
}

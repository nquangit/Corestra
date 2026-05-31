package dev.nquangit.corestra.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AggregationPlanTest {

    @Test
    void disabledFactoryDefaultsUseTriggeredStepToFalse() {
        assertFalse(AggregationPlan.disabled().useTriggeredStep());
    }

    @Test
    void fourArgCompatConstructorDefaultsUseTriggeredStepToFalse() {
        AggregationPlan plan = new AggregationPlan(false, AggregationPlan.Mode.APPEND_RAW_RESPONSES, null, List.of());
        assertFalse(plan.useTriggeredStep());
    }

    @Test
    void enabledWithUseTriggeredStepIsValidWithoutFinalStepId() {
        // Must not throw — useTriggeredStep=true relaxes the finalStepId constraint.
        assertDoesNotThrow(() -> new AggregationPlan(
                true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, null, List.of(), true));
    }

    @Test
    void enabledWithoutUseTriggeredStepRequiresFinalStepId() {
        assertThrows(IllegalArgumentException.class, () ->
                new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, null, List.of(), false));
    }

    @Test
    void jacksonRoundTripPreservesUseTriggeredStep() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AggregationPlan original = new AggregationPlan(
                true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, null, List.of(), true);
        String json = mapper.writeValueAsString(original);
        AggregationPlan deserialized = mapper.readValue(json, AggregationPlan.class);
        assertEquals(original, deserialized);
    }

    @Test
    void jacksonDeserializesLegacyJsonWithoutUseTriggeredStepDefaultsToFalse() throws Exception {
        String legacyJson = "{\"enabled\":true,\"mode\":\"APPEND_RAW_RESPONSES\","
                + "\"finalStepId\":\"step-1\",\"selectedExtractVariables\":[]}";
        ObjectMapper mapper = new ObjectMapper();
        AggregationPlan plan = mapper.readValue(legacyJson, AggregationPlan.class);
        assertFalse(plan.useTriggeredStep());
        assertTrue(plan.enabled());
    }
}

package dev.nquangit.corestra.access.scenario;

import dev.nquangit.corestra.access.matrix.ScenarioPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioInstanceTest {
    @Test
    void stripsScenarioPrefixAndExposesPlainVariableMap() {
        ScenarioVariable variable = new ScenarioVariable(
                "scenario.confirmToken",
                "confirm-123",
                1000L,
                0L,
                false,
                false,
                "flow-1",
                "step-2",
                "data-1");
        ScenarioInstance scenario = new ScenarioInstance(
                "scenario-1",
                "Transfer setup",
                "data-1",
                Map.of("ignored-key", variable),
                ScenarioPolicy.REUSE_EXISTING,
                1000L,
                false,
                "flow-1",
                "step-3");

        assertEquals("confirmToken", variable.key());
        assertEquals(Map.of("confirmToken", "confirm-123"), scenario.toVariableMap());
        assertFalse(scenario.toVariableMap().containsKey("scenario.confirmToken"));
    }

    @Test
    void defensivelyCopiesVariables() {
        ScenarioVariable variable = variable("confirmToken", "confirm-123");
        LinkedHashMap<String, ScenarioVariable> variables = new LinkedHashMap<>();
        variables.put(variable.key(), variable);

        ScenarioInstance scenario = new ScenarioInstance(
                "scenario-1",
                "Transfer setup",
                "data-1",
                variables,
                ScenarioPolicy.REUSE_EXISTING,
                1000L,
                false,
                "flow-1",
                "");
        variables.clear();

        assertEquals(Map.of("confirmToken", variable), scenario.variables());
        assertThrows(UnsupportedOperationException.class,
                () -> scenario.variables().put("other", variable("other", "value")));
    }

    @Test
    void detectsExpiredVariables() {
        ScenarioInstance scenario = new ScenarioInstance(
                "scenario-1",
                "Transfer setup",
                "data-1",
                Map.of("confirmToken", new ScenarioVariable(
                        "confirmToken",
                        "confirm-123",
                        1000L,
                        2000L,
                        false,
                        false,
                        "flow-1",
                        "step-2",
                        "data-1")),
                ScenarioPolicy.REUSE_EXISTING,
                1000L,
                false,
                "flow-1",
                "");

        assertTrue(scenario.isExpired(Clock.fixed(Instant.ofEpochMilli(2000L), ZoneOffset.UTC)));
        assertFalse(scenario.isExpired(Clock.fixed(Instant.ofEpochMilli(1999L), ZoneOffset.UTC)));
    }

    private static ScenarioVariable variable(String key, String value) {
        return new ScenarioVariable(key, value, 1000L, 0L, false, false, "flow-1", "step-1", "data-1");
    }
}

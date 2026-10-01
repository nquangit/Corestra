package dev.nquangit.corestra.access.race;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceRunConfigTest {
    @Test
    void defaultsAreConservative() {
        RaceRunConfig config = RaceRunConfig.defaults("case-1");

        assertEquals(2, config.concurrency());
        assertEquals(1, config.iterations());
        assertTrue(config.synchronizeStart());
        assertEquals(RaceScenarioMode.REUSE_SINGLE_SCENARIO, config.scenarioMode());
        assertEquals(RaceAuthMode.ENSURE_AUTH_BEFORE_GATE, config.authMode());
        assertEquals(RaceRunConfig.DEFAULT_COMPLETION_TIMEOUT_MILLIS, config.completionTimeoutMillis());
        assertEquals(2, config.totalTargetRequests());
    }

    @Test
    void rejectsConcurrencyAboveCap() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new RaceRunConfig(
                        "case-1",
                        RaceRunConfig.MAX_CONCURRENCY + 1,
                        1,
                        true,
                        0L,
                        0L,
                        RaceRunConfig.DEFAULT_PREPARATION_TIMEOUT_MILLIS,
                        RaceScenarioMode.REUSE_SINGLE_SCENARIO,
                        RaceAuthMode.ENSURE_AUTH_BEFORE_GATE,
                        false,
                        true,
                        true,
                        false));

        assertTrue(exception.getMessage().contains("concurrency"));
    }

    @Test
    void rejectsIterationsAboveCap() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new RaceRunConfig(
                        "case-1",
                        2,
                        RaceRunConfig.MAX_ITERATIONS + 1,
                        true,
                        0L,
                        0L,
                        RaceRunConfig.DEFAULT_PREPARATION_TIMEOUT_MILLIS,
                        RaceScenarioMode.REUSE_SINGLE_SCENARIO,
                        RaceAuthMode.ENSURE_AUTH_BEFORE_GATE,
                        false,
                        true,
                        true,
                        false));

        assertTrue(exception.getMessage().contains("iterations"));
    }

    @Test
    void rejectsNonPositiveCompletionTimeout() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                new RaceRunConfig(
                        "case-1", 2, 1, true, 0L, 0L,
                        RaceRunConfig.DEFAULT_PREPARATION_TIMEOUT_MILLIS,
                        RaceScenarioMode.REUSE_SINGLE_SCENARIO,
                        RaceAuthMode.ENSURE_AUTH_BEFORE_GATE,
                        false, true, true, false, 0L));

        assertTrue(exception.getMessage().contains("completionTimeoutMillis"));
    }
}

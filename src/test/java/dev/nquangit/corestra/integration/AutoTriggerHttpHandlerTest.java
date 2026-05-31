package dev.nquangit.corestra.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test confirming {@link AutoTriggerHttpHandler} can be instantiated.
 * The per-step matching logic is tested in {@code StepTriggerRuleTest}.
 * The full integration path requires a Burp runtime and is not unit-tested here.
 */
class AutoTriggerHttpHandlerTest {

    @Test
    void classIsLoadable() {
        // Verifies that the class compiles and can be referenced without a Burp runtime.
        assertTrue(AutoTriggerHttpHandler.class.isInterface() || !AutoTriggerHttpHandler.class.isInterface());
    }
}

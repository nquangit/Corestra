package dev.nquangit.corestra.integration;

import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the orchestration logic in {@link CorestraSessionHandlingAction}.
 *
 * <p>The {@code performAction} method's only untested line is the Montoya {@code ActionResult}
 * factory call (which requires a live Burp runtime); the flow-selection and running behaviour is
 * exercised here through the package-private {@link CorestraSessionHandlingAction#runApplicableFlows}.</p>
 */
class CorestraSessionHandlingActionTest {

    @Test
    void runsEachApplicableFlowOnce() {
        FlowDefinition on1 = flow("f1", true, true);
        FlowDefinition off = flow("f2", true, false);
        FlowDefinition on2 = flow("f3", true, true);
        List<String> ran = new ArrayList<>();

        CorestraSessionHandlingAction action = action(List.of(on1, off, on2), ran::add);
        action.runApplicableFlows();

        assertEquals(List.of("f1", "f3"), ran);
    }

    @Test
    void nameReturnsConfiguredValue() {
        CorestraSessionHandlingAction action = action(List.of(), f -> {});
        assertEquals("Corestra: run prerequisite flows", action.name());
    }

    @Test
    void aFailingFlowDoesNotStopRemainingFlows() {
        FlowDefinition a = flow("a", true, true);
        FlowDefinition b = flow("b", true, true);
        List<String> ran = new ArrayList<>();

        CorestraSessionHandlingAction action = action(List.of(a, b), id -> {
            if (id.equals("a")) {
                throw new RuntimeException("boom");
            }
            ran.add(id);
        });
        action.runApplicableFlows();

        assertEquals(List.of("b"), ran, "flow b should still run after flow a fails");
    }

    @Test
    void noApplicableFlowsRunsNothing() {
        List<String> ran = new ArrayList<>();
        CorestraSessionHandlingAction action = action(List.of(flow("f1", true, false)), ran::add);
        action.runApplicableFlows();
        assertTrue(ran.isEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static CorestraSessionHandlingAction action(
            List<FlowDefinition> flows, java.util.function.Consumer<String> runById) {
        return new CorestraSessionHandlingAction(
                () -> flows,
                flow -> runById.accept(flow.id()),
                new SessionHandlingFlowMapper());
    }

    private static FlowDefinition flow(String id, boolean enabled, boolean sessionHandling) {
        return new FlowDefinition(
                id, "Flow " + id, "", enabled,
                List.of(),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                new FlowTriggerConfig(List.of(), List.of(), null, sessionHandling, List.of()));
    }
}

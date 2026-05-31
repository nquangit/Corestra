package dev.nquangit.corestra.integration;

import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SessionHandlingFlowMapper}.
 */
class SessionHandlingFlowMapperTest {

    private final SessionHandlingFlowMapper mapper = new SessionHandlingFlowMapper();

    @Test
    void selectsOnlyFlowsWithSessionHandlingEnabled() {
        FlowDefinition on = flow("f1", "On", true, true);
        FlowDefinition off = flow("f2", "Off", true, false);

        List<FlowDefinition> result = mapper.applicableFlows(List.of(on, off));

        assertEquals(List.of(on), result);
    }

    @Test
    void excludesDisabledFlowsEvenWhenSessionHandlingEnabled() {
        FlowDefinition disabled = flow("f1", "Disabled", false, true);

        assertTrue(mapper.applicableFlows(List.of(disabled)).isEmpty());
    }

    @Test
    void preservesOrderOfApplicableFlows() {
        FlowDefinition a = flow("a", "A", true, true);
        FlowDefinition b = flow("b", "B", true, true);

        assertEquals(List.of(a, b), mapper.applicableFlows(List.of(a, b)));
    }

    @Test
    void emptyInputYieldsEmptyResult() {
        assertTrue(mapper.applicableFlows(List.of()).isEmpty());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static FlowDefinition flow(String id, String name, boolean enabled, boolean sessionHandling) {
        return new FlowDefinition(
                id, name, "", enabled,
                List.of(),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                new FlowTriggerConfig(List.of(), List.of(), null, sessionHandling, List.of()));
    }
}

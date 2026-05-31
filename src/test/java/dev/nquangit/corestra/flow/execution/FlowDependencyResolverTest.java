package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FlowDependencyResolver}.
 */
class FlowDependencyResolverTest {

    private FlowDependencyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new FlowDependencyResolver();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates a flow with no prerequisite flows. */
    private static FlowDefinition flowWithNoPrereqs(String id) {
        return new FlowDefinition(
                id,
                "Flow " + id,
                null,
                true,
                List.of(),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    /** Creates a flow that declares the given prerequisite flow ids. */
    private static FlowDefinition flowWithPrereqs(String id, List<String> prereqFlowIds) {
        FlowTriggerConfig trigger = new FlowTriggerConfig(
                List.of(),         // prerequisiteStepIds (intra-flow)
                prereqFlowIds,     // prerequisiteFlowIds (inter-flow)
                null,
                false,
                List.of());
        return new FlowDefinition(
                id,
                "Flow " + id,
                null,
                true,
                List.of(),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                trigger);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void flowWithNoPrerequisitesReturnsEmptyList() {
        FlowDefinition target = flowWithNoPrereqs("target");
        Map<String, FlowDefinition> allFlows = Map.of("target", target);

        List<FlowDefinition> prereqs = resolver.resolvePrerequisites(target, allFlows);

        assertTrue(prereqs.isEmpty(), "Expected no prerequisites for a flow with none declared");
    }

    @Test
    void linearChainResolvesInCorrectOrder() {
        // A <- B <- C  (C depends on B, B depends on A)
        FlowDefinition flowA = flowWithNoPrereqs("A");
        FlowDefinition flowB = flowWithPrereqs("B", List.of("A"));
        FlowDefinition flowC = flowWithPrereqs("C", List.of("B"));

        Map<String, FlowDefinition> allFlows = Map.of("A", flowA, "B", flowB, "C", flowC);

        List<FlowDefinition> prereqs = resolver.resolvePrerequisites(flowC, allFlows);

        assertEquals(2, prereqs.size(), "Expected 2 prerequisites (A and B)");
        assertEquals("A", prereqs.get(0).id(), "A must appear before B");
        assertEquals("B", prereqs.get(1).id(), "B must appear before C");
    }

    @Test
    void cycleDetectionThrowsIllegalStateException() {
        // A -> B -> A (cycle)
        FlowDefinition flowA = flowWithPrereqs("A", List.of("B"));
        FlowDefinition flowB = flowWithPrereqs("B", List.of("A"));

        Map<String, FlowDefinition> allFlows = Map.of("A", flowA, "B", flowB);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> resolver.resolvePrerequisites(flowA, allFlows));
        assertTrue(ex.getMessage().contains("Cyclic dependency"), "Expected cycle message, got: " + ex.getMessage());
    }

    @Test
    void directSelfReferenceIsDetectedAsCycle() {
        // A -> A (self-reference)
        FlowDefinition flowA = flowWithPrereqs("A", List.of("A"));

        Map<String, FlowDefinition> allFlows = Map.of("A", flowA);

        assertThrows(IllegalStateException.class,
                () -> resolver.resolvePrerequisites(flowA, allFlows),
                "Self-reference should be detected as a cycle");
    }

    @Test
    void missingPrerequisiteFlowThrowsIllegalArgumentException() {
        // Target requires "missing-flow" which is not in allFlows
        FlowDefinition target = flowWithPrereqs("target", List.of("missing-flow"));

        Map<String, FlowDefinition> allFlows = Map.of("target", target);

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolvePrerequisites(target, allFlows),
                "Referencing a flow not in allFlows should throw IllegalArgumentException");
    }
}

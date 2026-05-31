package dev.nquangit.corestra.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.StepExecutionRecord;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDefinitionTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void constructorRejectsBlankIdentifiersAndDuplicateStepIds() {
        assertThrows(IllegalArgumentException.class, () -> new FlowDefinition(
                " ",
                "Login flow",
                null,
                true,
                List.of(),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none()));

        FlowStep firstStep = sampleStep("step-auth", true);
        FlowStep duplicateStep = sampleStep("step-auth", false);

        assertThrows(IllegalArgumentException.class, () -> new FlowDefinition(
                "flow-login",
                "Login flow",
                "Handles authentication prerequisites.",
                true,
                List.of(firstStep, duplicateStep),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none()));
    }

    @Test
    void constructorDefensivelyCopiesCollectionsAndExposesImmutableViews() {
        List<FlowStep> steps = new ArrayList<>();
        steps.add(sampleStep("step-auth", true));

        List<ExtractRule> sharedExtractRules = new ArrayList<>();
        sharedExtractRules.add(sampleSharedExtractRule());

        FlowDefinition flowDefinition = new FlowDefinition(
                "flow-login",
                "Login flow",
                "Captures and reuses dynamic authentication material.",
                true,
                steps,
                new FlowRuleSet(sharedExtractRules, List.of()),
                new AggregationPlan(true, AggregationPlan.Mode.APPEND_SELECTED_EXTRACTS, "step-auth", List.of("csrfToken")),
                FlowTriggerConfig.none());

        steps.clear();
        sharedExtractRules.clear();

        assertEquals(1, flowDefinition.steps().size());
        assertEquals(1, flowDefinition.rules().sharedExtractRules().size());
        assertThrows(UnsupportedOperationException.class, () -> flowDefinition.steps().add(sampleStep("step-extra", true)));
        assertThrows(UnsupportedOperationException.class, () -> flowDefinition.rules().sharedExtractRules().add(sampleSharedExtractRule()));
    }

    @Test
    void disabledStepsAndFollowUpHooksRoundTripThroughJackson() throws Exception {
        FlowDefinition original = sampleFlowDefinition();

        String json = OBJECT_MAPPER.writeValueAsString(original);
        FlowDefinition restored = OBJECT_MAPPER.readValue(json, FlowDefinition.class);
        ObjectNode serialized = (ObjectNode) OBJECT_MAPPER.readTree(json);
        ArrayNode steps = (ArrayNode) serialized.get("steps");

        assertEquals(original, restored);
        assertEquals("recover-session-flow", restored.triggerConfig().recoveryFlowId());
        assertFalse(restored.steps().get(1).enabled());
        assertTrue(serialized.has("aggregation"));
        assertFalse(serialized.has("aggregationPlan"));
        assertTrue(steps.get(0).has("notes"));
        assertFalse(steps.get(0).has("description"));
    }

    @Test
    void legacyAliasesDeserializeIntoEquivalentCurrentModel() throws Exception {
        FlowDefinition original = sampleFlowDefinition();
        ObjectNode legacyTree = OBJECT_MAPPER.valueToTree(original);
        ArrayNode legacySteps = (ArrayNode) legacyTree.get("steps");

        legacyTree.set("aggregationPlan", legacyTree.remove("aggregation"));
        for (int index = 0; index < legacySteps.size(); index++) {
            ObjectNode stepNode = (ObjectNode) legacySteps.get(index);
            stepNode.set("description", stepNode.remove("notes"));
        }

        FlowDefinition restored = OBJECT_MAPPER.treeToValue(legacyTree, FlowDefinition.class);

        assertEquals(original, restored);
        assertTrue(legacyTree.has("aggregationPlan"));
        assertFalse(legacyTree.has("aggregation"));
        assertTrue(((ObjectNode) legacySteps.get(0)).has("description"));
        assertFalse(((ObjectNode) legacySteps.get(0)).has("notes"));
    }

    @Test
    void constructorRejectsInvalidInternalStepReferences() {
        IllegalArgumentException invalidAggregation = assertThrows(IllegalArgumentException.class, () -> new FlowDefinition(
                "flow-login",
                "Login flow",
                null,
                true,
                List.of(sampleStep("step-auth", true), sampleStep("step-refresh", true)),
                FlowRuleSet.empty(),
                new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "missing-step", List.of()),
                FlowTriggerConfig.none()));
        assertTrue(invalidAggregation.getMessage().contains("aggregation.finalStepId"));

        IllegalArgumentException invalidTriggerStep = assertThrows(IllegalArgumentException.class, () -> new FlowDefinition(
                "flow-login",
                "Login flow",
                null,
                true,
                List.of(sampleStep("step-auth", true), sampleStep("step-refresh", true)),
                FlowRuleSet.empty(),
                new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "step-refresh", List.of()),
                new FlowTriggerConfig(List.of("missing-step"), List.of("bootstrap-flow"), null, false, List.of())));
        assertTrue(invalidTriggerStep.getMessage().contains("triggerConfig.prerequisiteStepIds"));

        IllegalArgumentException invalidPrecondition = assertThrows(IllegalArgumentException.class, () -> new FlowDefinition(
                "flow-login",
                "Login flow",
                null,
                true,
                List.of(
                        sampleStep("step-auth", true),
                        sampleStepWithPrecondition(
                                "step-refresh",
                                true,
                                new FlowStep.StepPrecondition(
                                        FlowStep.PreconditionType.STEP_COMPLETED,
                                        "missing-step",
                                        "Wait for a step that does not exist."))),
                FlowRuleSet.empty(),
                new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "step-refresh", List.of()),
                FlowTriggerConfig.none()));
        assertTrue(invalidPrecondition.getMessage().contains("STEP_COMPLETED preconditions"));
    }

    @Test
    void flowDefinitionWithUseTriggeredStepDoesNotRequireFinalStepId() {
        assertDoesNotThrow(() -> new FlowDefinition(
                "flow-login",
                "Login flow",
                null,
                true,
                List.of(sampleStep("step-auth", true)),
                FlowRuleSet.empty(),
                new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, null, List.of(), true),
                FlowTriggerConfig.none()));
    }

    @Test
    void flowDefinitionStillRejectsMissingFinalStepIdWithoutUseTriggeredStep() {
        assertThrows(IllegalArgumentException.class, () -> new FlowDefinition(
                "flow-login",
                "Login flow",
                null,
                true,
                List.of(sampleStep("step-auth", true)),
                FlowRuleSet.empty(),
                new AggregationPlan(true, AggregationPlan.Mode.APPEND_RAW_RESPONSES, "non-existent-step-id", List.of(), false),
                FlowTriggerConfig.none()));
    }

    @Test
    void rulesRejectMissingRequiredSelectorsWhilePreservingSelectorFreeShapes() {
        IllegalArgumentException invalidExtractSelector = assertThrows(IllegalArgumentException.class, () -> new ExtractRule(
                "extract-header",
                "Capture header",
                true,
                ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.HEADER,
                "  ",
                "headerValue",
                List.of()));
        assertTrue(invalidExtractSelector.getMessage().contains("sourceSelector"));

        IllegalArgumentException invalidInjectSelector = assertThrows(IllegalArgumentException.class, () -> new InjectRule(
                "inject-header",
                "Inject header",
                true,
                InjectRule.Target.REQUEST_HEADER,
                "  ",
                InjectRule.ValueSourceType.VARIABLE,
                "headerValue",
                null,
                InjectRule.WriteStrategy.OVERWRITE));
        assertTrue(invalidInjectSelector.getMessage().contains("targetSelector"));

        ExtractRule bodyTextRule = assertDoesNotThrow(() -> new ExtractRule(
                "extract-body",
                "Capture response body",
                true,
                ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.BODY_TEXT,
                null,
                "bodyText",
                List.of()));
        assertEquals("", bodyTextRule.sourceSelector());

        InjectRule rawBodyAppendRule = assertDoesNotThrow(() -> new InjectRule(
                "inject-body",
                "Append to body",
                true,
                InjectRule.Target.REQUEST_RAW_BODY_APPEND,
                null,
                InjectRule.ValueSourceType.STATIC,
                null,
                "tail",
                InjectRule.WriteStrategy.APPEND));
        assertEquals("", rawBodyAppendRule.targetSelector());
    }

    @Test
    void executionContextReturnsUpdatedCopiesWhenAugmented() {
        ExecutionContext initial = ExecutionContext.start(
                "run-001",
                "flow-login",
                Map.of("scope", "repeater"),
                1_715_000_000_000L);

        StepExecutionRecord record = new StepExecutionRecord(
                "step-auth",
                "POST /login HTTP/1.1\nHost: example.test\n\nuser=a",
                "HTTP/1.1 200 OK\nSet-Cookie: sid=123",
                200,
                true,
                null,
                1_715_000_000_100L,
                1_715_000_000_300L,
                Map.of("sid", "123"));

        ExecutionContext updated = initial
                .withVariable("csrfToken", "abc123")
                .withAggregationAppend("step-auth summary\n")
                .withStepRecord(record)
                .markCompleted(1_715_000_000_500L);

        assertNotSame(initial, updated);
        assertEquals(Map.of("scope", "repeater"), initial.metadata());
        assertEquals("abc123", updated.variables().get("csrfToken"));
        assertEquals("step-auth summary\n", updated.aggregationBuffer());
        assertEquals(List.of(record), updated.stepHistory().get("step-auth"));
        assertEquals(1_715_000_000_500L, updated.completedAtEpochMillis());
        assertThrows(UnsupportedOperationException.class, () -> updated.variables().put("extra", "value"));
    }

    private static FlowDefinition sampleFlowDefinition() {
        List<FlowStep> steps = List.of(
                sampleStep("step-auth", true),
                sampleStep("step-refresh", false));

        FlowRuleSet ruleSet = new FlowRuleSet(
                List.of(sampleSharedExtractRule()),
                List.of(sampleSharedInjectRule()));

        AggregationPlan aggregation = new AggregationPlan(
                true,
                AggregationPlan.Mode.APPEND_SELECTED_EXTRACTS,
                "step-refresh",
                List.of("csrfToken", "sessionCookie"));

        FlowTriggerConfig triggerConfig = new FlowTriggerConfig(
                List.of("step-auth"),
                List.of("bootstrap-flow"),
                "recover-session-flow",
                true,
                List.of(
                        new FlowTriggerConfig.FollowUpFlowHook(
                                FlowTriggerConfig.FollowUpCondition.ON_FAILURE,
                                "recover-session-flow"),
                        new FlowTriggerConfig.FollowUpFlowHook(
                                FlowTriggerConfig.FollowUpCondition.ON_SUCCESS,
                                "reporting-flow")));

        return new FlowDefinition(
                "flow-login",
                "Login flow",
                "Authenticates and refreshes a session for downstream tooling.",
                true,
                steps,
                ruleSet,
                aggregation,
                triggerConfig);
    }

    private static FlowStep sampleStep(String stepId, boolean enabled) {
        return sampleStepWithPrecondition(
                stepId,
                enabled,
                new FlowStep.StepPrecondition(
                        FlowStep.PreconditionType.VARIABLE_PRESENT,
                        "csrfToken",
                        "Requires a previously extracted CSRF token."));
    }

    private static FlowStep sampleStepWithPrecondition(String stepId, boolean enabled, FlowStep.StepPrecondition precondition) {
        StoredRequestTemplate requestTemplate = new StoredRequestTemplate(
                "POST",
                "https",
                "example.test",
                443,
                "/login?next=%2Fdashboard",
                List.of("Host: example.test", "Content-Type: application/x-www-form-urlencoded"),
                "username=alice&password=secret");

        ExtractRule extractRule = new ExtractRule(
                "extract-csrf",
                "Capture CSRF token",
                true,
                ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.REGEX,
                "name=\\\"csrf\\\" value=\\\"([^\\\"]+)\\\"",
                "csrfToken",
                List.of("trim"));

        InjectRule injectRule = new InjectRule(
                "inject-csrf",
                "Inject CSRF token",
                true,
                InjectRule.Target.REQUEST_FORM_FIELD,
                "csrf",
                InjectRule.ValueSourceType.VARIABLE,
                "csrfToken",
                null,
                InjectRule.WriteStrategy.OVERWRITE);

        return new FlowStep(
                stepId,
                "Authentication step",
                enabled,
                "Captured from the target login page.",
                requestTemplate,
                List.of(extractRule),
                List.of(injectRule),
                List.of(precondition),
                FlowStep.StepErrorStrategy.CONTINUE_FLOW);
    }

    private static ExtractRule sampleSharedExtractRule() {
        return new ExtractRule(
                "extract-session-cookie",
                "Capture session cookie",
                true,
                ExtractRule.SourceSide.RESPONSE,
                ExtractRule.ExtractorType.COOKIE,
                "sid",
                "sessionCookie",
                List.of());
    }

    private static InjectRule sampleSharedInjectRule() {
        return new InjectRule(
                "inject-trace-header",
                "Inject trace header",
                true,
                InjectRule.Target.REQUEST_HEADER,
                "X-Corestra-Trace",
                InjectRule.ValueSourceType.STATIC,
                null,
                "enabled",
                InjectRule.WriteStrategy.OVERWRITE);
    }
}

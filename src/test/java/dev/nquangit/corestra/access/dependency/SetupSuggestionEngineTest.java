package dev.nquangit.corestra.access.dependency;

import dev.nquangit.corestra.access.matrix.ScenarioPolicy;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowStep.StepErrorStrategy;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.ExtractRule.ExtractorType;
import dev.nquangit.corestra.flow.rule.ExtractRule.SourceSide;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupSuggestionEngineTest {
    private final SetupSuggestionEngine engine = new SetupSuggestionEngine();

    @Test
    void targetStepRequiredVariableProducedEarlierSuggestsRunPrefix() {
        FlowDefinition flow = flow(List.of(
                step("setup", "/setup", List.of(extract("scenario.confirmToken"))),
                step("target", "/confirm/{{scenario.confirmToken}}", List.of())));

        SetupSuggestionResult result = engine.suggestForTargetStep(flow, "target", RenderMode.STRICT);

        assertTrue(result.success());
        assertEquals(SetupMode.RUN_PREFIX, result.suggestedSetup().mode());
        assertEquals(flow.id(), result.suggestedSetup().setupFlowId());
        assertEquals("target", result.suggestedSetup().untilBeforeStepId());
        assertEquals(ScenarioPolicy.FRESH_PER_TEST, result.suggestedSetup().scenarioPolicy());
        assertEquals(Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")),
                result.requiredScenarioVariables());
        assertEquals("setup", result.producerStepIds()
                .get(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")));
    }

    @Test
    void targetStepWithNoScenarioVariablesNeedsNoSetup() {
        FlowDefinition flow = flow(List.of(step("target", "/accounts/{{data.accountId}}", List.of())));

        SetupSuggestionResult result = engine.suggestForTargetStep(flow, "target", RenderMode.STRICT);

        assertTrue(result.success());
        assertEquals(SetupMode.NONE, result.suggestedSetup().mode());
        assertTrue(result.requiredScenarioVariables().isEmpty());
    }

    @Test
    void missingProducerRequiresManualSetupAndWarns() {
        FlowDefinition flow = flow(List.of(step("target", "/confirm/{{scenario.confirmToken}}", List.of())));

        SetupSuggestionResult result = engine.suggestForTargetStep(flow, "target", RenderMode.STRICT);

        assertFalse(result.success());
        assertEquals(SetupMode.NONE, result.suggestedSetup().mode());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("Missing producer")));
        assertTrue(result.reason().contains("Manual setup"));
    }

    @Test
    void producerAfterTargetWarnsThatPrefixCannotSatisfyDependency() {
        FlowDefinition flow = flow(List.of(
                step("target", "/confirm/{{scenario.confirmToken}}", List.of()),
                step("late-setup", "/setup", List.of(extract("scenario.confirmToken")))));

        SetupSuggestionResult result = engine.suggestForTargetStep(flow, "target", RenderMode.STRICT);

        assertFalse(result.success());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("after target")));
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("No producer before target")));
    }

    @Test
    void multipleProducersWarnAndNearestBeforeTargetIsSelected() {
        FlowDefinition flow = flow(List.of(
                step("setup-1", "/setup-1", List.of(extract("scenario.confirmToken"))),
                step("setup-2", "/setup-2", List.of(extract("scenario.confirmToken"))),
                step("target", "/confirm/{{scenario.confirmToken}}", List.of())));

        SetupSuggestionResult result = engine.suggestForTargetStep(flow, "target", RenderMode.STRICT);

        assertTrue(result.success());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("Multiple producers")));
        assertEquals("setup-2", result.producerStepIds()
                .get(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")));
    }

    @Test
    void suggestionDoesNotMutateFlowDefinition() {
        FlowDefinition flow = flow(List.of(
                step("setup", "/setup", List.of(extract("scenario.confirmToken"))),
                step("target", "/confirm/{{scenario.confirmToken}}", List.of())));
        List<FlowStep> originalSteps = flow.steps();

        engine.suggestForTargetStep(flow, "target", RenderMode.STRICT);

        assertEquals(originalSteps, flow.steps());
    }

    private static FlowDefinition flow(List<FlowStep> steps) {
        return new FlowDefinition(
                "flow-1",
                "Flow",
                "",
                true,
                steps,
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private static FlowStep step(String id, String path, List<ExtractRule> extractRules) {
        return new FlowStep(
                id,
                id,
                true,
                "",
                new StoredRequestTemplate("GET", "https", "example.test", 443, path, List.of(), ""),
                extractRules,
                List.of(),
                List.of(),
                StepErrorStrategy.FAIL_FLOW);
    }

    private static ExtractRule extract(String targetVariableName) {
        return new ExtractRule(
                "extract-" + targetVariableName.replace('.', '-'),
                "Extract",
                true,
                SourceSide.RESPONSE,
                ExtractorType.BODY_TEXT,
                "",
                targetVariableName,
                List.of());
    }
}

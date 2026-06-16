package dev.nquangit.corestra.access.scenario;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowStep.StepErrorStrategy;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.flow.execution.StepExecutionRecord;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.ExtractRule.ExtractorType;
import dev.nquangit.corestra.flow.rule.ExtractRule.SourceSide;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioSetupFlowRunnerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC);

    @Test
    void runPrefixExecutesOnlyEnabledStepsBeforeTargetAndDoesNotMutateOriginalFlow() {
        CapturingSetupExecutor executor = new CapturingSetupExecutor(Map.of(
                "step-1", Map.of("scenario.first", "first"),
                "step-2", Map.of("scenario.second", "second"),
                "step-3", Map.of("scenario.third", "third")));
        ScenarioSetupFlowRunner runner = runner(executor);
        FlowDefinition flow = flow(List.of(
                step("step-1", "scenario.first"),
                step("step-2", "scenario.second"),
                step("step-3", "scenario.third")));
        List<FlowStep> originalSteps = flow.steps();

        ScenarioSetupRunResult result = runner.runSetup(
                flow,
                "step-3",
                AuthProfile.manual("auth-1", "Owner", "admin"),
                dataProfile(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.success());
        assertEquals(1, executor.count());
        assertEquals(List.of("step-1", "step-2"), executor.stepIds());
        assertEquals(Map.of("first", "first", "second", "second"), result.scenarioVariables());
        assertEquals(originalSteps, flow.steps());
    }

    @Test
    void runPrefixFailsWhenTargetStepIsNotFound() {
        CapturingSetupExecutor executor = new CapturingSetupExecutor(Map.of());
        ScenarioSetupRunResult result = runner(executor).runSetup(
                flow(List.of(step("step-1", "scenario.first"))),
                "missing-step",
                AuthProfile.manual("auth-1", "Owner", "admin"),
                dataProfile(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.failed());
        assertTrue(result.reason().contains("missing-step"));
        assertEquals(0, executor.count());
    }

    @Test
    void runPrefixFailsWhenUntilBeforeStepIsFirstStep() {
        CapturingSetupExecutor executor = new CapturingSetupExecutor(Map.of());
        ScenarioSetupRunResult result = runner(executor).runSetup(
                flow(List.of(step("step-1", "scenario.first"), step("step-2", "scenario.second"))),
                "step-1",
                AuthProfile.manual("auth-1", "Owner", "admin"),
                dataProfile(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.failed());
        assertTrue(result.reason().contains("no executable steps"));
        assertEquals(0, executor.count());
    }

    @Test
    void extractedScenarioPrefixedVariableBecomesPlainScenarioKey() {
        CapturingSetupExecutor executor = new CapturingSetupExecutor(Map.of(
                "step-1", Map.of("scenario.confirmToken", "confirm-123")));
        ScenarioSetupRunResult result = runner(executor).runSetup(
                flow(List.of(step("step-1", "scenario.confirmToken"))),
                "",
                AuthProfile.manual("auth-1", "Owner", "admin"),
                dataProfile(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.success());
        assertEquals(Map.of("confirmToken", "confirm-123"), result.scenarioVariables());
        assertFalse(result.scenarioVariables().containsKey("scenario.confirmToken"));
    }

    @Test
    void plainExtractedVariableIsAcceptedWithRecommendationWarning() {
        CapturingSetupExecutor executor = new CapturingSetupExecutor(Map.of(
                "step-1", Map.of("confirmToken", "confirm-123")));
        ScenarioSetupRunResult result = runner(executor).runSetup(
                flow(List.of(step("step-1", "confirmToken"))),
                "",
                AuthProfile.manual("auth-1", "Owner", "admin"),
                dataProfile(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.success());
        assertEquals(Map.of("confirmToken", "confirm-123"), result.scenarioVariables());
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("scenario.confirmToken")));
    }

    private static ScenarioSetupFlowRunner runner(CapturingSetupExecutor executor) {
        return new ScenarioSetupFlowRunner(executor, CLOCK);
    }

    private static FlowDefinition flow(List<FlowStep> steps) {
        return new FlowDefinition(
                "flow-setup",
                "Setup",
                "",
                true,
                steps,
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private static FlowStep step(String id, String targetVariableName) {
        return new FlowStep(
                id,
                id,
                true,
                "",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/" + id, List.of(), ""),
                List.of(new ExtractRule(
                        "extract-" + id,
                        "Extract " + id,
                        true,
                        SourceSide.RESPONSE,
                        ExtractorType.BODY_TEXT,
                        "",
                        targetVariableName,
                        List.of())),
                List.of(),
                List.of(),
                StepErrorStrategy.FAIL_FLOW);
    }

    private static DataProfile dataProfile() {
        return DataProfile.enabled("data-1", "Owner data", "auth-1", Map.of("accountId", "acct-1"));
    }

    private static StepExecutionRecord record(String stepId, Map<String, String> extractedVariables) {
        return new StepExecutionRecord(
                stepId,
                "",
                "",
                200,
                true,
                "",
                1000L,
                1000L,
                extractedVariables);
    }

    private static final class CapturingSetupExecutor implements ScenarioSetupFlowRunner.SetupFlowExecutor {
        private final Map<String, Map<String, String>> extractedByStepId;
        private List<String> stepIds = List.of();
        private int count;

        private CapturingSetupExecutor(Map<String, Map<String, String>> extractedByStepId) {
            this.extractedByStepId = Map.copyOf(extractedByStepId);
        }

        @Override
        public ExecutionResult execute(FlowDefinition flow, ExecutionContext context) {
            count++;
            stepIds = flow.steps().stream().map(FlowStep::id).toList();
            List<StepExecutionRecord> records = flow.steps().stream()
                    .map(step -> record(step.id(), extractedByStepId.getOrDefault(step.id(), Map.of())))
                    .toList();
            return ExecutionResult.success(flow.id(), flow.name(), records);
        }

        private int count() {
            return count;
        }

        private List<String> stepIds() {
            return stepIds;
        }
    }
}

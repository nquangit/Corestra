package dev.nquangit.corestra.flow.execution;

import burp.api.montoya.http.message.requests.HttpRequest;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.control.ConditionMatchPolicy;
import dev.nquangit.corestra.flow.control.ConditionValueSource;
import dev.nquangit.corestra.flow.control.StepAction;
import dev.nquangit.corestra.flow.control.StepActionStatus;
import dev.nquangit.corestra.flow.control.StepActionType;
import dev.nquangit.corestra.flow.control.StepCondition;
import dev.nquangit.corestra.flow.control.StepConditionActionRule;
import dev.nquangit.corestra.flow.control.StepConditionEvaluator;
import dev.nquangit.corestra.flow.control.StepConditionOperator;
import dev.nquangit.corestra.flow.control.StepConditionSource;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.HttpClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowEngineConditionActionTest {

    @Test
    void breakActionStopsFollowingSteps() {
        QueueStepExecutor executor = new QueueStepExecutor();
        executor.add("step-1", record("step-1", 200, Map.of()));

        FlowStep step1 = step("step-1").withConditionActionRules(List.of(rule(
                action(StepActionType.BREAK_FLOW, Map.of()),
                statusCondition("200"))));
        FlowDefinition flow = flow("flow-1", step1, step("step-2"));

        ExecutionResult result = engine(executor).execute(flow, context("flow-1"));

        assertTrue(result.success());
        assertEquals(List.of("step-1"), executor.executedSteps());
        assertEquals(1, result.actionRecords().size());
        assertEquals(StepActionType.BREAK_FLOW, result.actionRecords().getFirst().actionType());
        assertTrue(result.completionReason().contains("stopped"));
    }

    @Test
    void retryCurrentStepRetriesOnlyCurrentStep() {
        QueueStepExecutor executor = new QueueStepExecutor();
        executor.add("step-1", record("step-1", 500, Map.of()));
        executor.add("step-1", record("step-1", 200, Map.of()));
        executor.add("step-2", record("step-2", 200, Map.of()));

        FlowStep step1 = step("step-1").withConditionActionRules(List.of(rule(
                action(StepActionType.RETRY_CURRENT_STEP, Map.of("maxAttempts", "1")),
                statusCondition("500"))));
        FlowDefinition flow = flow("flow-1", step1, step("step-2"));
        List<StepExecutionProgress> progress = new ArrayList<>();
        ExecutionResult result = engine(executor).execute(flow, context("flow-1"), progress::add);

        assertTrue(result.success());
        assertEquals(List.of("step-1", "step-1", "step-2"), executor.executedSteps());
        assertEquals(List.of(1, 2, 1), progress.stream().filter(e -> e.phase() == StepExecutionProgress.Phase.STARTED).map(StepExecutionProgress::attempt).toList());
        assertEquals(6, progress.size());
        assertEquals(3, result.stepRecords().size());
        assertEquals(StepActionStatus.COMPLETED, result.actionRecords().getFirst().status());
    }

    @Test
    void retryFlowFromStartRestartsAtFirstStep() {
        QueueStepExecutor executor = new QueueStepExecutor();
        executor.add("step-1", record("step-1", 200, Map.of()));
        executor.add("step-2", record("step-2", 500, Map.of()));
        executor.add("step-1", record("step-1", 200, Map.of()));
        executor.add("step-2", record("step-2", 200, Map.of()));

        FlowStep step2 = step("step-2").withConditionActionRules(List.of(rule(
                action(StepActionType.RETRY_FLOW_FROM_START, Map.of("maxAttempts", "1")),
                statusCondition("500"))));
        FlowDefinition flow = flow("flow-1", step("step-1"), step2);

        ExecutionResult result = engine(executor).execute(flow, context("flow-1"));

        assertTrue(result.success());
        assertEquals(List.of("step-1", "step-2", "step-1", "step-2"), executor.executedSteps());
        assertEquals(4, result.stepRecords().size());
    }

    @Test
    void sleepActionUsesInjectedSleeper() {
        QueueStepExecutor executor = new QueueStepExecutor();
        executor.add("step-1", record("step-1", 200, Map.of()));
        List<Long> sleeps = new ArrayList<>();

        FlowStep step = step("step-1").withConditionActionRules(List.of(rule(
                action(StepActionType.SLEEP, Map.of("seconds", "2")),
                statusCondition("200"))));

        ExecutionResult result = engine(executor, id -> Optional.empty(), sleeps::add).execute(
                flow("flow-1", step),
                context("flow-1"));

        assertTrue(result.success());
        assertEquals(List.of(2_000L), sleeps);
    }

    @Test
    void triggerFlowRunsResolvedFlowAndMergesVariables() {
        QueueStepExecutor executor = new QueueStepExecutor();
        executor.add("main-1", record("main-1", 200, Map.of()));
        executor.add("helper-1", record("helper-1", 200, Map.of("helperToken", "abc")));
        executor.add("main-2", record("main-2", 200, Map.of()));

        FlowDefinition helper = flow("helper-flow", step("helper-1"));
        FlowStep main1 = step("main-1").withConditionActionRules(List.of(rule(
                action(StepActionType.TRIGGER_FLOW, Map.of("flowId", "helper-flow")),
                statusCondition("200"))));
        FlowDefinition main = flow("main-flow", main1, step("main-2"));

        ExecutionResult result = engine(
                executor,
                id -> "helper-flow".equals(id) ? Optional.of(helper) : Optional.empty(),
                ignored -> { }).execute(main, context("main-flow"));

        assertTrue(result.success());
        assertEquals(List.of("main-1", "helper-1", "main-2"), executor.executedSteps());
        assertEquals("abc", executor.contextFor("main-2").variables().get("helperToken"));
        assertEquals(2, result.stepRecords().size());
        assertTrue(result.actionRecords().stream().anyMatch(record -> record.actionType() == StepActionType.TRIGGER_FLOW));
    }

    @Test
    void missingTriggeredFlowFailsCleanly() {
        QueueStepExecutor executor = new QueueStepExecutor();
        executor.add("step-1", record("step-1", 200, Map.of()));

        FlowStep step = step("step-1").withConditionActionRules(List.of(rule(
                action(StepActionType.TRIGGER_FLOW, Map.of("flowId", "missing-flow")),
                statusCondition("200"))));

        ExecutionResult result = engine(executor).execute(flow("flow-1", step), context("flow-1"));

        assertFalse(result.success());
        assertTrue(result.failureMessage().contains("Triggered flow not found"));
    }

    private static FlowEngine engine(QueueStepExecutor executor) {
        return engine(executor, id -> Optional.empty(), ignored -> { });
    }

    private static FlowEngine engine(
            QueueStepExecutor executor,
            FlowDefinitionResolver resolver,
            Sleeper sleeper) {
        return new FlowEngine(
                executor,
                request -> null,
                resolver,
                sleeper,
                new StepConditionEvaluator());
    }

    private static FlowDefinition flow(String id, FlowStep... steps) {
        return new FlowDefinition(
                id,
                id,
                "",
                true,
                List.of(steps),
                FlowRuleSet.empty(),
                null,
                FlowTriggerConfig.none());
    }

    private static FlowStep step(String id) {
        return new FlowStep(
                id,
                id,
                true,
                "",
                new StoredRequestTemplate("GET", "http", "example.test", 80, "/", List.of(), ""),
                List.of(),
                List.of(),
                List.of(),
                FlowStep.StepErrorStrategy.FAIL_FLOW);
    }

    private static StepConditionActionRule rule(StepAction action, StepCondition condition) {
        return new StepConditionActionRule(
                "rule-" + action.type(),
                "Rule",
                true,
                ConditionMatchPolicy.ALL,
                List.of(condition),
                List.of(action));
    }

    private static StepCondition statusCondition(String expectedStatus) {
        return new StepCondition(
                "condition-" + expectedStatus,
                "Status",
                true,
                StepConditionSource.RESPONSE_STATUS,
                "",
                StepConditionOperator.EQUALS,
                ConditionValueSource.STATIC,
                expectedStatus,
                true,
                false);
    }

    private static StepAction action(StepActionType type, Map<String, String> parameters) {
        return new StepAction(
                "action-" + type,
                type.name(),
                true,
                type,
                parameters);
    }

    private static ExecutionContext context(String flowId) {
        return ExecutionContext.start("run-1", flowId, Map.of(), 1_000L);
    }

    private static StepExecutionRecord record(String stepId, int status, Map<String, String> extractedVariables) {
        return new StepExecutionRecord(
                stepId,
                "GET / HTTP/1.1\r\n\r\n",
                "HTTP/1.1 " + status + " Status\r\n\r\n{}",
                status,
                true,
                "",
                1_000L,
                1_001L,
                extractedVariables);
    }

    private static final class QueueStepExecutor extends StepExecutor {
        private final Map<String, Queue<StepExecutionRecord>> records = new HashMap<>();
        private final List<String> executedSteps = new ArrayList<>();
        private final Map<String, ExecutionContext> contexts = new HashMap<>();

        private QueueStepExecutor() {
            super(new ExtractionService(), new InjectionService());
        }

        private void add(String stepId, StepExecutionRecord record) {
            records.computeIfAbsent(stepId, ignored -> new ArrayDeque<>()).add(record);
        }

        private List<String> executedSteps() {
            return List.copyOf(executedSteps);
        }

        private ExecutionContext contextFor(String stepId) {
            return contexts.get(stepId);
        }

        @Override
        public StepExecutionRecord execute(FlowStep step, ExecutionContext context, HttpClient httpClient) {
            executedSteps.add(step.id());
            contexts.put(step.id(), context);
            Queue<StepExecutionRecord> queue = records.get(step.id());
            if (queue == null || queue.isEmpty()) {
                return record(step.id(), 200, Map.of());
            }
            return queue.remove();
        }
    }

    @Test void observersArePerRunAndFailuresCannotChangeExecutionOrProduceSkippedStepEvents() {
        QueueStepExecutor executor = new QueueStepExecutor();
        var engine = engine(executor);
        var flow = flow("flow", step("one"), step("off").withEnabled(false), step("two"));
        List<StepExecutionProgress> events = new ArrayList<>();
        assertTrue(engine.execute(flow, context("flow"), events::add).success());
        assertEquals(List.of("one", "one", "two", "two"), events.stream().map(StepExecutionProgress::stepId).toList());
        int recorded = events.size();
        assertTrue(engine.execute(flow, context("flow"), event -> { throw new IllegalStateException("UI failure"); }).success());
        assertEquals(recorded, events.size());
    }
}

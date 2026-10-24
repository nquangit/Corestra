package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.session.SessionBindingException;
import dev.nquangit.corestra.interaction.InteractionAbortedException;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowStep.StepErrorStrategy;
import dev.nquangit.corestra.flow.control.StepAction;
import dev.nquangit.corestra.flow.control.StepActionExecutionRecord;
import dev.nquangit.corestra.flow.control.StepActionStatus;
import dev.nquangit.corestra.flow.control.StepActionType;
import dev.nquangit.corestra.flow.control.StepConditionActionRule;
import dev.nquangit.corestra.flow.control.StepConditionEvaluator;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.HttpClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Executes a complete {@link FlowDefinition} step by step.
 *
 * <p>For each enabled step the engine delegates to a {@link StepExecutor}, records the outcome
 * in the {@link ExecutionContext}, and stores the resulting variable values for use by subsequent
 * steps.
 *
 * <p>When a step fails, the configured {@link StepErrorStrategy} determines whether the flow
 * stops immediately ({@code FAIL_FLOW}) or continues with the remaining steps
 * ({@code CONTINUE_FLOW}).
 *
 * <p>Disabled steps are skipped and not recorded.
 */
public final class FlowEngine {
    private static final int DEFAULT_RETRY_LIMIT = 1;
    private static final int MAX_RETRY_LIMIT = 10;
    private static final long MAX_SLEEP_SECONDS = 300L;
    private static final int MAX_TRIGGER_DEPTH = 5;

    private final StepExecutor stepExecutor;
    private final HttpClient httpClient;
    private final StepConditionEvaluator conditionEvaluator;
    private final FlowDefinitionResolver flowResolver;
    private final Sleeper sleeper;
    private final List<Consumer<ExecutionResult>> executionListeners = new CopyOnWriteArrayList<>();

    /**
     * Creates a {@code FlowEngine}.
     *
     * @param extractionService the service used to apply extract rules; must not be {@code null}
     * @param injectionService  the service used to apply inject rules; must not be {@code null}
     * @param httpClient        the HTTP transport used to send requests; must not be {@code null}
     */
    public FlowEngine(
            ExtractionService extractionService,
            InjectionService injectionService,
            HttpClient httpClient) {
        this(extractionService, injectionService, httpClient, FlowDefinitionResolver.empty(), new ThreadSleeper());
    }

    /**
     * Creates a {@code FlowEngine} with support for triggered-flow actions.
     *
     * @param extractionService the service used to apply extract rules; must not be {@code null}
     * @param injectionService the service used to apply inject rules; must not be {@code null}
     * @param httpClient the HTTP transport used to send requests; must not be {@code null}
     * @param flowResolver resolver used by {@link StepActionType#TRIGGER_FLOW}
     * @param sleeper sleeper used by {@link StepActionType#SLEEP}
     */
    public FlowEngine(
            ExtractionService extractionService,
            InjectionService injectionService,
            HttpClient httpClient,
            FlowDefinitionResolver flowResolver,
            Sleeper sleeper) {
        this(new StepExecutor(
                Objects.requireNonNull(extractionService, "extractionService"),
                Objects.requireNonNull(injectionService, "injectionService")),
             Objects.requireNonNull(httpClient, "httpClient"),
             flowResolver,
             sleeper,
             new StepConditionEvaluator());
    }

    /**
     * Creates a {@code FlowEngine} with an explicitly provided {@link StepExecutor}.
     *
     * <p>This constructor is intended for testing, where the step executor may be a subclass
     * that bypasses the Montoya HTTP request bridge.
     *
     * @param stepExecutor the step executor to use; must not be {@code null}
     * @param httpClient   the HTTP transport used to send requests; must not be {@code null}
     */
    FlowEngine(StepExecutor stepExecutor, HttpClient httpClient) {
        this(stepExecutor, httpClient, FlowDefinitionResolver.empty(), new ThreadSleeper(), new StepConditionEvaluator());
    }

    /**
     * Creates a {@code FlowEngine} with explicitly provided collaborators.
     *
     * @param stepExecutor the step executor
     * @param httpClient the HTTP transport
     * @param flowResolver triggered-flow resolver
     * @param sleeper action sleeper
     * @param conditionEvaluator condition evaluator
     */
    FlowEngine(
            StepExecutor stepExecutor,
            HttpClient httpClient,
            FlowDefinitionResolver flowResolver,
            Sleeper sleeper,
            StepConditionEvaluator conditionEvaluator) {
        this.stepExecutor = Objects.requireNonNull(stepExecutor, "stepExecutor");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.flowResolver = Objects.requireNonNullElse(flowResolver, FlowDefinitionResolver.empty());
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.conditionEvaluator = Objects.requireNonNull(conditionEvaluator, "conditionEvaluator");
    }

    /**
     * Executes the supplied flow definition using the provided execution context.
     *
     * <p>Enabled steps are executed in declaration order. Variables extracted from each step are
     * made available to subsequent steps. Disabled steps are skipped without recording.
     *
     * @param flow    the flow definition to execute; must not be {@code null}
     * @param context the initial execution context; must not be {@code null}
     * @return an {@link ExecutionResult} describing the final outcome
     */
    public ExecutionResult execute(FlowDefinition flow, ExecutionContext context) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(context, "context");
        ExecutionResult result = executeInternal(flow, context, new ControlState());
        for (Consumer<ExecutionResult> listener : executionListeners) {
            try {
                listener.accept(result);
            } catch (RuntimeException ignored) {
                // Diagnostics must not change flow execution semantics.
            }
        }
        return result;
    }

    /** Registers an observer invoked after each top-level execution completes. */
    public void addExecutionListener(Consumer<ExecutionResult> listener) {
        executionListeners.add(Objects.requireNonNull(listener, "listener"));
    }

    private ExecutionResult executeInternal(FlowDefinition flow, ExecutionContext context, ControlState controlState) {
        if (controlState.flowStack.contains(flow.id())) {
            return ExecutionResult.failure(
                    flow.id(),
                    flow.name(),
                    List.of(),
                    List.of(),
                    "Triggered flow cycle detected: " + String.join(" -> ", controlState.flowStack) + " -> " + flow.id(),
                    context);
        }
        if (controlState.flowStack.size() >= MAX_TRIGGER_DEPTH) {
            return ExecutionResult.failure(
                    flow.id(),
                    flow.name(),
                    List.of(),
                    List.of(),
                    "Triggered flow depth exceeded " + MAX_TRIGGER_DEPTH,
                    context);
        }

        controlState.flowStack.addLast(flow.id());
        List<StepExecutionRecord> allRecords = new ArrayList<>();
        List<StepActionExecutionRecord> actionRecords = new ArrayList<>();
        ExecutionContext currentContext = context;

        int stepIndex = 0;
        try {
        while (stepIndex < flow.steps().size()) {
            FlowStep step = flow.steps().get(stepIndex);
            if (!step.enabled()) {
                // Skip disabled steps — not recorded
                stepIndex++;
                continue;
            }

            ExecutionContext beforeStepContext = currentContext;
            StepExecutionRecord record = stepExecutor.execute(step, currentContext, httpClient);
            allRecords.add(record);

            if (!record.success() && (InteractionAbortedException.isAborted(record.errorMessage())
                    || SessionBindingException.isAborted(record.errorMessage())
                    || ("true".equals(currentContext.metadata().get(FlowInputValidator.REQUIRE_INPUTS))
                    && record.errorMessage().startsWith(FlowInputValidator.FAILURE_PREFIX)))) {
                return ExecutionResult.failure(flow.id(), flow.name(), List.copyOf(allRecords),
                        List.copyOf(actionRecords), "Step '" + step.id() + "' failed: " + record.errorMessage(),
                        complete(currentContext.withStepRecord(record)));
            }

            // Store extracted variables in context for subsequent steps
            for (Map.Entry<String, String> entry : FlowInputValidator.scopedOutputs(record.extractedVariables(),
                    currentContext.metadata().get(FlowInputValidator.OUTPUT_SCOPE)).entrySet()) {
                currentContext = currentContext.withVariable(entry.getKey(), entry.getValue());
            }

            // Record the step in the execution context history
            currentContext = currentContext.withStepRecord(record);

            ActionOutcome actionOutcome = processConditionActions(
                    flow,
                    step,
                    record,
                    currentContext,
                    context,
                    beforeStepContext,
                    controlState);
            actionRecords.addAll(actionOutcome.records());
            currentContext = actionOutcome.context();

            if (actionOutcome.control() == ActionControl.FAIL_FLOW) {
                return ExecutionResult.failure(
                        flow.id(),
                        flow.name(),
                        List.copyOf(allRecords),
                        List.copyOf(actionRecords),
                        actionOutcome.message(),
                        complete(currentContext));
            }
            if (actionOutcome.control() == ActionControl.BREAK_FLOW) {
                return ExecutionResult.success(
                        flow.id(),
                        flow.name(),
                        List.copyOf(allRecords),
                        List.copyOf(actionRecords),
                        complete(currentContext),
                        actionOutcome.message());
            }
            if (actionOutcome.control() == ActionControl.RETRY_CURRENT_STEP) {
                continue;
            }
            if (actionOutcome.control() == ActionControl.RETRY_FLOW_FROM_START) {
                currentContext = context;
                stepIndex = 0;
                continue;
            }

            // Handle step failure
            if (!record.success()) {
                if (step.errorStrategy() == StepErrorStrategy.FAIL_FLOW) {
                    String failureMessage = "Step '" + step.id() + "' failed: " + record.errorMessage();
                    return ExecutionResult.failure(
                            flow.id(),
                            flow.name(),
                            List.copyOf(allRecords),
                            List.copyOf(actionRecords),
                            failureMessage,
                            complete(currentContext));
                }
                // CONTINUE_FLOW: record failure and continue
            }

            stepIndex++;
        }

        return ExecutionResult.success(
                flow.id(),
                flow.name(),
                List.copyOf(allRecords),
                List.copyOf(actionRecords),
                complete(currentContext),
                "COMPLETED");
        } finally {
            controlState.flowStack.removeLast();
        }
    }

    private ActionOutcome processConditionActions(
            FlowDefinition flow,
            FlowStep step,
            StepExecutionRecord record,
            ExecutionContext currentContext,
            ExecutionContext initialContext,
            ExecutionContext beforeStepContext,
            ControlState controlState) {
        if (step.conditionActionRules().isEmpty()) {
            return ActionOutcome.none(currentContext);
        }

        List<StepActionExecutionRecord> records = new ArrayList<>();
        ExecutionContext workingContext = currentContext;
        for (StepConditionActionRule rule : step.conditionActionRules()) {
            if (!rule.enabled()) {
                continue;
            }
            List<String> warnings = new ArrayList<>();
            if (!conditionEvaluator.matchesRule(rule, record, workingContext, warnings)) {
                continue;
            }

            for (StepAction action : rule.actions()) {
                if (!action.enabled()) {
                    continue;
                }
                ActionOutcome outcome = executeAction(
                        flow,
                        step,
                        rule,
                        action,
                        workingContext,
                        initialContext,
                        beforeStepContext,
                        controlState);
                records.addAll(outcome.records());
                workingContext = outcome.context();
                if (outcome.control() != ActionControl.NONE) {
                    return outcome.withPrependedRecords(records);
                }
            }
        }
        return new ActionOutcome(ActionControl.NONE, workingContext, List.copyOf(records), "");
    }

    private ActionOutcome executeAction(
            FlowDefinition flow,
            FlowStep step,
            StepConditionActionRule rule,
            StepAction action,
            ExecutionContext currentContext,
            ExecutionContext initialContext,
            ExecutionContext beforeStepContext,
            ControlState controlState) {
        return switch (action.type()) {
            case BREAK_FLOW -> completed(
                    flow,
                    step,
                    rule,
                    action,
                    currentContext,
                    ActionControl.BREAK_FLOW,
                    "Flow stopped by action '" + action.name() + "' on step '" + step.id() + "'");
            case SLEEP -> sleep(flow, step, rule, action, currentContext);
            case RETRY_CURRENT_STEP -> retryCurrentStep(flow, step, rule, action, currentContext, beforeStepContext, controlState);
            case RETRY_FLOW_FROM_START -> retryFlowFromStart(flow, step, rule, action, currentContext, initialContext, controlState);
            case TRIGGER_FLOW -> triggerFlow(flow, step, rule, action, currentContext, controlState);
        };
    }

    private ActionOutcome sleep(
            FlowDefinition flow,
            FlowStep step,
            StepConditionActionRule rule,
            StepAction action,
            ExecutionContext currentContext) {
        Optional<Long> seconds = longParameter(action, "seconds");
        if (seconds.isEmpty() || seconds.get() < 0L || seconds.get() > MAX_SLEEP_SECONDS) {
            return failed(flow, step, rule, action, currentContext,
                    "Sleep action requires seconds between 0 and " + MAX_SLEEP_SECONDS);
        }
        long startedAt = System.currentTimeMillis();
        try {
            sleeper.sleep(seconds.get() * 1_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failed(flow, step, rule, action, currentContext, "Sleep interrupted");
        }
        return new ActionOutcome(
                ActionControl.NONE,
                currentContext,
                List.of(record(flow, step, rule, action, StepActionStatus.COMPLETED,
                        "Slept for " + seconds.get() + " second(s)", startedAt)),
                "");
    }

    private ActionOutcome retryCurrentStep(
            FlowDefinition flow,
            FlowStep step,
            StepConditionActionRule rule,
            StepAction action,
            ExecutionContext currentContext,
            ExecutionContext beforeStepContext,
            ControlState controlState) {
        int maxAttempts = retryLimit(action);
        String key = flow.id() + ":" + step.id() + ":" + action.id();
        int attempts = controlState.stepRetryCounts.getOrDefault(key, 0);
        if (attempts >= maxAttempts) {
            return new ActionOutcome(
                    ActionControl.NONE,
                    currentContext,
                    List.of(record(flow, step, rule, action, StepActionStatus.FAILED,
                            "Retry current step limit reached (" + maxAttempts + ")")),
                    "");
        }
        controlState.stepRetryCounts.put(key, attempts + 1);
        return completed(flow, step, rule, action, beforeStepContext, ActionControl.RETRY_CURRENT_STEP,
                "Retrying current step '" + step.id() + "' (" + (attempts + 1) + "/" + maxAttempts + ")");
    }

    private ActionOutcome retryFlowFromStart(
            FlowDefinition flow,
            FlowStep step,
            StepConditionActionRule rule,
            StepAction action,
            ExecutionContext currentContext,
            ExecutionContext initialContext,
            ControlState controlState) {
        int maxAttempts = retryLimit(action);
        String key = flow.id() + ":" + action.id();
        int attempts = controlState.flowRetryCounts.getOrDefault(key, 0);
        if (attempts >= maxAttempts) {
            return new ActionOutcome(
                    ActionControl.NONE,
                    currentContext,
                    List.of(record(flow, step, rule, action, StepActionStatus.FAILED,
                            "Retry flow limit reached (" + maxAttempts + ")")),
                    "");
        }
        controlState.flowRetryCounts.put(key, attempts + 1);
        return completed(flow, step, rule, action, initialContext, ActionControl.RETRY_FLOW_FROM_START,
                "Retrying flow from start (" + (attempts + 1) + "/" + maxAttempts + ")");
    }

    private ActionOutcome triggerFlow(
            FlowDefinition flow,
            FlowStep step,
            StepConditionActionRule rule,
            StepAction action,
            ExecutionContext currentContext,
            ControlState controlState) {
        String targetFlowId = action.parameters().getOrDefault("flowId", "").strip();
        if (targetFlowId.isBlank()) {
            return failed(flow, step, rule, action, currentContext, "Trigger flow action requires flowId");
        }
        if (controlState.flowStack.contains(targetFlowId)) {
            return failed(flow, step, rule, action, currentContext, "Triggered flow cycle detected for " + targetFlowId);
        }
        Optional<FlowDefinition> resolved = flowResolver.resolve(targetFlowId);
        if (resolved.isEmpty()) {
            return failed(flow, step, rule, action, currentContext, "Triggered flow not found: " + targetFlowId);
        }

        ExecutionContext triggerContext = contextForTriggeredFlow(currentContext, resolved.get());
        long startedAt = System.currentTimeMillis();
        ExecutionResult triggered = executeInternal(resolved.get(), triggerContext, controlState);
        if ("true".equals(currentContext.metadata().get(dev.nquangit.corestra.manual.ManualRequestTemplate.ENABLED))) {
            for (Consumer<ExecutionResult> listener : executionListeners) {
                try { listener.accept(triggered); } catch (RuntimeException ignored) { }
            }
        }
        List<StepActionExecutionRecord> records = new ArrayList<>(triggered.actionRecords());
        if (!triggered.success()) {
            records.add(record(flow, step, rule, action, StepActionStatus.FAILED,
                    "Triggered flow failed: " + Objects.requireNonNullElse(triggered.failureMessage(), "unknown failure"),
                    startedAt));
            return new ActionOutcome(ActionControl.FAIL_FLOW, currentContext, List.copyOf(records),
                    "Triggered flow failed: " + targetFlowId
                            + (InteractionAbortedException.isAborted(triggered.failureMessage())
                            || SessionBindingException.isAborted(triggered.failureMessage())
                            ? ": " + triggered.failureMessage() : ""));
        }

        ExecutionContext mergedContext = triggered.finalContext()
                .map(finalContext -> mergeVariables(currentContext, finalContext.variables()))
                .orElse(currentContext);
        records.add(record(flow, step, rule, action, StepActionStatus.COMPLETED,
                "Triggered flow completed: " + targetFlowId, startedAt));
        return new ActionOutcome(ActionControl.NONE, mergedContext, List.copyOf(records), "");
    }

    private ActionOutcome completed(
            FlowDefinition flow,
            FlowStep step,
            StepConditionActionRule rule,
            StepAction action,
            ExecutionContext currentContext,
            ActionControl control,
            String message) {
        return new ActionOutcome(
                control,
                currentContext,
                List.of(record(flow, step, rule, action, StepActionStatus.COMPLETED, message)),
                message);
    }

    private ActionOutcome failed(
            FlowDefinition flow,
            FlowStep step,
            StepConditionActionRule rule,
            StepAction action,
            ExecutionContext currentContext,
            String message) {
        return new ActionOutcome(
                ActionControl.FAIL_FLOW,
                currentContext,
                List.of(record(flow, step, rule, action, StepActionStatus.FAILED, message)),
                message);
    }

    private StepActionExecutionRecord record(
            FlowDefinition flow,
            FlowStep step,
            StepConditionActionRule rule,
            StepAction action,
            StepActionStatus status,
            String message) {
        return record(flow, step, rule, action, status, message, System.currentTimeMillis());
    }

    private StepActionExecutionRecord record(
            FlowDefinition flow,
            FlowStep step,
            StepConditionActionRule rule,
            StepAction action,
            StepActionStatus status,
            String message,
            long startedAt) {
        long completedAt = Math.max(System.currentTimeMillis(), startedAt);
        return new StepActionExecutionRecord(
                flow.id(),
                step.id(),
                rule.id(),
                action.id(),
                action.type(),
                status,
                message,
                startedAt,
                completedAt);
    }

    private int retryLimit(StepAction action) {
        Optional<Long> configured = longParameter(action, "maxAttempts");
        if (configured.isEmpty()) {
            return DEFAULT_RETRY_LIMIT;
        }
        long value = configured.get();
        if (value < 0L) {
            return DEFAULT_RETRY_LIMIT;
        }
        return (int) Math.min(value, MAX_RETRY_LIMIT);
    }

    private Optional<Long> longParameter(StepAction action, String key) {
        String value = action.parameters().get(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.strip()));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private ExecutionContext contextForTriggeredFlow(ExecutionContext currentContext, FlowDefinition targetFlow) {
        ExecutionContext triggerContext = ExecutionContext.start(
                currentContext.runId(),
                targetFlow.id(),
                currentContext.metadata(),
                System.currentTimeMillis());
        return mergeVariables(triggerContext, currentContext.variables());
    }

    private ExecutionContext mergeVariables(ExecutionContext base, Map<String, String> variables) {
        ExecutionContext merged = base;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            merged = merged.withVariable(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    private ExecutionContext complete(ExecutionContext context) {
        long completedAt = Math.max(System.currentTimeMillis(), context.startedAtEpochMillis());
        return context.markCompleted(completedAt);
    }

    private enum ActionControl {
        NONE,
        BREAK_FLOW,
        RETRY_CURRENT_STEP,
        RETRY_FLOW_FROM_START,
        FAIL_FLOW
    }

    private static final class ControlState {
        private final java.util.ArrayDeque<String> flowStack = new java.util.ArrayDeque<>();
        private final Map<String, Integer> stepRetryCounts = new HashMap<>();
        private final Map<String, Integer> flowRetryCounts = new HashMap<>();
    }

    private record ActionOutcome(
            ActionControl control,
            ExecutionContext context,
            List<StepActionExecutionRecord> records,
            String message) {
        private static ActionOutcome none(ExecutionContext context) {
            return new ActionOutcome(ActionControl.NONE, context, List.of(), "");
        }

        private ActionOutcome withPrependedRecords(List<StepActionExecutionRecord> existing) {
            return new ActionOutcome(control, context, List.copyOf(existing), message);
        }
    }
}

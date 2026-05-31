package dev.nquangit.corestra.flow.execution;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowStep.StepErrorStrategy;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.HttpClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private final StepExecutor stepExecutor;
    private final HttpClient httpClient;

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
        this(new StepExecutor(
                Objects.requireNonNull(extractionService, "extractionService"),
                Objects.requireNonNull(injectionService, "injectionService")),
             Objects.requireNonNull(httpClient, "httpClient"));
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
        this.stepExecutor = Objects.requireNonNull(stepExecutor, "stepExecutor");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
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

        List<StepExecutionRecord> allRecords = new ArrayList<>();
        ExecutionContext currentContext = context;

        for (FlowStep step : flow.steps()) {
            if (!step.enabled()) {
                // Skip disabled steps — not recorded
                continue;
            }

            StepExecutionRecord record = stepExecutor.execute(step, currentContext, httpClient);
            allRecords.add(record);

            // Store extracted variables in context for subsequent steps
            for (Map.Entry<String, String> entry : record.extractedVariables().entrySet()) {
                currentContext = currentContext.withVariable(entry.getKey(), entry.getValue());
            }

            // Record the step in the execution context history
            currentContext = currentContext.withStepRecord(record);

            // Handle step failure
            if (!record.success()) {
                if (step.errorStrategy() == StepErrorStrategy.FAIL_FLOW) {
                    String failureMessage = "Step '" + step.id() + "' failed: " + record.errorMessage();
                    return ExecutionResult.failure(flow.id(), flow.name(), List.copyOf(allRecords), failureMessage);
                }
                // CONTINUE_FLOW: record failure and continue
            }
        }

        return ExecutionResult.success(flow.id(), flow.name(), List.copyOf(allRecords));
    }
}

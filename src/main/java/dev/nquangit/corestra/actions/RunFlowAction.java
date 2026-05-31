package dev.nquangit.corestra.actions;

import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.flow.execution.FlowEngine;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit flow-execution action bridging the UI to the {@link FlowEngine}.
 *
 * <p>This class performs no I/O itself and holds no Swing state; it builds the
 * {@link ExecutionContext} and orchestrates the engine. Callers are responsible for invoking
 * it off the Swing event dispatch thread, since {@link #run} and {@link #runSingleStep} block
 * on network requests.</p>
 */
public final class RunFlowAction {

    private final FlowEngine engine;

    /**
     * Creates a run action backed by the supplied engine.
     *
     * @param engine the flow engine used to execute flows
     * @throws NullPointerException if {@code engine} is {@code null}
     */
    public RunFlowAction(FlowEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Executes the whole flow from the first enabled step to the last.
     *
     * @param flow the flow to execute
     * @return the execution result
     * @throws NullPointerException if {@code flow} is {@code null}
     */
    public ExecutionResult run(FlowDefinition flow) {
        Objects.requireNonNull(flow, "flow");
        return engine.execute(flow, newContext(flow));
    }

    /**
     * Executes a single step in isolation for debugging, regardless of whether it is enabled.
     *
     * @param flow the flow the step belongs to (supplies id, name, rules, trigger config)
     * @param step the step to execute
     * @return the execution result containing the single step record
     * @throws NullPointerException if either argument is {@code null}
     */
    public ExecutionResult runSingleStep(FlowDefinition flow, FlowStep step) {
        FlowDefinition single = singleStepFlow(flow, step);
        return engine.execute(single, newContext(single));
    }

    /**
     * Builds a throwaway one-step flow that preserves the parent flow's identity and rules but
     * disables aggregation, so the chosen step runs exactly once. A disabled step is force-enabled
     * so it actually executes; an already-enabled step is reused as-is.
     *
     * @param flow the parent flow
     * @param step the step to isolate
     * @return a one-step flow definition
     * @throws NullPointerException if either argument is {@code null}
     */
    static FlowDefinition singleStepFlow(FlowDefinition flow, FlowStep step) {
        Objects.requireNonNull(flow, "flow");
        Objects.requireNonNull(step, "step");

        FlowStep runnable = step.enabled() ? step : forceEnabled(step);
        return new FlowDefinition(
                flow.id(), flow.name(), flow.description(), flow.enabled(),
                List.of(runnable), flow.rules(), AggregationPlan.disabled(), flow.triggerConfig());
    }

    private static FlowStep forceEnabled(FlowStep step) {
        return step.withEnabled(true);
    }

    private static ExecutionContext newContext(FlowDefinition flow) {
        return ExecutionContext.start(
                UUID.randomUUID().toString(), flow.id(), Map.of(), System.currentTimeMillis());
    }
}

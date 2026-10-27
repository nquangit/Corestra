package dev.nquangit.corestra.manual;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.execution.StepExecutionProgress;
import java.util.*;

/** Compact, per-run progress owned by the UI thread, independent of selection and persisted flow configuration. */
public final class ManualRunProgress {
    /** Display states distinguish execution failure, cancellation and deliberately skipped requests. */
    public enum State {
        NOT_RUN("Not run"), RUNNING("Running"), COMPLETED("Done"), FAILED("Failed"), SKIPPED("Skipped"), STOPPED("Stopped");
        private final String label;
        State(String label) { this.label = label; }
        /** Returns a readable status without relying only on color. */
        public String label() { return label; }
    }
    /** Metadata for one request's latest attempt. */
    public record Status(State state, int attempt, int statusCode, String detail) {
        /** Creates a state before any request attempt. */
        public static Status initial(boolean enabled) { return new Status(enabled ? State.NOT_RUN : State.SKIPPED, 0, 0, enabled ? "Not run in this execution" : "Disabled or outside the selected range"); }
    }
    private final String flowId;
    private final Map<String, Status> states = new LinkedHashMap<>();
    private final Set<String> scheduled = new HashSet<>();
    private boolean finished;
    private String lastStarted;
    private final Map<String, Integer> order = new HashMap<>();

    /** Resets colors for a new explicit selected-step/range/full-flow run. */
    public ManualRunProgress(FlowDefinition flow, int first, int last, boolean singleStep) {
        flowId = flow.id();
        for (int i = 0; i < flow.steps().size(); i++) {
            var step = flow.steps().get(i); boolean enabled = i >= first && i <= last && (singleStep || step.enabled());
            states.put(step.id(), Status.initial(enabled)); order.put(step.id(), i); if (enabled) scheduled.add(step.id());
        }
    }
    /** Applies only this parent flow's events. Child flow IDs cannot overwrite parent rows. */
    public void accept(StepExecutionProgress event) {
        accept(event, false);
    }
    /** Suppresses new preparation attempts after a stop request, while retaining a response already in flight. */
    public void accept(StepExecutionProgress event, boolean cancelled) {
        if (finished || !flowId.equals(event.flowId()) || !scheduled.contains(event.stepId())) return;
        if (cancelled && (event.phase() == StepExecutionProgress.Phase.STARTED || states.get(event.stepId()).state() == State.NOT_RUN)) return;
        if (event.phase() == StepExecutionProgress.Phase.STARTED) {
            if (lastStarted != null && order.get(event.stepId()) < order.get(lastStarted)) {
                // A retry from the beginning must not leave later rows green from the previous pass.
                int from = order.get(event.stepId());
                states.replaceAll((id, old) -> scheduled.contains(id) && order.get(id) >= from ? Status.initial(true) : old);
            }
            lastStarted = event.stepId();
        }
        State state = switch (event.phase()) { case STARTED -> State.RUNNING; case COMPLETED -> State.COMPLETED; case FAILED -> State.FAILED; };
        states.put(event.stepId(), new Status(state, event.attempt(), event.statusCode(), Objects.requireNonNullElse(event.message(), "")));
    }
    /** Prevents stale events changing a completed run; unstarted steps remain gray. */
    public void finish(boolean cancelled) {
        if (finished) return; finished = true;
        states.replaceAll((id, old) -> old.state() == State.RUNNING || (cancelled && id.equals(lastStarted) && old.state() == State.FAILED && old.statusCode() == 0)
                ? new Status(cancelled ? State.STOPPED : State.FAILED, old.attempt(), old.statusCode(), cancelled ? "Execution stopped" : "Execution ended before this attempt completed") : old);
    }
    /** Returns immutable display state without retaining any HTTP payloads. */
    public Map<String, Status> snapshot() { return Map.copyOf(states); }
    /** Returns completed request count against scheduled requests; retries do not inflate the count. */
    public String summary() {
        long done = scheduled.stream().filter(id -> states.get(id).state() == State.COMPLETED).count();
        long failed = scheduled.stream().filter(id -> states.get(id).state() == State.FAILED).count();
        long stopped = scheduled.stream().filter(id -> states.get(id).state() == State.STOPPED).count();
        return done + "/" + scheduled.size() + " done" + (failed == 0 ? "" : " · " + failed + " failed") + (stopped == 0 ? "" : " · " + stopped + " stopped");
    }
}

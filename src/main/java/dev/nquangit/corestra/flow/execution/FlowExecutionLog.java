package dev.nquangit.corestra.flow.execution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe, bounded, in-memory timeline of flow step executions. */
public final class FlowExecutionLog {
    /** Default maximum number of retained step entries. */
    public static final int DEFAULT_CAPACITY = 1_000;

    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /** Creates a log with the default capacity. */
    public FlowExecutionLog() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a bounded log.
     *
     * @param capacity maximum retained step entries
     */
    public FlowExecutionLog(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** Adds all step records from one completed flow run. */
    public void record(ExecutionResult result) {
        Objects.requireNonNull(result, "result");
        String runId = UUID.randomUUID().toString();
        synchronized (entries) {
            if (result.stepRecords().isEmpty()) {
                entries.addFirst(new Entry(runId, result.flowId(), result.flowName(), "", result.success(),
                        result.failureMessage() == null ? "" : result.failureMessage(), 0, 0L, 0L, "", ""));
            } else {
                for (StepExecutionRecord step : result.stepRecords()) {
                    entries.addFirst(new Entry(runId, result.flowId(), result.flowName(), step.stepId(),
                            step.success(), step.errorMessage(), step.responseStatusCode(),
                            step.startedAtEpochMillis(), step.completedAtEpochMillis(),
                            step.renderedRequest(), step.receivedResponse()));
                }
            }
            while (entries.size() > capacity) entries.removeLast();
        }
        listeners.forEach(Runnable::run);
    }

    /** Returns a newest-first immutable snapshot. */
    public List<Entry> snapshot() {
        synchronized (entries) {
            return List.copyOf(new ArrayList<>(entries));
        }
    }

    /** Clears all retained entries. */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
        listeners.forEach(Runnable::run);
    }

    /** Registers a change listener. */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** One request/response exchange in a flow run. */
    public record Entry(
            String runId,
            String flowId,
            String flowName,
            String stepId,
            boolean success,
            String errorMessage,
            int statusCode,
            long startedAtEpochMillis,
            long completedAtEpochMillis,
            String request,
            String response) {
        /** Returns elapsed milliseconds. */
        public long durationMillis() {
            return Math.max(0L, completedAtEpochMillis - startedAtEpochMillis);
        }
    }
}

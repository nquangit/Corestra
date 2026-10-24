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

    /** Maximum retained HTTP text footprint, estimated conservatively at two bytes per character. */
    public static final long DEFAULT_MAX_PAYLOAD_BYTES = 32L * 1024 * 1024;

    private final int capacity;
    private final long maxPayloadBytes;
    private long payloadBytes;
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
        this(capacity, DEFAULT_MAX_PAYLOAD_BYTES);
    }

    /** Creates a log bounded by entry count and retained raw HTTP text size. */
    public FlowExecutionLog(int capacity, long maxPayloadBytes) {
        if (maxPayloadBytes < 1) throw new IllegalArgumentException("maxPayloadBytes must be positive");
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
        this.maxPayloadBytes = maxPayloadBytes;
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
                    boolean oversized = payloadSize(step.renderedRequest(), step.receivedResponse()) > maxPayloadBytes;
                    String error = Objects.requireNonNullElse(step.errorMessage(), "");
                    if (oversized) error += " [Raw HTTP omitted from Flow Log: exceeds retained payload limit]";
                    Entry entry = new Entry(runId, result.flowId(), result.flowName(), step.stepId(),
                            step.success(), error, step.responseStatusCode(),
                            step.startedAtEpochMillis(), step.completedAtEpochMillis(),
                            oversized ? "" : step.renderedRequest(), oversized ? "" : step.receivedResponse());
                    entries.addFirst(entry);
                    payloadBytes += payloadSize(entry.request(), entry.response());
                    trimToLimits();
                }
            }
            trimToLimits();
        }
        listeners.forEach(Runnable::run);
    }

    private void trimToLimits() {
        while (entries.size() > capacity || payloadBytes > maxPayloadBytes) {
            Entry removed = entries.removeLast();
            payloadBytes -= payloadSize(removed.request(), removed.response());
        }
    }

    private static long payloadSize(String request, String response) {
        return 2L * ((request == null ? 0L : request.length()) + (response == null ? 0L : response.length()));
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
            payloadBytes = 0;
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

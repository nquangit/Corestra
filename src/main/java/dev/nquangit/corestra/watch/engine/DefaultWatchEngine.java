package dev.nquangit.corestra.watch.engine;

import dev.nquangit.corestra.flow.execution.Sleeper;
import dev.nquangit.corestra.flow.execution.ThreadSleeper;
import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchEngine;
import dev.nquangit.corestra.watch.WatchProbeClient;
import dev.nquangit.corestra.watch.WatchProbeClient.ProbeResponse;
import dev.nquangit.corestra.watch.WatchResult;
import dev.nquangit.corestra.watch.WatchSnapshot;
import dev.nquangit.corestra.watch.WatchTiming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Montoya-free {@link WatchEngine}: probes through a {@link WatchProbeClient}, reads observations
 * with {@link WatchObservationReader}, waits for AFTER to settle, and evaluates with
 * {@link WatchEvaluator}. Clock and sleeper are injected for deterministic tests.
 *
 * <p>Settling: after the first AFTER capture, when {@code settleStableCount > 1} and
 * {@code settleMaxMillis > 0}, the probe is re-sent every {@code settleIntervalMillis} until
 * {@code settleStableCount} consecutive successful captures have identical values, or until
 * {@code settleMaxMillis} has elapsed since the first AFTER capture (then {@code settled=false}).
 * Failed captures break a streak and never become the final AFTER while a successful one exists.
 * When re-capturing is disabled the first AFTER is taken as settled.</p>
 */
public final class DefaultWatchEngine implements WatchEngine {
    private final WatchProbeClient client;
    private final LongSupplier clock;
    private final Sleeper sleeper;
    private final WatchObservationReader reader = new WatchObservationReader(new WatchNumberParser());
    private final WatchEvaluator evaluator = new WatchEvaluator();

    /**
     * Creates an engine with injected time.
     *
     * @param client      probe client
     * @param clockMillis millisecond clock
     * @param sleeper     sleeper used between re-captures
     */
    public DefaultWatchEngine(WatchProbeClient client, LongSupplier clockMillis, Sleeper sleeper) {
        this.client = Objects.requireNonNull(client, "client");
        this.clock = Objects.requireNonNull(clockMillis, "clockMillis");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * Creates an engine using the system clock and {@link Thread#sleep}.
     *
     * @param client probe client
     */
    public DefaultWatchEngine(WatchProbeClient client) {
        this(client, System::currentTimeMillis, new ThreadSleeper());
    }

    @Override
    public WatchSnapshot capture(WatchDefinition definition, Map<String, String> variables, List<String> inheritedHeaders) {
        if (definition == null || !definition.probe().configured()) {
            return WatchSnapshot.failed(clock.getAsLong(), 0, "Probe request is not configured");
        }
        ProbeResponse response;
        try {
            response = client.fetch(definition, variables == null ? Map.of() : variables,
                    inheritedHeaders == null ? List.of() : inheritedHeaders, definition.timing().probeTimeoutMillis());
        } catch (RuntimeException failed) {
            response = ProbeResponse.failure("Probe failed: " + Objects.requireNonNullElse(failed.getMessage(),
                    failed.getClass().getSimpleName()));
        }
        long now = clock.getAsLong();
        if (response == null) return WatchSnapshot.failed(now, 0, "Probe returned no response");
        if (response.error() != null) {
            return WatchSnapshot.failed(now, response.statusCode(), "Probe failed: " + response.error());
        }
        if (definition.observations().isEmpty()) {
            return WatchSnapshot.failed(now, response.statusCode(), "No observations configured");
        }
        WatchObservationReader.Reading reading = reader.read(response, definition.observations());
        List<String> errors = new ArrayList<>();
        int status = response.statusCode();
        if (status < 200 || status > 299) errors.add("Probe returned HTTP " + status);
        errors.addAll(reading.errors());
        return new WatchSnapshot(now, status, reading.values(), errors);
    }

    @Override
    public WatchResult complete(WatchDefinition definition, Map<String, String> variables, List<String> inheritedHeaders,
                                WatchSnapshot before, List<String> extraNotes) {
        List<String> notes = new ArrayList<>(extraNotes == null ? List.of() : extraNotes);
        WatchSnapshot first = capture(definition, variables, inheritedHeaders);
        WatchTiming timing = definition == null ? WatchTiming.defaults() : definition.timing();
        WatchSnapshot last = first;
        WatchSnapshot lastGood = first.failed() ? null : first;
        boolean settled = true;
        if (definition != null && definition.probe().configured()
                && timing.settleStableCount() > 1 && timing.settleMaxMillis() > 0) {
            long start = first.capturedAtMillis();
            int streak = first.failed() ? 0 : 1;
            int captures = 1;
            while (streak < timing.settleStableCount()) {
                if (clock.getAsLong() - start >= timing.settleMaxMillis()) {
                    settled = false;
                    notes.add("AFTER still changing after " + captures + " captures in "
                            + (clock.getAsLong() - start) + " ms");
                    break;
                }
                try {
                    sleeper.sleep(timing.settleIntervalMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    settled = false;
                    notes.add("Settling was interrupted after " + captures + " captures");
                    break;
                }
                WatchSnapshot next = capture(definition, variables, inheritedHeaders);
                captures++;
                if (next.failed()) {
                    streak = 0;
                } else {
                    streak = lastGood != null && last == lastGood && next.values().equals(lastGood.values()) ? streak + 1 : 1;
                    lastGood = next;
                }
                last = next;
            }
        }
        WatchSnapshot after = lastGood != null ? lastGood : last;
        long settleMillis = Math.max(0, after.capturedAtMillis() - first.capturedAtMillis());
        WatchDefinition safe = definition == null ? WatchDefinition.empty("", "Watch") : definition;
        return evaluator.evaluate(safe, variables, before, after, settled, settleMillis, notes);
    }

    @Override
    public WatchResult evaluate(WatchDefinition definition, Map<String, String> variables, WatchSnapshot before,
                                WatchSnapshot after, boolean settled, long settleMillis, List<String> notes) {
        WatchDefinition safe = definition == null ? WatchDefinition.empty("", "Watch") : definition;
        return evaluator.evaluate(safe, variables, before, after, settled, settleMillis, notes);
    }
}

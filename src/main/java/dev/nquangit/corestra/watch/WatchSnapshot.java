package dev.nquangit.corestra.watch;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One capture of all observation values (runtime only, never persisted).
 *
 * @param capturedAtMillis capture time (injected clock)
 * @param probeStatusCode  probe HTTP status, or 0 when no response
 * @param values           observation key to value, in observation/document order
 * @param errors           per-observation or probe errors; non-empty means partial or failed
 */
public record WatchSnapshot(long capturedAtMillis, int probeStatusCode, Map<String, Value> values, List<String> errors) {
    /**
     * Copies collections defensively, keeping insertion order.
     *
     * @param capturedAtMillis capture time
     * @param probeStatusCode  status code
     * @param values           values
     * @param errors           errors
     */
    public WatchSnapshot {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values == null ? Map.of() : values));
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    /**
     * Returns a failed snapshot with no values.
     *
     * @param capturedAtMillis time
     * @param probeStatusCode  status code or 0
     * @param error            reason
     * @return failed snapshot
     */
    public static WatchSnapshot failed(long capturedAtMillis, int probeStatusCode, String error) {
        return new WatchSnapshot(capturedAtMillis, probeStatusCode, Map.of(), List.of(error));
    }

    /**
     * Returns whether the probe produced no values at all.
     *
     * @return true when failed
     */
    public boolean failed() {
        return values.isEmpty() && !errors.isEmpty();
    }

    /**
     * One observed value.
     *
     * @param raw    text as found in the response
     * @param number parsed exact decimal for NUMBER observations, or null (TEXT, or unparseable)
     */
    public record Value(String raw, BigDecimal number) {
    }
}

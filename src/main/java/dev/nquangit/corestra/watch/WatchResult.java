package dev.nquangit.corestra.watch;

import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of one watched Repeater send (runtime only).
 *
 * @param watchId       definition id
 * @param watchName     definition name
 * @param status        overall status
 * @param rows          one row per key present before or after, in key order
 * @param settled       whether the AFTER state was confirmed stable within settleMaxMillis
 * @param settleMillis  time from the first AFTER capture to the final one
 * @param before        BEFORE capture (may be failed)
 * @param after         final AFTER capture (may be failed)
 * @param notes         human-readable diagnostics (probe errors, unresolved variables, flow wait…)
 */
public record WatchResult(String watchId, String watchName, Status status, List<Row> rows, boolean settled,
                          long settleMillis, WatchSnapshot before, WatchSnapshot after, List<String> notes) {
    /**
     * Copies collections defensively.
     *
     * @param watchId      id
     * @param watchName    name
     * @param status       status
     * @param rows         rows
     * @param settled      settled
     * @param settleMillis settle time
     * @param before       before
     * @param after        after
     * @param notes        notes
     */
    public WatchResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** Overall status, most severe wins: ERROR &gt; FAIL &gt; PASS &gt; CHANGED &gt; NO_CHANGE. */
    public enum Status {
        /** At least one enabled expectation failed. */
        FAIL,
        /** All enabled expectations that matched a key passed (and at least one matched). */
        PASS,
        /** No expectation matched, and some value changed. */
        CHANGED,
        /** No expectation matched, and nothing changed. */
        NO_CHANGE,
        /** BEFORE or AFTER capture failed so nothing can be concluded. */
        ERROR
    }

    /** Per-row verdict. */
    public enum Verdict {
        /** Expectations for this key passed. */
        OK,
        /** An expectation for this key failed. */
        FAIL,
        /** No expectation applies. */
        INFO,
        /** An expectation could not be evaluated (unresolved variable, non-numeric value…). */
        ERROR
    }

    /**
     * One observation key.
     *
     * @param key         snapshot key, e.g. {@code balance[0123]}
     * @param beforeRaw   raw BEFORE text, or null when absent
     * @param afterRaw    raw AFTER text, or null when absent
     * @param delta       after - before for numeric values, else null
     * @param changed     whether the value differs (presence change counts)
     * @param verdict     row verdict
     * @param explanation expectation explained with substituted values, e.g.
     *                    {@code "expected -500000 - max(500000 * 0.011, 5000) = -505500"}; "" when none
     */
    public record Row(String key, String beforeRaw, String afterRaw, BigDecimal delta, boolean changed,
                      Verdict verdict, String explanation) {
    }
}

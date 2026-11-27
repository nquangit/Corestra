package dev.nquangit.corestra.watch;

import java.util.List;
import java.util.Map;

/**
 * Captures and evaluates Watch state. Montoya-free; blocking; never throws for probe or
 * evaluation problems (they become snapshot errors, notes and {@link WatchResult.Status#ERROR}).
 */
public interface WatchEngine {
    /**
     * Captures one snapshot (used for BEFORE and for "Test probe" in the UI).
     *
     * @param definition       watch
     * @param variables        request variables
     * @param inheritedHeaders header lines from the live request
     * @return snapshot (possibly failed)
     */
    WatchSnapshot capture(WatchDefinition definition, Map<String, String> variables, List<String> inheritedHeaders);

    /**
     * Captures AFTER, re-capturing every {@code settleIntervalMillis} until
     * {@code settleStableCount} consecutive identical captures or {@code settleMaxMillis} elapse,
     * then evaluates expectations against {@code before}.
     *
     * @param definition       watch
     * @param variables        request variables
     * @param inheritedHeaders header lines from the live request
     * @param before           BEFORE snapshot
     * @param extraNotes       notes to carry into the result (e.g. downstream flow outcome)
     * @return result
     */
    WatchResult complete(WatchDefinition definition, Map<String, String> variables, List<String> inheritedHeaders,
                         WatchSnapshot before, List<String> extraNotes);

    /**
     * Evaluates expectations between two snapshots without sending anything.
     *
     * @param definition watch
     * @param variables  request variables
     * @param before     BEFORE
     * @param after      AFTER
     * @param settled    whether AFTER was stable
     * @param settleMillis settle duration
     * @param notes      notes
     * @return result
     */
    WatchResult evaluate(WatchDefinition definition, Map<String, String> variables, WatchSnapshot before,
                         WatchSnapshot after, boolean settled, long settleMillis, List<String> notes);
}

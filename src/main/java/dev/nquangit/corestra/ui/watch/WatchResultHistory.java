package dev.nquangit.corestra.ui.watch;

import dev.nquangit.corestra.watch.WatchResult;
import dev.nquangit.corestra.watch.WatchSnapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Bounded, newest-first list of received Watch results plus the text formatting the panel shows.
 * Not thread-safe: the panel only touches it on the EDT.
 */
public final class WatchResultHistory {
    /** Default number of results kept. */
    public static final int DEFAULT_CAPACITY = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();

    /**
     * Creates a history.
     *
     * @param capacity maximum entries (at least 1)
     */
    public WatchResultHistory(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /**
     * One received result.
     *
     * @param receivedAtMillis time it was received
     * @param result           result
     */
    public record Entry(long receivedAtMillis, WatchResult result) {
    }

    /**
     * Adds a result at the front, dropping the oldest beyond capacity.
     *
     * @param receivedAtMillis receive time
     * @param result           result
     */
    public void add(long receivedAtMillis, WatchResult result) {
        entries.addFirst(new Entry(receivedAtMillis, result));
        while (entries.size() > capacity) {
            entries.removeLast();
        }
    }

    /** Removes all entries. */
    public void clear() {
        entries.clear();
    }

    /**
     * Returns entries, newest first.
     *
     * @return snapshot list
     */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /**
     * Returns the entry count.
     *
     * @return size
     */
    public int size() {
        return entries.size();
    }

    /**
     * Formats a receive time as local HH:mm:ss.
     *
     * @param millis epoch millis
     * @return time text
     */
    public static String time(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /**
     * One-line summary: verdict counts, changed rows and settle state.
     *
     * @param result result
     * @return summary
     */
    public static String summary(WatchResult result) {
        long changed = result.rows().stream().filter(WatchResult.Row::changed).count();
        long failed = result.rows().stream().filter(row -> row.verdict() == WatchResult.Verdict.FAIL).count();
        long ok = result.rows().stream().filter(row -> row.verdict() == WatchResult.Verdict.OK).count();
        long errors = result.rows().stream().filter(row -> row.verdict() == WatchResult.Verdict.ERROR).count();
        StringBuilder text = new StringBuilder();
        text.append(result.rows().size()).append(" key(s), ").append(changed).append(" changed");
        if (ok > 0) text.append(", ").append(ok).append(" ok");
        if (failed > 0) text.append(", ").append(failed).append(" failed");
        if (errors > 0) text.append(", ").append(errors).append(" error(s)");
        text.append(result.settled() ? "; settled" : "; NOT settled").append(" in ").append(result.settleMillis()).append(" ms");
        if (!result.notes().isEmpty()) text.append("; ").append(result.notes().getFirst());
        return text.toString();
    }

    /**
     * Rows for the detail table: key, before, after, delta, verdict, explanation.
     *
     * @param result result
     * @return table rows
     */
    public static List<Object[]> detailRows(WatchResult result) {
        List<Object[]> rows = new ArrayList<>();
        for (WatchResult.Row row : result.rows()) {
            rows.add(new Object[]{row.key(), nullText(row.beforeRaw()), nullText(row.afterRaw()),
                    delta(row.delta()), row.verdict().name(), row.explanation()});
        }
        return rows;
    }

    /**
     * Notes plus BEFORE/AFTER capture errors as text.
     *
     * @param result result
     * @return multi-line text
     */
    public static String notesText(WatchResult result) {
        StringBuilder text = new StringBuilder();
        text.append("Watch: ").append(result.watchName()).append("  Status: ").append(result.status()).append('\n');
        for (String note : result.notes()) text.append("• ").append(note).append('\n');
        appendErrors(text, "BEFORE", result.before());
        appendErrors(text, "AFTER", result.after());
        return text.toString().stripTrailing();
    }

    /**
     * Rows for the "Test probe" table: key, raw, parsed number; errors as rows with key "error".
     *
     * @param snapshot snapshot
     * @return table rows
     */
    public static List<Object[]> snapshotRows(WatchSnapshot snapshot) {
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<String, WatchSnapshot.Value> entry : snapshot.values().entrySet()) {
            WatchSnapshot.Value value = entry.getValue();
            rows.add(new Object[]{entry.getKey(), value.raw(),
                    value.number() == null ? "" : value.number().toPlainString()});
        }
        for (String error : snapshot.errors()) {
            rows.add(new Object[]{"error", error, ""});
        }
        return rows;
    }

    private static void appendErrors(StringBuilder text, String label, WatchSnapshot snapshot) {
        if (snapshot == null) return;
        for (String error : snapshot.errors()) text.append(label).append(" error: ").append(error).append('\n');
    }

    private static String delta(BigDecimal delta) {
        if (delta == null) return "";
        String plain = delta.toPlainString();
        return delta.signum() > 0 ? "+" + plain : plain;
    }

    private static String nullText(String value) {
        return value == null ? "(absent)" : value;
    }
}

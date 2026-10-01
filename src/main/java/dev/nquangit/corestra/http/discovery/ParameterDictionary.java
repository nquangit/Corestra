package dev.nquangit.corestra.http.discovery;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Thread-safe, size-bounded dictionary of HTTP parameter observations. */
public final class ParameterDictionary {
    /** Default maximum number of distinct scoped entries. */
    public static final int DEFAULT_CAPACITY = 5_000;
    private final int capacity;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ThreadLocal<Integer> batchDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Boolean> batchChanged = ThreadLocal.withInitial(() -> false);

    /** Creates a dictionary with the default capacity. */
    public ParameterDictionary() {
        this(DEFAULT_CAPACITY);
    }

    /** Creates a dictionary with a maximum number of scoped entries. */
    public ParameterDictionary(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** Adds or updates one observation. */
    public void observe(String host, String path, Location location, String name, String example) {
        String normalizedName = Objects.requireNonNullElse(name, "").strip();
        if (normalizedName.isEmpty()) return;
        Key key = new Key(normalize(host), normalizePath(path), Objects.requireNonNull(location),
                normalizedName.toLowerCase(Locale.ROOT));
        long now = System.currentTimeMillis();
        synchronized (entries) {
            Entry previous = entries.remove(key);
            String retainedExample = example == null || example.isBlank()
                    ? previous == null ? "" : previous.example()
                    : abbreviate(example);
            entries.put(key, new Entry(normalizedName, key.host(), key.path(), location,
                    previous == null ? 1L : previous.count() + 1L,
                    retainedExample, previous == null ? now : previous.firstSeenEpochMillis(), now));
            while (entries.size() > capacity) {
                entries.remove(entries.keySet().iterator().next());
            }
        }
        notifyChanged();
    }

    /** Returns newest-observed entries first. */
    public List<Entry> snapshot() {
        synchronized (entries) {
            List<Entry> result = new ArrayList<>(entries.values());
            java.util.Collections.reverse(result);
            return List.copyOf(result);
        }
    }

    /** Clears the runtime dictionary. */
    public void clear() {
        synchronized (entries) { entries.clear(); }
        listeners.forEach(Runnable::run);
    }

    /** Removes retained examples without clearing names or counts. */
    public void clearExamples() {
        synchronized (entries) {
            entries.replaceAll((key, entry) -> new Entry(entry.name(), entry.host(), entry.path(),
                    entry.location(), entry.count(), "", entry.firstSeenEpochMillis(), entry.lastSeenEpochMillis()));
        }
        listeners.forEach(Runnable::run);
    }

    /** Runs multiple observations while emitting at most one change notification. */
    public void batch(Runnable observations) {
        Objects.requireNonNull(observations, "observations");
        batchDepth.set(batchDepth.get() + 1);
        try {
            observations.run();
        } finally {
            int remaining = batchDepth.get() - 1;
            batchDepth.set(remaining);
            if (remaining == 0 && batchChanged.get()) {
                batchChanged.set(false);
                listeners.forEach(Runnable::run);
            }
        }
    }

    /** Registers a change listener. */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    private void notifyChanged() {
        if (batchDepth.get() > 0) {
            batchChanged.set(true);
        } else {
            listeners.forEach(Runnable::run);
        }
    }

    private static String normalize(String value) {
        return Objects.requireNonNullElse(value, "").strip().toLowerCase(Locale.ROOT);
    }

    private static String normalizePath(String value) {
        String path = Objects.requireNonNullElse(value, "/").strip();
        int query = path.indexOf('?');
        return query >= 0 ? path.substring(0, query) : path;
    }

    private static String abbreviate(String value) {
        String normalized = value.replace("\r", "\\r").replace("\n", "\\n");
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
    }

    /** Location where a parameter name was observed. */
    public enum Location { QUERY, FORM, COOKIE, JSON, XML, MULTIPART, HEADER, RESPONSE_JSON, RESPONSE_HTML }

    /** Immutable dictionary row. */
    public record Entry(String name, String host, String path, Location location, long count,
                        String example, long firstSeenEpochMillis, long lastSeenEpochMillis) {
        /** Returns the latest observation timestamp. */
        public Instant lastSeen() { return Instant.ofEpochMilli(lastSeenEpochMillis); }
    }

    private record Key(String host, String path, Location location, String normalizedName) { }
}

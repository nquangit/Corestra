package dev.nquangit.corestra.http.discovery;

import java.util.*;

/** Bounded runtime corpus. Uniqueness is per category/host; exports deduplicate across hosts. */
public final class WordlistDictionary {
    /** Maximum scoped words held in memory, without retaining HTTP messages. */
    public static final int DEFAULT_CAPACITY = 20_000;
    private final int capacity;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();
    private long revision, evicted;

    /** Creates a corpus with the default memory bound. */
    public WordlistDictionary() { this(DEFAULT_CAPACITY); }
    /** Creates a corpus bounded by scoped entries. */
    public WordlistDictionary(int capacity) { if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive"); this.capacity = capacity; }

    /** Categories are separate wordlists, with stable portable export filenames. */
    public enum Category {
        QUERY_PARAMETERS("Query parameters"), FORM_PARAMETERS("Form parameters"), JSON_KEYS("Request JSON keys"),
        XML_NAMES("XML names"), MULTIPART_NAMES("Multipart names"), COOKIE_NAMES("Cookie names"),
        REQUEST_HEADERS("Request headers"), RESPONSE_HEADERS("Response headers"), RESPONSE_JSON_KEYS("Response JSON keys"),
        HTML_FORM_NAMES("HTML form names"), URL_PATHS("Paths (dynamic IDs normalized)"), PATH_SEGMENTS("Path segments"),
        FILE_NAMES("File names"), FILE_EXTENSIONS("File extensions"), VALUE_WORDS("Value words");
        private final String label;
        Category(String label) { this.label = label; }
        /** Returns a UI label. */
        @Override public String toString() { return label; }
        /** Returns the UTF-8 text filename used in ZIP exports. */
        public String fileName() { return name().toLowerCase(Locale.ROOT).replace('_', '-') + ".txt"; }
    }
    /** One scoped word. No values/examples or raw requests are retained as metadata. */
    public record Entry(Category category, String word, String host) { }
    private record Key(Category category, String word, String host) { }

    /** Adds a unique scoped word. Header names are case-insensitive; other categories preserve case. */
    public synchronized void observe(Category category, String word, String host) {
        if (word == null || word.isBlank() || word.length() > 512 || word.chars().anyMatch(Character::isISOControl)) return;
        String normalized = word.strip();
        if (category == Category.REQUEST_HEADERS || category == Category.RESPONSE_HEADERS) normalized = normalized.toLowerCase(Locale.ROOT);
        String normalizedHost = Objects.requireNonNullElse(host, "").strip().toLowerCase(Locale.ROOT);
        Key key = new Key(Objects.requireNonNull(category), normalized, normalizedHost);
        if (entries.containsKey(key)) return;
        entries.put(key, new Entry(category, normalized, normalizedHost)); revision++;
        while (entries.size() > capacity) { entries.remove(entries.keySet().iterator().next()); evicted++; }
    }
    /** Returns an immutable corpus snapshot. */
    public synchronized List<Entry> snapshot() { return List.copyOf(entries.values()); }
    /** Returns a change revision, allowing UI refreshes to be coalesced. */
    public synchronized long revision() { return revision; }
    /** Returns how many scoped entries were evicted since the last clear. */
    public synchronized long evicted() { return evicted; }
    /** Clears only the in-memory corpus. */
    public synchronized void clear() { entries.clear(); evicted = 0; revision++; }
    /** Removes reviewed unwanted words, preserving identical words on other hosts. */
    public synchronized void remove(Collection<Entry> selected) {
        boolean changed = false;
        for (Entry e : selected) changed |= entries.remove(new Key(e.category(), e.word(), e.host())) != null;
        if (changed) revision++;
    }
    /** Produces sorted unique words in each group after exact host and substring filters. */
    public static Map<Category, List<String>> grouped(List<Entry> snapshot, Category category, String host, String query) {
        Map<Category, SortedSet<String>> groups = new EnumMap<>(Category.class);
        String q = Objects.requireNonNullElse(query, "").strip().toLowerCase(Locale.ROOT);
        for (Entry e : snapshot) {
            if (category != null && category != e.category()) continue;
            if (host != null && !host.isBlank() && !host.equals(e.host())) continue;
            if (!q.isBlank() && !e.word().toLowerCase(Locale.ROOT).contains(q)) continue;
            groups.computeIfAbsent(e.category(), ignored -> new TreeSet<>()).add(e.word());
        }
        Map<Category, List<String>> result = new EnumMap<>(Category.class);
        groups.forEach((key, values) -> result.put(key, List.copyOf(values))); return Collections.unmodifiableMap(result);
    }
}

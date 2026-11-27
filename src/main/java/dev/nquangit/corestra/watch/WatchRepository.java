package dev.nquangit.corestra.watch;

import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Persistence;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.config.CorestraSettingsKeys;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stores Watch definitions in Burp project persistence (or in memory for tests) as the versioned
 * document {@code {"version":1,"watches":[...]}}.
 *
 * <p>The stored document is read once and cached; writes update the cache only after they succeed.
 * A malformed document, an unsupported version or individual unreadable watches cause the raw
 * document to be copied to {@link CorestraSettingsKeys#WATCH_DOCUMENT_QUARANTINE}; readable watches
 * are still returned and the original document is never deleted by recovery.</p>
 *
 * <p>Listeners run after every successful change, outside the repository lock, on the calling thread.</p>
 */
public final class WatchRepository {
    /** Current document version. */
    public static final int CURRENT_VERSION = 1;

    private final PersistedObject extensionData;
    private static final ObjectMapper MAPPER = mapper();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private List<WatchDefinition> cached = List.of();
    private List<String> diagnostics = List.of();
    private boolean loaded;

    /** Creates an in-memory repository. */
    public WatchRepository() {
        this.extensionData = null;
        this.loaded = true;
    }

    /**
     * Creates a Burp project-backed repository.
     *
     * @param persistence Montoya persistence facade
     */
    public WatchRepository(Persistence persistence) {
        this.extensionData = Objects.requireNonNull(persistence, "persistence").extensionData();
    }

    /**
     * Returns the shared mapper configuration (unknown properties ignored).
     *
     * @return new mapper
     */
    public static ObjectMapper mapper() {
        return JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .build();
    }

    /**
     * Loads all watches in stored order.
     *
     * @return immutable list
     */
    public synchronized List<WatchDefinition> load() {
        if (loaded) {
            return cached;
        }
        String json = extensionData.getString(CorestraSettingsKeys.WATCH_DOCUMENT);
        loaded = true;
        if (json == null || json.isBlank()) {
            return cached;
        }
        ArrayList<String> problems = new ArrayList<>();
        cached = List.copyOf(parse(json, problems));
        if (!problems.isEmpty()) {
            quarantine(json);
            problems.add("The original Watch document was copied to " + CorestraSettingsKeys.WATCH_DOCUMENT_QUARANTINE + ".");
        }
        diagnostics = List.copyOf(problems);
        return cached;
    }

    /**
     * Returns problems found while loading the stored document (empty when it loaded cleanly).
     *
     * @return diagnostics
     */
    public synchronized List<String> loadDiagnostics() {
        load();
        return diagnostics;
    }

    /**
     * Finds one watch.
     *
     * @param id watch id
     * @return watch, if present
     */
    public synchronized Optional<WatchDefinition> find(String id) {
        return load().stream().filter(watch -> watch.id().equals(id)).findFirst();
    }

    /**
     * Replaces all watches.
     *
     * @param watches watches in display order
     * @throws IllegalArgumentException when an id is blank or duplicated
     * @throws IllegalStateException when persistence fails (the cache is unchanged)
     */
    public void save(List<WatchDefinition> watches) {
        synchronized (this) {
            write(watches);
        }
        notifyListeners();
    }

    /**
     * Inserts or replaces (by id, keeping position) one watch.
     *
     * @param watch watch
     */
    public void upsert(WatchDefinition watch) {
        Objects.requireNonNull(watch, "watch");
        synchronized (this) {
            ArrayList<WatchDefinition> updated = new ArrayList<>(load());
            boolean replaced = false;
            for (int i = 0; i < updated.size(); i++) {
                if (updated.get(i).id().equals(watch.id())) {
                    updated.set(i, watch);
                    replaced = true;
                }
            }
            if (!replaced) {
                updated.add(watch);
            }
            write(updated);
        }
        notifyListeners();
    }

    /**
     * Deletes one watch.
     *
     * @param id watch id
     * @return true when something was deleted
     */
    public boolean delete(String id) {
        synchronized (this) {
            List<WatchDefinition> current = load();
            List<WatchDefinition> updated = current.stream().filter(watch -> !watch.id().equals(id)).toList();
            if (updated.size() == current.size()) {
                return false;
            }
            write(updated);
        }
        notifyListeners();
        return true;
    }

    /** Removes the stored document (and its quarantine copy) and empties the cache. */
    public void clear() {
        synchronized (this) {
            if (extensionData != null) {
                extensionData.deleteString(CorestraSettingsKeys.WATCH_DOCUMENT);
                extensionData.deleteString(CorestraSettingsKeys.WATCH_DOCUMENT_QUARANTINE);
            }
            cached = List.of();
            diagnostics = List.of();
            loaded = true;
        }
        notifyListeners();
    }

    /**
     * Registers a change listener.
     *
     * @param listener listener
     */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * Unregisters a change listener.
     *
     * @param listener listener
     */
    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * Serializes watches as a versioned document.
     *
     * @param watches watches
     * @return JSON document
     */
    public static String serialize(List<WatchDefinition> watches) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("version", CURRENT_VERSION);
        root.set("watches", MAPPER.valueToTree(List.copyOf(watches)));
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize watches", exception);
        }
    }

    /**
     * Reads the {@code watches} array of a document, skipping unreadable entries.
     *
     * @param watchesNode array node
     * @param problems    receives one message per skipped entry
     * @return readable watches
     */
    public static List<WatchDefinition> readWatches(JsonNode watchesNode, List<String> problems) {
        ArrayList<WatchDefinition> result = new ArrayList<>();
        if (!(watchesNode instanceof ArrayNode array)) {
            problems.add("Watch document has no watches array.");
            return result;
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            try {
                WatchDefinition watch = MAPPER.treeToValue(array.get(i), WatchDefinition.class);
                if (watch.id().isBlank() || !ids.add(watch.id())) {
                    problems.add("Watch #" + (i + 1) + " has a blank or duplicate id and was skipped.");
                    continue;
                }
                result.add(watch);
            } catch (JsonProcessingException | IllegalArgumentException exception) {
                String reason = exception instanceof JsonProcessingException json ? json.getOriginalMessage()
                        : exception.getMessage();
                problems.add("Watch #" + (i + 1) + " could not be read: " + reason);
            }
        }
        return result;
    }

    private static List<WatchDefinition> parse(String json, List<String> problems) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            problems.add("Stored Watch document is not valid JSON: " + exception.getOriginalMessage());
            return List.of();
        }
        if (root == null || !root.isObject()) {
            problems.add("Stored Watch document is not a JSON object.");
            return List.of();
        }
        int version = root.path("version").asInt(-1);
        if (version != CURRENT_VERSION) {
            problems.add("Unsupported Watch document version: " + root.path("version").asText("missing"));
            return List.of();
        }
        return readWatches(root.get("watches"), problems);
    }

    private void write(List<WatchDefinition> watches) {
        List<WatchDefinition> normalized = validate(watches);
        if (extensionData != null) {
            extensionData.setString(CorestraSettingsKeys.WATCH_DOCUMENT, serialize(normalized));
        }
        cached = normalized;
        loaded = true;
    }

    private static List<WatchDefinition> validate(List<WatchDefinition> watches) {
        Objects.requireNonNull(watches, "watches");
        Set<String> ids = new HashSet<>();
        for (WatchDefinition watch : watches) {
            Objects.requireNonNull(watch, "watch");
            if (watch.id().isBlank()) {
                throw new IllegalArgumentException("Watch id must not be blank");
            }
            if (!ids.add(watch.id())) {
                throw new IllegalArgumentException("Duplicate watch id: " + watch.id());
            }
        }
        return List.copyOf(watches);
    }

    private void quarantine(String json) {
        try {
            extensionData.setString(CorestraSettingsKeys.WATCH_DOCUMENT_QUARANTINE, json);
        } catch (RuntimeException ignored) {
            // Recovery must not fail loading; the original document is left in place.
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}

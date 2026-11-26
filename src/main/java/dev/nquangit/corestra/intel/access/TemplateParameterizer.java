package dev.nquangit.corestra.intel.access;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces literal object identifiers in a request with {@code {{data.<name>}}} placeholders.
 *
 * <p>This turns a captured request ("GET /users/4821") into a reusable access template
 * ("GET /users/{{data.userId}}") plus the Data Profile values needed to reproduce the original
 * request, which is what Generate Matrix needs to test the same object with other actors.</p>
 *
 * <p>Template rendering substitutes values verbatim, so stored values are kept in the encoding of
 * the place they came from: URL-encoded for path, query and form values, JSON-escaped for JSON
 * strings. Rendering the template with the returned values reproduces the original request.</p>
 */
public final class TemplateParameterizer {
    private static final Pattern JSON_PATH_STEP = Pattern.compile("([^.\\[\\]]+)|\\[(\\d+)]");
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    /**
     * Parameterizes the given literal identifiers.
     *
     * @param request original request
     * @param identifiers identifiers to replace (parameterized ones are ignored)
     * @return new request, the data values that reproduce the original, and the added placeholders
     */
    public Result parameterize(StoredRequestTemplate request, List<IdentifierOccurrence> identifiers) {
        return parameterize(request, identifiers, Map.of());
    }

    /**
     * Parameterizes the given literal identifiers without clobbering keys of an existing Data Profile.
     *
     * @param request original request
     * @param identifiers identifiers to replace (parameterized ones are ignored)
     * @param existingValues values already stored in the target Data Profile; a key holding a different
     *                       value is never reused, a key holding the same value is
     * @return new request, data values, added placeholders and skipped identifiers
     */
    public Result parameterize(StoredRequestTemplate request, List<IdentifierOccurrence> identifiers,
                               Map<String, String> existingValues) {
        Objects.requireNonNull(request, "request");
        Map<String, String> existing = existingValues == null ? Map.of() : existingValues;
        List<IdentifierOccurrence> literal = identifiers == null ? List.of()
                : identifiers.stream().filter(identifier -> !identifier.parameterized()).toList();

        Map<String, String> keyByValue = new LinkedHashMap<>();
        Set<String> allocated = new LinkedHashSet<>();
        Map<IdentifierOccurrence, String> keys = new LinkedHashMap<>();
        for (IdentifierOccurrence identifier : literal) {
            String key = keyByValue.computeIfAbsent(identifier.value(),
                    value -> allocateKey(identifier.name(), value, existing, allocated));
            keys.put(identifier, key);
        }

        String pathAndQuery = Objects.requireNonNullElse(request.pathAndQuery(), "");
        String body = Objects.requireNonNullElse(request.body(), "");
        List<String> skipped = new ArrayList<>();
        Map<String, String> rawValues = new LinkedHashMap<>();
        Map<String, String> jsonReplacements = new LinkedHashMap<>();
        for (Map.Entry<IdentifierOccurrence, String> entry : keys.entrySet()) {
            IdentifierOccurrence identifier = entry.getKey();
            String key = entry.getValue();
            String placeholder = "{{data." + key + "}}";
            switch (identifier.location()) {
                case PATH -> pathAndQuery = replacePathSegment(pathAndQuery, identifier, key, placeholder,
                        rawValues, skipped);
                case QUERY -> pathAndQuery = replaceQuery(pathAndQuery, identifier, key, placeholder, rawValues, skipped);
                case FORM -> body = replacePairs(body, identifier, key, placeholder, rawValues, skipped);
                case JSON -> jsonReplacements.put(identifier.selector(), key);
            }
        }
        if (!jsonReplacements.isEmpty()) {
            body = replaceJson(body, jsonReplacements, rawValues, skipped);
        }

        Set<ScopedVariableRef> added = new LinkedHashSet<>();
        Map<String, String> usedValues = new LinkedHashMap<>();
        String rendered = pathAndQuery + "\n" + body;
        rawValues.forEach((key, value) -> {
            if (rendered.contains("{{data." + key + "}}")) {
                added.add(new ScopedVariableRef(VariableScope.DATA, key));
                usedValues.put(key, value);
            }
        });
        StoredRequestTemplate updated = new StoredRequestTemplate(
                request.method(), request.scheme(), request.host(), request.port(), pathAndQuery,
                request.headerLines(), body);
        return new Result(updated, usedValues, added, skipped);
    }

    private static String replacePathSegment(String pathAndQuery, IdentifierOccurrence identifier, String key,
                                             String placeholder, Map<String, String> rawValues, List<String> skipped) {
        int query = pathAndQuery.indexOf('?');
        String path = query < 0 ? pathAndQuery : pathAndQuery.substring(0, query);
        String rest = query < 0 ? "" : pathAndQuery.substring(query);
        int index = Integer.parseInt(identifier.selector());
        String[] segments = path.startsWith("/") ? path.substring(1).split("/", -1) : new String[0];
        if (index >= segments.length || !decode(segments[index]).equals(identifier.value())
                || !consistent(rawValues, key, segments[index])) {
            skipped.add(identifier.describe());
            return pathAndQuery;
        }
        rawValues.put(key, segments[index]);
        segments[index] = placeholder;
        return "/" + String.join("/", segments) + rest;
    }

    private static String replaceQuery(String pathAndQuery, IdentifierOccurrence identifier, String key,
                                       String placeholder, Map<String, String> rawValues, List<String> skipped) {
        int query = pathAndQuery.indexOf('?');
        if (query < 0) {
            skipped.add(identifier.describe());
            return pathAndQuery;
        }
        int fragment = pathAndQuery.indexOf('#', query);
        String encoded = fragment < 0 ? pathAndQuery.substring(query + 1) : pathAndQuery.substring(query + 1, fragment);
        String replaced = replacePairs(encoded, identifier, key, placeholder, rawValues, skipped);
        return pathAndQuery.substring(0, query + 1) + replaced + (fragment < 0 ? "" : pathAndQuery.substring(fragment));
    }

    private static String replacePairs(String encoded, IdentifierOccurrence identifier, String key, String placeholder,
                                       Map<String, String> rawValues, List<String> skipped) {
        String[] pairs = encoded.split("&", -1);
        for (int index = 0; index < pairs.length; index++) {
            int equals = pairs[index].indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String rawValue = pairs[index].substring(equals + 1);
            if (decode(pairs[index].substring(0, equals)).equals(identifier.selector())
                    && decode(rawValue).equals(identifier.value()) && consistent(rawValues, key, rawValue)) {
                rawValues.put(key, rawValue);
                pairs[index] = pairs[index].substring(0, equals + 1) + placeholder;
                return String.join("&", pairs);
            }
        }
        skipped.add(identifier.describe());
        return encoded;
    }

    private String replaceJson(String body, Map<String, String> replacements, Map<String, String> rawValues,
                               List<String> skipped) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception exception) {
            replacements.keySet().forEach(path -> skipped.add("JSON " + path));
            return body;
        }
        Map<String, String> markers = new LinkedHashMap<>();
        int counter = 0;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String marker = "__corestra_placeholder_" + counter++ + "__";
            JsonNode original = setLeaf(root, entry.getKey(), marker);
            String raw = original == null ? null : rawJson(original);
            if (raw == null || !consistent(rawValues, entry.getValue(), raw)) {
                skipped.add("JSON " + entry.getKey());
                continue;
            }
            rawValues.put(entry.getValue(), raw);
            String placeholder = "{{data." + entry.getValue() + "}}";
            markers.put("\"" + marker + "\"", original.isTextual() ? "\"" + placeholder + "\"" : placeholder);
        }
        if (markers.isEmpty()) {
            return body;
        }
        String serialized;
        try {
            serialized = json.writeValueAsString(root);
        } catch (Exception exception) {
            return body;
        }
        for (Map.Entry<String, String> marker : markers.entrySet()) {
            serialized = serialized.replace(marker.getKey(), marker.getValue());
        }
        return serialized;
    }

    /** JSON-escaped string content, or the literal number text. */
    private String rawJson(JsonNode node) {
        if (!node.isTextual()) {
            return node.asText();
        }
        try {
            String quoted = json.writeValueAsString(node.asText());
            return quoted.substring(1, quoted.length() - 1);
        } catch (Exception exception) {
            return null;
        }
    }

    /** The same key must stand for one exact raw text everywhere it is used. */
    private static boolean consistent(Map<String, String> rawValues, String key, String raw) {
        String previous = rawValues.get(key);
        return previous == null || previous.equals(raw);
    }

    /** Sets the leaf to a text marker; returns the original node, or null when not found. */
    private static JsonNode setLeaf(JsonNode root, String path, String marker) {
        Matcher matcher = JSON_PATH_STEP.matcher(path);
        List<Object> steps = new ArrayList<>();
        while (matcher.find()) {
            steps.add(matcher.group(1) != null ? matcher.group(1) : Integer.valueOf(matcher.group(2)));
        }
        if (steps.isEmpty()) {
            return null;
        }
        JsonNode parent = root;
        for (int index = 0; index < steps.size() - 1; index++) {
            parent = child(parent, steps.get(index));
            if (parent == null) {
                return null;
            }
        }
        Object leaf = steps.getLast();
        JsonNode current = child(parent, leaf);
        if (current == null || !current.isValueNode() || current.isNull()) {
            return null;
        }
        if (leaf instanceof Integer position && parent instanceof ArrayNode array) {
            array.set(position, TextNode.valueOf(marker));
        } else if (leaf instanceof String field && parent instanceof ObjectNode object) {
            object.set(field, TextNode.valueOf(marker));
        } else {
            return null;
        }
        return current;
    }

    private static JsonNode child(JsonNode node, Object step) {
        if (step instanceof Integer index) {
            return node != null && node.isArray() && index < node.size() ? node.get(index) : null;
        }
        return node != null && node.isObject() ? node.get((String) step) : null;
    }

    private static String allocateKey(String name, String decodedValue, Map<String, String> existing,
                                      Set<String> allocated) {
        String base = Objects.requireNonNullElse(name, "").replaceAll("[^A-Za-z0-9_]", "");
        if (base.isEmpty() || Character.isDigit(base.charAt(0))) {
            base = "id" + base;
        }
        String key = base;
        for (int suffix = 2; allocated.contains(key)
                || (existing.containsKey(key) && !decodedValue.equals(decode(existing.get(key)))); suffix++) {
            key = base + suffix;
        }
        allocated.add(key);
        return key;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(Objects.requireNonNullElse(value, ""), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return value;
        }
    }

    /**
     * Parameterization outcome.
     *
     * @param request request with placeholders
     * @param dataValues Data Profile values (raw, as they appeared) that reproduce the original request
     * @param addedPlaceholders new {@code data.*} placeholder references
     * @param skipped identifiers that could not be replaced (descriptions)
     */
    public record Result(
            StoredRequestTemplate request,
            Map<String, String> dataValues,
            Set<ScopedVariableRef> addedPlaceholders,
            List<String> skipped) {
        /** Freezes collections. */
        public Result {
            Objects.requireNonNull(request, "request");
            dataValues = Collections.unmodifiableMap(new LinkedHashMap<>(dataValues));
            addedPlaceholders = Collections.unmodifiableSet(new LinkedHashSet<>(addedPlaceholders));
            skipped = List.copyOf(skipped);
        }
    }
}

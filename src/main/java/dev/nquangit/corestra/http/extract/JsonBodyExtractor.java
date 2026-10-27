package dev.nquangit.corestra.http.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonPointer;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import dev.nquangit.corestra.flow.rule.ExtractRule.JsonResultMode;

import java.util.Objects;
import java.util.Optional;

/**
 * Extracts JSON values using legacy dot paths, JSON Pointer, or rooted Jayway JSONPath queries.
 *
 * <p>Path segments are split on {@code '.'} and traversed sequentially through the JSON tree.
 * Selectors prefixed with {@code pointer:} use JSON Pointer instead, including array indices
 * and escaped property names. Existing dot-path selectors keep their original behavior.
 */
public final class JsonBodyExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Configuration JSON_PATH = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider(OBJECT_MAPPER))
            .mappingProvider(new JacksonMappingProvider(OBJECT_MAPPER)).build();

    /** Detects explicit JSONPath roots without changing legacy property names such as $token. */
    public static boolean isJsonPath(String selector) {
        return selector != null && (selector.equals("$") || selector.startsWith("$.") || selector.startsWith("$["));
    }

    /** Validates query or pointer syntax without requiring a response or sending HTTP. */
    public static Optional<String> validateSelector(String selector) {
        try {
            if (isJsonPath(selector)) JsonPath.compile(selector);
            else if (selector != null && selector.startsWith("pointer:")) JsonPointer.compile(selector.substring(8));
            return Optional.empty();
        } catch (RuntimeException invalid) {
            return Optional.of("Invalid JSON selector: " + Objects.requireNonNullElse(invalid.getMessage(), invalid.getClass().getSimpleName()));
        }
    }

    /**
     * Extracts using an explicit JSONPath root, pointer: prefix, or legacy dot path.
     *
     * <p>Example: given body {@code {"data":{"token":"abc"}}} and path {@code "data.token"},
     * the returned value is {@code "abc"}.
     *
     * <p>JSONPath and JSON Pointer preserve containers as JSON text. Legacy dot paths retain
     * their original {@link JsonNode#asText()} conversion for containers.
     *
     * @param jsonBody       the JSON-encoded body string; must not be {@code null}
     * @param dotPath        the JSON selector; must not be {@code null}
     * @param targetVariable the variable name to associate with the result
     * @return a successful {@link ExtractionResult} containing the resolved value, or a failure if
     *         the body is not valid JSON or the path does not resolve to a value
     */
    public ExtractionResult extract(String jsonBody, String dotPath, String targetVariable) {
        return extract(jsonBody, dotPath, targetVariable, JsonResultMode.SINGLE);
    }

    /** Extracts using explicit match handling for rooted JSONPath queries. */
    public ExtractionResult extract(String jsonBody, String dotPath, String targetVariable, JsonResultMode mode) {
        Objects.requireNonNull(jsonBody, "jsonBody");
        Objects.requireNonNull(dotPath, "dotPath");
        Objects.requireNonNull(targetVariable, "targetVariable");

        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(jsonBody);
        } catch (Exception e) {
            return ExtractionResult.failure(targetVariable,
                    "Body is not valid JSON: " + e.getMessage());
        }

        return extractTree(root, dotPath, targetVariable, Objects.requireNonNull(mode, "mode"));
    }

    ExtractionResult extractTree(JsonNode root, String dotPath, String targetVariable) {
        return extractTree(root, dotPath, targetVariable, JsonResultMode.SINGLE);
    }

    private ExtractionResult extractTree(JsonNode root, String dotPath, String targetVariable, JsonResultMode mode) {
        if (isJsonPath(dotPath)) return extractQuery(root, dotPath, targetVariable, mode);
        if (dotPath.startsWith("pointer:")) {
            try {
                JsonNode selected = root == null ? null : root.at(dotPath.substring("pointer:".length()));
                if (selected == null || selected.isMissingNode() || selected.isNull()) {
                    return ExtractionResult.failure(targetVariable, "JSON Pointer did not resolve to a non-null value");
                }
                return ExtractionResult.success(targetVariable,
                        selected.isContainerNode() ? selected.toString() : selected.asText());
            } catch (IllegalArgumentException invalid) {
                return ExtractionResult.failure(targetVariable, "Invalid JSON Pointer selector");
            }
        }
        if (root == null) return ExtractionResult.failure(targetVariable, "Body is empty");

        String[] segments = dotPath.split("\\.", -1);
        JsonNode current = root;

        for (String segment : segments) {
            if (!current.isObject()) {
                return ExtractionResult.failure(targetVariable,
                        "Path segment '" + segment + "' cannot be resolved: node is not an object");
            }
            JsonNode next = current.get(segment);
            if (next == null || next.isMissingNode()) {
                return ExtractionResult.failure(targetVariable,
                        "JSON path '" + dotPath + "' not found");
            }
            current = next;
        }

        if (current.isNull()) {
            return ExtractionResult.failure(targetVariable,
                    "JSON path '" + dotPath + "' resolved to null");
        }

        return ExtractionResult.success(targetVariable, current.asText());
    }

    private ExtractionResult extractQuery(JsonNode root, String selector, String targetVariable, JsonResultMode mode) {
        if (root == null) return ExtractionResult.failure(targetVariable, "Body is empty");
        JsonPath query;
        try { query = JsonPath.compile(selector); }
        catch (RuntimeException invalid) {
            return ExtractionResult.failure(targetVariable, "Invalid JSONPath: " + invalid.getMessage());
        }
        try {
            Object result = query.read(root, JSON_PATH);
            JsonNode value = result instanceof JsonNode node ? node : OBJECT_MAPPER.valueToTree(result);
            if (value == null || value.isNull()) return ExtractionResult.failure(targetVariable, "JSONPath resolved to null");
            // A definite path selecting an array selects one node; a filter/wildcard selects a match list.
            var matches = !query.isDefinite() && value.isArray() ? (com.fasterxml.jackson.databind.node.ArrayNode) value
                    : OBJECT_MAPPER.createArrayNode().add(value);
            if (matches.isEmpty()) return ExtractionResult.failure(targetVariable, "JSONPath matched no values");
            if (mode == JsonResultMode.SINGLE && matches.size() != 1) {
                return ExtractionResult.failure(targetVariable, "JSONPath matched " + matches.size()
                        + " values; require one match, refine the query or explicitly select First/All matches.");
            }
            JsonNode selected = mode == JsonResultMode.ALL ? matches : matches.get(0);
            if (selected.isNull()) return ExtractionResult.failure(targetVariable, "Selected JSONPath match is null");
            return ExtractionResult.success(targetVariable, selected.isContainerNode() ? selected.toString() : selected.asText());
        } catch (PathNotFoundException missing) {
            return ExtractionResult.failure(targetVariable, "JSONPath did not resolve to a value");
        } catch (RuntimeException failed) {
            return ExtractionResult.failure(targetVariable, "JSONPath evaluation failed (" + failed.getClass().getSimpleName()
                    + "); check selector and JSON value types");
        }
    }
}

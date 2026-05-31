package dev.nquangit.corestra.http.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/**
 * Extracts a value from a JSON body string using a dot-separated path selector.
 *
 * <p>Path segments are split on {@code '.'} and traversed sequentially through the JSON tree.
 * Array element access is not supported; use {@link RegexExtractor} for those cases.
 */
public final class JsonBodyExtractor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Extracts the value at a dot-path from a JSON body string.
     *
     * <p>Example: given body {@code {"data":{"token":"abc"}}} and path {@code "data.token"},
     * the returned value is {@code "abc"}.
     *
     * <p>Non-object and non-scalar leaf nodes (arrays, etc.) are returned using their
     * {@link JsonNode#asText()} representation.
     *
     * @param jsonBody       the JSON-encoded body string; must not be {@code null}
     * @param dotPath        a dot-separated path into the JSON structure; must not be {@code null}
     * @param targetVariable the variable name to associate with the result
     * @return a successful {@link ExtractionResult} containing the resolved value, or a failure if
     *         the body is not valid JSON or the path does not resolve to a value
     */
    public ExtractionResult extract(String jsonBody, String dotPath, String targetVariable) {
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
}

package dev.nquangit.corestra.http.inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.flow.rule.InjectRule.WriteStrategy;

import java.util.Objects;

/**
 * Injects a value at a dot-path location within a JSON request body.
 *
 * <p>The path notation uses dots to separate object field names, for example
 * {@code "data.user.token"} refers to {@code root → data → user → token}.
 * Intermediate nodes that are missing are created as new {@link ObjectNode} instances.
 *
 * <p>{@link WriteStrategy#OVERWRITE} always writes the value; {@link WriteStrategy#APPEND}
 * and {@link WriteStrategy#SKIP_IF_PRESENT} both skip writing when the target field already
 * contains a non-null, non-missing value.
 *
 * <p>Failures (invalid JSON, non-object intermediate node) are reported via a returned
 * {@link InjectionOutcome} rather than by throwing exceptions.
 */
public final class JsonBodyInjector {

    private final ObjectMapper mapper;

    /**
     * Creates a {@code JsonBodyInjector} with a default {@link ObjectMapper}.
     */
    public JsonBodyInjector() {
        this.mapper = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /**
     * Injects {@code value} at the dot-path {@code jsonPath} inside {@code body}.
     *
     * @param body      the JSON body string to mutate; must not be {@code null}
     * @param jsonPath  the dot-separated path to the target field; must not be {@code null}
     * @param value     the string value to write; must not be {@code null}
     * @param strategy  {@link WriteStrategy#OVERWRITE} to always write;
     *                  {@link WriteStrategy#APPEND} or {@link WriteStrategy#SKIP_IF_PRESENT}
     *                  to skip if the path already holds a non-null value
     * @return an {@link InjectionOutcome} carrying the mutated JSON or a failure message
     */
    public InjectionOutcome inject(String body, String jsonPath, String value, WriteStrategy strategy) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(jsonPath, "jsonPath");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(strategy, "strategy");

        if (body.isBlank()) return InjectionOutcome.failure("Empty JSON body");
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            // Parser messages can include excerpts of credentials or scan payloads. Locations
            // are sufficient to diagnose malformed JSON without copying request data to logs.
            var location = e.getLocation();
            return InjectionOutcome.failure("Invalid JSON body" + (location == null ? ""
                    : " at line " + location.getLineNr() + ", column " + location.getColumnNr()));
        }

        if (!(root instanceof ObjectNode)) {
            return InjectionOutcome.failure("JSON root is not an object");
        }

        String[] segments = jsonPath.split("\\.", -1);
        ObjectNode current = (ObjectNode) root;

        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            JsonNode child = current.get(segment);
            if (child == null || child.isNull()) {
                ObjectNode newNode = mapper.createObjectNode();
                current.set(segment, newNode);
                current = newNode;
            } else if (child instanceof ObjectNode childObject) {
                current = childObject;
            } else {
                return InjectionOutcome.failure(
                        "Cannot traverse path '" + jsonPath + "': node at '" + segment + "' is not an object");
            }
        }

        String leafKey = segments[segments.length - 1];

        if (strategy == WriteStrategy.APPEND || strategy == WriteStrategy.SKIP_IF_PRESENT) {
            JsonNode existing = current.get(leafKey);
            if (existing != null && !existing.isNull() && !existing.isMissingNode()) {
                String strategyLabel = strategy == WriteStrategy.SKIP_IF_PRESENT ? "SKIP_IF_PRESENT" : "APPEND";
                return InjectionOutcome.skipped("Path '" + jsonPath + "' already has a value; skipped (" + strategyLabel + " strategy)");
            }
        }

        current.put(leafKey, value);

        try {
            return InjectionOutcome.success(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return InjectionOutcome.failure("Failed to serialise mutated JSON: " + e.getMessage());
        }
    }

    /**
     * Represents the outcome of a single JSON injection attempt.
     *
     * <p>Use {@link #success(String)}, {@link #skipped(String)}, or {@link #failure(String)}
     * to construct instances.
     */
    public static final class InjectionOutcome {

        private final String body;
        private final String message;
        private final boolean succeeded;
        private final boolean skipped;

        private InjectionOutcome(String body, String message, boolean succeeded, boolean skipped) {
            this.body = body;
            this.message = message;
            this.succeeded = succeeded;
            this.skipped = skipped;
        }

        /**
         * Creates a successful outcome carrying the mutated JSON body.
         *
         * @param mutatedBody the serialised JSON after injection
         * @return a successful {@code InjectionOutcome}
         */
        public static InjectionOutcome success(String mutatedBody) {
            return new InjectionOutcome(mutatedBody, null, true, false);
        }

        /**
         * Creates a skipped outcome when {@link WriteStrategy#APPEND} suppressed the write.
         *
         * @param reason a human-readable explanation
         * @return a skipped {@code InjectionOutcome} (treated as success for the body value)
         */
        public static InjectionOutcome skipped(String reason) {
            return new InjectionOutcome(null, reason, false, true);
        }

        /**
         * Creates a failed outcome carrying an error message.
         *
         * @param reason a human-readable description of the failure
         * @return a failed {@code InjectionOutcome}
         */
        public static InjectionOutcome failure(String reason) {
            return new InjectionOutcome(null, reason, false, false);
        }

        /**
         * Returns {@code true} when the injection mutated the JSON body.
         *
         * @return {@code true} on success
         */
        public boolean succeeded() {
            return succeeded;
        }

        /** Returns whether the configured write strategy deliberately preserved an existing value. */
        public boolean skipped() {
            return skipped;
        }

        /**
         * Returns the mutated JSON body, or {@code null} when the injection did not succeed.
         *
         * @return the mutated JSON, or {@code null}
         */
        public String body() {
            return body;
        }

        /**
         * Returns the diagnostic message, or {@code null} on success.
         *
         * @return the diagnostic message, or {@code null}
         */
        public String message() {
            return message;
        }
    }
}

package dev.nquangit.corestra.http.inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.nquangit.corestra.flow.rule.InjectRule.WriteStrategy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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

    private static final JsonNodeFactory EXACT_NODES = JsonNodeFactory.withExactBigDecimals(true);

    private final ObjectMapper mapper;

    /**
     * Creates a {@code JsonBodyInjector} with a default {@link ObjectMapper}.
     */
    public JsonBodyInjector() {
        // Decimals are kept as exact BigDecimals so untouched fields such as 1.10 are re-serialised
        // unchanged instead of being rounded through double.
        this.mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .setNodeFactory(EXACT_NODES);
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

        List<PathStep> steps = parsePath(jsonPath);
        if (steps == null) {
            return InjectionOutcome.failure("Invalid JSON path '" + jsonPath + "'");
        }
        if (!(root instanceof ObjectNode) && !(root instanceof ArrayNode
                && (steps.getFirst().isIndex() || steps.getFirst().ambiguous()))) {
            return InjectionOutcome.failure("JSON root is not an object");
        }

        JsonNode current = root;
        for (int i = 0; i < steps.size() - 1; i++) {
            PathStep step = steps.get(i).resolve(current);
            PathStep nextStep = steps.get(i + 1);
            if (step.isIndex()) {
                if (!(current instanceof ArrayNode array) || step.index() >= array.size()) {
                    return InjectionOutcome.failure("Cannot traverse path '" + jsonPath + "': no array element at index "
                            + step.index());
                }
                current = array.get(step.index());
                continue;
            }
            if (!(current instanceof ObjectNode object)) {
                return InjectionOutcome.failure(
                        "Cannot traverse path '" + jsonPath + "': node before '" + step.field() + "' is not an object");
            }
            JsonNode child = object.get(step.field());
            if ((child == null || child.isNull()) && !nextStep.isIndex()) {
                ObjectNode newNode = mapper.createObjectNode();
                object.set(step.field(), newNode);
                current = newNode;
            } else if (child instanceof ObjectNode || child instanceof ArrayNode) {
                current = child;
            } else {
                return InjectionOutcome.failure(
                        "Cannot traverse path '" + jsonPath + "': node at '" + step.field() + "' is not an object");
            }
        }

        PathStep leaf = steps.getLast().resolve(current);
        JsonNode existing;
        if (leaf.isIndex()) {
            if (!(current instanceof ArrayNode array) || leaf.index() >= array.size()) {
                return InjectionOutcome.failure("Cannot write path '" + jsonPath + "': no array element at index "
                        + leaf.index());
            }
            existing = array.get(leaf.index());
        } else if (current instanceof ObjectNode object) {
            existing = object.get(leaf.field());
        } else {
            return InjectionOutcome.failure(
                    "Cannot write path '" + jsonPath + "': node before '" + leaf.field() + "' is not an object");
        }

        if (strategy == WriteStrategy.APPEND || strategy == WriteStrategy.SKIP_IF_PRESENT) {
            if (existing != null && !existing.isNull() && !existing.isMissingNode()) {
                String strategyLabel = strategy == WriteStrategy.SKIP_IF_PRESENT ? "SKIP_IF_PRESENT" : "APPEND";
                return InjectionOutcome.skipped("Path '" + jsonPath + "' already has a value; skipped (" + strategyLabel + " strategy)");
            }
        }

        JsonNode replacement = typedValue(existing, value);
        if (leaf.isIndex()) {
            ((ArrayNode) current).set(leaf.index(), replacement);
        } else {
            ((ObjectNode) current).set(leaf.field(), replacement);
        }

        try {
            return InjectionOutcome.success(mapper.writeValueAsString(root));
        } catch (Exception e) {
            return InjectionOutcome.failure("Failed to serialise mutated JSON: " + e.getMessage());
        }
    }

    /**
     * Keeps the JSON type of the value being replaced: a numeric field stays numeric when the new
     * value is a valid number and a boolean stays boolean for {@code true}/{@code false}. New
     * fields and every other case are written as strings, as before.
     */
    private static JsonNode typedValue(JsonNode existing, String value) {
        if (existing != null && existing.isNumber() && plainNumber(value.strip())) {
            try {
                BigDecimal number = new BigDecimal(value.strip());
                if (existing.isIntegralNumber() && number.scale() <= 0) {
                    return EXACT_NODES.numberNode(number.toBigIntegerExact());
                }
                return EXACT_NODES.numberNode(number);
            } catch (NumberFormatException | ArithmeticException ignored) {
                return TextNode.valueOf(value);
            }
        }
        if (existing != null && existing.isBoolean()) {
            String normalized = value.strip();
            if ("true".equals(normalized) || "false".equals(normalized)) {
                return BooleanNode.valueOf(Boolean.parseBoolean(normalized));
            }
        }
        return TextNode.valueOf(value);
    }

    /**
     * Accepts only plain JSON-style numbers ({@code 12}, {@code -3}, {@code 1.50}); values such as
     * {@code 007}, {@code +5} or {@code 1E3} stay strings so leading zeros and formats survive.
     */
    private static boolean plainNumber(String value) {
        return value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?");
    }

    /**
     * Parses {@code a.b}, {@code $.a.b}, {@code a[0].b}, {@code a.0.b} and {@code $[0].b}.
     * A purely numeric segment is an array index; returns null for malformed paths.
     */
    private static List<PathStep> parsePath(String jsonPath) {
        String path = jsonPath.strip();
        if (path.startsWith("$.")) {
            path = path.substring(2);
        } else if (path.startsWith("$[")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return null;
        }
        List<PathStep> steps = new ArrayList<>();
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty()) {
                return null;
            }
            int bracket = segment.indexOf('[');
            String field = bracket < 0 ? segment : segment.substring(0, bracket);
            if (!field.isEmpty()) {
                steps.add(isDigits(field) ? PathStep.dottedDigits(field) : PathStep.field(field));
            }
            while (bracket >= 0) {
                int close = segment.indexOf(']', bracket);
                if (close < 0) {
                    return null;
                }
                String inside = segment.substring(bracket + 1, close).strip();
                if (!isDigits(inside)) {
                    return null;
                }
                steps.add(PathStep.index(Integer.parseInt(inside)));
                if (close + 1 == segment.length()) {
                    bracket = -1;
                } else if (segment.charAt(close + 1) == '[') {
                    bracket = close + 1;
                } else {
                    return null;
                }
            }
        }
        return steps.isEmpty() ? null : steps;
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty() || value.length() > 9) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * One path step. A dotted all-digit segment ({@code a.0.b}) is ambiguous: it is an array index
     * when applied to an array and an object key (e.g. {@code scores.2024}) otherwise.
     */
    private record PathStep(String field, int index, boolean ambiguous) {
        static PathStep field(String name) {
            return new PathStep(name, -1, false);
        }

        static PathStep index(int position) {
            return new PathStep(null, position, false);
        }

        static PathStep dottedDigits(String digits) {
            return new PathStep(digits, Integer.parseInt(digits), true);
        }

        boolean isIndex() {
            return field == null;
        }

        PathStep resolve(JsonNode current) {
            if (!ambiguous) {
                return this;
            }
            return current instanceof ArrayNode ? index(index) : field(field);
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

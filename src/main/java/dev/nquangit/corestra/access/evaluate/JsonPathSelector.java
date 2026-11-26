package dev.nquangit.corestra.access.evaluate;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Selects JSON nodes with a small, predictable path syntax used by evaluation rules.
 *
 * <p>Supported forms: {@code user.ssn}, {@code $.user.ssn}, {@code items[0].id},
 * {@code items[*].id}, {@code items.0.id}, {@code $[0].id}. When a field name is applied to an
 * array, it is applied to every element, so {@code ssn} also matches {@code [{"ssn":"…"}]}.
 * Missing and JSON {@code null} nodes are never returned.</p>
 */
public final class JsonPathSelector {
    private static final int MAX_RESULTS = 10_000;

    private JsonPathSelector() {
    }

    /**
     * Returns every node matching the path.
     *
     * @param root parsed JSON document, or null
     * @param path path expression
     * @return matching nodes in document order; empty when nothing matches or the path is blank
     */
    public static List<JsonNode> select(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return List.of();
        }
        List<String> steps = steps(path.strip());
        if (steps == null) {
            return List.of();
        }
        List<JsonNode> current = List.of(root);
        for (String step : steps) {
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                apply(node, step, next);
                if (next.size() > MAX_RESULTS) {
                    break;
                }
            }
            current = next;
            if (current.isEmpty()) {
                return List.of();
            }
        }
        return current.stream().filter(node -> !node.isMissingNode() && !node.isNull()).toList();
    }

    private static void apply(JsonNode node, String step, List<JsonNode> out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if ("[*]".equals(step)) {
            if (node.isArray() || node.isObject()) {
                node.forEach(out::add);
            }
            return;
        }
        if (step.startsWith("[")) {
            int index = Integer.parseInt(step.substring(1, step.length() - 1));
            if (node.isArray() && index < node.size()) {
                out.add(node.get(index));
            }
            return;
        }
        if (node.isObject()) {
            JsonNode child = node.get(step);
            if (child != null) {
                out.add(child);
            }
            return;
        }
        if (node.isArray()) {
            if (isIndex(step)) {
                int index = Integer.parseInt(step);
                if (index < node.size()) {
                    out.add(node.get(index));
                }
                return;
            }
            for (JsonNode element : node) {
                if (element.isObject()) {
                    JsonNode child = element.get(step);
                    if (child != null) {
                        out.add(child);
                    }
                }
            }
        }
    }

    /**
     * Splits a path into field names and bracket steps ({@code [3]}, {@code [*]}).
     *
     * @return steps, or null when the path is malformed
     */
    private static List<String> steps(String path) {
        String value = path;
        if (value.equals("$")) {
            return List.of();
        }
        if (value.startsWith("$.")) {
            value = value.substring(2);
        } else if (value.startsWith("$[")) {
            value = value.substring(1);
        }
        List<String> steps = new ArrayList<>();
        for (String segment : value.split("\\.", -1)) {
            String remaining = segment.strip();
            if (remaining.isEmpty()) {
                return null;
            }
            int bracket = remaining.indexOf('[');
            String field = bracket < 0 ? remaining : remaining.substring(0, bracket);
            if (!field.isEmpty()) {
                steps.add(field);
            }
            while (bracket >= 0) {
                int close = remaining.indexOf(']', bracket);
                if (close < 0) {
                    return null;
                }
                String inside = remaining.substring(bracket + 1, close).strip();
                if (!"*".equals(inside) && !isIndex(inside)) {
                    return null;
                }
                steps.add("[" + inside + "]");
                if (close + 1 == remaining.length()) {
                    bracket = -1;
                } else if (remaining.charAt(close + 1) == '[') {
                    bracket = close + 1;
                } else {
                    return null;
                }
            }
        }
        return steps;
    }

    private static boolean isIndex(String value) {
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
     * Returns a node's comparable text: plain text for scalars, JSON for containers.
     *
     * @param node JSON node
     * @return comparable text
     */
    public static String comparableText(JsonNode node) {
        Objects.requireNonNull(node, "node");
        return node.isValueNode() ? node.asText() : node.toString();
    }
}

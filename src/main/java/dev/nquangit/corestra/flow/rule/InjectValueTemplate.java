package dev.nquangit.corestra.flow.rule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** Parses injection templates against the flat variable keys used by Flowise execution. */
public final class InjectValueTemplate {
    private InjectValueTemplate() { }

    /** Validates a template and returns its distinct variable keys in declaration order. */
    public static List<String> variableKeys(String template) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        placeholders(template).forEach(placeholder -> keys.add(placeholder.key()));
        return List.copyOf(keys);
    }

    /** Resolves every placeholder once; a missing value rejects the entire template. */
    public static String render(String template, Map<String, String> variables) {
        Objects.requireNonNull(variables, "variables");
        return replace(template, key -> {
            String value = variables.get(key);
            if (value == null) {
                throw new IllegalArgumentException("context variable '" + key + "' not found");
            }
            return value;
        });
    }

    /** Renames placeholder keys without touching literal text or other variable names. */
    public static String rename(String template, Map<String, String> renames) {
        return replace(template, key -> "{{" + renames.getOrDefault(key, key) + "}}");
    }

    private static String replace(String template, Function<String, String> replacement) {
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        for (Placeholder placeholder : placeholders(template)) {
            result.append(template, cursor, placeholder.start());
            result.append(replacement.apply(placeholder.key()));
            cursor = placeholder.end();
        }
        return result.append(template, cursor, template.length()).toString();
    }

    private static List<Placeholder> placeholders(String template) {
        Objects.requireNonNull(template, "template");
        List<Placeholder> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < template.length()) {
            int open = template.indexOf("{{", cursor);
            int close = template.indexOf("}}", cursor);
            if (close >= 0 && (open < 0 || close < open)) {
                throw new IllegalArgumentException("Template contains stray closing braces at " + close);
            }
            if (open < 0) break;
            close = template.indexOf("}}", open + 2);
            if (close < 0) {
                throw new IllegalArgumentException("Template contains an unclosed placeholder at " + open);
            }
            String key = template.substring(open + 2, close).strip();
            if (key.isEmpty() || key.chars().anyMatch(ch -> Character.isWhitespace(ch) || ch == '{' || ch == '}')
                    || (close + 2 < template.length() && template.charAt(close + 2) == '}')) {
                throw new IllegalArgumentException("Template needs {{variableKey}}; blank, nested or malformed placeholders are not supported");
            }
            result.add(new Placeholder(open, close + 2, key));
            cursor = close + 2;
        }
        return result;
    }

    private record Placeholder(int start, int end, String key) { }
}

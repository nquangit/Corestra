package dev.nquangit.corestra.manual;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.http.transform.ValueTransformService;
import java.util.*;

/** Strict, single-pass inline templates used exclusively by the Manual Testing workspace. */
public final class ManualRequestTemplate {
    /** Execution metadata opting into inline request templates. */
    public static final String ENABLED = "corestra.manual.templates";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ValueTransformService TRANSFORMS = new ValueTransformService();
    private ManualRequestTemplate() { }

    /** Renders path, headers and body; the explicit service destination is never variable-controlled. */
    public static StoredRequestTemplate render(StoredRequestTemplate t, Map<String, String> variables) {
        return new StoredRequestTemplate(t.method(), t.scheme(), t.host(), t.port(), render(t.pathAndQuery(), variables),
                t.headerLines().stream().map(line -> render(line, variables)).toList(), render(t.body(), variables));
    }

    /** Resolves {{key|transform}} once. Missing keys fail; deliberately empty values are allowed. */
    public static String render(String text, Map<String, String> variables) {
        StringBuilder result = new StringBuilder();
        for (int cursor = 0; cursor < text.length();) {
            if (text.startsWith("\\{{", cursor)) { result.append("{{"); cursor += 3; continue; }
            if (!text.startsWith("{{", cursor)) { result.append(text.charAt(cursor++)); continue; }
            int end = text.indexOf("}}", cursor + 2);
            if (end < 0) throw new IllegalArgumentException("Unclosed manual template placeholder");
            String expression = text.substring(cursor + 2, end);
            String[] parts = expression.split("\\|", -1);
            String key = parts[0].strip();
            if (key.isBlank() || key.chars().anyMatch(c -> Character.isWhitespace(c) || c == '{' || c == '}'))
                throw new IllegalArgumentException("Invalid manual template variable");
            String value = variables.get(key);
            if (value == null) throw new IllegalArgumentException("Missing manual variable: " + key);
            for (int i = 1; i < parts.length; i++) {
                String transform = parts[i].stripLeading();
                if (!transform.contains(":")) transform = transform.strip();
                if (transform.isBlank()) throw new IllegalArgumentException("Empty manual transform for " + key);
                if (transform.strip().equals("jsonEscape")) value = jsonEscape(value);
                else {
                    var converted = TRANSFORMS.apply(value, List.of(transform));
                    if (!converted.success()) throw new IllegalArgumentException("Manual transform failed for " + key);
                    value = converted.value();
                }
            }
            result.append(value); cursor = end + 2;
        }
        return result.toString();
    }

    /** Escapes literal opening braces so copied captured data cannot become executable placeholders. */
    public static String literal(String value) { return value.replace("{{", "\\{{"); }

    /** Escapes the content of a JSON string, without adding surrounding quotes. */
    public static String jsonEscape(String value) {
        try { String json = JSON.writeValueAsString(value); return json.substring(1, json.length() - 1); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("Could not escape JSON string"); }
    }
}

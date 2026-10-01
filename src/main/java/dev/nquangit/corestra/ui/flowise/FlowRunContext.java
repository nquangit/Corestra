package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Builds the initial variable map used by an explicit Flowise run. */
public final class FlowRunContext {
    private FlowRunContext() {
    }

    /**
     * Builds scoped variables from the selected shared auth profile, Access Testing data profile,
     * and custom values.
     * Profile values are exposed through their documented scope only, which keeps collisions
     * deterministic when credential, auth, and data keys share the same suffix.
     *
     * @param authProfile selected auth profile, or {@code null}
     * @param dataProfile selected data profile, or {@code null}
     * @param customVariables user supplied variables; these override profile-derived values
     * @return immutable initial variable map
     */
    public static Map<String, String> variables(
            AuthProfile authProfile,
            DataProfile dataProfile,
            Map<String, String> customVariables) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (authProfile != null) {
            authProfile.credentials().forEach((key, value) -> values.put("credentials." + key, value));
            authProfile.state().variables().forEach((key, value) -> values.put("auth." + key, value));
            authProfile.state().headers().forEach((key, value) -> values.put("auth.header." + key, value));
            authProfile.state().cookies().forEach((key, value) -> values.put("auth.cookie." + key, value));
        }
        if (dataProfile != null) {
            dataProfile.values().forEach((key, value) -> values.put("data." + key, value));
        }
        if (customVariables != null) {
            customVariables.forEach((key, value) -> {
                String normalized = normalizeKey(key);
                if (values.containsKey(normalized)) {
                    values.remove(normalized);
                }
                values.put(normalized, Objects.requireNonNullElse(value, ""));
            });
        }
        return Map.copyOf(values);
    }

    /** Parses one {@code key=value} variable per line. Blank and comment lines are ignored. */
    public static Map<String, String> parseCustomVariables(String text) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        int lineNumber = 0;
        for (String line : text.split("\\R", -1)) {
            lineNumber++;
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new IllegalArgumentException(
                        "Run Context line " + lineNumber + " must use key=value.");
            }
            String key = normalizeKey(line.substring(0, separator));
            if (values.containsKey(key)) {
                throw new IllegalArgumentException(
                        "Run Context contains duplicate variable: " + key);
            }
            values.put(key, line.substring(separator + 1));
        }
        return Map.copyOf(values);
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Variable key must not be blank.");
        }
        String normalized = key.strip();
        if (normalized.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Variable key must not contain whitespace: " + normalized);
        }
        return normalized;
    }
}

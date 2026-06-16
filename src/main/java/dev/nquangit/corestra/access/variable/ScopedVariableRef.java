package dev.nquangit.corestra.access.variable;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable reference to a variable identified by a scope and scoped key path.
 *
 * @param scope the variable scope
 * @param key the key path inside the scope, using {@code segment(.segment)*}
 */
public record ScopedVariableRef(VariableScope scope, String key) {
    private static final Pattern KEY_SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    /**
     * Creates a scoped variable reference.
     *
     * @param scope the variable scope
     * @param key the key path inside the scope
     */
    public ScopedVariableRef {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        if (!isValidKey(key)) {
            throw new IllegalArgumentException("Variable key must match segment(.segment)* with non-blank segments");
        }
    }

    /**
     * Returns the full scoped key in {@code scope.segment(.segment)*} form.
     *
     * @return the full scoped key
     */
    public String scopedKey() {
        return scope.prefix() + "." + key;
    }

    /**
     * Parses a case-sensitive scoped variable key.
     *
     * @param scopedKey the full scoped key
     * @return the parsed reference
     */
    public static ScopedVariableRef parse(String scopedKey) {
        Objects.requireNonNull(scopedKey, "scopedKey");
        int dotIndex = scopedKey.indexOf('.');
        if (dotIndex < 1 || dotIndex == scopedKey.length() - 1) {
            throw new IllegalArgumentException("Scoped key must match scope.segment(.segment)*");
        }

        String scopeText = scopedKey.substring(0, dotIndex);
        VariableScope scope = VariableScope.fromPrefix(scopeText)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported variable scope: " + scopeText));
        String key = scopedKey.substring(dotIndex + 1);
        return new ScopedVariableRef(scope, key);
    }

    /**
     * Attempts to parse a case-sensitive scoped variable key.
     *
     * @param scopedKey the full scoped key
     * @return the parsed reference, or empty when invalid
     */
    public static Optional<ScopedVariableRef> tryParse(String scopedKey) {
        try {
            return Optional.of(parse(scopedKey));
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Optional.empty();
        }
    }

    static boolean isValidKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return Arrays.stream(key.split("\\.", -1)).allMatch(KEY_SEGMENT.asMatchPredicate());
    }
}

package dev.nquangit.corestra.access.variable;

import java.util.Arrays;
import java.util.Optional;

/**
 * Defines the supported top-level scopes for scoped variable keys.
 */
public enum VariableScope {
    ENV("env"),
    AUTH("auth"),
    DATA("data"),
    SCENARIO("scenario"),
    RUNTIME("runtime"),
    LEGACY("legacy");

    private final String prefix;

    VariableScope(String prefix) {
        this.prefix = prefix;
    }

    /**
     * Returns the lowercase textual prefix used in templates and stored keys.
     *
     * @return the case-sensitive scope prefix
     */
    public String prefix() {
        return prefix;
    }

    /**
     * Finds a scope by its case-sensitive lowercase prefix.
     *
     * @param prefix the prefix to resolve
     * @return the matching scope, or empty when the prefix is unsupported
     */
    public static Optional<VariableScope> fromPrefix(String prefix) {
        return Arrays.stream(values())
                .filter(scope -> scope.prefix.equals(prefix))
                .findFirst();
    }
}

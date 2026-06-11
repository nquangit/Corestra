package dev.nquangit.corestra.access.variable;

import java.util.Optional;

/**
 * Provides generated runtime-scoped variable values.
 */
public interface RuntimeValueProvider {
    /**
     * Resolves a runtime key.
     *
     * @param key the key inside the runtime scope
     * @return the generated value, or empty when unsupported
     */
    Optional<String> resolve(String key);
}

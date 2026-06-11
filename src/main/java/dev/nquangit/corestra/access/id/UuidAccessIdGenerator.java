package dev.nquangit.corestra.access.id;

import java.util.Objects;
import java.util.UUID;

/**
 * UUID-backed access identifier generator.
 */
public final class UuidAccessIdGenerator implements AccessIdGenerator {
    /**
     * Creates a new identifier in {@code prefix-uuid} form.
     *
     * @param prefix identifier prefix
     * @return a new identifier
     */
    @Override
    public String newId(String prefix) {
        String normalizedPrefix = normalizePrefix(prefix);
        return normalizedPrefix + "-" + UUID.randomUUID();
    }

    private static String normalizePrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        return prefix.strip();
    }
}

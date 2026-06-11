package dev.nquangit.corestra.access.security;

/**
 * Matches field names that should be treated as sensitive.
 */
public interface SensitiveKeyMatcher {
    /**
     * Returns whether a key should be redacted.
     *
     * @param key field, header, cookie, or variable key
     * @return true when the key is sensitive
     */
    boolean isSensitive(String key);
}

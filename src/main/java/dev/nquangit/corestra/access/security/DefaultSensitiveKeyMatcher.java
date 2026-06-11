package dev.nquangit.corestra.access.security;

import java.util.Locale;
import java.util.Set;

/**
 * Default case-insensitive matcher for common secret and sensitive-data keys.
 */
public final class DefaultSensitiveKeyMatcher implements SensitiveKeyMatcher {
    private static final Set<String> EXACT_KEYS = Set.of(
            "authorization",
            "cookie",
            "set-cookie",
            "x-csrf-token",
            "x-access-token",
            "x-refresh-token",
            "accesstoken",
            "refreshtoken",
            "csrftoken",
            "sessionid",
            "password",
            "deviceid",
            "accountnumber",
            "cardnumber",
            "nationalid");

    /**
     * Returns whether a key should be redacted.
     *
     * @param key field, header, cookie, or variable key
     * @return true when the key is sensitive
     */
    @Override
    public boolean isSensitive(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.strip().toLowerCase(Locale.ROOT);
        String compact = normalized.replace("-", "").replace("_", "");
        return EXACT_KEYS.contains(normalized) || EXACT_KEYS.contains(compact);
    }
}

package dev.nquangit.corestra.watch.engine;

/**
 * Glob matching for expectation key patterns: {@code *} matches any run of characters (including
 * none), {@code ?} matches exactly one character, and every other character, including
 * {@code [} and {@code ]}, is literal. Matching is case-sensitive and covers the whole key.
 * A blank pattern matches every key.
 */
public final class WatchKeyGlob {
    private WatchKeyGlob() {
    }

    /**
     * Returns whether {@code key} matches {@code pattern}.
     *
     * @param pattern glob (blank matches all)
     * @param key     snapshot key
     * @return true on a whole-key match
     */
    public static boolean matches(String pattern, String key) {
        if (key == null) return false;
        if (pattern == null || pattern.isBlank()) return true;
        return match(pattern.strip(), key);
    }

    /** Iterative wildcard matching with single-star backtracking (linear in practice). */
    private static boolean match(String pattern, String key) {
        int p = 0;
        int k = 0;
        int star = -1;
        int resume = 0;
        while (k < key.length()) {
            if (p < pattern.length() && (pattern.charAt(p) == '?' || pattern.charAt(p) == key.charAt(k))) {
                p++;
                k++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                resume = k;
            } else if (star >= 0) {
                p = star + 1;
                k = ++resume;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }
}

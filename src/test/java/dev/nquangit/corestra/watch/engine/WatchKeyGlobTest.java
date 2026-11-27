package dev.nquangit.corestra.watch.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchKeyGlobTest {
    @Test
    void literalBracketsAndWildcards() {
        assertTrue(WatchKeyGlob.matches("balance[0123]", "balance[0123]"));
        assertFalse(WatchKeyGlob.matches("balance[0123]", "balance[0124]"));
        assertFalse(WatchKeyGlob.matches("balance[0123]", "balance0"), "brackets are not a character class");
        assertTrue(WatchKeyGlob.matches("balance[*]", "balance[0123]"));
        assertTrue(WatchKeyGlob.matches("balance[*]", "balance[]"));
        assertFalse(WatchKeyGlob.matches("balance[*]", "fee[0123]"));
        assertTrue(WatchKeyGlob.matches("balance[012?]", "balance[0129]"));
        assertFalse(WatchKeyGlob.matches("balance[012?]", "balance[01299]"));
        assertTrue(WatchKeyGlob.matches("*", "anything"));
        assertTrue(WatchKeyGlob.matches("*[*]*", "a[b]c"));
        assertTrue(WatchKeyGlob.matches("a*b*c", "aXXbYYbZc"));
        assertFalse(WatchKeyGlob.matches("a*b*c", "aXXbYY"));
        assertTrue(WatchKeyGlob.matches("total", "total"));
        assertFalse(WatchKeyGlob.matches("total", "Total"));
        assertFalse(WatchKeyGlob.matches("total", "totals"));
        assertTrue(WatchKeyGlob.matches("a.b", "a.b"));
        assertFalse(WatchKeyGlob.matches("a.b", "axb"), "regex metacharacters are literal");
    }

    @Test
    void blankPatternMatchesAllAndNullKeyMatchesNothing() {
        assertTrue(WatchKeyGlob.matches("", "x"));
        assertTrue(WatchKeyGlob.matches(null, "x"));
        assertFalse(WatchKeyGlob.matches("*", null));
    }
}

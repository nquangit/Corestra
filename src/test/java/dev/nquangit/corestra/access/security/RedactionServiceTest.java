package dev.nquangit.corestra.access.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactionServiceTest {
    @Test
    void redactsAuthorizationCookieAndAccessToken() {
        RedactionService service = new RedactionService();

        assertEquals("<redacted>", service.redactHeader("Authorization", "Bearer abc"));
        assertEquals("<redacted>", service.redactHeader("Cookie", "SESSION=abc"));
        assertEquals("<redacted>", service.redactValue("accessToken", "abc"));
    }

    @Test
    void doesNotRedactNonSensitiveKeys() {
        RedactionService service = new RedactionService();

        assertEquals("Alice", service.redactValue("displayName", "Alice"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        DefaultSensitiveKeyMatcher matcher = new DefaultSensitiveKeyMatcher();

        assertTrue(matcher.isSensitive("authorization"));
        assertTrue(matcher.isSensitive("AUTHORIZATION"));
        assertTrue(matcher.isSensitive("x-csrf-token"));
        assertTrue(matcher.isSensitive("X-CSRF-TOKEN"));
        assertTrue(matcher.isSensitive("Refresh_Token"));
    }

    @Test
    void redactMapReturnsDefensiveUnmodifiableResult() {
        RedactionService service = new RedactionService();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("accessToken", "abc");
        values.put("displayName", "Alice");

        Map<String, String> redacted = service.redactMap(values);
        values.put("displayName", "Changed");

        assertEquals("<redacted>", redacted.get("accessToken"));
        assertEquals("Alice", redacted.get("displayName"));
        assertThrows(UnsupportedOperationException.class, () -> redacted.put("password", "secret"));
    }

    @Test
    void redactHeadersRedactsSensitiveHeaderLines() {
        RedactionService service = new RedactionService();

        List<String> redacted = service.redactHeaders(List.of(
                "Authorization: Bearer abc",
                "X-Display-Name: Alice",
                "Set-Cookie: SESSION=abc"));

        assertEquals(List.of(
                "Authorization: <redacted>",
                "X-Display-Name: Alice",
                "Set-Cookie: <redacted>"), redacted);
    }

    @Test
    void redactHttpMessageRedactsSensitiveHeadersAndInlineSecrets() {
        RedactionService service = new RedactionService();

        String redacted = service.redactHttpMessage("""
                GET / HTTP/1.1
                Authorization: Bearer abc
                Cookie: SESSION=abc
                X-Display-Name: Alice

                {"accessToken":"secret","displayName":"Alice"}""");

        assertTrue(redacted.contains("Authorization: <redacted>"));
        assertTrue(redacted.contains("Cookie: <redacted>"));
        assertTrue(redacted.contains("X-Display-Name: Alice"));
        assertTrue(redacted.contains("\"accessToken\":\"<redacted>\""));
        assertTrue(redacted.contains("\"displayName\":\"Alice\""));
    }
}

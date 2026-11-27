package dev.nquangit.corestra.integration.watch;

import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.watch.WatchScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WatchProbeRequestRendererTest {

    @Test void rendersPlaceholdersEverywhereAndInheritsHeaders() {
        var template = new StoredRequestTemplate("POST", "https", "bank.test", 8443, "/accounts/{{acct}}?q={{ q }}",
                List.of("Host: bank.test:8443", "Cookie: stale=1", "X-Acct: {{acct}}", "Content-Length: 999"),
                "{\"acct\":\"{{acct}}\",\"name\":\"Nguyễn\"}");

        var rendered = WatchProbeRequestRenderer.render(template, Map.of("acct", "0123", "q", "x"),
                List.of("cookie: session=live", "Authorization: Bearer t", "Host: evil", "Content-Length: 1"));

        assertEquals("bank.test", rendered.host());
        assertEquals(8443, rendered.port());
        assertTrue(rendered.secure());
        String body = "{\"acct\":\"0123\",\"name\":\"Nguyễn\"}";
        int length = body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        assertEquals("POST /accounts/0123?q=x HTTP/1.1\r\n"
                + "Host: bank.test:8443\r\n"
                + "X-Acct: 0123\r\n"
                + "cookie: session=live\r\n"
                + "Authorization: Bearer t\r\n"
                + "Content-Length: " + length + "\r\n"
                + "\r\n" + body, rendered.rawRequest());
    }

    @Test void addsHostWhenTemplateHasNoneAndOmitsContentLengthForEmptyBody() {
        var template = new StoredRequestTemplate("GET", "http", "bank.test", 80, "/me", List.of(), "");
        assertEquals("GET /me HTTP/1.1\r\nHost: bank.test\r\n\r\n",
                WatchProbeRequestRenderer.render(template, Map.of(), List.of()).rawRequest());
    }

    @Test void unresolvedPlaceholdersAreAllNamed() {
        var template = new StoredRequestTemplate("GET", "https", "bank.test", 443, "/a/{{one}}",
                List.of("X: {{two}}"), "{{one}}{{three}}");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> WatchProbeRequestRenderer.render(template, Map.of(), List.of()));
        assertEquals("unresolved probe placeholder(s): {{one}}, {{two}}, {{three}}", failure.getMessage());
    }

    @Test void valuesAreLiteralAndLineBreaksCannotSplitHeaders() {
        var template = new StoredRequestTemplate("GET", "https", "bank.test", 443, "/a",
                List.of("X-V: {{v}}"), "{{v}}");
        String raw = WatchProbeRequestRenderer.render(template, Map.of("v", "$1\r\nX-Evil: 1"), List.of()).rawRequest();
        assertTrue(raw.contains("X-V: $1X-Evil: 1\r\n"), raw);
        assertTrue(raw.endsWith("\r\n\r\n$1\r\nX-Evil: 1"), raw);
    }

    @Test void scopeMatching() {
        var scope = new WatchScope(List.of("post"), "bank\\.test$", "^/transfer");
        assertTrue(WatchScopeMatcher.matches(scope, "POST", "API.bank.test", "/transfer?x=1"));
        assertFalse(WatchScopeMatcher.matches(scope, "GET", "bank.test", "/transfer"));
        assertFalse(WatchScopeMatcher.matches(scope, "POST", "bank.test.evil", "/transfer"));
        assertFalse(WatchScopeMatcher.matches(scope, "POST", "bank.test", "/accounts"));
        assertTrue(WatchScopeMatcher.matches(new WatchScope(List.of(), "", ""), "PATCH", "x", "/"));
        assertThrows(java.util.regex.PatternSyntaxException.class,
                () -> WatchScopeMatcher.matches(new WatchScope(List.of(), "(", ""), "GET", "x", "/"));
    }
}

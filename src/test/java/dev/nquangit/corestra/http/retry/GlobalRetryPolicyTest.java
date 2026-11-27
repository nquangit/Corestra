package dev.nquangit.corestra.http.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.persistence.ProjectSettings;
import dev.nquangit.corestra.persistence.ProjectSettingsRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GlobalRetryPolicyTest {

    private static GlobalRetryPolicy policy(List<String> tools, List<Integer> statuses, String regex) {
        return new GlobalRetryPolicy(true, true, "", tools, statuses, regex, List.of(),
                3, 1_000, 30_000, 2.0, true, 60, true);
    }

    @Test
    void defaultsAreDisabledAndExcludeScanner() {
        GlobalRetryPolicy defaults = GlobalRetryPolicy.defaults();
        assertFalse(defaults.enabled());
        assertEquals(List.of("PROXY", "REPEATER", "INTRUDER"), defaults.toolTypes());
        assertEquals(List.of(429, 503), defaults.statusCodes());
        assertFalse(defaults.coversRequest("PROXY", "GET", "example.com", true));
        assertEquals("Disabled", defaults.summary());
    }

    @Test
    void coversRequestFiltersByToolScopeMethodAndHost() {
        GlobalRetryPolicy policy = new GlobalRetryPolicy(true, true, ".*\\.example\\.com", List.of("proxy", "Repeater"),
                List.of(429), "", List.of("get", "POST"), 3, 1_000, 30_000, 2.0, true, 60, true);
        assertTrue(policy.coversRequest("PROXY", "GET", "api.EXAMPLE.com", true));
        assertTrue(policy.coversRequest("REPEATER", "post", "api.example.com", true));
        assertFalse(policy.coversRequest("SCANNER", "GET", "api.example.com", true), "tool not selected");
        assertFalse(policy.coversRequest("EXTENSIONS", "GET", "api.example.com", true), "own traffic never covered");
        assertFalse(policy.coversRequest("PROXY", "GET", "api.example.com", false), "out of Burp scope");
        assertFalse(policy.coversRequest("PROXY", "DELETE", "api.example.com", true), "method not selected");
        assertFalse(policy.coversRequest("PROXY", "GET", "other.org", true), "host regex must match fully");
    }

    @Test
    void retryableByStatusOrFullMessageRegex() {
        GlobalRetryPolicy policy = policy(List.of("PROXY"), List.of(429), "(?i)request blocked");
        assertTrue(policy.isRetryable(429, null));
        assertTrue(policy.isRetryable(403, "HTTP/1.1 403 Forbidden\r\n\r\n<h1>Request Blocked</h1>"));
        assertFalse(policy.isRetryable(403, "HTTP/1.1 403 Forbidden\r\n\r\nno"));
        assertTrue(policy.needsFullMessage());
        assertFalse(policy(List.of("PROXY"), List.of(429), "").needsFullMessage());
    }

    @Test
    void rejectsInvalidSettings() {
        assertThrows(IllegalArgumentException.class, () -> policy(List.of(), List.of(429), ""), "enabled without tools");
        assertThrows(IllegalArgumentException.class, () -> policy(List.of("PROXY"), List.of(), ""), "no condition");
        assertThrows(IllegalArgumentException.class, () -> policy(List.of("EXTENSIONS"), List.of(429), ""));
        assertThrows(IllegalArgumentException.class, () -> policy(List.of("PROXY"), List.of(99), ""));
        assertThrows(IllegalArgumentException.class, () -> policy(List.of("PROXY"), List.of(429), "("));
        assertThrows(IllegalArgumentException.class, () -> new GlobalRetryPolicy(true, true, "", List.of("PROXY"),
                List.of(429), "", List.of(), 11, 1_000, 30_000, 2.0, true, 60, true));
        assertThrows(IllegalArgumentException.class, () -> new GlobalRetryPolicy(true, true, "", List.of("PROXY"),
                List.of(429), "", List.of(), 3, 5_000, 1_000, 2.0, true, 60, true), "max delay below base");
        assertThrows(IllegalArgumentException.class, () -> new GlobalRetryPolicy(true, true, "", List.of("PROXY"),
                List.of(429), "", List.of(), 3, 1_000, 30_000, 0.5, true, 60, true));
        assertThrows(IllegalArgumentException.class, () -> new GlobalRetryPolicy(true, true, "", List.of("PROXY"),
                List.of(429), "", List.of(), 3, 1_000, 30_000, 2.0, true, 301, true));
        assertThrows(IllegalArgumentException.class, () -> new GlobalRetryPolicy(true, true, "", List.of("PROXY"),
                List.of(429), "", List.of("GE T"), 3, 1_000, 30_000, 2.0, true, 60, true));
    }

    @Test
    void roundTripsInsideProjectSettingsAndLegacyDocumentsGetDefaults() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        GlobalRetryPolicy policy = new GlobalRetryPolicy(true, false, "api\\..*", List.of("PROXY", "SCANNER"),
                List.of(503, 429), "(?i)captcha", List.of("GET"), 5, 250, 10_000, 1.5, false, 90, false);
        ProjectSettings settings = ProjectSettings.defaults().withGlobalRetry(policy);

        ProjectSettings restored = mapper.readValue(mapper.writeValueAsString(settings), ProjectSettings.class);
        assertEquals(policy, restored.globalRetry());
        assertEquals(settings, restored);

        ProjectSettings legacy = mapper.readValue(
                "{\"sendMode\":\"PROXY_PIPELINE\",\"proxyHost\":\"127.0.0.2\",\"proxyPort\":9090}", ProjectSettings.class);
        assertEquals(GlobalRetryPolicy.defaults(), legacy.globalRetry());
        assertEquals(HttpSendMode.PROXY_PIPELINE, legacy.sendMode());
    }

    @Test
    void withersKeepTheRetryPolicy() {
        GlobalRetryPolicy policy = policy(List.of("PROXY"), List.of(429), "");
        ProjectSettings settings = ProjectSettings.defaults().withGlobalRetry(policy)
                .withSendMode(HttpSendMode.PROXY_PIPELINE).withProxyHost("h").withProxyPort(9000)
                .withSessionSelection(null);
        assertEquals(policy, settings.globalRetry());
        ProjectSettingsRepository repository = new ProjectSettingsRepository();
        repository.save(settings);
        assertEquals(policy, repository.load().globalRetry());
    }

    @Test
    void retryAfterParsesSecondsAndHttpDates() {
        Instant now = Instant.parse("2026-11-27T10:00:00Z");
        assertEquals(7_000L, RetryAfterParser.delayMillis(" 7 ", now).getAsLong());
        assertEquals(30_000L, RetryAfterParser.delayMillis("Fri, 27 Nov 2026 10:00:30 GMT", now).getAsLong());
        assertEquals(0L, RetryAfterParser.delayMillis("Fri, 27 Nov 2026 09:00:00 GMT", now).getAsLong());
        assertTrue(RetryAfterParser.delayMillis("soon", now).isEmpty());
        assertTrue(RetryAfterParser.delayMillis(null, now).isEmpty());
        assertTrue(RetryAfterParser.delayMillis("-5", now).isEmpty());
    }
}

package dev.nquangit.corestra.http.retry;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.control.StepConditionOperator;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.persistence.ProjectSettings;
import dev.nquangit.corestra.persistence.ProjectSettingsRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.nquangit.corestra.flow.control.StepConditionOperator.*;
import static dev.nquangit.corestra.http.retry.RetryConditionSource.*;
import static org.junit.jupiter.api.Assertions.*;

class RetryRulesTest {
    private final RetryRuleEvaluator evaluator = new RetryRuleEvaluator();

    private static final String CLOUDFLARE_CHALLENGE = """
            HTTP/2 403 Forbidden
            Server: cloudflare
            Cf-Mitigated: challenge
            Content-Type: text/html

            <title>Just a moment...</title>""";
    private static final String CLOUDFLARE_BLOCK = """
            HTTP/1.1 403 Forbidden
            Server: cloudflare

            <title>Attention Required! | Cloudflare</title> Error code: 1020""";
    private static final String APP_403 = """
            HTTP/1.1 403 Forbidden
            Content-Type: application/json

            {"error":{"code":"FORBIDDEN","retryable":false}}""";
    private static final String RATE_LIMITED = """
            HTTP/1.1 429 Too Many Requests
            Retry-After: 3

            slow down""";

    private static RetryMessage msg(String raw) {
        return RetryMessage.parse(raw, "POST", "/api/orders?id=1");
    }

    private static RetryCondition c(RetryConditionSource source, String selector, StepConditionOperator operator, String value) {
        return RetryCondition.of(source, selector, operator, value);
    }

    @Test
    void conditionsCoverEverySourceAndOperator() {
        RetryMessage app = msg(APP_403);
        assertTrue(evaluator.matches(c(STATUS, "", EQUALS, "403"), app));
        assertTrue(evaluator.matches(c(STATUS, "", IN, "401, 403"), app));
        assertTrue(evaluator.matches(c(STATUS, "", NUMERIC_GTE, "400"), app));
        assertFalse(evaluator.matches(c(STATUS, "", NUMERIC_LT, "400"), app));
        assertTrue(evaluator.matches(c(RESPONSE_HEADER, "content-type", CONTAINS, "JSON"), app), "case-insensitive by default");
        assertFalse(evaluator.matches(new RetryCondition(true, 1, RESPONSE_HEADER, "Content-Type", CONTAINS, "JSON", true, false), app));
        assertTrue(evaluator.matches(c(RESPONSE_HEADER, "Retry-After", NOT_EXISTS, ""), app));
        assertTrue(evaluator.matches(c(RESPONSE_HEADER, "Retry-After", NOT_EQUALS, "5"), app), "absent value is not equal");
        assertTrue(evaluator.matches(c(RESPONSE_JSON_PATH, "$.error.code", EQUALS, "forbidden"), app));
        assertTrue(evaluator.matches(c(RESPONSE_JSON_PATH, "error.retryable", EQUALS, "false"), app));
        assertFalse(evaluator.matches(c(RESPONSE_JSON_PATH, "error.missing", EXISTS, ""), app));
        assertTrue(evaluator.matches(c(RESPONSE_HEADERS, "", MATCHES_REGEX, "^http/1\\.1 403"), app), "regex is case-insensitive");
        assertTrue(evaluator.matches(c(RESPONSE_FULL_MESSAGE, "", MATCHES_REGEX, "json\\r\\n\\r\\n\\{"), app));
        assertTrue(evaluator.matches(c(BODY_LENGTH, "", NUMERIC_LT, "100"), app));
        assertTrue(evaluator.matches(c(REQUEST_METHOD, "", IN, "POST, PUT"), app));
        assertTrue(evaluator.matches(c(REQUEST_PATH, "", MATCHES_REGEX, "^/api/"), app));
        assertTrue(evaluator.matches(c(RESPONSE_BODY, "", CONTAINS, "retryable").negated().negated(), app));
        assertFalse(evaluator.matches(c(RESPONSE_BODY, "", CONTAINS, "retryable").negated(), app));
        assertFalse(evaluator.matches(c(RESPONSE_JSON_PATH, "a", EXISTS, ""), msg("HTTP/1.1 200 OK\n\nnot json")));
    }

    @Test
    void groupsAreAndWithinAndOrAcross() {
        RetryRule rule = RetryRule.of("CF or app json", RetryRule.Action.STOP, List.of(
                c(STATUS, "", EQUALS, "403"),
                c(RESPONSE_HEADER, "Server", CONTAINS, "cloudflare"),
                c(STATUS, "", EQUALS, "403").inGroup(2),
                c(RESPONSE_JSON_PATH, "error.code", EQUALS, "FORBIDDEN").inGroup(2)));
        assertTrue(evaluator.matches(rule, msg(CLOUDFLARE_BLOCK)), "group 1 matches");
        assertTrue(evaluator.matches(rule, msg(APP_403)), "group 2 matches");
        assertFalse(evaluator.matches(rule, msg(RATE_LIMITED)));
        assertEquals("(status = \"403\" AND header[Server] contains \"cloudflare\")\n"
                + "OR (status = \"403\" AND json[error.code] = \"FORBIDDEN\")", rule.expression());

        RetryRule empty = RetryRule.of("empty", RetryRule.Action.RETRY,
                List.of(new RetryCondition(false, 1, STATUS, "", EQUALS, "403", false, false)));
        assertFalse(evaluator.matches(empty, msg(APP_403)), "no enabled conditions never matches");
    }

    @Test
    void firstEnabledMatchingRuleWins() {
        RetryRule disabled = new RetryRule(null, "off", false, RetryRule.Action.RETRY,
                List.of(c(STATUS, "", EQUALS, "403")), null, null, null);
        RetryRule cf = RetryRule.of("cf", RetryRule.Action.STOP, List.of(c(RESPONSE_HEADER, "Server", CONTAINS, "cloudflare")));
        RetryRule any403 = RetryRule.of("403", RetryRule.Action.RETRY, List.of(c(STATUS, "", EQUALS, "403")));
        List<RetryRule> rules = List.of(disabled, cf, any403);
        assertEquals("cf", evaluator.firstMatch(rules, msg(CLOUDFLARE_BLOCK)).orElseThrow().name());
        assertEquals("403", evaluator.firstMatch(rules, msg(APP_403)).orElseThrow().name());
        assertTrue(evaluator.firstMatch(rules, msg(RATE_LIMITED)).isEmpty());
    }

    @Test
    void presetsMatchTheirSignaturesAndNotEachOther() {
        Map<String, RetryRule> presets = RetryPresets.all().stream()
                .collect(Collectors.toMap(RetryPresets.Preset::name, RetryPresets.Preset::create));
        Function<String, RetryRule> p = presets::get;
        assertTrue(evaluator.matches(p.apply("Cloudflare challenge (stop)"), msg(CLOUDFLARE_CHALLENGE)));
        assertFalse(evaluator.matches(p.apply("Cloudflare challenge (stop)"), msg(CLOUDFLARE_BLOCK)));
        assertTrue(evaluator.matches(p.apply("Cloudflare WAF block (stop)"), msg(CLOUDFLARE_BLOCK)));
        assertFalse(evaluator.matches(p.apply("Cloudflare WAF block (stop)"), msg(APP_403)));
        assertTrue(evaluator.matches(p.apply("Rate limited (429/503)"), msg(RATE_LIMITED)));
        assertTrue(evaluator.matches(p.apply("Retry-After header present"), msg(RATE_LIMITED)));
        assertTrue(evaluator.matches(p.apply("Akamai block (stop)"),
                msg("HTTP/1.1 403 Forbidden\n\n<H1>Access Denied</H1>Reference #18.4a2b3c4d.1700000000.1")));
        assertTrue(evaluator.matches(p.apply("Imperva / Incapsula block (stop)"),
                msg("HTTP/1.1 403 Forbidden\nX-Iinfo: 1-2-3\n\nblocked")));
        assertTrue(evaluator.matches(p.apply("F5 BIG-IP ASM block (stop)"),
                msg("HTTP/1.1 200 OK\n\nThe requested URL was rejected. Your support ID is: 123")));
        assertTrue(evaluator.matches(p.apply("AWS WAF / CloudFront block (stop)"),
                msg("HTTP/1.1 403 Forbidden\nX-Cache: Error from cloudfront\n\n<H1>403 ERROR</H1>")));
        assertEquals(10_000L, p.apply("Cloudflare rate limit (1015)").delayMillis());
        RetryPresets.Preset app403 = RetryPresets.all().get(RetryPresets.all().size() - 1);
        assertNotEquals(app403.create().id(), app403.create().id(), "fresh ids");
        assertEquals(RetryConfig.defaults(), RetryConfig.defaults(), "defaults are stable");
        for (RetryPresets.Preset preset : RetryPresets.all()) {
            RetryRule rule = preset.create();
            if (rule.action() == RetryRule.Action.STOP && !rule.name().startsWith("Application")) {
                assertNotEquals(RetryRule.Highlight.NONE, rule.highlight(), preset.name());
            }
        }
    }

    @Test
    void conditionAndProfileValidation() {
        assertThrows(IllegalArgumentException.class, () -> c(RESPONSE_HEADER, "", EQUALS, "x"), "header needs a name");
        assertThrows(IllegalArgumentException.class, () -> c(STATUS, "", EQUALS, " "), "value required");
        assertThrows(IllegalArgumentException.class, () -> c(RESPONSE_BODY, "", MATCHES_REGEX, "("));
        assertThrows(IllegalArgumentException.class, () -> c(STATUS, "", NUMERIC_GT, "abc"));
        assertThrows(IllegalArgumentException.class, () -> c(STATUS, "", EQUALS, "1").inGroup(RetryCondition.MAX_GROUP + 1));
        assertDoesNotThrow(() -> new RetryCondition(false, 1, STATUS, "", EQUALS, "", false, false), "disabled may be incomplete");
        assertThrows(IllegalArgumentException.class, () -> RetryRule.of("r", RetryRule.Action.RETRY, List.of())
                .withDelayMillis(RetryProfile.MAX_DELAY_MILLIS + 1));
        RetryProfile standard = RetryProfile.standard("x");
        assertThrows(IllegalArgumentException.class, () -> new RetryProfile(null, "x", true, List.of(), true, "", "",
                List.of(), List.of(), 3, 1_000, 30_000, 2.0, true, 60, true), "enabled profile needs a tool");
        assertThrows(IllegalArgumentException.class, () -> new RetryProfile(null, "x", true, List.of("EXTENSIONS"), true,
                "", "", List.of(), List.of(), 3, 1_000, 30_000, 2.0, true, 60, true));
        assertThrows(IllegalArgumentException.class, () -> new RetryProfile(null, "x", true, List.of("PROXY"), true,
                "(", "", List.of(), List.of(), 3, 1_000, 30_000, 2.0, true, 60, true));
        assertThrows(IllegalArgumentException.class, () -> new RetryConfig(true,
                List.of(standard, new RetryProfile(standard.id(), "dup", false, List.of(), true, "", "", List.of(),
                        List.of(), 3, 1_000, 30_000, 2.0, true, 60, true))), "duplicate ids");
        assertThrows(IllegalArgumentException.class, () -> new RetryConfig(true, List.of()), "enabled needs a profile");
    }

    @Test
    void firstCoveringProfileIsSelected() {
        RetryProfile api = new RetryProfile(null, "API host", true, List.of("PROXY", "INTRUDER"), false,
                "api\\.example\\.com", "^/v2/", List.of(), List.of(), 3, 1_000, 30_000, 2.0, true, 60, true);
        RetryProfile login = new RetryProfile(null, "Login POST", true, List.of("REPEATER"), true, ".*\\.example\\.com",
                "/login", List.of("post"), List.of(), 1, 0, 0, 1.0, false, 10, false);
        RetryProfile fallback = RetryProfile.standard("Default");
        RetryConfig config = new RetryConfig(true, List.of(api, login, fallback));

        assertEquals("API host", config.select(ctx("INTRUDER", "GET", "API.example.com", "/v2/users", false)).orElseThrow().name());
        assertEquals("Default", config.select(ctx("PROXY", "GET", "api.example.com", "/v1/users", true)).orElseThrow().name());
        assertEquals("Login POST", config.select(ctx("REPEATER", "POST", "www.example.com", "/login?next=/", true)).orElseThrow().name());
        assertEquals("Default", config.select(ctx("REPEATER", "GET", "www.example.com", "/login", true)).orElseThrow().name());
        assertTrue(config.select(ctx("SCANNER", "GET", "www.example.com", "/", true)).isEmpty());
        assertTrue(config.select(ctx("PROXY", "GET", "other.org", "/", false)).isEmpty(), "default is scope-only");
        assertTrue(new RetryConfig(false, List.of(fallback)).select(ctx("PROXY", "GET", "a", "/", true)).isEmpty());
    }

    @Test
    void configRoundTripsAndLegacyGlobalRetryIsMigrated() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RetryProfile profile = new RetryProfile(null, "CF host", true, List.of("PROXY", "SCANNER"), false, "cdn\\..*",
                "/api", List.of("GET"), RetryPresets.all().stream().map(RetryPresets.Preset::create).toList(),
                5, 250, 10_000, 1.5, false, 90, false);
        ProjectSettings settings = ProjectSettings.defaults()
                .withRetry(new RetryConfig(true, List.of(profile, RetryProfile.standard("Default"))));
        ProjectSettings restored = mapper.readValue(mapper.writeValueAsString(settings), ProjectSettings.class);
        assertEquals(settings, restored);
        assertFalse(mapper.writeValueAsString(settings).contains("globalRetry"), "legacy key is never written");

        String legacy = "{\"sendMode\":\"PROXY_PIPELINE\",\"proxyHost\":\"127.0.0.2\",\"proxyPort\":9090,"
                + "\"globalRetry\":{\"enabled\":true,\"burpScopeOnly\":false,\"hostPattern\":\"api\\\\..*\","
                + "\"toolTypes\":[\"PROXY\",\"SCANNER\"],\"statusCodes\":[429,503],\"responsePattern\":\"blocked\","
                + "\"methods\":[\"GET\"],\"maxRetries\":5,\"baseDelayMillis\":250,\"maxDelayMillis\":10000,"
                + "\"backoffMultiplier\":1.5,\"respectRetryAfter\":false,\"maxTotalWaitSeconds\":90,\"hostCooldown\":false}}";
        ProjectSettings migrated = mapper.readValue(legacy, ProjectSettings.class);
        assertEquals(HttpSendMode.PROXY_PIPELINE, migrated.sendMode());
        RetryConfig config = migrated.retry();
        assertTrue(config.enabled());
        RetryProfile only = config.profiles().get(0);
        assertEquals(List.of("PROXY", "SCANNER"), only.toolTypes());
        assertEquals("api\\..*", only.hostPattern());
        assertEquals(5, only.maxRetries());
        assertEquals(List.of("Status codes", "Response regex"), only.rules().stream().map(RetryRule::name).toList());
        assertTrue(evaluator.matches(only.rules().get(0), msg(RATE_LIMITED)));
        assertTrue(evaluator.matches(only.rules().get(1), msg("HTTP/1.1 403 X\n\nrequest blocked")));
        assertFalse(evaluator.matches(only.rules().get(1), msg("HTTP/1.1 403 X\n\nBLOCKED")), "legacy regex was case-sensitive");

        ProjectSettings absent = mapper.readValue("{\"proxyPort\":8080}", ProjectSettings.class);
        assertEquals(RetryConfig.defaults().enabled(), absent.retry().enabled());
        assertEquals(1, absent.retry().profiles().size());

        ProjectSettingsRepository repository = new ProjectSettingsRepository();
        repository.save(settings.withProxyPort(9000).withSendMode(HttpSendMode.STANDARD));
        assertEquals(settings.retry(), repository.load().retry(), "withers keep retry config");
    }

    @Test
    void parsesRawResponsesForTheTestView() {
        RetryMessage message = RetryMessage.parse("HTTP/2 429\r\nRetry-After: 7\r\n\r\nbody", "", "");
        assertEquals(429, message.status());
        assertEquals("7", message.responseHeader("retry-after").orElseThrow());
        assertEquals("body", message.body());
        assertThrows(IllegalArgumentException.class, () -> RetryMessage.parse("not http", "", ""));
    }

    @Test
    void retryAfterParsesSecondsAndHttpDates() {
        Instant now = Instant.parse("2026-11-27T10:00:00Z");
        assertEquals(7_000L, RetryAfterParser.delayMillis(" 7 ", now).getAsLong());
        assertEquals(30_000L, RetryAfterParser.delayMillis("Fri, 27 Nov 2026 10:00:30 GMT", now).getAsLong());
        assertEquals(0L, RetryAfterParser.delayMillis("Fri, 27 Nov 2026 09:00:00 GMT", now).getAsLong());
        assertTrue(RetryAfterParser.delayMillis("soon", now).isEmpty());
        assertTrue(RetryAfterParser.delayMillis("-5", now).isEmpty());
    }

    private static RetryRequestContext ctx(String tool, String method, String host, String path, boolean scope) {
        return new RetryRequestContext(tool, method, host, path, scope);
    }
}

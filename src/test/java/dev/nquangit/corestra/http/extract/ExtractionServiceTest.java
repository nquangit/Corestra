package dev.nquangit.corestra.http.extract;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ExtractionService} and its component extractors.
 */
class ExtractionServiceTest {

    private ExtractionService service;

    @BeforeEach
    void setUp() {
        service = new ExtractionService();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ExtractRule rule(
            ExtractRule.ExtractorType type,
            ExtractRule.SourceSide side,
            String selector) {
        return new ExtractRule("id-1", "test-rule", true, side, type, selector, "myVar", List.of());
    }

    private static ExtractRule ruleNoSelector(
            ExtractRule.ExtractorType type,
            ExtractRule.SourceSide side) {
        return new ExtractRule("id-1", "test-rule", true, side, type, null, "myVar", List.of());
    }

    private static HttpMessageSnapshot requestWith(List<String> headers, String body) {
        return new HttpMessageSnapshot("GET / HTTP/1.1", headers, body);
    }

    private static HttpMessageSnapshot responseWith(String statusLine, List<String> headers, String body) {
        return new HttpMessageSnapshot(statusLine, headers, body);
    }

    // -----------------------------------------------------------------------
    // HEADER extraction
    // -----------------------------------------------------------------------

    @Nested
    class HeaderExtraction {

        @Test
        void extractsHeaderValueFromRequest() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.HEADER, ExtractRule.SourceSide.REQUEST, "Authorization");
            HttpMessageSnapshot req = requestWith(
                    List.of("Host: example.com", "Authorization: Bearer token123"),
                    "");

            ExtractionResult result = service.extract(rule, req, null);

            assertTrue(result.isSuccess());
            assertEquals("Bearer token123", result.value());
            assertEquals("myVar", result.targetVariable());
        }

        @Test
        void extractsHeaderValueFromResponse() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.HEADER, ExtractRule.SourceSide.RESPONSE, "X-Auth-Token");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK",
                    List.of("Content-Type: application/json", "X-Auth-Token: secret"),
                    "{}");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("secret", result.value());
        }

        @Test
        void headerMatchingIsCaseInsensitive() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.HEADER, ExtractRule.SourceSide.REQUEST, "content-type");
            HttpMessageSnapshot req = requestWith(List.of("Content-Type: text/html"), "");

            ExtractionResult result = service.extract(rule, req, null);

            assertTrue(result.isSuccess());
            assertEquals("text/html", result.value());
        }

        @Test
        void returnsFailureWhenHeaderNotFound() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.HEADER, ExtractRule.SourceSide.REQUEST, "X-Missing");
            HttpMessageSnapshot req = requestWith(List.of("Host: example.com"), "");

            ExtractionResult result = service.extract(rule, req, null);

            assertFalse(result.isSuccess());
            assertNotNull(result.failureReason());
        }

        @Test
        void returnsFailureWhenRequestSnapshotIsNull() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.HEADER, ExtractRule.SourceSide.REQUEST, "Host");

            ExtractionResult result = service.extract(rule, null, null);

            assertFalse(result.isSuccess());
        }

        @Test
        void returnsFailureWhenResponseSnapshotIsNull() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.HEADER, ExtractRule.SourceSide.RESPONSE, "Set-Cookie");

            ExtractionResult result = service.extract(rule, null, null);

            assertFalse(result.isSuccess());
        }
    }

    // -----------------------------------------------------------------------
    // COOKIE extraction
    // -----------------------------------------------------------------------

    @Nested
    class CookieExtraction {

        @Test
        void extractsCookieFromRequestCookieHeader() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.COOKIE, ExtractRule.SourceSide.REQUEST, "session");
            HttpMessageSnapshot req = requestWith(
                    List.of("Cookie: session=abc123; csrf=xyz"),
                    "");

            ExtractionResult result = service.extract(rule, req, null);

            assertTrue(result.isSuccess());
            assertEquals("abc123", result.value());
        }

        @Test
        void extractsCookieFromResponseSetCookieHeader() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.COOKIE, ExtractRule.SourceSide.RESPONSE, "token");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK",
                    List.of("Set-Cookie: token=secret; HttpOnly"),
                    "");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("secret", result.value());
        }

        @Test
        void extractsCookieFromLaterResponseSetCookieHeader() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.COOKIE, ExtractRule.SourceSide.RESPONSE, "token");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK",
                    List.of(
                            "Set-Cookie: JSESSIONID=first-cookie; Path=/; HttpOnly",
                            "set-cookie: route=node-1; Path=/",
                            "Set-Cookie: token=expected-token; Path=/; Secure; HttpOnly"),
                    "");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("expected-token", result.value());
        }

        @Test
        void returnsFailureWhenCookieHeaderAbsent() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.COOKIE, ExtractRule.SourceSide.REQUEST, "session");
            HttpMessageSnapshot req = requestWith(List.of("Host: example.com"), "");

            ExtractionResult result = service.extract(rule, req, null);

            assertFalse(result.isSuccess());
        }

        @Test
        void setCookieAttributesAreNotTreatedAsCookies() {
            // "Path" and "SameSite" are Set-Cookie attributes, not cookies, and must not match.
            ExtractRule rulePath = rule(ExtractRule.ExtractorType.COOKIE, ExtractRule.SourceSide.RESPONSE, "Path");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK",
                    List.of("Set-Cookie: token=secret; Path=/; SameSite=Strict"),
                    "");

            ExtractionResult pathResult = service.extract(rulePath, null, resp);

            assertFalse(pathResult.isSuccess(),
                    "Cookie attribute 'Path' must not be extractable from Set-Cookie");
        }

        @Test
        void returnsFailureWhenNamedCookieNotFound() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.COOKIE, ExtractRule.SourceSide.REQUEST, "missing");
            HttpMessageSnapshot req = requestWith(List.of("Cookie: session=abc"), "");

            ExtractionResult result = service.extract(rule, req, null);

            assertFalse(result.isSuccess());
        }
    }

    // -----------------------------------------------------------------------
    // JSON_PATH extraction
    // -----------------------------------------------------------------------

    @Nested
    class JsonPathExtraction {

        @Test
        void extractsTopLevelField() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.JSON_PATH, ExtractRule.SourceSide.RESPONSE, "token");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK",
                    List.of("Content-Type: application/json"),
                    "{\"token\":\"jwt-value\"}");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("jwt-value", result.value());
        }

        @Test
        void extractsNestedField() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.JSON_PATH, ExtractRule.SourceSide.RESPONSE, "data.user.id");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK",
                    List.of(),
                    "{\"data\":{\"user\":{\"id\":\"42\"}}}");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("42", result.value());
        }

        @Test
        void returnsFailureOnInvalidJson() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.JSON_PATH, ExtractRule.SourceSide.RESPONSE, "token");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK", List.of(), "not-json");

            ExtractionResult result = service.extract(rule, null, resp);

            assertFalse(result.isSuccess());
            assertNotNull(result.failureReason());
        }

        @Test
        void returnsFailureWhenPathNotFound() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.JSON_PATH, ExtractRule.SourceSide.RESPONSE, "missing.key");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK", List.of(), "{\"token\":\"x\"}");

            ExtractionResult result = service.extract(rule, null, resp);

            assertFalse(result.isSuccess());
        }

        @Test
        void returnsFailureForNullJsonValue() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.JSON_PATH, ExtractRule.SourceSide.RESPONSE, "key");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK", List.of(), "{\"key\":null}");

            ExtractionResult result = service.extract(rule, null, resp);

            assertFalse(result.isSuccess());
        }
    }

    // -----------------------------------------------------------------------
    // REGEX extraction
    // -----------------------------------------------------------------------

    @Nested
    class RegexExtraction {

        @Test
        void extractsFullMatchWithGroupZero() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.REGEX, ExtractRule.SourceSide.RESPONSE, "\\d+");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK", List.of(), "user_id=42");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("42", result.value());
        }

        @Test
        void extractsCaptureGroupByIndex() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.REGEX, ExtractRule.SourceSide.RESPONSE, "token=(\\w+)|1");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK", List.of(), "token=abc123");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("abc123", result.value());
        }

        @Test
        void returnsFailureWhenPatternDoesNotMatch() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.REGEX, ExtractRule.SourceSide.RESPONSE, "xyz");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK", List.of(), "no match here");

            ExtractionResult result = service.extract(rule, null, resp);

            assertFalse(result.isSuccess());
        }

        @Test
        void returnsFailureForInvalidRegexPattern() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.REGEX, ExtractRule.SourceSide.RESPONSE, "[invalid");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK", List.of(), "some body");

            ExtractionResult result = service.extract(rule, null, resp);

            assertFalse(result.isSuccess());
            assertNotNull(result.failureReason());
        }

        @Test
        void returnsFailureWhenGroupIndexOutOfBounds() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.REGEX, ExtractRule.SourceSide.RESPONSE, "(\\w+)|5");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK", List.of(), "hello");

            ExtractionResult result = service.extract(rule, null, resp);

            assertFalse(result.isSuccess());
        }

        @Test
        void regexAlternationWithoutExplicitGroupIsNotMisreadAsLegacyGroupSuffix() {
            ExtractRule rule = rule(ExtractRule.ExtractorType.REGEX, ExtractRule.SourceSide.RESPONSE, "token=(\\w+)|abc");
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK", List.of(), "token=secret");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("token=secret", result.value());
        }

        @Test
        void extractsFromResponseHeadersWhenConfigured() {
            ExtractRule rule = new ExtractRule(
                    "id-1", "header-regex", true,
                    ExtractRule.SourceSide.RESPONSE,
                    ExtractRule.ExtractorType.REGEX,
                    "X-Trace-Id: ([a-z0-9-]+)",
                    "traceId",
                    List.of(),
                    ExtractRule.RegexSource.HEADERS,
                    1);
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK",
                    List.of("Content-Type: application/json", "X-Trace-Id: abc-123"),
                    "{\"ignored\":true}");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("abc-123", result.value());
        }

        @Test
        void fullMessageRegexCanMatchStatusHeadersAndBody() {
            ExtractRule rule = new ExtractRule(
                    "id-1", "full-regex", true,
                    ExtractRule.SourceSide.RESPONSE,
                    ExtractRule.ExtractorType.REGEX,
                    "(?s)HTTP/1\\.1 201.*X-Token: ([^\\r\\n]+).*created",
                    "token",
                    List.of("trim"),
                    ExtractRule.RegexSource.FULL_MESSAGE,
                    1);
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 201 Created",
                    List.of("X-Token: value-with-space "), "created");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("value-with-space", result.value());
        }

        @Test
        void requestFullMessageRegexCanReadRequestLineHeadersAndBody() {
            HttpMessageSnapshot req = new HttpMessageSnapshot("POST /orders/981?draft=1 HTTP/1.1",
                    List.of("Host: shop.test", "X-Csrf: c-77"), "note=hello");
            ExtractRule path = new ExtractRule("p", "order", true, ExtractRule.SourceSide.REQUEST,
                    ExtractRule.ExtractorType.REGEX, "/orders/(\\d+)", "orderId", List.of(),
                    ExtractRule.RegexSource.FULL_MESSAGE, 1);
            ExtractRule header = new ExtractRule("h", "csrf", true, ExtractRule.SourceSide.REQUEST,
                    ExtractRule.ExtractorType.REGEX, "X-Csrf: (\\S+)", "csrf", List.of(),
                    ExtractRule.RegexSource.FULL_MESSAGE, 1);
            ExtractRule across = new ExtractRule("a", "across", true, ExtractRule.SourceSide.REQUEST,
                    ExtractRule.ExtractorType.REGEX, "(?s)draft=(\\d).*note=(\\w+)", "note", List.of(),
                    ExtractRule.RegexSource.FULL_MESSAGE, 2);
            ExtractRule bodyOnly = new ExtractRule("b", "body only", true, ExtractRule.SourceSide.REQUEST,
                    ExtractRule.ExtractorType.REGEX, "/orders/(\\d+)", "missing", List.of(),
                    ExtractRule.RegexSource.BODY, 1);

            assertEquals("981", service.extract(path, req, null).value());
            assertEquals("c-77", service.extract(header, req, null).value());
            assertEquals("hello", service.extract(across, req, null).value());
            assertFalse(service.extract(bodyOnly, req, null).isSuccess());
        }

        @Test
        void explicitGroupKeepsRegexAlternationIntact() {
            ExtractRule rule = new ExtractRule(
                    "id-1", "alternation", true,
                    ExtractRule.SourceSide.RESPONSE,
                    ExtractRule.ExtractorType.REGEX,
                    "token=(alpha|beta)",
                    "token",
                    List.of(),
                    ExtractRule.RegexSource.BODY,
                    1);

            ExtractionResult result = service.extract(rule, null,
                    responseWith("HTTP/1.1 200 OK", List.of(), "token=beta"));

            assertTrue(result.isSuccess());
            assertEquals("beta", result.value());
        }

        @Test
        void appliesTransformsAfterExtractionInOrder() {
            ExtractRule rule = new ExtractRule(
                    "id-1", "transform", true,
                    ExtractRule.SourceSide.RESPONSE,
                    ExtractRule.ExtractorType.REGEX,
                    "token=([^;]+)",
                    "token",
                    List.of("trim", "substring:7", "uppercase"),
                    ExtractRule.RegexSource.BODY,
                    1);

            ExtractionResult result = service.extract(rule, null,
                    responseWith("HTTP/1.1 200 OK", List.of(), "token=  bearer abc  ;"));

            assertTrue(result.isSuccess());
            assertEquals("ABC", result.value());
        }

        @Test
        void invalidTransformReturnsActionableFailure() {
            ExtractRule rule = new ExtractRule(
                    "id-1", "transform", true,
                    ExtractRule.SourceSide.RESPONSE,
                    ExtractRule.ExtractorType.BODY_TEXT,
                    null,
                    "value",
                    List.of("substring:99"),
                    ExtractRule.RegexSource.BODY,
                    0);

            ExtractionResult result = service.extract(rule, null,
                    responseWith("HTTP/1.1 200 OK", List.of(), "short"));

            assertFalse(result.isSuccess());
            assertTrue(result.failureReason().contains("substring:99"));
        }
    }

    // -----------------------------------------------------------------------
    // BODY_TEXT extraction
    // -----------------------------------------------------------------------

    @Nested
    class BodyTextExtraction {

        @Test
        void returnsEntireBody() {
            ExtractRule rule = ruleNoSelector(ExtractRule.ExtractorType.BODY_TEXT, ExtractRule.SourceSide.RESPONSE);
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK", List.of(), "Hello, World!");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("Hello, World!", result.value());
        }

        @Test
        void returnsEmptyStringForEmptyBody() {
            ExtractRule rule = ruleNoSelector(ExtractRule.ExtractorType.BODY_TEXT, ExtractRule.SourceSide.RESPONSE);
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 204 No Content", List.of(), "");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("", result.value());
        }
    }

    // -----------------------------------------------------------------------
    // STATUS_CODE extraction
    // -----------------------------------------------------------------------

    @Nested
    class StatusCodeExtraction {

        @Test
        void extractsStatusCodeFromResponseStatusLine() {
            ExtractRule rule = ruleNoSelector(ExtractRule.ExtractorType.STATUS_CODE, ExtractRule.SourceSide.RESPONSE);
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 200 OK", List.of(), "");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("200", result.value());
        }

        @Test
        void extractsNonOkStatusCode() {
            ExtractRule rule = ruleNoSelector(ExtractRule.ExtractorType.STATUS_CODE, ExtractRule.SourceSide.RESPONSE);
            HttpMessageSnapshot resp = responseWith("HTTP/1.1 401 Unauthorized", List.of(), "");

            ExtractionResult result = service.extract(rule, null, resp);

            assertTrue(result.isSuccess());
            assertEquals("401", result.value());
        }

        @Test
        void returnsFailureWhenResponseIsNull() {
            ExtractRule rule = ruleNoSelector(ExtractRule.ExtractorType.STATUS_CODE, ExtractRule.SourceSide.RESPONSE);

            ExtractionResult result = service.extract(rule, null, null);

            assertFalse(result.isSuccess());
        }

        @Test
        void returnsFailureWhenStatusLineIsBlank() {
            ExtractRule rule = ruleNoSelector(ExtractRule.ExtractorType.STATUS_CODE, ExtractRule.SourceSide.RESPONSE);
            HttpMessageSnapshot resp = responseWith("", List.of(), "");

            ExtractionResult result = service.extract(rule, null, resp);

            assertFalse(result.isSuccess());
        }
    }

    // -----------------------------------------------------------------------
    // ExtractionResult factory tests
    // -----------------------------------------------------------------------

    @Nested
    class ExtractionResultFactoryTests {

        @Test
        void successResultIsSuccess() {
            ExtractionResult result = ExtractionResult.success("var", "value");
            assertTrue(result.isSuccess());
            assertEquals("var", result.targetVariable());
            assertEquals("value", result.value());
            assertNull(result.failureReason());
        }

        @Test
        void failureResultIsNotSuccess() {
            ExtractionResult result = ExtractionResult.failure("var", "something went wrong");
            assertFalse(result.isSuccess());
            assertEquals("var", result.targetVariable());
            assertNull(result.value());
            assertEquals("something went wrong", result.failureReason());
        }
    }
}

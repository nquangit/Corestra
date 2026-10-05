package dev.nquangit.corestra.http.inject;

import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.flow.rule.InjectRule.Target;
import dev.nquangit.corestra.flow.rule.InjectRule.ValueSourceType;
import dev.nquangit.corestra.flow.rule.InjectRule.WriteStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InjectionService} and its component injectors.
 */
class InjectionServiceTest {

    private InjectionService service;

    @BeforeEach
    void setUp() {
        service = new InjectionService();
    }

    @Test
    void injectsBearerTemplateAndAppliesTransformsToCompleteValue() {
        InjectRule rule = new InjectRule("bearer", "Bearer", true, Target.REQUEST_HEADER, "Authorization",
                ValueSourceType.TEMPLATE, "", "Bearer {{auth.refreshToken}}", WriteStrategy.OVERWRITE,
                List.of("suffix:-done"));
        RequestMutationResult result = service.apply(List.of(rule), Map.of("auth.refreshToken", "new-token"),
                request("POST /refresh HTTP/1.1", List.of("Authorization: Bearer old-token"), ""));
        assertEquals(List.of("Authorization: Bearer new-token-done"), result.headers());
    }

    @Test
    void missingTemplateVariablePreservesRequestInsteadOfInjectingPartialValue() {
        InjectRule rule = new InjectRule("bearer", "Bearer", true, Target.REQUEST_HEADER, "Authorization",
                ValueSourceType.TEMPLATE, "", "Bearer {{auth.refreshToken}}/{{missing}}", WriteStrategy.OVERWRITE);
        RequestMutationResult result = service.apply(List.of(rule), Map.of("auth.refreshToken", "secret"),
                request("POST /refresh HTTP/1.1", List.of("Authorization: Bearer old-token"), ""));
        assertEquals(List.of("Authorization: Bearer old-token"), result.headers());
        assertTrue(result.diagnostics().stream().anyMatch(message -> message.contains("missing")));
        assertTrue(result.diagnostics().stream().noneMatch(message -> message.contains("secret")));
    }

    @Test
    void staticSourceKeepsPlaceholderTextLiteralForCompatibility() {
        InjectRule rule = staticRule("static", Target.REQUEST_HEADER, "X-Value", "{{token}}", WriteStrategy.OVERWRITE);
        RequestMutationResult result = service.apply(List.of(rule), Map.of("token", "resolved"),
                request("GET / HTTP/1.1", List.of(), ""));
        assertEquals(List.of("X-Value: {{token}}"), result.headers());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static InjectRule rule(
            String name,
            Target target,
            String selector,
            ValueSourceType sourceType,
            String sourceKey,
            String staticValue,
            WriteStrategy strategy) {
        return new InjectRule("id-" + name, name, true, target, selector, sourceType, sourceKey, staticValue, strategy);
    }

    private static InjectRule staticRule(String name, Target target, String selector, String value, WriteStrategy strategy) {
        return rule(name, target, selector, ValueSourceType.STATIC, "", value, strategy);
    }

    private static InjectRule variableRule(String name, Target target, String selector, String varKey, WriteStrategy strategy) {
        return rule(name, target, selector, ValueSourceType.VARIABLE, varKey, "", strategy);
    }

    private static InjectRule disabledRule(String name, Target target, String selector) {
        return new InjectRule("id-" + name, name, false, target, selector, ValueSourceType.STATIC, "", "ignored", WriteStrategy.OVERWRITE);
    }

    private static InjectionService.MutableHttpRequest request(String requestLine, List<String> headers, String body) {
        return new InjectionService.MutableHttpRequest(requestLine, headers, body);
    }

    // -----------------------------------------------------------------------
    // REQUEST_HEADER injection
    // -----------------------------------------------------------------------

    @Nested
    class HeaderInjection {

        @Test
        void injectsNewHeader() {
            InjectRule r = staticRule("r1", Target.REQUEST_HEADER, "X-Custom", "hello", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1", List.of("Host: example.com"), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.headers().stream().anyMatch(h -> h.equalsIgnoreCase("X-Custom: hello")));
        }

        @Test
        void overwritesExistingHeader() {
            InjectRule r = staticRule("r1", Target.REQUEST_HEADER, "Authorization", "Bearer new", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1",
                    List.of("Host: example.com", "Authorization: Bearer old"), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            long count = result.headers().stream()
                    .filter(h -> h.toLowerCase().startsWith("authorization:"))
                    .count();
            assertEquals(1, count, "Should have exactly one Authorization header after OVERWRITE");
            assertTrue(result.headers().stream().anyMatch(h -> h.contains("Bearer new")));
        }

        @Test
        void appendAddsHeaderEvenIfPresent() {
            InjectRule r = staticRule("r1", Target.REQUEST_HEADER, "X-Token", "second", WriteStrategy.APPEND);
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1",
                    List.of("X-Token: first"), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            long count = result.headers().stream()
                    .filter(h -> h.toLowerCase().startsWith("x-token:"))
                    .count();
            assertEquals(2, count, "APPEND should add a second X-Token header");
        }

        @Test
        void skipsDisabledRule() {
            InjectRule disabled = disabledRule("r1", Target.REQUEST_HEADER, "X-Never");
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1", List.of("Host: example.com"), "");

            RequestMutationResult result = service.apply(List.of(disabled), Map.of(), req);

            assertTrue(result.headers().stream().noneMatch(h -> h.toLowerCase().startsWith("x-never:")));
        }

        @Test
        void resolvesValueFromContextVariable() {
            InjectRule r = variableRule("r1", Target.REQUEST_HEADER, "X-Token", "tokenVar", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1", List.of(), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of("tokenVar", "ctx-value"), req);

            assertTrue(result.headers().stream().anyMatch(h -> h.contains("ctx-value")));
        }

        @Test
        void transformsVariableBeforeInjection() {
            InjectRule r = new InjectRule(
                    "id-r1", "r1", true,
                    Target.REQUEST_HEADER, "Authorization",
                    ValueSourceType.VARIABLE, "tokenVar", "",
                    WriteStrategy.OVERWRITE,
                    List.of("trim", "stripPrefix:Bearer", "trim", "prefix:Token "));
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1", List.of(), "");

            RequestMutationResult result = service.apply(
                    List.of(r), Map.of("tokenVar", "  Bearer abc123  "), req);

            assertTrue(result.headers().contains("Authorization: Token abc123"));
        }

        @Test
        void invalidTransformSkipsInjectionAndReportsDiagnostic() {
            InjectRule r = new InjectRule(
                    "id-r1", "r1", true,
                    Target.REQUEST_HEADER, "X-Value",
                    ValueSourceType.STATIC, "", "short",
                    WriteStrategy.OVERWRITE,
                    List.of("substring:50"));

            RequestMutationResult result = service.apply(
                    List.of(r), Map.of(), request("GET / HTTP/1.1", List.of(), ""));

            assertTrue(result.headers().isEmpty());
            assertTrue(result.diagnostics().stream().anyMatch(message ->
                    message.contains("substring:50") && message.contains("transform failed")));
        }

        @Test
        void skipsRuleWhenContextVariableMissing() {
            InjectRule r = variableRule("r1", Target.REQUEST_HEADER, "X-Token", "missingVar", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1", List.of(), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.headers().stream().noneMatch(h -> h.toLowerCase().startsWith("x-token:")));
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("missingVar")));
        }

        @Test
        void skipIfPresentLeavesHeaderUnchangedWhenAlreadyPresent() {
            InjectRule r = staticRule("r1", Target.REQUEST_HEADER, "Authorization", "Bearer new", WriteStrategy.SKIP_IF_PRESENT);
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1",
                    List.of("Host: example.com", "Authorization: Bearer old"), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            // The existing Authorization header must not be replaced
            assertTrue(result.headers().stream().anyMatch(h -> h.contains("Bearer old")),
                    "SKIP_IF_PRESENT must not overwrite an existing header");
            assertFalse(result.headers().stream().anyMatch(h -> h.contains("Bearer new")),
                    "SKIP_IF_PRESENT must not inject a new value when header is already present");
        }

        @Test
        void skipIfPresentInjectsHeaderWhenAbsent() {
            InjectRule r = staticRule("r1", Target.REQUEST_HEADER, "X-New", "value", WriteStrategy.SKIP_IF_PRESENT);
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1", List.of("Host: example.com"), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.headers().stream().anyMatch(h -> h.contains("X-New: value")),
                    "SKIP_IF_PRESENT must inject the header when it is absent");
        }
    }

    // -----------------------------------------------------------------------
    // REQUEST_COOKIE injection
    // -----------------------------------------------------------------------

    @Nested
    class CookieInjection {

        @Test
        void injectsNewCookieWhenNoCookieHeaderExists() {
            InjectRule r = staticRule("r1", Target.REQUEST_COOKIE, "session", "abc123", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1", List.of("Host: example.com"), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.headers().stream().anyMatch(h -> h.contains("session=abc123")));
        }

        @Test
        void overwritesExistingCookie() {
            InjectRule r = staticRule("r1", Target.REQUEST_COOKIE, "session", "new-val", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1",
                    List.of("Cookie: session=old-val; csrf=token"), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.headers().stream().anyMatch(h -> h.contains("session=new-val")));
            assertFalse(result.headers().stream().anyMatch(h -> h.contains("session=old-val")));
        }

        @Test
        void appendAddsDuplicateCookie() {
            InjectRule r = staticRule("r1", Target.REQUEST_COOKIE, "session", "extra", WriteStrategy.APPEND);
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1",
                    List.of("Cookie: session=original"), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            String cookieLine = result.headers().stream()
                    .filter(h -> h.toLowerCase().startsWith("cookie:"))
                    .findFirst().orElse("");
            // Strip the "Cookie: " prefix before splitting on ';' to get pure cookie pairs
            String cookieValue = cookieLine.contains(":") ? cookieLine.substring(cookieLine.indexOf(':') + 1).strip() : cookieLine;
            long sessionCount = java.util.Arrays.stream(cookieValue.split(";"))
                    .map(String::strip)
                    .filter(p -> p.startsWith("session="))
                    .count();
            assertEquals(2, sessionCount, "APPEND should result in two session cookies");
        }
    }

    // -----------------------------------------------------------------------
    // REQUEST_QUERY_PARAMETER injection
    // -----------------------------------------------------------------------

    @Nested
    class QueryParamInjection {

        @Test
        void literalPlusInSelectedNameDoesNotMatchSpaceEncodedAsPlus() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "a+b", "X", WriteStrategy.OVERWRITE);
            var result = service.apply(List.of(r), Map.of(), request(
                    "GET /api?a+b=space&a%2Bb=literal&path=/login.php HTTP/1.1", List.of(), ""));
            assertEquals("GET /api?a+b=space&a%2Bb=X&path=/login.php HTTP/1.1", result.requestLine());
        }

        @Test
        void consecutiveQueryRulesPreserveUnrelatedValuesAndEncodeUnicodeOnlyInInjectedValues() {
            InjectRule first = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "one", "a/b", WriteStrategy.OVERWRITE);
            InjectRule second = staticRule("r2", Target.REQUEST_QUERY_PARAMETER, "two", "Việt", WriteStrategy.OVERWRITE);
            var result = service.apply(List.of(first, second), Map.of(), request(
                    "GET /api?one=old&path=/login.php&two=old&escape=%2f%20 HTTP/1.1", List.of(), ""));
            assertEquals("GET /api?one=a%2Fb&path=/login.php&two=Vi%E1%BB%87t&escape=%2f%20 HTTP/1.1",
                    result.requestLine());
        }

        @Test
        void overwritingOneParameterPreservesOtherRawValues() {
            InjectRule r = variableRule("r1", Target.REQUEST_QUERY_PARAMETER, "param1", "X", WriteStrategy.OVERWRITE);
            var result = service.apply(List.of(r), Map.of("X", "new/value &+"),
                    request("GET /api?param1=replace_me&path=/login.php HTTP/1.1", List.of(), ""));
            assertEquals("GET /api?param1=new%2Fvalue+%26%2B&path=/login.php HTTP/1.1", result.requestLine());
        }

        @Test
        void preservesUntouchedEscapesDuplicatesFlagsAndEmptyPairs() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "param1", "X", WriteStrategy.OVERWRITE);
            String raw = "path=/login.php&esc=%2f%2F%20&plus=a+b&literal=a/b:c?d=e&dup=1&dup=2&flag&empty=&bad=%ZZ&&";
            var result = service.apply(List.of(r), Map.of(),
                    request("POST /api?param1=replace_me&" + raw + " HTTP/2", List.of(), ""));
            assertEquals("POST /api?param1=X&" + raw + " HTTP/2", result.requestLine());
        }

        @Test
        void matchesEncodedNamesAndOverwritesEveryOccurrenceWithoutCollapsingOtherDuplicates() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "param1", "X", WriteStrategy.OVERWRITE);
            var result = service.apply(List.of(r), Map.of(), request(
                    "GET /api?param%31=old&dup=1&param1=older&dup=2&path=/login.php HTTP/1.1", List.of(), ""));
            assertEquals("GET /api?param%31=X&dup=1&param1=X&dup=2&path=/login.php HTTP/1.1", result.requestLine());
        }

        @Test
        void appendKeepsExistingOccurrencesAndOnlyEncodesNewPair() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "tag", "new/value", WriteStrategy.APPEND);
            var result = service.apply(List.of(r), Map.of(),
                    request("GET /api?tag=old&path=/login.php HTTP/1.1", List.of(), ""));
            assertEquals("GET /api?tag=old&path=/login.php&tag=new%2Fvalue HTTP/1.1", result.requestLine());
        }

        @Test
        void skipMatchesEncodedNameWithoutNormalizingAnything() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "param1", "X", WriteStrategy.SKIP_IF_PRESENT);
            String original = "GET /api?param%31&path=/login.php&invalid%ZZ=bad% HTTP/1.1";
            assertEquals(original, service.apply(List.of(r), Map.of(), request(original, List.of(), "")).requestLine());
        }

        @Test
        void addingMissingParameterPreservesMalformedAndEmptyQuerySegments() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "token name", "a&b=+", WriteStrategy.OVERWRITE);
            var result = service.apply(List.of(r), Map.of(), request(
                    "GET https://example.test/api?path=/login.php&broken%ZZ=raw%&flag&& HTTP/1.1", List.of(), ""));
            assertEquals("GET https://example.test/api?path=/login.php&broken%ZZ=raw%&flag&&&token+name=a%26b%3D%2B HTTP/1.1",
                    result.requestLine());
        }

        @Test
        void injectsQueryParamIntoRequestLine() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "token", "abc", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("GET /search HTTP/1.1", List.of(), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("token")));
        }

        @Test
        void overwritesExistingQueryParam() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "page", "2", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("GET /list?page=1&size=10 HTTP/1.1", List.of(), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("page")));
        }

        @Test
        void appendAddsQueryParamAlongsideExisting() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "tag", "new", WriteStrategy.APPEND);
            InjectionService.MutableHttpRequest req = request("GET /list?tag=old HTTP/1.1", List.of(), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("tag")));
        }

        @Test
        void mutatedRequestLineIsReflectedInResult() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "token", "abc123", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("GET /search HTTP/1.1", List.of(), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.requestLine().contains("token=abc123"),
                    "RequestMutationResult.requestLine() must contain the injected query parameter; got: " + result.requestLine());
        }

        @Test
        void overwriteQueryParamUpdatesRequestLine() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "page", "2", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("GET /list?page=1&size=10 HTTP/1.1", List.of(), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.requestLine().contains("page=2"),
                    "Overwritten query param must appear in result requestLine; got: " + result.requestLine());
            assertFalse(result.requestLine().contains("page=1"),
                    "Old query param value must not remain in result requestLine; got: " + result.requestLine());
        }

        @Test
        void skipIfPresentQueryParamLeavesRequestLineUnchanged() {
            InjectRule r = staticRule("r1", Target.REQUEST_QUERY_PARAMETER, "page", "99", WriteStrategy.SKIP_IF_PRESENT);
            InjectionService.MutableHttpRequest req = request("GET /list?page=1 HTTP/1.1", List.of(), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertEquals("GET /list?page=1 HTTP/1.1", result.requestLine(),
                    "SKIP_IF_PRESENT must not modify the request line when query param already exists");
        }
    }

    // -----------------------------------------------------------------------
    // REQUEST_FORM_FIELD injection
    // -----------------------------------------------------------------------

    @Nested
    class FormFieldInjection {

        @Test
        void injectsFormFieldIntoEmptyBody() {
            InjectRule r = staticRule("r1", Target.REQUEST_FORM_FIELD, "username", "alice", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("POST /login HTTP/1.1", List.of(), "");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.body().contains("username=alice"));
        }

        @Test
        void overwritesExistingFormField() {
            InjectRule r = staticRule("r1", Target.REQUEST_FORM_FIELD, "username", "bob", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("POST /login HTTP/1.1", List.of(),
                    "username=alice&password=secret");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.body().contains("username=bob"));
            assertFalse(result.body().contains("username=alice"));
        }

        @Test
        void appendAddsFormFieldAlongsideExisting() {
            InjectRule r = staticRule("r1", Target.REQUEST_FORM_FIELD, "tag", "extra", WriteStrategy.APPEND);
            InjectionService.MutableHttpRequest req = request("POST /submit HTTP/1.1", List.of(), "tag=original");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.body().contains("tag=extra"));
        }

        @Test
        void addsNewFormFieldWhenNotPresent() {
            InjectRule r = staticRule("r1", Target.REQUEST_FORM_FIELD, "csrf", "token123", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("POST /login HTTP/1.1", List.of(), "username=alice");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.body().contains("csrf=token123"));
        }
    }

    // -----------------------------------------------------------------------
    // REQUEST_JSON_PATH injection
    // -----------------------------------------------------------------------

    @Nested
    class JsonPathInjection {

        @Test
        void injectsTopLevelJsonField() {
            InjectRule r = staticRule("r1", Target.REQUEST_JSON_PATH, "token", "jwt-value", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("POST /api HTTP/1.1", List.of(), "{\"user\":\"alice\"}");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.body().contains("\"token\":\"jwt-value\""));
        }

        @Test
        void injectsNestedJsonField() {
            InjectRule r = staticRule("r1", Target.REQUEST_JSON_PATH, "data.user.id", "42", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("POST /api HTTP/1.1", List.of(), "{\"data\":{\"user\":{}}}");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.body().contains("\"id\":\"42\""));
        }

        @Test
        void skipJsonInjectionWhenBodyIsInvalidJson() {
            InjectRule r = staticRule("r1", Target.REQUEST_JSON_PATH, "token", "val", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("POST /api HTTP/1.1", List.of(), "not-json");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertEquals("not-json", result.body(), "Body should be unchanged when JSON is invalid");
            assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("Invalid JSON")));
        }

        @Test
        void appendStrategySkipsWhenFieldAlreadyPresent() {
            InjectRule r = staticRule("r1", Target.REQUEST_JSON_PATH, "token", "new", WriteStrategy.APPEND);
            InjectionService.MutableHttpRequest req = request("POST /api HTTP/1.1", List.of(), "{\"token\":\"existing\"}");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.body().contains("\"token\":\"existing\""),
                    "APPEND should not overwrite existing JSON value");
        }
    }

    // -----------------------------------------------------------------------
    // REQUEST_REGEX_REPLACEMENT injection
    // -----------------------------------------------------------------------

    @Nested
    class RegexReplacementInjection {

        @Test
        void replacesPatternInBody() {
            InjectRule r = staticRule("r1", Target.REQUEST_REGEX_REPLACEMENT, "token=\\w+", "token=replaced", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("POST /api HTTP/1.1", List.of(), "token=old-value&other=x");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.body().contains("token=replaced"));
        }

        @Test
        void bodyUnchangedWhenRegexHasNoMatches() {
            InjectRule r = staticRule("r1", Target.REQUEST_REGEX_REPLACEMENT, "NOTFOUND", "replacement", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("POST /api HTTP/1.1", List.of(), "some body content");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertEquals("some body content", result.body());
        }

        @Test
        void diagnosticAddedForInvalidRegex() {
            InjectRule r = staticRule("r1", Target.REQUEST_REGEX_REPLACEMENT, "[invalid", "x", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("POST /api HTTP/1.1", List.of(), "body");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("Invalid regex")));
        }
    }

    // -----------------------------------------------------------------------
    // REQUEST_RAW_BODY_APPEND / REQUEST_RAW_BODY_REPLACE
    // -----------------------------------------------------------------------

    @Nested
    class RawBodyInjection {

        @Test
        void appendsToBody() {
            InjectRule r = new InjectRule("id-1", "r1", true,
                    Target.REQUEST_RAW_BODY_APPEND, "",
                    ValueSourceType.STATIC, "", "&extra=1", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("POST /api HTTP/1.1", List.of(), "base=val");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertEquals("base=val&extra=1", result.body());
        }

        @Test
        void replacesBodyCompletely() {
            InjectRule r = new InjectRule("id-1", "r1", true,
                    Target.REQUEST_RAW_BODY_REPLACE, "",
                    ValueSourceType.STATIC, "", "brand-new-body", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("POST /api HTTP/1.1", List.of(), "old-body");

            RequestMutationResult result = service.apply(List.of(r), Map.of(), req);

            assertEquals("brand-new-body", result.body());
        }
    }

    // -----------------------------------------------------------------------
    // RequestMutationResult factory tests
    // -----------------------------------------------------------------------

    @Nested
    class RequestMutationResultTests {

        @Test
        void factoryCreatesImmutableResult() {
            RequestMutationResult r = RequestMutationResult.of(
                    "GET / HTTP/1.1", List.of("Host: example.com"), "body", List.of("diag"));

            assertEquals("GET / HTTP/1.1", r.requestLine());
            assertEquals(List.of("Host: example.com"), r.headers());
            assertEquals("body", r.body());
            assertEquals(List.of("diag"), r.diagnostics());
            assertThrows(UnsupportedOperationException.class, () -> r.headers().add("extra"));
        }

        @Test
        void factoryRejectsNullRequestLine() {
            assertThrows(NullPointerException.class,
                    () -> RequestMutationResult.of(null, List.of(), "body", List.of()));
        }

        @Test
        void factoryRejectsNullHeaders() {
            assertThrows(NullPointerException.class,
                    () -> RequestMutationResult.of("GET / HTTP/1.1", null, "body", List.of()));
        }

        @Test
        void factoryRejectsNullBody() {
            assertThrows(NullPointerException.class,
                    () -> RequestMutationResult.of("GET / HTTP/1.1", List.of(), null, List.of()));
        }

        @Test
        void factoryRejectsNullDiagnostics() {
            assertThrows(NullPointerException.class,
                    () -> RequestMutationResult.of("GET / HTTP/1.1", List.of(), "body", null));
        }
    }

    // -----------------------------------------------------------------------
    // Multiple rules applied in order
    // -----------------------------------------------------------------------

    @Nested
    class MultipleRules {

        @Test
        void appliesMultipleRulesSequentially() {
            InjectRule r1 = staticRule("header-rule", Target.REQUEST_HEADER, "X-Step", "one", WriteStrategy.OVERWRITE);
            InjectRule r2 = staticRule("body-rule", Target.REQUEST_RAW_BODY_REPLACE, "", "new-body", WriteStrategy.OVERWRITE);
            InjectionService.MutableHttpRequest req = request("GET / HTTP/1.1", List.of(), "old-body");

            RequestMutationResult result = service.apply(List.of(r1, r2), Map.of(), req);

            assertTrue(result.headers().stream().anyMatch(h -> h.contains("X-Step: one")));
            assertEquals("new-body", result.body());
            assertEquals(2, result.diagnostics().size());
        }
    }
}

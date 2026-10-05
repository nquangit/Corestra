package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionMode;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthProviderRequestApplierTest {
    private final AuthProviderRequestApplier applier = new AuthProviderRequestApplier(
            new TemplateRenderer(new VariableResolver(key -> Optional.empty())));

    @Test
    void queryOverridePreservesEveryUntouchedRawParameter() {
        String raw = "path=/login.php&escaped=%2f%20&dup=1&dup=2&flag&broken=%XX&&";
        StoredRequestTemplate request = request(List.of("X-Test: unchanged"), "/accounts?access_token=old&" + raw, "body");
        AuthProvider provider = provider(rule(AuthInjectionLocation.QUERY, "access_token",
                "{{auth.accessToken}}", AuthInjectionMode.OVERRIDE));
        var result = applier.apply(request, provider, store(), RenderMode.STRICT);
        assertEquals("/accounts?access_token=token-abc&" + raw, result.request().pathAndQuery());
        assertEquals(request.headerLines(), result.request().headerLines());
        assertEquals(request.body(), result.request().body());
    }

    @Test
    void queryAppendAndSkipPreserveExistingEncodedOccurrences() {
        StoredRequestTemplate request = request(List.of(), "/accounts?access%5ftoken=old&path=/login.php", "");
        for (AuthInjectionMode mode : List.of(AuthInjectionMode.APPEND, AuthInjectionMode.SKIP_IF_PRESENT)) {
            var result = applier.apply(request, provider(rule(AuthInjectionLocation.QUERY, "access_token",
                    "{{auth.accessToken}}", mode)), store(), RenderMode.STRICT);
            assertEquals(request.pathAndQuery() + (mode == AuthInjectionMode.APPEND ? "&access_token=token-abc" : ""),
                    result.request().pathAndQuery());
        }
    }

    @Test
    void appliesHeaderOverrideWithRenderedValue() {
        StoredRequestTemplate request = request(List.of("Authorization: Bearer old"), "/accounts", "");
        AuthProvider provider = provider(rule(
                AuthInjectionLocation.HEADER,
                "Authorization",
                "Bearer {{auth.accessToken}}",
                AuthInjectionMode.OVERRIDE));

        AuthProviderApplicationResult result = applier.apply(request, provider, store(), RenderMode.STRICT);

        assertEquals(List.of("Authorization: Bearer token-abc"), result.request().headerLines());
    }

    @Test
    void skipsExistingHeaderWhenConfigured() {
        StoredRequestTemplate request = request(List.of("X-Api-Key: existing"), "/accounts", "");
        AuthProvider provider = provider(rule(
                AuthInjectionLocation.HEADER,
                "X-Api-Key",
                "new",
                AuthInjectionMode.SKIP_IF_PRESENT));

        AuthProviderApplicationResult result = applier.apply(request, provider, store(), RenderMode.STRICT);

        assertEquals(List.of("X-Api-Key: existing"), result.request().headerLines());
    }

    @Test
    void appliesCookieIntoNormalizedCookieHeader() {
        StoredRequestTemplate request = request(List.of("Accept: application/json", "Cookie: theme=light"), "/accounts", "");
        AuthProvider provider = provider(rule(
                AuthInjectionLocation.COOKIE,
                "session",
                "{{auth.sessionId}}",
                AuthInjectionMode.OVERRIDE));

        AuthProviderApplicationResult result = applier.apply(request, provider, store(), RenderMode.STRICT);

        assertEquals(List.of("Accept: application/json", "Cookie: theme=light; session=sess-123"),
                result.request().headerLines());
    }

    @Test
    void injectsQueryParameter() {
        StoredRequestTemplate request = request(List.of(), "/accounts?existing=1", "");
        AuthProvider provider = provider(rule(
                AuthInjectionLocation.QUERY,
                "access_token",
                "{{auth.accessToken}}",
                AuthInjectionMode.OVERRIDE));

        AuthProviderApplicationResult result = applier.apply(request, provider, store(), RenderMode.STRICT);

        assertEquals("/accounts?existing=1&access_token=token-abc", result.request().pathAndQuery());
    }

    @Test
    void unsupportedBodyJsonRulePreservesRequestAndWarns() {
        StoredRequestTemplate request = request(List.of(), "/accounts", "{}");
        AuthProvider provider = provider(rule(
                AuthInjectionLocation.BODY_JSON,
                "$.token",
                "{{auth.accessToken}}",
                AuthInjectionMode.OVERRIDE));

        AuthProviderApplicationResult result = applier.apply(request, provider, store(), RenderMode.STRICT);

        assertEquals(request, result.request());
        assertTrue(result.warnings().stream().anyMatch(warning ->
                warning.contains("not supported") && warning.contains("HEADER, COOKIE, or QUERY")));
    }

    private static ScopedVariableStore store() {
        return ScopedVariableStore.builder()
                .value(VariableScope.AUTH, "accessToken", "token-abc")
                .value(VariableScope.AUTH, "sessionId", "sess-123")
                .build();
    }

    private static StoredRequestTemplate request(List<String> headers, String pathAndQuery, String body) {
        return new StoredRequestTemplate("GET", "https", "example.test", 443, pathAndQuery, headers, body);
    }

    private static AuthProvider provider(AuthInjectionRule rule) {
        return new AuthProvider(
                "provider-1",
                "Provider",
                "",
                true,
                "login-flow",
                "refresh-flow",
                "",
                List.of(rule),
                List.of(),
                List.of(),
                AuthFallbackPolicy.LOGIN_ONLY,
                AuthRetryPolicy.NO_RETRY);
    }

    private static AuthInjectionRule rule(
            AuthInjectionLocation location,
            String target,
            String valueTemplate,
            AuthInjectionMode mode) {
        return new AuthInjectionRule("rule-1", "Rule", true, location, target, valueTemplate, mode);
    }
}

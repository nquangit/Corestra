package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualAuthApplierTest {
    private final ManualAuthApplier applier = new ManualAuthApplier(
            new TemplateRenderer(new VariableResolver(key -> Optional.empty())));

    @Test
    void manualAuthorizationHeaderOverridesExistingHeader() {
        StoredRequestTemplate result = applier.apply(
                request(List.of("Authorization: Bearer old", "X-Trace: one")),
                new AuthState(Map.of("accessToken", "new"), Map.of("Authorization", "Bearer {{auth.accessToken}}"), Map.of(), 0, 0),
                ScopedVariableStore.empty().withAuth("accessToken", "new"),
                RenderMode.STRICT);

        assertFalse(result.headerLines().contains("Authorization: Bearer old"));
        assertTrue(result.headerLines().contains("Authorization: Bearer new"));
        assertTrue(result.headerLines().contains("X-Trace: one"));
    }

    @Test
    void csrfHeaderAppendsWhenAbsent() {
        StoredRequestTemplate result = applier.apply(
                request(List.of("X-Trace: one")),
                new AuthState(Map.of("csrfToken", "csrf"), Map.of("X-CSRF-Token", "{{auth.csrfToken}}"), Map.of(), 0, 0),
                ScopedVariableStore.empty().withAuth("csrfToken", "csrf"),
                RenderMode.STRICT);

        assertEquals(List.of("X-Trace: one", "X-CSRF-Token: csrf"), result.headerLines());
    }

    @Test
    void cookiesMergeAndOverrideExistingCookieNames() {
        StoredRequestTemplate result = applier.apply(
                request(List.of("Cookie: SESSION=old; theme=dark", "X-Trace: one")),
                new AuthState(Map.of(), Map.of(), Map.of("SESSION", "new", "deviceId", "device-1"), 0, 0),
                ScopedVariableStore.empty(),
                RenderMode.STRICT);

        assertEquals(List.of("X-Trace: one", "Cookie: SESSION=new; theme=dark; deviceId=device-1"),
                result.headerLines());
    }

    @Test
    void existingNonAuthHeadersArePreserved() {
        StoredRequestTemplate result = applier.apply(
                request(List.of("X-Trace: one", "Accept: application/json")),
                new AuthState(Map.of(), Map.of("X-CSRF-Token", "csrf"), Map.of(), 0, 0),
                ScopedVariableStore.empty(),
                RenderMode.STRICT);

        assertEquals(List.of("X-Trace: one", "Accept: application/json", "X-CSRF-Token: csrf"),
                result.headerLines());
    }

    private static StoredRequestTemplate request(List<String> headers) {
        return new StoredRequestTemplate("GET", "https", "example.test", 443, "/accounts", headers, "");
    }
}

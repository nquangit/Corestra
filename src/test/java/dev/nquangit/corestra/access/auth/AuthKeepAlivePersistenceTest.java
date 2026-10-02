package dev.nquangit.corestra.access.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceDocument;
import dev.nquangit.corestra.access.persistence.AccessWorkspaceSerializer;
import dev.nquangit.corestra.access.security.ExportMode;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuthKeepAlivePersistenceTest {
    @Test
    void legacyProviderDefaultsToDisabledKeepAliveAndPreservesTtl() throws Exception {
        AuthProvider provider = new ObjectMapper().readValue("""
                {"id":"provider","name":"Provider","enabled":true,"loginFlowId":"login",
                 "fallbackPolicy":"LOGIN_ONLY","stateTtlSeconds":180}
                """, AuthProvider.class);
        assertFalse(provider.keepAlive().enabled());
        assertEquals(180, provider.stateTtlSeconds());
        assertEquals(provider, new ObjectMapper().readValue(new ObjectMapper().writeValueAsString(provider), AuthProvider.class));
    }

    @Test
    void allExportModesPreserveKeepAliveConfigurationAndSanitizeRules() {
        AuthKeepAliveConfig config = new AuthKeepAliveConfig("keep", List.of(new AuthSuccessRule(
                "alive", "Alive", true, AuthRuleType.BODY_CONTAINS, "accessToken", "keepalive-secret")),
                List.of(new AuthFailureRule("expired", "Expired", true, AuthRuleType.BODY_CONTAINS,
                        "accessToken", "expiry-secret")));
        AuthProvider provider = new AuthProvider("provider", "Provider", "", true, "login", "", "",
                List.of(), List.of(), List.of(), AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY, 180L, config);
        AccessWorkspaceDocument doc = new AccessWorkspaceDocument(AccessWorkspace.builder().authProvider(provider).build(), List.of());
        AccessWorkspaceSerializer serializer = new AccessWorkspaceSerializer();
        for (ExportMode mode : List.of(ExportMode.FULL_EXPORT, ExportMode.SANITIZED_EXPORT, ExportMode.EXCLUDE_SECRETS)) {
            var serialized = serializer.serialize(doc, mode);
            String json = serialized.toString();
            AuthProvider restored = serializer.deserialize(serialized).workspace().authProviders().get(provider.id());
            assertEquals("keep", restored.keepAlive().flowId());
            assertEquals(1, restored.keepAlive().successRules().size());
            assertEquals(1, restored.keepAlive().expiredRules().size());
            if (mode == ExportMode.FULL_EXPORT) assertEquals(provider, restored);
            else {
                assertFalse(json.contains("keepalive-secret"));
                assertFalse(json.contains("expiry-secret"));
            }
        }
    }
}

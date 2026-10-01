package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowRunContextTest {
    @Test
    void buildsScopedProfileVariablesAndLetsCustomValuesOverrideThem() {
        AuthProfile auth = new AuthProfile(
                "auth-1", "Alice", "user", "manual",
                Map.of("username", "alice", "password", "secret"),
                new AuthState(
                        Map.of("accessToken", "token"),
                        Map.of("Authorization", "Bearer token"),
                        Map.of("SESSION", "cookie"), 0L, 0L),
                true);
        DataProfile data = DataProfile.enabled(
                "data-1", "Alice account", "auth-1", Map.of("accountId", "123"));

        Map<String, String> values = FlowRunContext.variables(
                auth, data, Map.of("credentials.username", "override", "env.baseUrl", "https://test"));

        assertEquals("override", values.get("credentials.username"));
        assertEquals("secret", values.get("credentials.password"));
        assertEquals("token", values.get("auth.accessToken"));
        assertEquals("Bearer token", values.get("auth.header.Authorization"));
        assertEquals("cookie", values.get("auth.cookie.SESSION"));
        assertEquals("123", values.get("data.accountId"));
        assertEquals("https://test", values.get("env.baseUrl"));
    }

    @Test
    void parsesValuesAfterTheFirstEqualsAndRejectsMalformedOrDuplicateKeys() {
        assertEquals(
                Map.of("token", "a=b=c", "env.name", "test"),
                FlowRunContext.parseCustomVariables("# comment\ntoken=a=b=c\nenv.name=test\n"));

        assertThrows(IllegalArgumentException.class,
                () -> FlowRunContext.parseCustomVariables("missing-separator"));
        assertThrows(IllegalArgumentException.class,
                () -> FlowRunContext.parseCustomVariables("token=one\ntoken=two"));
        assertThrows(IllegalArgumentException.class,
                () -> FlowRunContext.parseCustomVariables("bad key=value"));
    }
}

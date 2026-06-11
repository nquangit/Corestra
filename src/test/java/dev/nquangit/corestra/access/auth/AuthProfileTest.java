package dev.nquangit.corestra.access.auth;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthProfileTest {
    @Test
    void authStateDefensivelyCopiesMapsAndExposesUnmodifiableMaps() {
        Map<String, String> variables = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> cookies = new LinkedHashMap<>();
        variables.put("accessToken", "token-1");
        headers.put("Authorization", "Bearer token-1");
        cookies.put("SESSION", "cookie-1");

        AuthState state = new AuthState(variables, headers, cookies, 1000L, 500L);
        variables.put("refreshToken", "changed");
        headers.put("X-Extra", "changed");
        cookies.put("OTHER", "changed");

        assertEquals(Map.of("accessToken", "token-1"), state.variables());
        assertEquals(Map.of("Authorization", "Bearer token-1"), state.headers());
        assertEquals(Map.of("SESSION", "cookie-1"), state.cookies());
        assertThrows(UnsupportedOperationException.class, () -> state.variables().put("csrfToken", "csrf"));
        assertThrows(UnsupportedOperationException.class, () -> state.headers().put("X-Test", "value"));
        assertThrows(UnsupportedOperationException.class, () -> state.cookies().put("C", "value"));
    }

    @Test
    void authProfileDefensivelyCopiesCredentialsAndExposesUnmodifiableCredentials() {
        Map<String, String> credentials = new LinkedHashMap<>();
        credentials.put("username", "alice");

        AuthProfile profile = new AuthProfile(
                "auth-owner",
                "Owner",
                "admin",
                "manual",
                credentials,
                AuthState.empty(),
                true);
        credentials.put("password", "changed");

        assertEquals(Map.of("username", "alice"), profile.credentials());
        assertThrows(UnsupportedOperationException.class, () -> profile.credentials().put("password", "secret"));
    }

    @Test
    void manualFactoryDefaultsProviderStateAndEnabled() {
        AuthProfile profile = AuthProfile.manual("auth-target", "Target", "viewer");

        assertEquals(AuthProfile.MANUAL_PROVIDER_ID, profile.providerId());
        assertEquals(AuthState.empty(), profile.state());
        assertTrue(profile.credentials().isEmpty());
        assertTrue(profile.enabled());
    }
}

package dev.nquangit.corestra.access.auth;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuthStateTest {
    @Test
    void normalizesLegacyAuthPrefixedVariablesOnConstruction() {
        AuthState state = new AuthState(
                Map.of("auth.accessToken", "token", "auth.expiresIn", "60"),
                Map.of(),
                Map.of(),
                0L,
                0L);

        assertEquals("token", state.variables().get("accessToken"));
        assertEquals("60", state.variables().get("expiresIn"));
        assertFalse(state.variables().containsKey("auth.accessToken"));
    }

    @Test
    void canonicalVariableWinsWhenLegacyAndCanonicalFormsExist() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("accessToken", "canonical");
        values.put("auth.accessToken", "legacy");

        assertEquals("canonical", AuthState.normalizeVariables(values).get("accessToken"));
    }
}

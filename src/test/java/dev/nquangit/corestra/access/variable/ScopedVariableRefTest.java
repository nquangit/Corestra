package dev.nquangit.corestra.access.variable;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedVariableRefTest {
    @Test
    void parsesScopedKeysWithAllowedSegments() {
        ScopedVariableRef auth = ScopedVariableRef.parse("auth.accessToken");
        ScopedVariableRef data = ScopedVariableRef.parse("data.account.id");
        ScopedVariableRef scenario = ScopedVariableRef.parse("scenario.confirm-token");
        ScopedVariableRef env = ScopedVariableRef.parse("env.base_url");

        assertEquals(VariableScope.AUTH, auth.scope());
        assertEquals("accessToken", auth.key());
        assertEquals(VariableScope.DATA, data.scope());
        assertEquals("account.id", data.key());
        assertEquals(VariableScope.SCENARIO, scenario.scope());
        assertEquals("confirm-token", scenario.key());
        assertEquals(VariableScope.ENV, env.scope());
        assertEquals("base_url", env.key());
    }

    @Test
    void rejectsInvalidScopedKeys() {
        assertInvalid("token");
        assertInvalid("auth");
        assertInvalid("auth.");
        assertInvalid("auth..token");
        assertInvalid("auth.token.");
        assertInvalid("auth..");
        assertInvalid("AUTH.token");
        assertInvalid("Auth.token");
        assertInvalid("auth.to ken");
        assertInvalid(" auth.token");
        assertInvalid("auth.token ");
    }

    @Test
    void tryParseReturnsEmptyForInvalidKeys() {
        assertTrue(ScopedVariableRef.tryParse("AUTH.token").isEmpty());
        assertTrue(ScopedVariableRef.tryParse("auth..token").isEmpty());
        assertTrue(ScopedVariableRef.tryParse(null).isEmpty());
    }

    @Test
    void scopedKeyReturnsCanonicalForm() {
        ScopedVariableRef ref = new ScopedVariableRef(VariableScope.AUTH, "accessToken");

        assertEquals("auth.accessToken", ref.scopedKey());
    }

    private static void assertInvalid(String scopedKey) {
        assertThrows(IllegalArgumentException.class, () -> ScopedVariableRef.parse(scopedKey));
    }
}

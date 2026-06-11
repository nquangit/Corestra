package dev.nquangit.corestra.access.variable;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopedVariableStoreTest {
    @Test
    void resolvesValuesByScopeAndScopedKey() {
        ScopedVariableStore store = ScopedVariableStore.builder()
                .value(VariableScope.ENV, "baseUrl", "https://example.test")
                .value(VariableScope.AUTH, "accessToken", "abc")
                .value(VariableScope.DATA, "account.id", "42")
                .value(VariableScope.SCENARIO, "flow-name", "login")
                .value(VariableScope.LEGACY, "token", "legacy-token")
                .build();

        assertEquals(Optional.of("https://example.test"), store.resolve("env.baseUrl"));
        assertEquals(Optional.of("abc"), store.resolve(new ScopedVariableRef(VariableScope.AUTH, "accessToken")));
        assertEquals(Optional.of("42"), store.resolve("data.account.id"));
        assertEquals(Optional.of("login"), store.resolve("scenario.flow-name"));
        assertEquals(Optional.of("legacy-token"), store.resolve("legacy.token"));
    }

    @Test
    void defensivelyCopiesInputMaps() {
        Map<String, String> authValues = new LinkedHashMap<>();
        authValues.put("accessToken", "first");

        ScopedVariableStore store = ScopedVariableStore.builder()
                .scope(VariableScope.AUTH, authValues)
                .build();
        authValues.put("accessToken", "changed");
        authValues.put("refreshToken", "added");

        assertEquals(Optional.of("first"), store.resolve("auth.accessToken"));
        assertTrue(store.resolve("auth.refreshToken").isEmpty());
    }

    @Test
    void valuesReturnsUnmodifiableMap() {
        ScopedVariableStore store = ScopedVariableStore.empty().withAuth("accessToken", "abc");
        Map<String, String> authValues = store.values(VariableScope.AUTH);

        assertEquals(Map.of("accessToken", "abc"), authValues);
        assertThrows(UnsupportedOperationException.class, () -> authValues.put("refreshToken", "def"));
    }

    @Test
    void withValueAndWithScopeReturnNewStoresWithoutMutatingOriginal() {
        ScopedVariableStore original = ScopedVariableStore.empty().withAuth("accessToken", "first");
        ScopedVariableStore withValue = original.withAuth("refreshToken", "second");
        ScopedVariableStore withScope = original.withScope(VariableScope.AUTH, Map.of("idToken", "third"));

        assertEquals(Optional.of("first"), original.resolve("auth.accessToken"));
        assertTrue(original.resolve("auth.refreshToken").isEmpty());
        assertTrue(original.resolve("auth.idToken").isEmpty());

        assertEquals(Optional.of("first"), withValue.resolve("auth.accessToken"));
        assertEquals(Optional.of("second"), withValue.resolve("auth.refreshToken"));
        assertEquals(Optional.of("third"), withScope.resolve("auth.idToken"));
        assertTrue(withScope.resolve("auth.accessToken").isEmpty());
    }

    @Test
    void rejectsInvalidStoreKeysAndNullValues() {
        assertThrows(IllegalArgumentException.class,
                () -> ScopedVariableStore.empty().withAuth("access token", "abc"));
        assertThrows(IllegalArgumentException.class,
                () -> ScopedVariableStore.empty().withAuth("token.", "abc"));
        assertThrows(NullPointerException.class,
                () -> ScopedVariableStore.empty().withAuth("token", null));
    }

    @Test
    void emptyScopeValuesAreUnmodifiable() {
        Map<String, String> values = ScopedVariableStore.empty().values(VariableScope.AUTH);

        assertTrue(values.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> values.put("token", "abc"));
    }
}

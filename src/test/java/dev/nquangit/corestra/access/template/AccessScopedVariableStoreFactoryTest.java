package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessScopedVariableStoreFactoryTest {
    @Test
    void mapsAccessInputsIntoScopedStore() {
        AccessScopedVariableStoreFactory factory = new AccessScopedVariableStoreFactory();
        AuthState authState = new AuthState(
                Map.of("accessToken", "token-abc"),
                Map.of("Authorization", "Bearer token-abc"),
                Map.of("SESSION", "cookie-abc"),
                0L,
                0L);
        DataProfile dataProfile = new DataProfile(
                "data-1",
                "Owner data",
                "auth-owner",
                Map.of("accountId", "acct-123"),
                true);

        ScopedVariableStore store = factory.from(
                Map.of("baseUrl", "https://example.test"),
                authState,
                dataProfile,
                Map.of("confirmToken", "confirm-xyz"),
                Map.of("token", "legacy-token"));

        assertEquals(Optional.of("https://example.test"), store.resolve("env.baseUrl"));
        assertEquals(Optional.of("token-abc"), store.resolve("auth.accessToken"));
        assertEquals(Optional.of("acct-123"), store.resolve("data.accountId"));
        assertEquals(Optional.of("confirm-xyz"), store.resolve("scenario.confirmToken"));
        assertEquals(Optional.of("legacy-token"), store.resolve("legacy.token"));
        assertTrue(store.resolve("auth.Authorization").isEmpty());
        assertTrue(store.resolve("auth.SESSION").isEmpty());
    }

    @Test
    void acceptsMissingAuthAndDataInputs() {
        AccessScopedVariableStoreFactory factory = new AccessScopedVariableStoreFactory();

        ScopedVariableStore store = factory.from(null, null, null, null, null);

        assertTrue(store.resolve("auth.accessToken").isEmpty());
        assertTrue(store.resolve("data.accountId").isEmpty());
    }
}

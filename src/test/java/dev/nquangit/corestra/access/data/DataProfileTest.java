package dev.nquangit.corestra.access.data;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataProfileTest {
    @Test
    void defensivelyCopiesValuesAndExposesUnmodifiableValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("accountId", "acct-1");

        DataProfile profile = new DataProfile("data-owner", "Owner data", "auth-owner", values, true);
        values.put("cardId", "card-1");

        assertEquals(Map.of("accountId", "acct-1"), profile.values());
        assertThrows(UnsupportedOperationException.class, () -> profile.values().put("customerId", "cust-1"));
    }

    @Test
    void enabledFactoryCreatesEnabledProfile() {
        DataProfile profile = DataProfile.enabled("data-owner", "Owner data", "auth-owner", Map.of("accountId", "acct-1"));

        assertTrue(profile.enabled());
        assertEquals("auth-owner", profile.ownerAuthProfileId());
        assertEquals("acct-1", profile.values().get("accountId"));
    }
}

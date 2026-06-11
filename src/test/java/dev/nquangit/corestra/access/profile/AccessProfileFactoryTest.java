package dev.nquangit.corestra.access.profile;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessProfileFactoryTest {
    @Test
    void createsManualAuthProfileWithManualProviderAndDefensiveStateCopies() {
        AccessProfileFactory factory = new AccessProfileFactory(prefix -> prefix + "-fixed");
        Map<String, String> variables = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> cookies = new LinkedHashMap<>();
        variables.put("accessToken", "token-abc");
        headers.put("Authorization", "Bearer token-abc");
        cookies.put("SESSION", "cookie-abc");

        AuthProfile profile = factory.manualAuthProfile("Owner", "admin", variables, headers, cookies);
        variables.put("refreshToken", "changed");
        headers.put("X-Changed", "changed");
        cookies.put("OTHER", "changed");

        assertEquals("auth-fixed", profile.id());
        assertEquals("manual", profile.providerId());
        assertTrue(profile.enabled());
        assertEquals(Map.of("accessToken", "token-abc"), profile.state().variables());
        assertEquals(Map.of("Authorization", "Bearer token-abc"), profile.state().headers());
        assertEquals(Map.of("SESSION", "cookie-abc"), profile.state().cookies());
    }

    @Test
    void createsDataProfileWithOwnerAndDefensiveValueCopy() {
        AccessProfileFactory factory = new AccessProfileFactory(prefix -> prefix + "-fixed");
        Map<String, String> values = new LinkedHashMap<>();
        values.put("accountId", "acct-1");

        DataProfile profile = factory.dataProfile("Owner data", "auth-owner", values);
        values.put("cardId", "changed");

        assertEquals("data-fixed", profile.id());
        assertEquals("auth-owner", profile.ownerAuthProfileId());
        assertTrue(profile.enabled());
        assertEquals(Map.of("accountId", "acct-1"), profile.values());
    }
}

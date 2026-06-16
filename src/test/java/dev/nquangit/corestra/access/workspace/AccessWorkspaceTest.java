package dev.nquangit.corestra.access.workspace;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessWorkspaceTest {
    @Test
    void defensivelyCopiesMapsAndExposesUnmodifiableAccessors() {
        AuthProvider authProvider = provider("provider-1");
        Map<String, AuthProvider> authProviders = new LinkedHashMap<>();
        authProviders.put(authProvider.id(), authProvider);
        AuthProfile authProfile = AuthProfile.manual("auth-1", "Owner", "admin");
        Map<String, AuthProfile> authProfiles = new LinkedHashMap<>();
        authProfiles.put(authProfile.id(), authProfile);
        EvaluationProfile evaluationProfile = evaluationProfile("eval-1");
        Map<String, EvaluationProfile> evaluationProfiles = new LinkedHashMap<>();
        evaluationProfiles.put(evaluationProfile.id(), evaluationProfile);

        AccessWorkspace workspace = new AccessWorkspace(authProviders, authProfiles, Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), evaluationProfiles);
        authProviders.clear();
        authProfiles.clear();
        evaluationProfiles.clear();

        assertEquals(Map.of(authProvider.id(), authProvider), workspace.authProviders());
        assertEquals(Map.of(authProfile.id(), authProfile), workspace.authProfiles());
        assertEquals(Map.of(evaluationProfile.id(), evaluationProfile), workspace.evaluationProfiles());
        assertThrows(UnsupportedOperationException.class,
                () -> workspace.authProviders().put("provider-2", provider("provider-2")));
        assertThrows(UnsupportedOperationException.class,
                () -> workspace.authProfiles().put("auth-2", AuthProfile.manual("auth-2", "Target", "viewer")));
        assertThrows(UnsupportedOperationException.class,
                () -> workspace.evaluationProfiles().put("eval-2", evaluationProfile("eval-2")));
    }

    @Test
    void builderAndCopyWithCreateNewSnapshotsWithoutMutatingOriginal() {
        AccessWorkspace original = AccessWorkspace.builder()
                .authProfile(AuthProfile.manual("auth-1", "Owner", "admin"))
                .build();
        DataProfile dataProfile = DataProfile.enabled("data-1", "Owner data", "auth-1", Map.of("accountId", "acct-1"));
        AuthProvider authProvider = provider("provider-1");
        EvaluationProfile evaluationProfile = evaluationProfile("eval-1");

        AccessWorkspace updated = original.withDataProfile(dataProfile)
                .withAuthProvider(authProvider)
                .withEvaluationProfile(evaluationProfile);

        assertTrue(original.dataProfiles().isEmpty());
        assertTrue(original.authProviders().isEmpty());
        assertTrue(original.evaluationProfiles().isEmpty());
        assertEquals(Map.of(dataProfile.id(), dataProfile), updated.dataProfiles());
        assertEquals(Map.of(authProvider.id(), authProvider), updated.authProviders());
        assertEquals(Map.of(evaluationProfile.id(), evaluationProfile), updated.evaluationProfiles());
        assertEquals(original.authProfiles(), updated.authProfiles());
    }

    private static AuthProvider provider(String id) {
        return new AuthProvider(
                id,
                "Provider",
                "",
                true,
                "",
                "",
                "",
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                AuthFallbackPolicy.MANUAL_ONLY,
                AuthRetryPolicy.NO_RETRY);
    }

    private static EvaluationProfile evaluationProfile(String id) {
        return new EvaluationProfile(
                id,
                "Evaluation",
                true,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of());
    }
}

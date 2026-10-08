package dev.nquangit.corestra.session;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import dev.nquangit.corestra.config.CorestraSettingsKeys;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

class SessionProfileRepositoryTest {
    @Test
    void sharedOnlyScopePersistsAndMatchesWithoutAlternatives() {
        var persistence = new FakePersistence();
        var scope = new SessionScope(SessionScopeMode.CUSTOM,
                List.of(new SessionScopeCriterion(SessionCriterionType.HEADER, "X-User", "^Alice$")),
                List.of());
        var profile = new SessionProfile("alice", "Alice", true, false, scope,
                SessionAuthConfig.defaults(), SessionSchedulerConfig.disabled(), SessionState.empty(), List.of(), List.of());
        new SessionProfileRepository(persistence.persistence()).saveProfile(profile);
        var loaded = new SessionProfileRepository(persistence.persistence()).profile("alice").orElseThrow();
        assertEquals(scope, loaded.scope());
        assertTrue(loaded.scope().rules().isEmpty());
        assertTrue(loaded.scope().matches(false, "GET", "api", "/", List.of("X-User: Alice"), ""));
        assertFalse(loaded.scope().matches(false, "GET", "api", "/", List.of("X-User: Bob"), ""));
    }

    @Test
    void alternativeScopeRulesSurviveRealProjectPersistenceReload() {
        var persistence = new FakePersistence();
        var scope = new SessionScope(SessionScopeMode.CUSTOM,
                List.of(new SessionScopeCriterion(SessionCriterionType.HEADER, "X-User", "^Alice$")),
                List.of(new SessionScopeRule("API", List.of(new SessionScopeCriterion(SessionCriterionType.HOST, "", "^api\\.example$"))),
                        new SessionScopeRule("Admin", List.of(new SessionScopeCriterion(SessionCriterionType.HOST, "", "^admin\\.example$")))));
        var profile = new SessionProfile("alice", "Alice", true, false, scope,
                SessionAuthConfig.defaults(), SessionSchedulerConfig.disabled(), SessionState.empty(), List.of(), List.of());
        new SessionProfileRepository(persistence.persistence()).saveProfile(profile);
        var loaded = new SessionProfileRepository(persistence.persistence()).profile("alice").orElseThrow();
        assertEquals(scope, loaded.scope());
        assertTrue(loaded.scope().matches(false, "GET", "admin.example", "/", List.of("X-User: Alice"), ""));
        assertFalse(loaded.scope().matches(false, "GET", "admin.example", "/", List.of("X-User: Bob"), ""));
    }

    @Test
    void loadsPersistenceOnceAndKeepsWritesStateUpdatesAndDeletesInCache() {
        FakePersistence persistence = new FakePersistence();
        SessionProfile profile = SessionProfile.general();
        new SessionProfileRepository(persistence.persistence()).saveProfiles(List.of(profile));
        SessionProfileRepository repository = new SessionProfileRepository(persistence.persistence());
        List<SessionProfile> first = repository.loadProfiles();
        int reads = persistence.state().reads;
        for (int tick = 0; tick < 100; tick++) {
            assertSame(first, repository.loadProfiles());
            repository.profile(SessionProfile.GENERAL_ID);
        }
        assertEquals(reads, persistence.state().reads, "Polling and lookups must not reread project JSON");
        repository.updateState(profile.id(), state -> state.withAuthenticatedValues(Map.of("token", "new"), 2000, 60));
        assertEquals("new", repository.profile(profile.id()).orElseThrow().state().values().get("token"));
        assertEquals(reads, persistence.state().reads);
        assertEquals("new", new SessionProfileRepository(persistence.persistence()).profile(profile.id())
                .orElseThrow().state().values().get("token"));
        repository.clear();
        int afterClearReads = persistence.state().reads;
        assertEquals(SessionState.empty(), repository.loadProfiles().getFirst().state());
        assertEquals(afterClearReads, persistence.state().reads);
        assertNull(persistence.state().getString(CorestraSettingsKeys.SESSION_PROFILES_DOCUMENT));
    }

    @Test
    void failedWriteOrClearDoesNotReplaceValidCachedProfiles() {
        FakePersistence persistence = new FakePersistence();
        SessionProfileRepository repository = new SessionProfileRepository(persistence.persistence());
        repository.saveProfile(SessionProfile.general());
        List<SessionProfile> before = repository.loadProfiles();
        persistence.state().failWrites = true;
        assertThrows(IllegalStateException.class, () -> repository.saveProfile(SessionProfile.general().withEnabled(false)));
        assertSame(before, repository.loadProfiles());
        assertThrows(IllegalStateException.class, repository::clear);
        assertSame(before, repository.loadProfiles());
    }

    @Test
    void profileToStringUsesDisplayNameInsteadOfObjectIdentity() {
        SessionProfile enabled = new SessionProfile(
                "profile-1",
                "Owner",
                true,
                false,
                SessionScope.burpTargetScope(),
                SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(),
                SessionState.empty(),
                List.of(),
                List.of());
        SessionProfile disabled = enabled.withEnabled(false);

        assertEquals("Owner", enabled.toString());
        assertEquals("[off] Owner", disabled.toString());
        assertFalse(enabled.toString().contains("@"));
    }

    @Test
    void generalProfileExistsByDefaultAndCannotBeDeleted() {
        SessionProfileRepository repository = new SessionProfileRepository();

        List<SessionProfile> profiles = repository.loadProfiles();

        assertEquals(SessionProfile.GENERAL_ID, profiles.getFirst().id());
        assertTrue(profiles.getFirst().isGeneral());
        assertFalse(repository.deleteProfile(SessionProfile.GENERAL_ID));
        assertEquals(1, repository.loadProfiles().size());
    }

    @Test
    void saveAndReloadRoundTripsProfiles() {
        SessionProfileRepository repository = new SessionProfileRepository();
        SessionProfile profile = new SessionProfile(
                "profile-1",
                "Owner",
                true,
                false,
                SessionScope.custom(List.of()),
                SessionAuthConfig.defaults(),
                SessionSchedulerConfig.disabled(),
                new SessionState(Map.of("accessToken", "secret"), 0L, 0L, 100L),
                List.of(),
                List.of());

        repository.saveProfile(profile);

        assertEquals("secret", repository.profile("profile-1").orElseThrow().state().values().get("accessToken"));
        assertEquals(SessionProfile.GENERAL_ID, repository.loadProfiles().getFirst().id());
    }

    @Test
    void linkedAuthProfileIdRoundTripsAndOldProfilesRemainStandalone() {
        FakePersistence persistence = new FakePersistence();
        SessionProfileRepository repository = new SessionProfileRepository(persistence.persistence());
        SessionProfile linked = new SessionProfile(
                "profile-1", "Alice binding", true, false, SessionScope.custom(List.of()),
                "auth-alice", SessionAuthConfig.defaults(), SessionSchedulerConfig.disabled(),
                SessionState.empty(), List.of(), List.of());

        repository.saveProfile(linked);
        SessionProfile reloaded = new SessionProfileRepository(persistence.persistence())
                .profile(linked.id()).orElseThrow();

        assertEquals("auth-alice", reloaded.linkedAuthProfileId());
        assertTrue(reloaded.usesLinkedAuthProfile());
        assertFalse(SessionProfile.general().usesLinkedAuthProfile());
    }

    @Test
    void invalidStoredJsonIsQuarantinedAndDefaultsAreReturned() {
        FakePersistence persistence = new FakePersistence();
        persistence.state().seed(CorestraSettingsKeys.SESSION_PROFILES_DOCUMENT, "not-json{{");
        SessionProfileRepository repository = new SessionProfileRepository(persistence.persistence());

        List<SessionProfile> profiles = repository.loadProfiles();

        assertEquals(1, profiles.size());
        assertEquals(SessionProfile.GENERAL_ID, profiles.getFirst().id());
        assertNotNull(persistence.state().getString(CorestraSettingsKeys.SESSION_PROFILES_DOCUMENT_QUARANTINE));
        assertNull(persistence.state().getString(CorestraSettingsKeys.SESSION_PROFILES_DOCUMENT));
        int reads = persistence.state().reads;
        repository.loadProfiles();
        assertEquals(reads, persistence.state().reads, "Recovered defaults must also be cached");
    }

    private static final class FakePersistence {
        private final FakeState state = new FakeState();
        private final Persistence persistence = (Persistence) Proxy.newProxyInstance(
                Persistence.class.getClassLoader(),
                new Class<?>[]{Persistence.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "extensionData" -> state.proxy();
                    case "toString" -> "FakePersistence";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        Persistence persistence() {
            return persistence;
        }

        FakeState state() {
            return state;
        }
    }

    private static final class FakeState {
        private final Map<String, String> data = new HashMap<>();
        int reads;
        boolean failWrites;
        private final PersistedObject proxy = (PersistedObject) Proxy.newProxyInstance(
                PersistedObject.class.getClassLoader(),
                new Class<?>[]{PersistedObject.class},
                (instance, method, args) -> switch (method.getName()) {
                    case "getString" -> {
                        reads++;
                        yield data.get((String) args[0]);
                    }
                    case "setString" -> {
                        if (failWrites) throw new IllegalStateException("failed write");
                        data.put((String) args[0], (String) args[1]);
                        yield null;
                    }
                    case "deleteString" -> {
                        if (failWrites) throw new IllegalStateException("failed delete");
                        data.remove((String) args[0]);
                        yield null;
                    }
                    case "toString" -> "FakePersistedObject";
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        PersistedObject proxy() {
            return proxy;
        }

        void seed(String key, String value) {
            data.put(key, value);
        }

        String getString(String key) {
            return data.get(key);
        }
    }
}

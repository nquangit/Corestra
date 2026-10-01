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

class SessionProfileRepositoryTest {

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
        private final PersistedObject proxy = (PersistedObject) Proxy.newProxyInstance(
                PersistedObject.class.getClassLoader(),
                new Class<?>[]{PersistedObject.class},
                (instance, method, args) -> switch (method.getName()) {
                    case "getString" -> data.get((String) args[0]);
                    case "setString" -> {
                        data.put((String) args[0], (String) args[1]);
                        yield null;
                    }
                    case "deleteString" -> {
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

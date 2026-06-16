package dev.nquangit.corestra.persistence;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import dev.nquangit.corestra.config.CorestraSettingsKeys;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProjectSettingsRepositoryTest {

    @Test
    void loadReturnsDefaultsWhenKeyAbsent() {
        ProjectSettings settings = repo(new FakeState()).load();

        assertEquals(HttpSendMode.STANDARD, settings.sendMode());
        assertEquals(ProjectSettings.DEFAULT_PROXY_HOST, settings.proxyHost());
        assertEquals(ProjectSettings.DEFAULT_PROXY_PORT, settings.proxyPort());
    }

    @Test
    void saveAndLoadRoundTrips() {
        FakeState state = new FakeState();
        ProjectSettingsRepository repo = repo(state);
        ProjectSettings saved = new ProjectSettings(HttpSendMode.PROXY_PIPELINE, "127.0.0.2", 9090);

        repo.save(saved);

        assertEquals(saved, repo.load());
        assertEquals(1, state.setStringCallCount());
    }

    @Test
    void saveWritesProjectSettingsKey() {
        FakeState state = new FakeState();

        repo(state).save(ProjectSettings.defaults());

        assertNotNull(state.getString(CorestraSettingsKeys.PROJECT_SETTINGS));
    }

    @Test
    void loadReturnsDefaultsWhenStoredValueIsCorrupted() {
        FakeState state = new FakeState();
        state.seed(CorestraSettingsKeys.PROJECT_SETTINGS, "not-json{{");

        ProjectSettings settings = repo(state).load();

        assertEquals(ProjectSettings.defaults(), settings);
    }

    @Test
    void inMemoryRepositoryRoundTrips() {
        ProjectSettingsRepository repo = new ProjectSettingsRepository();
        ProjectSettings saved = new ProjectSettings(HttpSendMode.PROXY_PIPELINE, "proxy.local", 8081);

        repo.save(saved);

        assertEquals(saved, repo.load());
    }

    private static ProjectSettingsRepository repo(FakeState state) {
        Persistence persistence = (Persistence) Proxy.newProxyInstance(
                Persistence.class.getClassLoader(),
                new Class<?>[]{Persistence.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "extensionData" -> state.proxy();
                    case "toString" -> "FakePersistence";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        return new ProjectSettingsRepository(persistence);
    }

    private static final class FakeState {
        private final Map<String, String> data = new HashMap<>();
        private int setCount;

        private final PersistedObject proxy = (PersistedObject) Proxy.newProxyInstance(
                PersistedObject.class.getClassLoader(),
                new Class<?>[]{PersistedObject.class},
                (instance, method, args) -> switch (method.getName()) {
                    case "getString" -> data.get((String) args[0]);
                    case "setString" -> {
                        setCount++;
                        data.put((String) args[0], (String) args[1]);
                        yield null;
                    }
                    case "toString" -> "FakePersistedObject";
                    case "hashCode" -> System.identityHashCode(instance);
                    case "equals" -> instance == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        PersistedObject proxy() { return proxy; }
        void seed(String key, String value) { data.put(key, value); }
        String getString(String key) { return data.get(key); }
        int setStringCallCount() { return setCount; }
    }
}

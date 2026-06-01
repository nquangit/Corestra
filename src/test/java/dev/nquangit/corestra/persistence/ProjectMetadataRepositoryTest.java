package dev.nquangit.corestra.persistence;

import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import dev.nquangit.corestra.config.CorestraSettingsKeys;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProjectMetadataRepositoryTest {

    @Test
    void loadReturnsDefaultWhenKeyAbsent() {
        FakeState state = new FakeState();
        ProjectMetadataRepository repo = repo(state);

        ProjectMetadata meta = repo.load();

        assertEquals("", meta.name());
        assertEquals("", meta.owner());
        assertTrue(meta.createdAtEpochMillis() > 0);
        assertEquals(0, state.setStringCallCount());
    }

    @Test
    void saveAndLoadRoundTrips() {
        FakeState state = new FakeState();
        ProjectMetadataRepository repo = repo(state);
        ProjectMetadata saved = new ProjectMetadata("MyProj", "desc", "alice", "note", 1000L, 2000L);

        repo.save(saved);
        ProjectMetadata loaded = repo.load();

        assertEquals(saved, loaded);
        assertEquals(1, state.setStringCallCount());
    }

    @Test
    void saveWritesToProjectMetadataKey() {
        FakeState state = new FakeState();
        repo(state).save(new ProjectMetadata("X", "", "", "", 1L, 1L));
        assertNotNull(state.getString(CorestraSettingsKeys.PROJECT_METADATA));
    }

    @Test
    void loadReturnsDefaultWhenStoredValueIsCorrupted() {
        FakeState state = new FakeState();
        state.seed(CorestraSettingsKeys.PROJECT_METADATA, "not-valid-json{{");
        ProjectMetadata meta = repo(state).load();
        assertEquals("", meta.name());
        assertTrue(meta.createdAtEpochMillis() > 0);
    }

    private static ProjectMetadataRepository repo(FakeState state) {
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
        return new ProjectMetadataRepository(persistence);
    }

    private static final class FakeState {
        private final Map<String, String> data = new HashMap<>();
        private int setCount;

        private final PersistedObject proxy = (PersistedObject) Proxy.newProxyInstance(
                PersistedObject.class.getClassLoader(),
                new Class<?>[]{PersistedObject.class},
                (instance, method, args) -> switch (method.getName()) {
                    case "getString" -> data.get((String) args[0]);
                    case "setString" -> { setCount++; data.put((String) args[0], (String) args[1]); yield null; }
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

package dev.nquangit.corestra.watch;

import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Persistence;
import dev.nquangit.corestra.config.CorestraSettingsKeys;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchRepositoryTest {

    private static WatchDefinition fullWatch(String id) {
        return WatchConfigFixtures.fullWatch(id);
    }

    @Test
    void roundTripsEveryFieldThroughProjectPersistence() {
        FakePersistence persistence = new FakePersistence();
        WatchDefinition watch = fullWatch("w1");
        new WatchRepository(persistence.persistence()).save(List.of(watch));

        List<WatchDefinition> loaded = new WatchRepository(persistence.persistence()).load();

        assertEquals(List.of(watch), loaded);
        WatchDefinition reloaded = loaded.getFirst();
        assertEquals(ExtractRule.RegexSource.FULL_MESSAGE, reloaded.requestVariables().get(1).regexSource());
        assertEquals(1, reloaded.requestVariables().get(1).regexGroupIndex());
        assertEquals(ExtractRule.SourceSide.REQUEST, reloaded.requestVariables().getFirst().sourceSide());
        assertEquals(8443, reloaded.probe().request().port());
        assertEquals(WatchExpectation.Type.DELTA_BETWEEN, reloaded.expectations().getFirst().type());
        assertEquals(WatchObservation.ValueType.TEXT, reloaded.observations().get(1).valueType());
        assertEquals(2, reloaded.observations().getFirst().numberFormat().minorUnitScale());
        assertTrue(persistence.state().getString(CorestraSettingsKeys.WATCH_DOCUMENT).contains("\"version\":1"));
    }

    @Test
    void unconfiguredProbeRoundTripsAsNull() {
        FakePersistence persistence = new FakePersistence();
        WatchDefinition empty = WatchDefinition.empty("w0", "Empty");
        new WatchRepository(persistence.persistence()).save(List.of(empty));
        WatchDefinition loaded = new WatchRepository(persistence.persistence()).load().getFirst();
        assertEquals(empty, loaded);
        assertFalse(loaded.probe().configured());
        assertFalse(persistence.state().getString(CorestraSettingsKeys.WATCH_DOCUMENT).contains("configured"));
    }

    @Test
    void missingFieldsLoadWithDefaults() {
        FakePersistence persistence = new FakePersistence();
        persistence.state().seed(CorestraSettingsKeys.WATCH_DOCUMENT,
                "{\"version\":1,\"watches\":[{\"id\":\"w1\",\"futureField\":42}]}");

        WatchDefinition loaded = new WatchRepository(persistence.persistence()).load().getFirst();

        assertEquals("w1", loaded.id());
        assertEquals("Watch", loaded.name());
        assertFalse(loaded.enabled());
        assertEquals(WatchScope.defaults(), loaded.scope());
        assertEquals(WatchProbe.DEFAULT_INHERIT_HEADERS, loaded.probe().inheritHeaders());
        assertEquals(WatchDisplayOptions.defaults(), loaded.display());
        assertEquals(WatchTiming.defaults(), loaded.timing());
        assertNull(persistence.state().getString(CorestraSettingsKeys.WATCH_DOCUMENT_QUARANTINE));
    }

    @Test
    void malformedDocumentIsQuarantinedWithoutDeletingTheOriginal() {
        FakePersistence persistence = new FakePersistence();
        persistence.state().seed(CorestraSettingsKeys.WATCH_DOCUMENT, "not-json{{");
        WatchRepository repository = new WatchRepository(persistence.persistence());

        assertTrue(repository.load().isEmpty());
        assertEquals("not-json{{", persistence.state().getString(CorestraSettingsKeys.WATCH_DOCUMENT_QUARANTINE));
        assertEquals("not-json{{", persistence.state().getString(CorestraSettingsKeys.WATCH_DOCUMENT));
        assertFalse(repository.loadDiagnostics().isEmpty());
        int reads = persistence.state().reads;
        repository.load();
        assertEquals(reads, persistence.state().reads, "Recovered state must be cached");
    }

    @Test
    void oneInvalidWatchKeepsTheValidOnesAndQuarantinesTheDocument() {
        FakePersistence persistence = new FakePersistence();
        String json = "{\"version\":1,\"watches\":[{\"id\":\"good\",\"name\":\"Good\"},"
                + "{\"id\":\"bad\",\"requestVariables\":[{\"id\":\"\"}]}]}";
        persistence.state().seed(CorestraSettingsKeys.WATCH_DOCUMENT, json);

        List<WatchDefinition> loaded = new WatchRepository(persistence.persistence()).load();

        assertEquals(1, loaded.size());
        assertEquals("good", loaded.getFirst().id());
        assertEquals(json, persistence.state().getString(CorestraSettingsKeys.WATCH_DOCUMENT_QUARANTINE));
    }

    @Test
    void unsupportedVersionIsQuarantined() {
        FakePersistence persistence = new FakePersistence();
        String json = "{\"version\":99,\"watches\":[]}";
        persistence.state().seed(CorestraSettingsKeys.WATCH_DOCUMENT, json);
        assertTrue(new WatchRepository(persistence.persistence()).load().isEmpty());
        assertEquals(json, persistence.state().getString(CorestraSettingsKeys.WATCH_DOCUMENT_QUARANTINE));
    }

    @Test
    void upsertDeleteAndListeners() {
        WatchRepository repository = new WatchRepository();
        AtomicInteger notified = new AtomicInteger();
        Runnable listener = notified::incrementAndGet;
        repository.addListener(listener);

        repository.upsert(fullWatch("a"));
        repository.upsert(fullWatch("b"));
        repository.upsert(fullWatch("a").withName("Renamed"));

        assertEquals(List.of("a", "b"), repository.load().stream().map(WatchDefinition::id).toList());
        assertEquals("Renamed", repository.find("a").orElseThrow().name());
        assertTrue(repository.delete("a"));
        assertFalse(repository.delete("missing"));
        assertEquals(List.of("b"), repository.load().stream().map(WatchDefinition::id).toList());
        assertEquals(4, notified.get());

        repository.removeListener(listener);
        repository.upsert(fullWatch("c"));
        assertEquals(4, notified.get());
    }

    @Test
    void rejectsBlankAndDuplicateIds() {
        WatchRepository repository = new WatchRepository();
        assertThrows(IllegalArgumentException.class, () -> repository.upsert(WatchDefinition.empty("", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> repository.save(List.of(fullWatch("a"), fullWatch("a"))));
    }

    @Test
    void cachesAfterFirstLoadAndFailedWriteKeepsCache() {
        FakePersistence persistence = new FakePersistence();
        new WatchRepository(persistence.persistence()).save(List.of(fullWatch("a")));
        WatchRepository repository = new WatchRepository(persistence.persistence());
        List<WatchDefinition> first = repository.load();
        int reads = persistence.state().reads;
        assertSame(first, repository.load());
        assertEquals(reads, persistence.state().reads);

        persistence.state().failWrites = true;
        assertThrows(IllegalStateException.class, () -> repository.upsert(fullWatch("b")));
        assertEquals(first, repository.load());
    }

    @Test
    void clearRemovesStoredDocumentAndCache() {
        FakePersistence persistence = new FakePersistence();
        WatchRepository repository = new WatchRepository(persistence.persistence());
        repository.save(List.of(fullWatch("a")));
        repository.clear();
        assertTrue(repository.load().isEmpty());
        assertNull(persistence.state().getString(CorestraSettingsKeys.WATCH_DOCUMENT));
    }

    static final class FakePersistence {
        private final FakeState state = new FakeState();
        private final Persistence persistence = (Persistence) Proxy.newProxyInstance(
                Persistence.class.getClassLoader(), new Class<?>[]{Persistence.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "extensionData" -> state.proxy;
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

    static final class FakeState {
        private final Map<String, String> data = new HashMap<>();
        int reads;
        boolean failWrites;
        private final PersistedObject proxy = (PersistedObject) Proxy.newProxyInstance(
                PersistedObject.class.getClassLoader(), new Class<?>[]{PersistedObject.class},
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

        void seed(String key, String value) {
            data.put(key, value);
        }

        String getString(String key) {
            return data.get(key);
        }
    }
}

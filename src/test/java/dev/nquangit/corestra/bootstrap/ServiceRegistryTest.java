package dev.nquangit.corestra.bootstrap;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.persistence.FlowRepository;
import dev.nquangit.corestra.persistence.FlowSerializer;
import dev.nquangit.corestra.persistence.PersistenceMigrationService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceRegistryTest {

    @Test
    void closeClosesRegisteredServicesExactlyOnce() {
        ServiceRegistry registry = registry();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        registry.registerCloseable(first::incrementAndGet);
        registry.registerCloseable(second::incrementAndGet);

        registry.close();
        registry.close();

        assertEquals(1, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void registerAfterCloseClosesServiceImmediately() {
        ServiceRegistry registry = registry();
        AtomicInteger closed = new AtomicInteger();

        registry.close();
        registry.registerCloseable(closed::incrementAndGet);

        assertEquals(1, closed.get());
    }

    private static ServiceRegistry registry() {
        return new ServiceRegistry(montoyaApi(), stubFlowRepository(), stubFlowEngine());
    }

    private static MontoyaApi montoyaApi() {
        return (MontoyaApi) Proxy.newProxyInstance(
                MontoyaApi.class.getClassLoader(),
                new Class<?>[]{MontoyaApi.class},
                (proxy, method, args) -> objectMethod(proxy, method.getName(), args));
    }

    private static FlowRepository stubFlowRepository() {
        Map<String, String> store = new HashMap<>();
        PersistedObject extensionData = (PersistedObject) Proxy.newProxyInstance(
                PersistedObject.class.getClassLoader(),
                new Class<?>[]{PersistedObject.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getString" -> store.get((String) args[0]);
                    case "setString" -> { store.put((String) args[0], (String) args[1]); yield null; }
                    case "deleteString" -> { store.remove((String) args[0]); yield null; }
                    case "stringKeys" -> Set.copyOf(store.keySet());
                    default -> objectMethod(proxy, method.getName(), args);
                });
        Preferences preferences = (Preferences) Proxy.newProxyInstance(
                Preferences.class.getClassLoader(),
                new Class<?>[]{Preferences.class},
                (proxy, method, args) -> objectMethod(proxy, method.getName(), args));
        Persistence persistence = (Persistence) Proxy.newProxyInstance(
                Persistence.class.getClassLoader(),
                new Class<?>[]{Persistence.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "extensionData" -> extensionData;
                    case "preferences" -> preferences;
                    default -> objectMethod(proxy, method.getName(), args);
                });
        return new FlowRepository(persistence, new FlowSerializer(), new PersistenceMigrationService());
    }

    private static FlowEngine stubFlowEngine() {
        return new FlowEngine(new ExtractionService(), new InjectionService(), request -> null);
    }

    private static Object objectMethod(Object proxy, String name, Object[] args) {
        return switch (name) {
            case "toString" -> proxy.getClass().getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(name);
        };
    }
}

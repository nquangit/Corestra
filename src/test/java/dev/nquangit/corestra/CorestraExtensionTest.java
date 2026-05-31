package dev.nquangit.corestra;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;
import dev.nquangit.corestra.bootstrap.ServiceRegistry;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CorestraExtensionTest {

    @Test
    void initializeSetsExtensionNameAndDelegatesBootstrapWithSameApi() {
        AtomicReference<String> extensionName = new AtomicReference<>();
        AtomicReference<MontoyaApi> delegatedApi = new AtomicReference<>();
        MontoyaApi api = montoyaApi(extensionName);

        CorestraExtension extension = new CorestraExtension(checkedApi -> {
            delegatedApi.set(checkedApi);
            return new ServiceRegistry(checkedApi, stubFlowRepository(), stubFlowEngine());
        });

        extension.initialize(api);

        assertEquals("Corestra", extensionName.get());
        assertSame(api, delegatedApi.get());
    }

    private static MontoyaApi montoyaApi(AtomicReference<String> extensionName) {
        Extension extension = (Extension) Proxy.newProxyInstance(
                Extension.class.getClassLoader(),
                new Class<?>[]{Extension.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method.getName(), args);
                    }

                    return switch (method.getName()) {
                        case "setName" -> {
                            extensionName.set((String) args[0]);
                            yield null;
                        }
                        default -> throw new UnsupportedOperationException(method.toString());
                    };
                });

        return (MontoyaApi) Proxy.newProxyInstance(
                MontoyaApi.class.getClassLoader(),
                new Class<?>[]{MontoyaApi.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return handleObjectMethod(proxy, method.getName(), args);
                    }

                    return switch (method.getName()) {
                        case "extension" -> extension;
                        default -> throw new UnsupportedOperationException(method.toString());
                    };
                });
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
                    default -> handleObjectMethod(proxy, method.getName(), args);
                });
        Preferences preferences = (Preferences) Proxy.newProxyInstance(
                Preferences.class.getClassLoader(),
                new Class<?>[]{Preferences.class},
                (proxy, method, args) -> handleObjectMethod(proxy, method.getName(), args));
        Persistence persistence = (Persistence) Proxy.newProxyInstance(
                Persistence.class.getClassLoader(),
                new Class<?>[]{Persistence.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "extensionData" -> extensionData;
                    case "preferences" -> preferences;
                    default -> handleObjectMethod(proxy, method.getName(), args);
                });
        return new FlowRepository(persistence, new FlowSerializer(), new PersistenceMigrationService());
    }

    private static FlowEngine stubFlowEngine() {
        return new FlowEngine(new ExtractionService(), new InjectionService(), request -> null);
    }

    private static Object handleObjectMethod(Object proxy, String methodName, Object[] args) {
        return switch (methodName) {
            case "toString" -> proxy.getClass().getName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(methodName);
        };
    }
}

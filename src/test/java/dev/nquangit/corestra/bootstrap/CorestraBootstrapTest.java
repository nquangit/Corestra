package dev.nquangit.corestra.bootstrap;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.extension.Extension;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.sessions.SessionHandlingAction;
import burp.api.montoya.persistence.Persistence;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Preferences;
import burp.api.montoya.ui.UserInterface;
import burp.api.montoya.ui.editor.HttpRequestEditor;
import burp.api.montoya.ui.editor.HttpResponseEditor;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import burp.api.montoya.logging.Logging;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@ResourceLock("MontoyaObjectFactory")
class CorestraBootstrapTest {
    private MontoyaObjectFactory previousFactory;

    @BeforeEach
    void installNativeEditorMessageFactory() {
        previousFactory = ObjectFactoryLocator.FACTORY;
        ObjectFactoryLocator.FACTORY = proxy(MontoyaObjectFactory.class, (p, m, a) -> switch (m.getName()) {
            case "httpRequest" -> proxy(HttpRequest.class, (q, n, b) ->
                    n.getName().equals("toString") ? a[a.length - 1].toString() : objectMethod(q, n.getName(), b));
            case "httpResponse" -> proxy(HttpResponse.class, (q, n, b) ->
                    n.getName().equals("toString") ? a[0].toString() : objectMethod(q, n.getName(), b));
            default -> objectMethod(p, m.getName(), a);
        });
    }

    @AfterEach
    void restoreNativeEditorMessageFactory() {
        ObjectFactoryLocator.FACTORY = previousFactory;
    }


    @Test
    void initializeCreatesServiceRegistryWithSharedMontoyaApiSingleton() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "Skipping full bootstrap test in headless environment");
        AtomicReference<ExtensionUnloadingHandler> unloadingHandler = new AtomicReference<>();
        MontoyaApi api = montoyaApi(unloadingHandler);

        SwingUtilities.invokeAndWait(() -> {
            ServiceRegistry registry = new CorestraBootstrap().initialize(api);
            try {
                assertNotNull(registry);
                assertSame(api, registry.montoyaApi());
                assertNotNull(unloadingHandler.get());
            } finally {
                unloadingHandler.get().extensionUnloaded();
            }
        });
    }

    private static MontoyaApi montoyaApi(AtomicReference<ExtensionUnloadingHandler> unloadingHandler) {
        Map<String, String> store = new HashMap<>();

        PersistedObject extensionData = proxy(PersistedObject.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "getString" -> store.get((String) args[0]);
                    case "setString" -> { store.put((String) args[0], (String) args[1]); yield null; }
                    case "deleteString" -> { store.remove((String) args[0]); yield null; }
                    case "stringKeys" -> Set.copyOf(store.keySet());
                    default -> objectMethod(proxy, method.getName(), args);
                });

        Preferences preferences = proxy(Preferences.class,
                (p, m, a) -> objectMethod(p, m.getName(), a));

        Persistence persistence = proxy(Persistence.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "extensionData" -> extensionData;
                    case "preferences" -> preferences;
                    default -> objectMethod(proxy, method.getName(), args);
                });

        HttpRequestEditor requestEditor = proxy(HttpRequestEditor.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "uiComponent" -> new JPanel();
                    case "setRequest", "setSearchExpression" -> null;
                    case "getRequest" -> proxy(HttpRequest.class,
                            (p, m, a) -> objectMethod(p, m.getName(), a));
                    case "isModified" -> false;
                    case "selection" -> Optional.empty();
                    case "caretPosition" -> 0;
                    default -> objectMethod(proxy, method.getName(), args);
                });

        HttpResponseEditor responseEditor = proxy(HttpResponseEditor.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "uiComponent" -> new JPanel();
                    case "setResponse", "setSearchExpression" -> null;
                    case "getResponse" -> proxy(HttpResponse.class,
                            (p, m, a) -> objectMethod(p, m.getName(), a));
                    case "isModified" -> false;
                    case "selection" -> Optional.empty();
                    case "caretPosition" -> 0;
                    default -> objectMethod(proxy, method.getName(), args);
                });

        UserInterface userInterface = proxy(UserInterface.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "applyThemeToComponent" -> null;
                    case "registerSuiteTab", "registerContextMenuItemsProvider" -> registration();
                    case "createHttpRequestEditor" -> requestEditor;
                    case "createHttpResponseEditor" -> responseEditor;
                    case "currentDisplayFont", "currentEditorFont" -> null;
                    default -> objectMethod(proxy, method.getName(), args);
                });

        Extension extension = proxy(Extension.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "registerUnloadingHandler" -> {
                        unloadingHandler.set((ExtensionUnloadingHandler) args[0]);
                        yield registration();
                    }
                    case "setName", "unload" -> null;
                    case "filename" -> "";
                    case "isBapp" -> false;
                    default -> objectMethod(proxy, method.getName(), args);
                });

        Http http = proxy(Http.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "registerSessionHandlingAction" -> {
                        SessionHandlingAction ignored = (SessionHandlingAction) args[0];
                        yield registration();
                    }
                    case "registerHttpHandler" -> {
                        HttpHandler ignored = (HttpHandler) args[0];
                        yield registration();
                    }
                    default -> objectMethod(proxy, method.getName(), args);
                });

        return proxy(MontoyaApi.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "logging" -> proxy(Logging.class, (p, m, a) -> null);
                    case "extension" -> extension;
                    case "http" -> http;
                    case "persistence" -> persistence;
                    case "userInterface" -> userInterface;
                    default -> objectMethod(proxy, method.getName(), args);
                });
    }

    @FunctionalInterface
    private interface ProxyHandler {
        Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, ProxyHandler handler) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(),
                new Class<?>[]{iface},
                (proxy, method, args) -> handler.invoke(proxy, method, args));
    }

    private static Registration registration() {
        return proxy(Registration.class, (proxy, method, args) ->
                switch (method.getName()) {
                    case "isRegistered" -> true;
                    case "deregister" -> null;
                    default -> objectMethod(proxy, method.getName(), args);
                });
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

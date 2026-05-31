package dev.nquangit.corestra.actions;

import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.MessageEditorHttpRequestResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for selection normalization in {@link CorestraContextMenuProvider#collectRequestResponses}.
 */
class CorestraContextMenuProviderTest {

    @Test
    void prefersMessageEditorRequestResponseWhenPresent() {
        HttpRequestResponse editorPair = pair(stubRequest());
        ContextMenuEvent event = event(editorPair, List.of(pair(stubRequest())));

        List<HttpRequestResponse> result = CorestraContextMenuProvider.collectRequestResponses(event);

        assertEquals(1, result.size());
        assertSame(editorPair, result.get(0), "editor selection should take precedence");
    }

    @Test
    void fallsBackToSelectedRequestResponsesWhenNoEditor() {
        HttpRequestResponse a = pair(stubRequest());
        HttpRequestResponse b = pair(stubRequest());
        ContextMenuEvent event = event(null, List.of(a, b));

        List<HttpRequestResponse> result = CorestraContextMenuProvider.collectRequestResponses(event);

        assertEquals(List.of(a, b), result);
    }

    @Test
    void skipsSelectedEntriesWithoutARequest() {
        HttpRequestResponse withReq = pair(stubRequest());
        HttpRequestResponse withoutReq = pair(null);
        ContextMenuEvent event = event(null, List.of(withoutReq, withReq));

        List<HttpRequestResponse> result = CorestraContextMenuProvider.collectRequestResponses(event);

        assertEquals(1, result.size());
        assertSame(withReq, result.get(0));
    }

    @Test
    void returnsEmptyWhenNothingSelected() {
        ContextMenuEvent event = event(null, List.of());

        assertTrue(CorestraContextMenuProvider.collectRequestResponses(event).isEmpty());
    }

    // ── proxy-based Montoya fakes ──────────────────────────────────────────────

    private static ContextMenuEvent event(HttpRequestResponse editorPair, List<HttpRequestResponse> selected) {
        Optional<MessageEditorHttpRequestResponse> editor = editorPair == null
                ? Optional.empty()
                : Optional.of(messageEditor(editorPair));
        return proxy(ContextMenuEvent.class, (p, m, a) -> switch (m.getName()) {
            case "messageEditorRequestResponse" -> editor;
            case "selectedRequestResponses" -> selected;
            default -> objectMethod(p, m.getName(), a);
        });
    }

    private static MessageEditorHttpRequestResponse messageEditor(HttpRequestResponse pair) {
        return proxy(MessageEditorHttpRequestResponse.class, (p, m, a) -> switch (m.getName()) {
            case "requestResponse" -> pair;
            default -> objectMethod(p, m.getName(), a);
        });
    }

    private static HttpRequestResponse pair(HttpRequest request) {
        return proxy(HttpRequestResponse.class, (p, m, a) -> switch (m.getName()) {
            case "request" -> request;
            default -> objectMethod(p, m.getName(), a);
        });
    }

    private static HttpRequest stubRequest() {
        return proxy(HttpRequest.class, (p, m, a) -> objectMethod(p, m.getName(), a));
    }

    @FunctionalInterface
    private interface Handler {
        Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> iface, Handler handler) {
        return (T) Proxy.newProxyInstance(
                iface.getClassLoader(), new Class<?>[]{iface},
                (p, m, a) -> handler.invoke(p, m, a));
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

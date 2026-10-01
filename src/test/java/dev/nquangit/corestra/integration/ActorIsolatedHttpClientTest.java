package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActorIsolatedHttpClientTest {
    @Test
    void forwardsTheExactActorPreparedRequestToTheUndecoratedTransport() {
        HttpRequest request = proxy(HttpRequest.class);
        HttpResponse response = proxy(HttpResponse.class);
        AtomicReference<HttpRequest> received = new AtomicReference<>();
        ActorIsolatedHttpClient client = new ActorIsolatedHttpClient(candidate -> {
            received.set(candidate);
            return response;
        });

        assertSame(response, client.send(request));
        assertSame(request, received.get());
    }

    @Test
    void requiresAnExplicitUndecoratedTransport() {
        assertThrows(NullPointerException.class, () -> new ActorIsolatedHttpClient(null));
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[]{type}, (instance, method, arguments) -> null));
    }
}

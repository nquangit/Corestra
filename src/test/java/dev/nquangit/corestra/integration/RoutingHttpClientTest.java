package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.persistence.ProjectSettings;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoutingHttpClientTest {

    @Test
    void standardModeDelegatesToStandardClientOnly() {
        AtomicReference<ProjectSettings> settings = new AtomicReference<>(ProjectSettings.defaults());
        CountingClient standard = new CountingClient();
        CountingClient proxy = new CountingClient();
        RoutingHttpClient client = new RoutingHttpClient(settings::get, standard, proxy);

        client.send(null);

        assertEquals(1, standard.count());
        assertEquals(0, proxy.count());
    }

    @Test
    void proxyPipelineModeDelegatesToProxyClientOnly() {
        AtomicReference<ProjectSettings> settings = new AtomicReference<>(
                new ProjectSettings(HttpSendMode.PROXY_PIPELINE, "127.0.0.1", 8080));
        CountingClient standard = new CountingClient();
        CountingClient proxy = new CountingClient();
        RoutingHttpClient client = new RoutingHttpClient(settings::get, standard, proxy);

        client.send(null);

        assertEquals(0, standard.count());
        assertEquals(1, proxy.count());
    }

    @Test
    void changingSettingsAffectsSubsequentSends() {
        AtomicReference<ProjectSettings> settings = new AtomicReference<>(ProjectSettings.defaults());
        CountingClient standard = new CountingClient();
        CountingClient proxy = new CountingClient();
        RoutingHttpClient client = new RoutingHttpClient(settings::get, standard, proxy);

        client.send(null);
        settings.set(new ProjectSettings(HttpSendMode.PROXY_PIPELINE, "127.0.0.1", 8080));
        client.send(null);

        assertEquals(1, standard.count());
        assertEquals(1, proxy.count());
    }

    private static final class CountingClient implements HttpClient {
        private int count;

        @Override
        public HttpResponse send(HttpRequest request) {
            count++;
            return (HttpResponse) Proxy.newProxyInstance(
                    HttpResponse.class.getClassLoader(),
                    new Class<?>[]{HttpResponse.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "statusCode" -> (short) 200;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        int count() {
            return count;
        }
    }
}

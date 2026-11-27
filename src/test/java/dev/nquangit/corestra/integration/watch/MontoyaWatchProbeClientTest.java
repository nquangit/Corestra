package dev.nquangit.corestra.integration.watch;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.internal.MontoyaObjectFactory;
import burp.api.montoya.internal.ObjectFactoryLocator;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.integration.CorestraTrafficOrigin;
import dev.nquangit.corestra.integration.ManualTestMessages;
import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchProbe;
import dev.nquangit.corestra.watch.WatchProbeClient.ProbeResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@ResourceLock("MontoyaObjectFactory")
class MontoyaWatchProbeClientTest {
    private MontoyaObjectFactory previous;
    private final List<HttpRequest> sent = new CopyOnWriteArrayList<>();
    private final List<Long> timeouts = new CopyOnWriteArrayList<>();
    private MontoyaWatchProbeClient client;

    @BeforeEach void setup() {
        previous = ObjectFactoryLocator.FACTORY;
        ObjectFactoryLocator.FACTORY = ManualTestMessages.factory();
    }

    @AfterEach void tearDown() {
        try { if (client != null) client.close(); }
        finally { ObjectFactoryLocator.FACTORY = previous; }
    }

    private static WatchDefinition definition(String path) {
        return WatchDefinition.empty("w", "W").withProbe(new WatchProbe(
                new StoredRequestTemplate("GET", "https", "bank.test", 443, path, List.of("Accept: application/json"), ""),
                List.of("Cookie")));
    }

    @Test void rendersSendsAsCorestraOriginAndMapsResponse() {
        CorestraTrafficOrigin origin = new CorestraTrafficOrigin();
        origin.activate();
        List<Boolean> markedDuringSend = new CopyOnWriteArrayList<>();
        client = new MontoyaWatchProbeClient((request, timeout) -> {
            sent.add(request);
            timeouts.add(timeout);
            markedDuringSend.add(origin.hasActiveMarker(request));
            return ManualTestMessages.response("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"balance\":1}");
        }, origin);

        ProbeResponse response = client.fetch(definition("/accounts/{{acct}}"), Map.of("acct", "0123"),
                List.of("Cookie: sid=live"), 2_000);

        assertNull(response.error());
        assertEquals(200, response.statusCode());
        assertEquals(List.of("Content-Type: application/json"), response.headers());
        assertEquals("{\"balance\":1}", response.body());
        HttpRequest request = sent.getFirst();
        assertEquals("/accounts/0123", request.path());
        assertEquals("sid=live", request.headerValue("Cookie"));
        assertEquals("bank.test", request.headerValue("Host"));
        assertEquals("bank.test", request.httpService().host());
        assertEquals(List.of(true), markedDuringSend, "probe must be marked as Corestra-origin traffic");
        assertEquals(List.of(2_000L), timeouts);
    }

    @Test void unresolvedPlaceholderFailsWithoutSending() {
        client = new MontoyaWatchProbeClient((request, timeout) -> { sent.add(request); return null; }, null);
        ProbeResponse response = client.fetch(definition("/accounts/{{acct}}"), Map.of(), List.of(), 1_000);
        assertEquals(0, response.statusCode());
        assertEquals("unresolved probe placeholder(s): {{acct}}", response.error());
        assertTrue(sent.isEmpty());
    }

    @Test void unconfiguredProbeFails() {
        client = new MontoyaWatchProbeClient((request, timeout) -> null, null);
        assertEquals("probe request is not configured",
                client.fetch(WatchDefinition.empty("w", "W"), Map.of(), List.of(), 1_000).error());
        assertEquals("probe request is not configured", client.fetch(null, Map.of(), List.of(), 1_000).error());
    }

    @Test void transportFailureAndNullResponseBecomeErrors() {
        client = new MontoyaWatchProbeClient((request, timeout) -> { throw new IllegalStateException("connection refused"); }, null);
        assertEquals("probe send failed: connection refused", client.fetch(definition("/a"), Map.of(), List.of(), 1_000).error());
        client.close();
        client = new MontoyaWatchProbeClient((request, timeout) -> null, null);
        assertEquals("probe received no response", client.fetch(definition("/a"), Map.of(), List.of(), 1_000).error());
    }

    @Test void hungTransportIsBoundedByTimeout() {
        CountDownLatch release = new CountDownLatch(1);
        client = new MontoyaWatchProbeClient((request, timeout) -> {
            try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return null;
        }, null);
        try {
            ProbeResponse response = assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> client.fetch(definition("/a"), Map.of(), List.of(), 100));
            assertEquals("probe timed out after 100 ms", response.error());
        } finally {
            release.countDown();
        }
    }

    @Test void closedClientFailsWithoutThrowing() {
        client = new MontoyaWatchProbeClient((request, timeout) -> { sent.add(request); return null; }, null);
        client.close();
        assertEquals("probe client is closed", client.fetch(definition("/a"), Map.of(), List.of(), 1_000).error());
        assertTrue(sent.isEmpty());
    }
}

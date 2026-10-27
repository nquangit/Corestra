package dev.nquangit.corestra.integration;

import burp.api.montoya.http.handler.*;
import burp.api.montoya.http.message.requests.HttpRequest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Recognizes active Corestra sends across Burp callback threads and removes their private routing marker. */
public final class CorestraTrafficOrigin implements HttpHandler, AutoCloseable {
    /** Reserved internal header; stripped from outbound traffic even when its value is stale or forged. */
    public static final String HEADER = "X-Corestra-Internal-Send";
    private static final int MAX_SENDS = 2_048;
    private static final int MAX_MESSAGES_PER_SEND = 256;
    private final Map<String, Lease> sends = new HashMap<>();
    private final Map<Integer, Lease> messages = new HashMap<>();
    private boolean registered;
    private boolean closed;

    /** Called after Burp has registered this handler, before any marked request may be sent. */
    public synchronized void activate() { if (!closed) registered = true; }

    /** Marks one native send for its entire synchronous lifetime, then releases every correlated message ID. */
    public <T> T send(HttpRequest request, Function<HttpRequest, T> transport) {
        Lease lease = open();
        try {
            HttpRequest marked;
            try { marked = stripMarker(request).withAddedHeader(HEADER, lease.token); }
            catch (RuntimeException failure) {
                throw new HttpTransportException(dev.nquangit.corestra.flow.execution.ExecutionFailureType.REQUEST_PREPARATION,
                        "Could not mark the internal request; request was not sent", failure);
            }
            return transport.apply(marked);
        } finally { release(lease); }
    }

    /** Recognizes a marked request before the stripping handler runs, including session-action callbacks. */
    public synchronized boolean hasActiveMarker(HttpRequest request) {
        return request != null && markerLease(request) != null;
    }

    /** Recognizes a live request after its private header has already been removed by another callback. */
    public synchronized boolean owns(HttpRequestToBeSent request) {
        return messages.containsKey(request.messageId()) || hasActiveMarker(request);
    }

    @Override public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        synchronized (this) {
            Lease lease = markerLease(request);
            if (lease != null) {
                // Stop rather than forward an untracked internal request when lifecycle/bounds forbid it.
                if (closed || (!lease.ids.contains(request.messageId()) && lease.ids.size() >= MAX_MESSAGES_PER_SEND))
                    return RequestToBeSentAction.drop();
                lease.ids.add(request.messageId());
                messages.put(request.messageId(), lease);
            }
        }
        try {
            return RequestToBeSentAction.continueWith(stripMarker(request));
        } catch (RuntimeException failedToStrip) {
            return RequestToBeSentAction.drop();
        }
    }

    private Lease markerLease(HttpRequest request) {
        for (var header : request.headers()) {
            if (HEADER.equalsIgnoreCase(header.name())) {
                Lease lease = sends.get(header.value());
                if (lease != null) return lease;
            }
        }
        return null;
    }

    private static HttpRequest stripMarker(HttpRequest request) {
        long count = request.headers().stream().filter(header -> HEADER.equalsIgnoreCase(header.name())).count();
        for (long i = 0; i < count && request.hasHeader(HEADER); i++) request = request.withRemovedHeader(HEADER);
        if (request.hasHeader(HEADER)) throw new IllegalStateException("Internal send marker could not be removed");
        return request;
    }

    @Override public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        return ResponseReceivedAction.continueWith(response);
    }

    private synchronized Lease open() {
        if (closed) throw new HttpTransportException(dev.nquangit.corestra.flow.execution.ExecutionFailureType.CANCELLED,
                "Corestra extension was unloaded; request was not sent", null);
        if (!registered || sends.size() >= MAX_SENDS)
            throw new HttpTransportException(dev.nquangit.corestra.flow.execution.ExecutionFailureType.API_UNAVAILABLE,
                    "Corestra origin protection is unavailable or at capacity; request was not sent", null);
        var lease = new Lease(UUID.randomUUID().toString());
        sends.put(lease.token, lease);
        return lease;
    }

    private synchronized void release(Lease lease) {
        sends.remove(lease.token, lease);
        for (int id : lease.ids) messages.remove(id, lease);
    }

    /** Blocks new sends; active leases remain recognizable until their transport calls return. */
    @Override public synchronized void close() { closed = true; }

    private static final class Lease {
        private final String token;
        private final Set<Integer> ids = new HashSet<>();
        private Lease(String token) { this.token = token; }
    }
}

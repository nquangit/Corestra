package dev.nquangit.corestra.integration;

import burp.api.montoya.core.Annotations;
import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.retry.GlobalRetryPolicy;
import dev.nquangit.corestra.http.retry.HostCooldownGate;
import dev.nquangit.corestra.http.retry.LiveRetryEngine;
import dev.nquangit.corestra.http.retry.RetryObservation;
import dev.nquangit.corestra.http.retry.RetryWaiter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Applies the project's {@link GlobalRetryPolicy} to live Burp traffic from Proxy, Repeater,
 * Intruder and Scanner.
 *
 * <p>Request side: requests covered by the policy are admitted by message ID and, when their host
 * is cooling down after a rate-limit, wait (bounded by the policy's budget) before being sent.
 * Response side: a retryable response is resent through a Corestra-origin sender and the final
 * response replaces the original for the calling tool, with a note describing what happened.
 *
 * <p>Register this handler <em>before</em> the Session Handler and Auto Trigger so the cooldown
 * wait happens before session injection and downstream handlers see the final response. Its own
 * resends are Corestra-origin traffic, which every Corestra handler skips, so there is no loop.
 * Requests that never get a response (timeouts, dropped connections) never reach this handler and
 * cannot be retried here.
 */
public final class GlobalRetryHttpHandler implements HttpHandler, AutoCloseable {
    private static final int PENDING_LIMIT = 4_096;

    private final Supplier<GlobalRetryPolicy> policy;
    private final LiveRetryEngine engine;
    private final HostCooldownGate gate;
    private final RetryWaiter waiter;
    private final CorestraTrafficOrigin origin;
    private final Map<Integer, Admitted> pending = new LinkedHashMap<>();
    private volatile HttpClient sender;
    private volatile Consumer<String> diagnostics = message -> { };
    private volatile boolean closed;

    private record Admitted(GlobalRetryPolicy policy, String hostKey) { }

    /**
     * Creates the handler.
     *
     * @param policy  current policy, read once per request
     * @param engine  retry loop
     * @param gate    per-host cooldown shared with {@code engine}
     * @param waiter  waits for host cooldowns on the request side
     * @param origin  recognizes Corestra's own sends; may be {@code null} in tests
     */
    public GlobalRetryHttpHandler(Supplier<GlobalRetryPolicy> policy, LiveRetryEngine engine, HostCooldownGate gate,
                                  RetryWaiter waiter, CorestraTrafficOrigin origin) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
        this.origin = origin;
    }

    /**
     * Sets the Corestra-origin transport used for resends. Without it, cooldown waits still apply but
     * responses are never replaced.
     *
     * @param sender transport, or {@code null}
     */
    public void setSender(HttpClient sender) {
        this.sender = sender;
    }

    /**
     * Sets the diagnostics sink.
     *
     * @param sink message consumer
     */
    public void setDiagnostics(Consumer<String> sink) {
        this.diagnostics = Objects.requireNonNull(sink, "sink");
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        try {
            if (closed || (origin != null && origin.owns(request))
                    || AutoTriggerHttpHandler.hasProxyPipelineMarker(request)) {
                return RequestToBeSentAction.continueWith(request);
            }
            GlobalRetryPolicy current = policy.get();
            if (current == null || !current.enabled()) {
                return RequestToBeSentAction.continueWith(request);
            }
            HttpService service = request.httpService();
            String host = service == null ? null : service.host();
            if (!current.coversRequest(request.toolSource().toolType().name(), request.method(), host,
                    request.isInScope())) {
                return RequestToBeSentAction.continueWith(request);
            }
            String hostKey = hostKey(service);
            if (current.hostCooldown()) {
                long remaining = gate.remainingMillis(hostKey);
                if (remaining > 0) waiter.await(Math.min(remaining, current.maxTotalWaitMillis()));
            }
            admit(request.messageId(), new Admitted(current, hostKey));
        } catch (RuntimeException ignored) {
            // Retry is best effort; it must never break live traffic.
        }
        return RequestToBeSentAction.continueWith(request);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        try {
            Admitted admitted = take(response.messageId());
            HttpClient transport = sender;
            if (admitted == null || closed || transport == null) {
                return ResponseReceivedAction.continueWith(response);
            }
            HttpRequest initiating = response.initiatingRequest();
            GlobalRetryPolicy current = admitted.policy();
            LiveRetryEngine.Result<HttpResponse> result = engine.execute(current, admitted.hostKey(), response,
                    candidate -> observe(candidate, current), () -> transport.send(initiating));
            if (result.stop() == LiveRetryEngine.Stop.SEND_FAILED) {
                diagnostics.accept("Corestra global retry: resend to " + admitted.hostKey() + " failed: " + result.detail());
            }
            if (!result.replaced()) {
                if (result.stop() == LiveRetryEngine.Stop.NOT_RETRYABLE) {
                    return ResponseReceivedAction.continueWith(response);
                }
                return ResponseReceivedAction.continueWith(response, withNote(response.annotations(), note(result)));
            }
            return ResponseReceivedAction.continueWith(result.response(), withNote(response.annotations(), note(result)));
        } catch (RuntimeException ignored) {
            return ResponseReceivedAction.continueWith(response);
        }
    }

    /** Stops admitting requests and wakes every waiting thread. */
    @Override
    public void close() {
        closed = true;
        synchronized (pending) {
            pending.clear();
        }
        if (waiter instanceof AutoCloseable closable) {
            try {
                closable.close();
            } catch (Exception ignored) {
                // Nothing else to release.
            }
        }
    }

    static String note(LiveRetryEngine.Result<?> result) {
        StringBuilder text = new StringBuilder("Corestra retry: ");
        text.append(result.resends()).append(result.resends() == 1 ? " resend" : " resends")
                .append(", waited ").append(String.format(Locale.ROOT, "%.1f", result.waitedMillis() / 1000.0)).append('s')
                .append(", final status ").append(result.finalStatus());
        switch (result.stop()) {
            case RECOVERED -> text.append(" (recovered)");
            case RETRIES_EXHAUSTED -> text.append(" (gave up: max retries)");
            case BUDGET_EXCEEDED -> text.append(" (gave up: wait budget)");
            case ABORTED -> text.append(" (stopped: extension unloading)");
            case SEND_FAILED -> text.append(" (gave up: resend failed — ").append(result.detail()).append(')');
            case NOT_RETRYABLE -> { }
        }
        return text.toString();
    }

    private static Annotations withNote(Annotations existing, String note) {
        if (existing == null) return null;
        String notes = existing.notes();
        return existing.withNotes(notes == null || notes.isBlank() ? note : notes + " | " + note);
    }

    private static RetryObservation observe(HttpResponse response, GlobalRetryPolicy policy) {
        String fullMessage = policy.needsFullMessage()
                ? HttpMessageSnapshots.response(response, List.<ExtractRule>of()).regexInput(ExtractRule.RegexSource.FULL_MESSAGE)
                : null;
        return new RetryObservation(response.statusCode(), fullMessage, retryAfter(response.headers()));
    }

    private static String retryAfter(List<HttpHeader> headers) {
        if (headers == null) return null;
        for (HttpHeader header : headers) {
            if ("Retry-After".equalsIgnoreCase(header.name())) return header.value();
        }
        return null;
    }

    private static String hostKey(HttpService service) {
        if (service == null) return "";
        return service.host().toLowerCase(Locale.ROOT) + ":" + service.port();
    }

    private void admit(int messageId, Admitted admitted) {
        synchronized (pending) {
            if (closed) return;
            pending.put(messageId, admitted);
            if (pending.size() > PENDING_LIMIT) {
                pending.remove(pending.keySet().iterator().next());
            }
        }
    }

    private Admitted take(int messageId) {
        synchronized (pending) {
            return pending.remove(messageId);
        }
    }
}

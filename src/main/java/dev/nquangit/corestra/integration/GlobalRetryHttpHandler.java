package dev.nquangit.corestra.integration;

import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.HighlightColor;
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
import dev.nquangit.corestra.http.retry.HostCooldownGate;
import dev.nquangit.corestra.http.retry.LiveRetryEngine;
import dev.nquangit.corestra.http.retry.RetryConfig;
import dev.nquangit.corestra.http.retry.RetryMessage;
import dev.nquangit.corestra.http.retry.RetryProfile;
import dev.nquangit.corestra.http.retry.RetryRequestContext;
import dev.nquangit.corestra.http.retry.RetryRule;
import dev.nquangit.corestra.http.retry.RetryWaiter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Applies the project's retry profiles ({@link RetryConfig}) to live Burp traffic from Proxy,
 * Repeater, Intruder and Scanner.
 *
 * <p>Request side: the first profile covering the request is chosen and remembered by message ID;
 * when the host is cooling down after a rate-limit, the request waits (bounded by the profile's
 * budget) before being sent. Response side: the profile's ordered rules decide. A RETRY rule
 * resends through a Corestra-origin sender and the final response replaces the original for the
 * calling tool; a STOP rule delivers the response unchanged. Any matched rule is recorded in the
 * response notes and may highlight the response.
 *
 * <p>Register this handler <em>before</em> the Session Handler and Auto Trigger so the cooldown
 * wait happens before session injection and downstream handlers see the final response. Its own
 * resends are Corestra-origin traffic, which every Corestra handler skips, so there is no loop.
 * Requests that never get a response (timeouts, dropped connections) never reach this handler and
 * cannot be retried here.
 */
public final class GlobalRetryHttpHandler implements HttpHandler, AutoCloseable {
    private static final int PENDING_LIMIT = 4_096;

    private final Supplier<RetryConfig> config;
    private final LiveRetryEngine engine;
    private final HostCooldownGate gate;
    private final RetryWaiter waiter;
    private final CorestraTrafficOrigin origin;
    private final Map<Integer, Admitted> pending = new LinkedHashMap<>();
    private volatile HttpClient sender;
    private volatile Consumer<String> diagnostics = message -> { };
    private volatile boolean closed;

    private record Admitted(RetryProfile profile, String hostKey, RetryRequestContext request,
                            List<String> requestHeaders) { }

    /**
     * Creates the handler.
     *
     * @param config  current retry configuration, read once per request
     * @param engine  retry loop
     * @param gate    per-host cooldown shared with {@code engine}
     * @param waiter  waits for host cooldowns on the request side
     * @param origin  recognizes Corestra's own sends; may be {@code null} in tests
     */
    public GlobalRetryHttpHandler(Supplier<RetryConfig> config, LiveRetryEngine engine, HostCooldownGate gate,
                                  RetryWaiter waiter, CorestraTrafficOrigin origin) {
        this.config = Objects.requireNonNull(config, "config");
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
            RetryConfig current = config.get();
            if (current == null || !current.enabled()) {
                return RequestToBeSentAction.continueWith(request);
            }
            HttpService service = request.httpService();
            RetryRequestContext context = new RetryRequestContext(request.toolSource().toolType().name(),
                    request.method(), service == null ? null : service.host(), request.path(), request.isInScope());
            Optional<RetryProfile> profile = current.select(context);
            if (profile.isEmpty()) {
                return RequestToBeSentAction.continueWith(request);
            }
            String hostKey = hostKey(service);
            if (profile.get().hostCooldown()) {
                long remaining = gate.remainingMillis(hostKey);
                if (remaining > 0) waiter.await(Math.min(remaining, profile.get().maxTotalWaitMillis()));
            }
            admit(request.messageId(), new Admitted(profile.get(), hostKey, context, headerLines(request.headers())));
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
            RetryProfile profile = admitted.profile();
            LiveRetryEngine.Result<HttpResponse> result = engine.execute(profile, admitted.hostKey(), response,
                    candidate -> view(candidate, admitted), () -> transport.send(initiating));
            if (result.stop() == LiveRetryEngine.Stop.SEND_FAILED) {
                diagnostics.accept("Corestra retry: resend to " + admitted.hostKey() + " failed: " + result.detail());
            }
            if (result.stop() == LiveRetryEngine.Stop.NO_MATCH) {
                return ResponseReceivedAction.continueWith(response);
            }
            Annotations annotations = annotate(response.annotations(), note(profile, result), result.rule());
            return ResponseReceivedAction.continueWith(result.replaced() ? result.response() : response, annotations);
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

    static String note(RetryProfile profile, LiveRetryEngine.Result<?> result) {
        StringBuilder text = new StringBuilder("Corestra retry [").append(profile.name()).append("]: ");
        if (!result.replaced()) {
            text.append("rule '").append(result.rule() == null ? "?" : result.rule().name()).append("' - ");
            return text.append(switch (result.stop()) {
                case STOPPED_BY_RULE -> "stop, not retried";
                case RETRIES_EXHAUSTED -> "not retried (max retries)";
                case BUDGET_EXCEEDED -> "not retried (wait budget)";
                case ABORTED -> "not retried (extension unloading)";
                case SEND_FAILED -> "resend failed: " + result.detail();
                default -> result.stop().name().toLowerCase(Locale.ROOT);
            }).toString();
        }
        text.append(result.resends()).append(result.resends() == 1 ? " resend" : " resends");
        if (result.triggerRule() != null) text.append(" (rule '").append(result.triggerRule().name()).append("')");
        text.append(", waited ").append(String.format(Locale.ROOT, "%.1f", result.waitedMillis() / 1000.0))
                .append("s, final status ").append(result.finalStatus());
        switch (result.stop()) {
            case RECOVERED -> text.append(" - recovered");
            case STOPPED_BY_RULE -> text.append(" - stopped by rule '").append(result.rule().name()).append('\'');
            case RETRIES_EXHAUSTED -> text.append(" - gave up: max retries");
            case BUDGET_EXCEEDED -> text.append(" - gave up: wait budget");
            case ABORTED -> text.append(" - stopped: extension unloading");
            case SEND_FAILED -> text.append(" - gave up: resend failed (").append(result.detail()).append(')');
            case NO_MATCH -> { }
        }
        return text.toString();
    }

    private static Annotations annotate(Annotations existing, String note, RetryRule rule) {
        if (existing == null) return null;
        String notes = existing.notes();
        Annotations updated = existing.withNotes(notes == null || notes.isBlank() ? note : notes + " | " + note);
        if (rule != null && rule.highlight() != RetryRule.Highlight.NONE) {
            updated = updated.withHighlightColor(HighlightColor.valueOf(rule.highlight().name()));
        }
        return updated;
    }

    private static RetryMessage view(HttpResponse response, Admitted admitted) {
        return new RetryMessage(HttpMessageSnapshots.response(response, List.<ExtractRule>of()), response.statusCode(),
                admitted.request().method(), admitted.request().path(), admitted.requestHeaders());
    }

    private static List<String> headerLines(List<HttpHeader> headers) {
        if (headers == null) return List.of();
        return headers.stream().map(header -> header.name() + ": " + header.value()).toList();
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

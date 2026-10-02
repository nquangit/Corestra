package dev.nquangit.corestra.integration;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.requests.HttpRequest;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.http.inject.InjectionService.MutableHttpRequest;
import dev.nquangit.corestra.http.inject.RequestMutationResult;
import dev.nquangit.corestra.session.SessionInjectionPlan;
import dev.nquangit.corestra.session.SessionManager;
import dev.nquangit.corestra.session.SessionProfileSelection;
import dev.nquangit.corestra.session.SessionTrafficContext;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Project-level Session Handler integration for live Burp/Corestra HTTP traffic.
 */
public final class CorestraSessionHttpHandler implements HttpHandler, AutoCloseable {
    private final SessionManager sessionManager;
    private final InjectionService injectionService;
    private final Map<Integer, SessionRequestMarker> requestMarkers = new LinkedHashMap<>();
    private final Clock clock;
    private final int pendingLimit;
    private final long retentionMillis;
    private volatile boolean closed;

    /**
     * Creates a Session Handler HTTP integration.
     *
     * @param sessionManager session manager
     * @param injectionService request mutation service
     */
    public CorestraSessionHttpHandler(SessionManager sessionManager, InjectionService injectionService) {
        this(sessionManager, injectionService, Clock.systemUTC(), 10_000, 600_000);
    }

    CorestraSessionHttpHandler(SessionManager sessionManager, InjectionService injectionService,
                              Clock clock, int pendingLimit, long retentionMillis) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.injectionService = Objects.requireNonNull(injectionService, "injectionService");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (pendingLimit < 1 || retentionMillis < 1) {
            throw new IllegalArgumentException("Pending limit and retention must be positive");
        }
        this.pendingLimit = pendingLimit;
        this.retentionMillis = retentionMillis;
    }

    /**
     * Applies matching session injection rules before a request is sent.
     *
     * @param requestToBeSent outgoing request
     * @return request action
     */
    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent requestToBeSent) {
        try {
            takeGeneration(requestToBeSent.messageId());
            if (closed) {
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }
            if (AutoTriggerHttpHandler.hasProxyPipelineMarker(requestToBeSent)) {
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }
            if (requestToBeSent.toolSource().toolType() == ToolType.EXTENSIONS) {
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }

            SessionTrafficContext context = requestContext(requestToBeSent);
            SessionProfileSelection selection = sessionManager.selectProfile(context);
            if (selection.profile().isEmpty()) {
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }

            SessionInjectionPlan plan = sessionManager.prepareInjectionRules(
                    selection.profile().orElseThrow(), context);
            List<InjectRule> rules = plan.rules();
            if (rules.isEmpty()) {
                rememberGeneration(requestToBeSent.messageId(), plan, context);
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }

            MutableHttpRequest mutable = new MutableHttpRequest(
                    requestToBeSent.method() + " " + requestPath(requestToBeSent) + " "
                            + requestVersion(requestToBeSent),
                    headerLines(requestToBeSent),
                    requestToBeSent.bodyToString());
            RequestMutationResult mutated = injectionService.apply(rules, java.util.Map.of(), mutable);
            HttpRequest modified = HttpRequestMutationAdapter.apply(requestToBeSent, mutated);
            rememberGeneration(requestToBeSent.messageId(), plan, context);
            return RequestToBeSentAction.continueWith(modified);
        } catch (RuntimeException ignored) {
            return RequestToBeSentAction.continueWith(requestToBeSent);
        }
    }

    /**
     * Applies matching session extraction rules after a response is received.
     *
     * @param responseReceived received response
     * @return response action
     */
    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        try {
            // Montoya guarantees the same message ID across request and response callbacks.
            // The initiating request may be a copy, and AutoTrigger removes the proxy marker
            // before transmission. Only requests admitted here may update live session state.
            SessionRequestMarker marker = takeGeneration(responseReceived.messageId());
            if (closed || marker == null) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }
            if (responseReceived.toolSource().toolType() == ToolType.EXTENSIONS) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }
            HttpRequest initiating = responseReceived.initiatingRequest();
            if (AutoTriggerHttpHandler.hasProxyPipelineMarker(initiating)) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }

            sessionManager.applyExtractionRulesIfCurrent(
                    marker.profileId(),
                    marker.authenticationGeneration(),
                    requestContext(initiating, marker.toolTypeName()),
                    requestSnapshot(initiating),
                    responseSnapshot(responseReceived));
        } catch (RuntimeException ignored) {
            // Session handling must never break the live response.
        }
        return ResponseReceivedAction.continueWith(responseReceived);
    }

    private static SessionTrafficContext requestContext(HttpRequestToBeSent request) {
        return requestContext(request, request.toolSource().toolType().name());
    }

    private static SessionTrafficContext requestContext(HttpRequest request, String toolTypeName) {
        return new SessionTrafficContext(
                toolTypeName,
                inScope(request),
                request.method(),
                host(request),
                pathWithoutQuery(request.path()),
                headerLines(request),
                request.bodyToString());
    }

    private static HttpMessageSnapshot requestSnapshot(HttpRequest request) {
        return new HttpMessageSnapshot(
                request.method() + " " + requestPath(request) + " " + requestVersion(request),
                headerLines(request),
                request.bodyToString());
    }

    private static HttpMessageSnapshot responseSnapshot(HttpResponseReceived response) {
        return new HttpMessageSnapshot(
                requestVersion(response) + " " + response.statusCode(),
                response.headers().stream()
                        .map(header -> header.name() + ": " + header.value())
                        .collect(Collectors.toList()),
                response.bodyToString());
    }

    private static List<String> headerLines(HttpRequest request) {
        return request.headers().stream()
                .map(header -> header.name() + ": " + header.value())
                .collect(Collectors.toList());
    }

    private static boolean inScope(HttpRequest request) {
        try {
            return request != null && request.isInScope();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String host(HttpRequest request) {
        return request != null && request.httpService() != null ? request.httpService().host() : "";
    }

    private static String requestPath(HttpRequest request) {
        return request != null && request.path() != null && !request.path().isBlank() ? request.path() : "/";
    }

    private static String pathWithoutQuery(String pathAndQuery) {
        String path = pathAndQuery == null || pathAndQuery.isBlank() ? "/" : pathAndQuery;
        int queryStart = path.indexOf('?');
        return queryStart >= 0 ? path.substring(0, queryStart) : path;
    }

    private static String requestVersion(HttpRequest request) {
        String version = request.httpVersion();
        return version == null || version.isBlank() ? "HTTP/1.1" : version;
    }

    private static String requestVersion(HttpResponseReceived response) {
        String version = response.httpVersion();
        return version == null || version.isBlank() ? "HTTP/1.1" : version;
    }

    private synchronized void rememberGeneration(int messageId, SessionInjectionPlan plan,
                                                 SessionTrafficContext context) {
        if (!closed && !plan.profileId().isBlank()) {
            pruneExpired();
            requestMarkers.put(messageId, new SessionRequestMarker(
                    plan.profileId(), plan.authenticationGeneration(), context.toolTypeName(), clock.millis()));
            while (requestMarkers.size() > pendingLimit) {
                requestMarkers.remove(requestMarkers.keySet().iterator().next());
            }
        }
    }

    private synchronized SessionRequestMarker takeGeneration(int messageId) {
        pruneExpired();
        return requestMarkers.remove(messageId);
    }

    private void pruneExpired() {
        long now = clock.millis();
        var iterator = requestMarkers.values().iterator();
        while (iterator.hasNext()) {
            if (now - iterator.next().createdAtMillis() < retentionMillis) {
                break;
            }
            iterator.remove();
        }
    }

    /** Releases pending request contexts when the extension unloads. */
    @Override
    public synchronized void close() {
        closed = true;
        requestMarkers.clear();
    }

    private record SessionRequestMarker(String profileId, long authenticationGeneration,
                                        String toolTypeName, long createdAtMillis) {
    }
}

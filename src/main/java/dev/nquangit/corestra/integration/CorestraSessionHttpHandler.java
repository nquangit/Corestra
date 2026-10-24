package dev.nquangit.corestra.integration;

import java.util.function.Predicate;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.requests.HttpRequest;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.http.inject.InjectionService.MutableHttpRequest;
import dev.nquangit.corestra.http.inject.RequestMutationResult;
import dev.nquangit.corestra.session.SessionInjectionPlan;
import dev.nquangit.corestra.session.SessionManager;
import dev.nquangit.corestra.session.SessionProfileSelection;
import dev.nquangit.corestra.session.SessionTrafficContext;

import dev.nquangit.corestra.interaction.InteractionAbortedException;
import dev.nquangit.corestra.session.SessionSelectionService;
import dev.nquangit.corestra.session.SessionSelectionConfig;
import dev.nquangit.corestra.session.SessionGuard;
import java.util.function.Consumer;
import java.time.Clock;
import java.util.Optional;
import dev.nquangit.corestra.session.SessionRequestSession;
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
    private final SessionSelectionService sessionSelector;
    private final Consumer<String> diagnostics;
    private final Predicate<SessionTrafficContext> captureSession;

    /**
     * Creates a Session Handler HTTP integration.
     *
     * @param sessionManager session manager
     * @param injectionService request mutation service
     */
    public CorestraSessionHttpHandler(SessionManager sessionManager, InjectionService injectionService) {
        this(sessionManager, injectionService, null, message -> { }, traffic -> false, Clock.systemUTC(), 10_000, 600_000);
    }

    /** Creates live handling with optional explicit choice and safe failure diagnostics. */
    public CorestraSessionHttpHandler(SessionManager manager, InjectionService injections,
                                     SessionSelectionService selector, Consumer<String> diagnostics) {
        this(manager, injections, selector, diagnostics, traffic -> false);
    }

    /** Captures handoff metadata only for requests matching a trigger that explicitly inherits a session. */
    public CorestraSessionHttpHandler(SessionManager manager, InjectionService injections,
                                     SessionSelectionService selector, Consumer<String> diagnostics,
                                     Predicate<SessionTrafficContext> captureSession) {
        this(manager, injections, Objects.requireNonNull(selector, "selector"), diagnostics, captureSession,
                Clock.systemUTC(), 10_000, 600_000);
    }

    CorestraSessionHttpHandler(SessionManager sessionManager, InjectionService injectionService,
                              Clock clock, int pendingLimit, long retentionMillis) {
        this(sessionManager, injectionService, null, message -> { }, traffic -> false, clock, pendingLimit, retentionMillis);
    }

    private CorestraSessionHttpHandler(SessionManager sessionManager, InjectionService injectionService,
                                       SessionSelectionService selector, Consumer<String> diagnostics,
                                       Predicate<SessionTrafficContext> captureSession,
                                       Clock clock, int pendingLimit, long retentionMillis) {
        this.captureSession = Objects.requireNonNull(captureSession, "captureSession");
        this.sessionSelector = selector;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
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
        boolean choosingSession = false;
        try {
            takeGeneration(requestToBeSent.messageId());
            if (requestToBeSent.hasHeader(CorestraSessionHandlingAction.INTERACTION_ABORT_HEADER)) {
                reportStopped("Prerequisite interaction aborted");
                return RequestToBeSentAction.drop();
            }
            if (closed) {
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }
            if (SessionGuard.isActive() || AutoTriggerHttpHandler.hasProxyPipelineMarker(requestToBeSent)) {
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }
            if (requestToBeSent.toolSource().toolType() == ToolType.EXTENSIONS) {
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }

            SessionTrafficContext context = requestContext(requestToBeSent);
            SessionSelectionConfig choiceConfig = sessionSelector == null ? null : sessionSelector.configuration();
            choosingSession = choiceConfig != null && choiceConfig.matches(context);
            SessionInjectionPlan plan;
            if (choosingSession) {
                plan = sessionSelector.choose(context, choiceConfig);
                if (closed) throw new InteractionAbortedException("Extension unloaded while selecting a session");
            } else {
                SessionProfileSelection selection = sessionManager.selectProfile(context);
                if (selection.profile().isEmpty()) return RequestToBeSentAction.continueWith(requestToBeSent);
                plan = sessionManager.prepareInjectionRules(selection.profile().orElseThrow(), context);
            }
            List<InjectRule> rules = plan.rules();
            if (rules.isEmpty()) {
                rememberGeneration(requestToBeSent.messageId(), plan, context, requestToBeSent);
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }

            MutableHttpRequest mutable = new MutableHttpRequest(
                    requestToBeSent.method() + " " + requestPath(requestToBeSent) + " "
                            + requestVersion(requestToBeSent),
                    choosingSession ? selectionHeaders(requestToBeSent, choiceConfig.stripHeader()) : headerLines(requestToBeSent),
                    requestToBeSent.bodyToString());
            RequestMutationResult mutated = choosingSession
                    ? injectionService.applyRequired(rules, java.util.Map.of(), mutable)
                    : injectionService.apply(rules, java.util.Map.of(), mutable);
            HttpRequest modified = HttpRequestMutationAdapter.apply(requestToBeSent, mutated);
            if (choosingSession && closed) throw new InteractionAbortedException("Extension unloaded while preparing the selected session");
            rememberGeneration(requestToBeSent.messageId(), plan, context, modified);
            return RequestToBeSentAction.continueWith(modified);
        } catch (InteractionAbortedException exception) {
            reportStopped(exception.getMessage());
            return RequestToBeSentAction.drop();
        } catch (RuntimeException ignored) {
            if (choosingSession) {
                reportStopped("Selected session could not be applied; request stopped");
                return RequestToBeSentAction.drop();
            }
            return RequestToBeSentAction.continueWith(requestToBeSent);
        }
    }

    private static List<String> selectionHeaders(HttpRequest request, String stripHeader) {
        return request.headers().stream()
                .filter(header -> stripHeader.isBlank() || !header.name().equalsIgnoreCase(stripHeader))
                .map(header -> header.name() + ": " + header.value()).toList();
    }

    private void reportStopped(String message) {
        try { diagnostics.accept("Corestra stopped request: " + message); }
        catch (RuntimeException ignored) { /* Logging must not override the drop action. */ }
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

            List<ExtractRule> binaryRules = sessionManager.profiles().stream()
                    .filter(profile -> profile.id().equals(marker.profileId()))
                    .flatMap(profile -> profile.extractionRules().stream())
                    .filter(rule -> rule.enabled() && rule.extractorType() == ExtractRule.ExtractorType.BODY_RAW)
                    .map(rule -> rule.toExtractRule()).toList();
            sessionManager.applyExtractionRulesIfCurrent(
                    marker.profileId(),
                    marker.authenticationGeneration(),
                    requestContext(initiating, marker.toolTypeName()),
                    requestSnapshot(initiating, binaryRules),
                    responseSnapshot(responseReceived, binaryRules));
        } catch (RuntimeException ignored) {
            // Session handling must never break the live response.
        }
        return ResponseReceivedAction.continueWith(responseReceived);
    }

    private static SessionTrafficContext requestContext(HttpRequestToBeSent request) {
        return requestContext(request, request.toolSource().toolType().name());
    }

    private static SessionTrafficContext requestContext(HttpRequest request, String toolTypeName) {
        return SessionTrafficContexts.fromRequest(request, toolTypeName);
    }

    private static HttpMessageSnapshot requestSnapshot(HttpRequest request, List<ExtractRule> rules) {
        return new HttpMessageSnapshot(
                request.method() + " " + requestPath(request) + " " + requestVersion(request),
                headerLines(request),
                request.bodyToString(), HttpBodyBytes.readForExtraction(request, rules, ExtractRule.SourceSide.REQUEST));
    }

    private static HttpMessageSnapshot responseSnapshot(HttpResponseReceived response, List<ExtractRule> rules) {
        return new HttpMessageSnapshot(
                requestVersion(response) + " " + response.statusCode(),
                response.headers().stream()
                        .map(header -> header.name() + ": " + header.value())
                        .collect(Collectors.toList()),
                response.bodyToString(), HttpBodyBytes.readForExtraction(response, rules, ExtractRule.SourceSide.RESPONSE));
    }

    private static List<String> headerLines(HttpRequest request) {
        return request.headers().stream()
                .map(header -> header.name() + ": " + header.value())
                .collect(Collectors.toList());
    }



    private static String requestPath(HttpRequest request) {
        return request != null && request.path() != null && !request.path().isBlank() ? request.path() : "/";
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
                                                 SessionTrafficContext context, HttpRequest prepared) {
        if (!closed && !plan.profileId().isBlank()) {
            boolean capture = captureSession.test(context);
            if (!capture) {
                var actual = SessionTrafficContexts.fromRequest(prepared, context.toolTypeName());
                capture = captureSession.test(new SessionTrafficContext(actual.toolTypeName(), actual.burpInScope(),
                        actual.method(), actual.host(), actual.path(), SessionRequestSession.matchHeaders(context.headers(), actual.headers()),
                        actual.body(), actual.scheme(), actual.port()));
            }
            pruneExpired();
            requestMarkers.put(messageId, new SessionRequestMarker(
                    plan.profileId(), plan.authenticationGeneration(), context.toolTypeName(), clock.millis(),
                    capture ? new SessionRequestSession(plan, context) : null));
            while (requestMarkers.size() > pendingLimit) {
                requestMarkers.remove(requestMarkers.keySet().iterator().next());
            }
        }
    }

    /** Returns the prepared session for this exact live request and releases selector headers while preserving its response marker. */
    public synchronized Optional<SessionRequestSession> takeSessionForRequest(int messageId) {
        pruneExpired();
        SessionRequestMarker marker = requestMarkers.get(messageId);
        if (closed || marker == null || marker.session() == null) return Optional.empty();
        requestMarkers.put(messageId, new SessionRequestMarker(marker.profileId(), marker.authenticationGeneration(),
                marker.toolTypeName(), marker.createdAtMillis(), null));
        return Optional.of(marker.session());
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
                                        String toolTypeName, long createdAtMillis, SessionRequestSession session) {
    }
}

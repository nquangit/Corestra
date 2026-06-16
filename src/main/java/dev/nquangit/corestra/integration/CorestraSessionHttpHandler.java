package dev.nquangit.corestra.integration;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.HttpService;
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
import dev.nquangit.corestra.session.SessionManager;
import dev.nquangit.corestra.session.SessionProfile;
import dev.nquangit.corestra.session.SessionProfileSelection;
import dev.nquangit.corestra.session.SessionTrafficContext;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Project-level Session Handler integration for live Burp/Corestra HTTP traffic.
 */
public final class CorestraSessionHttpHandler implements HttpHandler {
    private final SessionManager sessionManager;
    private final InjectionService injectionService;

    /**
     * Creates a Session Handler HTTP integration.
     *
     * @param sessionManager session manager
     * @param injectionService request mutation service
     */
    public CorestraSessionHttpHandler(SessionManager sessionManager, InjectionService injectionService) {
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.injectionService = Objects.requireNonNull(injectionService, "injectionService");
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

            SessionProfile profile = sessionManager.ensureAuthenticated(selection.profile().orElseThrow(), context);
            List<InjectRule> rules = sessionManager.renderedInjectionRules(profile, context.toolTypeName());
            if (rules.isEmpty()) {
                return RequestToBeSentAction.continueWith(requestToBeSent);
            }

            List<String> headersWithoutHost = headerLines(requestToBeSent).stream()
                    .filter(header -> !header.toLowerCase().startsWith("host:"))
                    .toList();
            MutableHttpRequest mutable = new MutableHttpRequest(
                    requestToBeSent.method() + " " + requestPath(requestToBeSent) + " HTTP/1.1",
                    headersWithoutHost,
                    requestToBeSent.bodyToString());
            RequestMutationResult mutated = injectionService.apply(rules, java.util.Map.of(), mutable);
            return RequestToBeSentAction.continueWith(rebuildRequest(requestToBeSent, mutated));
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
            if (responseReceived.toolSource().toolType() == ToolType.EXTENSIONS) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }
            HttpRequest initiating = responseReceived.initiatingRequest();
            if (AutoTriggerHttpHandler.hasProxyPipelineMarker(initiating)) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }

            SessionTrafficContext context = requestContext(responseReceived);
            SessionProfileSelection selection = sessionManager.selectProfile(context);
            if (selection.profile().isPresent()) {
                sessionManager.applyExtractionRules(
                        selection.profile().orElseThrow(),
                        context,
                        requestSnapshot(initiating),
                        responseSnapshot(responseReceived));
            }
        } catch (RuntimeException ignored) {
            // Session handling must never break the live response.
        }
        return ResponseReceivedAction.continueWith(responseReceived);
    }

    private static SessionTrafficContext requestContext(HttpRequestToBeSent request) {
        return new SessionTrafficContext(
                request.toolSource().toolType().name(),
                inScope(request),
                request.method(),
                host(request),
                pathWithoutQuery(request.path()),
                headerLines(request),
                request.bodyToString());
    }

    private static SessionTrafficContext requestContext(HttpResponseReceived response) {
        HttpRequest request = response.initiatingRequest();
        return new SessionTrafficContext(
                response.toolSource().toolType().name(),
                inScope(request),
                request.method(),
                host(request),
                pathWithoutQuery(request.path()),
                headerLines(request),
                request.bodyToString());
    }

    private static HttpMessageSnapshot requestSnapshot(HttpRequest request) {
        return new HttpMessageSnapshot(
                request.method() + " " + requestPath(request) + " HTTP/1.1",
                headerLines(request),
                request.bodyToString());
    }

    private static HttpMessageSnapshot responseSnapshot(HttpResponseReceived response) {
        return new HttpMessageSnapshot(
                "HTTP/1.1 " + response.statusCode(),
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

    private static HttpRequest rebuildRequest(HttpRequest original, RequestMutationResult mutated) {
        HttpService service = original.httpService();
        String host = service != null ? service.host() : "";
        int port = service != null ? service.port() : 443;
        boolean secure = service != null && service.secure();
        boolean defaultPort = (secure && port == 443) || (!secure && port == 80);

        StringBuilder raw = new StringBuilder();
        raw.append(mutated.requestLine()).append("\r\n");
        raw.append("Host: ").append(host);
        if (!defaultPort) {
            raw.append(":").append(port);
        }
        raw.append("\r\n");
        for (String header : mutated.headers()) {
            raw.append(header).append("\r\n");
        }
        raw.append("\r\n");
        if (mutated.body() != null && !mutated.body().isEmpty()) {
            raw.append(mutated.body());
        }
        return HttpRequest.httpRequest(service, raw.toString());
    }
}

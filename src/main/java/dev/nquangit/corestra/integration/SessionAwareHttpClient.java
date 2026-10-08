package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.http.inject.InjectionService.MutableHttpRequest;
import dev.nquangit.corestra.http.inject.RequestMutationResult;
import dev.nquangit.corestra.session.SessionGuard;
import dev.nquangit.corestra.session.SessionInjectionPlan;
import dev.nquangit.corestra.session.SessionManager;
import dev.nquangit.corestra.session.SessionProfileSelection;
import dev.nquangit.corestra.session.SessionTrafficContext;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Decorates an {@link HttpClient} with session-token injection before every {@link #send}.
 *
 * <p>When {@link SessionGuard} is already active on the calling thread (e.g. inside an
 * auth-refresh flow), the request is forwarded to the delegate unchanged to prevent infinite
 * recursion.
 *
 * <p>Injection is performed with tool-type {@code "PROXY"} so that Phase-1 prerequisite
 * requests pick up the same session rules that Burp's live PROXY traffic uses.
 */
public final class SessionAwareHttpClient implements HttpClient {

    private final HttpClient delegate;
    private final Supplier<SessionManager> sessionManagerSupplier;
    private final InjectionService injectionService;

    /**
     * Creates a {@code SessionAwareHttpClient}.
     *
     * @param delegate                 the underlying HTTP client to forward requests to
     * @param sessionManagerSupplier   late-bound supplier; may return {@code null} during startup
     * @param injectionService         injection service used to apply session rules
     */
    public SessionAwareHttpClient(
            HttpClient delegate,
            Supplier<SessionManager> sessionManagerSupplier,
            InjectionService injectionService) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.sessionManagerSupplier = Objects.requireNonNull(sessionManagerSupplier, "sessionManagerSupplier");
        this.injectionService = Objects.requireNonNull(injectionService, "injectionService");
    }

    /**
     * Sends {@code request} after applying any matching session injection rules.
     *
     * <p>Bypasses injection when:
     * <ul>
     *   <li>{@link SessionGuard#isActive()} — prevents re-entrant injection during auth refresh</li>
     *   <li>the session manager supplier returns {@code null} — startup safety</li>
     *   <li>no session profile matches the request</li>
     *   <li>the matched profile yields no injection rules</li>
     * </ul>
     *
     * @param request the request to send
     * @return the response
     */
    @Override
    public HttpResponse send(HttpRequest request) {
        return send(request, null);
    }

    /** Preserves a request-specific transport override through this decoration. */
    @Override
    public HttpResponse send(HttpRequest request, HttpSendMode sendModeOverride) {
        // 0. Null guard — forward null requests to the delegate unchanged.
        if (request == null) return delegate.send(request, sendModeOverride);

        // 1. Recursion guard — we're already inside a session-refresh on this thread.
        if (SessionGuard.isActive()) {
            return delegate.send(request, sendModeOverride);
        }

        // 2. Startup safety — manager may not be wired yet.
        SessionManager manager = sessionManagerSupplier.get();
        if (manager == null) {
            return delegate.send(request, sendModeOverride);
        }

        // 3. Build traffic context — always pretend we're PROXY so injection rules for
        //    live PROXY traffic also apply to Phase-1 prerequisite requests.
        SessionTrafficContext context = buildContext(request);

        // 4. Profile selection.
        SessionProfileSelection selection = manager.selectProfile(context);
        if (selection.profile().isEmpty()) {
            return delegate.send(request, sendModeOverride);
        }

        // 5–9. Run with guard active so any auth-refresh HTTP calls don't loop back here.
        try {
            SessionGuard.enter();
            SessionInjectionPlan plan = manager.prepareInjectionRules(
                    selection.profile().orElseThrow(), context);
            List<InjectRule> rules = plan.rules();
            if (rules.isEmpty()) {
                return delegate.send(request, sendModeOverride);
            }
            HttpRequest modified = applyRules(request, rules);
            return delegate.send(modified, sendModeOverride);
        } finally {
            SessionGuard.exit();
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static SessionTrafficContext buildContext(HttpRequest request) {
        return SessionTrafficContexts.fromRequest(request, "PROXY");
    }

    private HttpRequest applyRules(HttpRequest request, List<InjectRule> rules) {
        String method = request.method() != null ? request.method() : "";
        String fullPath = request.path() != null ? request.path() : "/";
        String body = request.bodyToString() != null ? request.bodyToString() : "";

        List<String> headers = request.headers().stream()
                .map(h -> h.name() + ": " + h.value())
                .toList();

        MutableHttpRequest mutable = new MutableHttpRequest(
                method + " " + fullPath + " " + requestVersion(request),
                headers,
                body);

        RequestMutationResult mutated = injectionService.apply(rules, java.util.Map.of(), mutable);
        return HttpRequestMutationAdapter.apply(request, mutated);
    }

    private static String requestVersion(HttpRequest request) {
        String version = request.httpVersion();
        return version == null || version.isBlank() ? "HTTP/1.1" : version;
    }
}

package dev.nquangit.corestra.integration;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import dev.nquangit.corestra.session.SessionTrafficContext;

/** Builds session matching attributes consistently for live and extension-generated traffic. */
public final class SessionTrafficContexts {
    private SessionTrafficContexts() { }

    /** Uses actual service metadata; unavailable metadata stays unknown rather than assuming HTTPS/443. */
    public static SessionTrafficContext fromRequest(HttpRequest request, String toolTypeName) {
        boolean inScope;
        try {
            inScope = request.isInScope();
        } catch (RuntimeException exception) {
            inScope = false;
        }
        HttpService service = request.httpService();
        String scheme = "";
        int port = 0;
        if (service != null) {
            try { port = service.port(); } catch (RuntimeException ignored) { }
            try { scheme = service.secure() ? "https" : "http"; } catch (RuntimeException ignored) { }
        }
        String path = request.path();
        if (path == null || path.isBlank()) path = "/";
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        return new SessionTrafficContext(toolTypeName, inScope, request.method(), service == null ? "" : service.host(),
                path, request.headers().stream().map(header -> header.name() + ": " + header.value()).toList(),
                request.bodyToString(), scheme, port);
    }
}

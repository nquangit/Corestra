package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.util.Objects;

/**
 * Access Testing transport boundary that forwards actor-prepared requests directly to the
 * configured routing transport, bypassing project-wide Session Handler decoration.
 */
public final class ActorIsolatedHttpClient implements HttpClient {
    private final HttpClient delegate;

    /**
     * Creates an actor-isolated transport.
     *
     * @param delegate undecorated routing transport
     */
    public ActorIsolatedHttpClient(HttpClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * Sends the exact actor-prepared request without applying global session state.
     *
     * @param request actor-prepared request
     * @return delegate response
     */
    @Override
    public HttpResponse send(HttpRequest request) {
        return delegate.send(request);
    }
}

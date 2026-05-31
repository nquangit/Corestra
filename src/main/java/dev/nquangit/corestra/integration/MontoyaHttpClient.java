package dev.nquangit.corestra.integration;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.util.Objects;

/**
 * {@link HttpClient} implementation that delegates to the Montoya HTTP API.
 */
public final class MontoyaHttpClient implements HttpClient {

    private final MontoyaApi api;

    /**
     * Constructs a {@code MontoyaHttpClient} backed by the supplied Montoya API.
     *
     * @param api the Montoya API provided by Burp Suite; must not be {@code null}
     * @throws NullPointerException if {@code api} is {@code null}
     */
    public MontoyaHttpClient(MontoyaApi api) {
        this.api = Objects.requireNonNull(api, "api must not be null");
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the underlying transport returns a {@code null}
     *                               {@link HttpRequestResponse} or a {@code null} response
     */
    @Override
    public HttpResponse send(HttpRequest request) {
        HttpRequestResponse reqRes = api.http().sendRequest(request);

        if (reqRes == null) {
            throw new IllegalStateException("sendRequest returned null for request: " + request);
        }

        HttpResponse response = reqRes.response();

        if (response == null) {
            throw new IllegalStateException("No response received for request: " + request);
        }

        return response;
    }
}

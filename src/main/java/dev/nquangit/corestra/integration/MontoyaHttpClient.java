package dev.nquangit.corestra.integration;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.Http;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

import java.util.Objects;
import dev.nquangit.corestra.flow.execution.ExecutionFailureType;

/**
 * {@link HttpClient} implementation that delegates to the Montoya HTTP API.
 */
public final class MontoyaHttpClient implements HttpClient {

    private final MontoyaApi api;
    private final CorestraTrafficOrigin origin;

    /**
     * Constructs a {@code MontoyaHttpClient} backed by the supplied Montoya API.
     *
     * @param api the Montoya API provided by Burp Suite; must not be {@code null}
     * @throws NullPointerException if {@code api} is {@code null}
     */
    public MontoyaHttpClient(MontoyaApi api) {
        this(api, null);
    }

    /** Creates a native sender with a shared, registered cross-thread origin boundary. */
    public MontoyaHttpClient(MontoyaApi api, CorestraTrafficOrigin origin) {
        this.api = Objects.requireNonNull(api, "api must not be null");
        this.origin = origin;
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException if the underlying transport returns a {@code null}
     *                               {@link HttpRequestResponse} or a {@code null} response
     */
    @Override
    public HttpResponse send(HttpRequest request) {
        return origin == null ? sendNative(request) : origin.send(request, this::sendNative);
    }

    private HttpResponse sendNative(HttpRequest request) {
        Http http;
        try {
            http = api.http();
        } catch (RuntimeException exception) {
            throw new HttpTransportException(ExecutionFailureType.API_UNAVAILABLE, "Montoya HTTP API is unavailable; Burp may still be starting, "
                    + "closing the project, or unloading the extension.", exception);
        }
        if (http == null) {
            throw new HttpTransportException(ExecutionFailureType.API_UNAVAILABLE, "Montoya HTTP API is unavailable; Burp may still be starting, "
                    + "closing the project, or unloading the extension.", null);
        }
        HttpRequestResponse reqRes;
        try {
            reqRes = http.sendRequest(request);
        } catch (RuntimeException exception) {
            throw new HttpTransportException(HttpTransportException.classify(exception), "Montoya HTTP send failed; Burp HTTP API may be unavailable during "
                    + "startup, project close, or extension unload.", exception);
        }

        if (reqRes == null) {
            throw new HttpTransportException(ExecutionFailureType.NO_RESPONSE, "sendRequest returned no request/response", null);
        }

        HttpResponse response = reqRes.response();

        if (response == null) {
            throw new HttpTransportException(ExecutionFailureType.NO_RESPONSE, "No response received from Montoya HTTP send", null);
        }

        return response;
    }
}

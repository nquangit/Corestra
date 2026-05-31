package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;

/**
 * Transport layer abstraction for sending HTTP requests.
 * <p>
 * This interface decouples the flow engine from any concrete Burp Suite API,
 * allowing the engine to be tested with a fake implementation.
 */
public interface HttpClient {

    /**
     * Sends an HTTP request and returns the response.
     *
     * @param request the request to send
     * @return the response received
     * @throws RuntimeException if the request cannot be sent
     */
    HttpResponse send(HttpRequest request);
}

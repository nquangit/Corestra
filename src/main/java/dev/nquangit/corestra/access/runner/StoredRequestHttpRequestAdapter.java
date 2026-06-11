package dev.nquangit.corestra.access.runner;

import java.util.Objects;

import burp.api.montoya.http.HttpService;
import burp.api.montoya.http.message.requests.HttpRequest;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

/**
 * Converts access-layer stored request templates into Montoya HTTP requests.
 */
public class StoredRequestHttpRequestAdapter {
    /**
     * Builds a Montoya HTTP request from a stored request template.
     *
     * @param request stored request template
     * @return Montoya HTTP request
     */
    public HttpRequest toHttpRequest(StoredRequestTemplate request) {
        Objects.requireNonNull(request, "request");
        boolean secure = "https".equalsIgnoreCase(request.scheme());
        HttpService service = HttpService.httpService(request.host(), request.port(), secure);
        return HttpRequest.httpRequest(service, rawRequest(request));
    }

    /**
     * Builds raw HTTP request text for evidence.
     *
     * @param request stored request template
     * @return raw HTTP request text
     */
    public String rawRequest(StoredRequestTemplate request) {
        Objects.requireNonNull(request, "request");
        StringBuilder raw = new StringBuilder();
        raw.append(request.method()).append(' ').append(request.pathAndQuery()).append(" HTTP/1.1\r\n");
        raw.append("Host: ").append(hostHeader(request)).append("\r\n");
        for (String header : request.headerLines()) {
            raw.append(header).append("\r\n");
        }
        raw.append("\r\n");
        if (!request.body().isEmpty()) {
            raw.append(request.body());
        }
        return raw.toString();
    }

    private static String hostHeader(StoredRequestTemplate request) {
        boolean defaultPort = ("https".equalsIgnoreCase(request.scheme()) && request.port() == 443)
                || ("http".equalsIgnoreCase(request.scheme()) && request.port() == 80);
        return defaultPort ? request.host() : request.host() + ":" + request.port();
    }
}

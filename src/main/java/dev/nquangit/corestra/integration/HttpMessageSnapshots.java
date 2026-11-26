package dev.nquangit.corestra.integration;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;

import java.util.List;

/**
 * Builds {@link HttpMessageSnapshot}s from Montoya messages identically on every runtime path
 * (flow engine, auto-trigger, session handler, manual testing, rule-editor preview).
 *
 * <p>Regex rules over {@code HEADERS}/{@code FULL_MESSAGE} search the start line, so all paths
 * must render it the same way; previously some included the reason phrase and some did not, so
 * a rule verified in the editor could behave differently in live traffic.
 */
public final class HttpMessageSnapshots {
    private HttpMessageSnapshots() { }

    /**
     * Snapshots a request as {@code METHOD path VERSION}, header lines and body.
     *
     * @param request the request
     * @param rules   rules deciding whether raw body bytes are needed
     * @return the snapshot
     */
    public static HttpMessageSnapshot request(HttpRequest request, List<ExtractRule> rules) {
        return new HttpMessageSnapshot(requestLine(request), headerLines(request.headers()),
                request.bodyToString(), HttpBodyBytes.readForExtraction(request, rules, ExtractRule.SourceSide.REQUEST));
    }

    /**
     * Snapshots a response as {@code VERSION status [reason]}, header lines and body.
     *
     * @param response the response
     * @param rules    rules deciding whether raw body bytes are needed
     * @return the snapshot
     */
    public static HttpMessageSnapshot response(HttpResponse response, List<ExtractRule> rules) {
        return new HttpMessageSnapshot(statusLine(response), headerLines(response.headers()),
                response.bodyToString(), HttpBodyBytes.readForExtraction(response, rules, ExtractRule.SourceSide.RESPONSE));
    }

    /**
     * Returns the request line, defaulting a blank path to {@code /} and a blank version to HTTP/1.1.
     *
     * @param request the request
     * @return the request line
     */
    public static String requestLine(HttpRequest request) {
        String path = request.path() == null || request.path().isBlank() ? "/" : request.path();
        String version = safe(request::httpVersion);
        return request.method() + " " + path + " " + (version.isBlank() ? "HTTP/1.1" : version);
    }

    /**
     * Returns the response status line, omitting a blank reason phrase.
     *
     * @param response the response
     * @return the status line
     */
    public static String statusLine(HttpResponse response) {
        return HttpMessageSnapshot.responseStartLine(safe(response::httpVersion), response.statusCode(),
                safe(response::reasonPhrase));
    }

    private static List<String> headerLines(List<HttpHeader> headers) {
        return headers.stream().map(header -> header.name() + ": " + header.value()).toList();
    }

    // Test adapters and some synthetic messages do not implement every accessor.
    private static String safe(java.util.function.Supplier<String> value) {
        try {
            String result = value.get();
            return result == null ? "" : result;
        } catch (RuntimeException unavailable) {
            return "";
        }
    }
}

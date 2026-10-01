package dev.nquangit.corestra.http.discovery;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.params.HttpParameterType;
import burp.api.montoya.http.message.params.ParsedHttpParameter;
import burp.api.montoya.http.message.requests.HttpRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.integration.ProxyPipelineHttpClient;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Passive HTTP handler that populates a scoped, runtime-only parameter dictionary. */
public final class ParameterDiscoveryHandler implements HttpHandler {
    private static final int MAX_RESPONSE_BODY = 1_000_000;
    private static final int MAX_FIELDS_PER_MESSAGE = 500;
    private static final Pattern HTML_NAME = Pattern.compile(
            "(?is)<(?:input|select|textarea|button)\\b[^>]*?\\bname\\s*=\\s*([\"'])(.*?)\\1");
    private final ParameterDictionary dictionary;
    private final RedactionService redactionService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean inScopeOnly = new AtomicBoolean(true);
    private final AtomicBoolean collectExamples = new AtomicBoolean(false);

    /** Creates the passive collector. */
    public ParameterDiscoveryHandler(ParameterDictionary dictionary, RedactionService redactionService) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.redactionService = Objects.requireNonNull(redactionService, "redactionService");
    }

    /** Enables or disables Burp-scope filtering. */
    public void setInScopeOnly(boolean value) { inScopeOnly.set(value); }

    /** Enables or disables retaining examples; retained examples are key-redacted. */
    public void setCollectExamples(boolean value) {
        collectExamples.set(value);
        if (!value) dictionary.clearExamples();
    }

    /** Returns whether scope filtering is enabled. */
    public boolean inScopeOnly() { return inScopeOnly.get(); }

    /** Returns whether redacted examples are retained. */
    public boolean collectExamples() { return collectExamples.get(); }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        if (eligible(request, request.toolSource().toolType())) dictionary.batch(() -> collectRequest(request));
        return RequestToBeSentAction.continueWith(request);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        HttpRequest request = response.initiatingRequest();
        if (eligible(request, response.toolSource().toolType())) {
            String body = response.bodyToString();
            if (body.length() <= MAX_RESPONSE_BODY) dictionary.batch(() -> collectResponse(request, body));
        }
        return ResponseReceivedAction.continueWith(response);
    }

    private void collectRequest(HttpRequest request) {
        String host = request.httpService() == null ? "" : request.httpService().host();
        String path = request.pathWithoutQuery();
        int count = 0;
        for (ParsedHttpParameter parameter : request.parameters()) {
            if (++count > MAX_FIELDS_PER_MESSAGE) break;
            dictionary.observe(host, path, location(parameter.type()), parameter.name(), example(parameter.name(), parameter.value()));
        }
        for (var header : request.headers()) {
            if (++count > MAX_FIELDS_PER_MESSAGE) break;
            dictionary.observe(host, path, ParameterDictionary.Location.HEADER, header.name(),
                    example(header.name(), header.value()));
        }
    }

    private void collectResponse(HttpRequest request, String body) {
        String host = request.httpService() == null ? "" : request.httpService().host();
        String path = request.pathWithoutQuery();
        String stripped = body.stripLeading();
        if (stripped.startsWith("{") || stripped.startsWith("[")) {
            try {
                int[] count = {0};
                walkJson(mapper.readTree(body), host, path, count, 0);
            } catch (Exception ignored) {
                // Malformed response JSON is ignored.
            }
        }
        Matcher matcher = HTML_NAME.matcher(body);
        int found = 0;
        while (found++ < MAX_FIELDS_PER_MESSAGE && matcher.find()) {
            dictionary.observe(host, path, ParameterDictionary.Location.RESPONSE_HTML, matcher.group(2), "");
        }
    }

    private void walkJson(JsonNode node, String host, String path, int[] count, int depth) {
        if (node == null || depth > 12 || count[0] >= MAX_FIELDS_PER_MESSAGE) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if (count[0]++ >= MAX_FIELDS_PER_MESSAGE) return;
                JsonNode value = entry.getValue();
                dictionary.observe(host, path, ParameterDictionary.Location.RESPONSE_JSON, entry.getKey(),
                        value != null && value.isValueNode() ? example(entry.getKey(), value.asText()) : "");
                walkJson(value, host, path, count, depth + 1);
            });
        } else if (node.isArray()) {
            node.forEach(child -> walkJson(child, host, path, count, depth + 1));
        }
    }

    private boolean eligible(HttpRequest request, ToolType toolType) {
        if (request == null || toolType == ToolType.EXTENSIONS
                || request.hasHeader(ProxyPipelineHttpClient.MARKER_HEADER)) {
            return false;
        }
        if (!inScopeOnly.get()) return true;
        try { return request.isInScope(); }
        catch (RuntimeException ignored) { return false; }
    }

    private String example(String key, String value) {
        return collectExamples.get() ? redactionService.redactValue(key, value) : "";
    }

    private static ParameterDictionary.Location location(HttpParameterType type) {
        if (type == HttpParameterType.URL) return ParameterDictionary.Location.QUERY;
        if (type == HttpParameterType.BODY) return ParameterDictionary.Location.FORM;
        if (type == HttpParameterType.COOKIE) return ParameterDictionary.Location.COOKIE;
        if (type == HttpParameterType.JSON) return ParameterDictionary.Location.JSON;
        if (type == HttpParameterType.XML || type == HttpParameterType.XML_ATTRIBUTE) return ParameterDictionary.Location.XML;
        return ParameterDictionary.Location.MULTIPART;
    }
}

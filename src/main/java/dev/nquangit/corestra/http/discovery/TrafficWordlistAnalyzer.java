package dev.nquangit.corestra.http.discovery;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.http.message.params.HttpParameterType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.access.security.RedactionService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import static dev.nquangit.corestra.http.discovery.WordlistDictionary.Category.*;

/** Extracts vocabulary into separate bounded sets; never saves complete HTTP messages or arbitrary values. */
public final class TrafficWordlistAnalyzer {
    private static final int MAX_FIELDS = 500;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HTML_NAME = Pattern.compile("(?is)<(?:input|select|textarea|button)\\b[^>]*?\\bname\\s*=\\s*([\"'])(.*?)\\1");
    private static final Set<String> VALUE_KEYS = Set.of("action", "mode", "type", "role", "format", "status", "state", "operation", "op", "method", "sort", "order", "direction", "view", "category", "scope", "response_type", "grant_type");
    private final WordlistDictionary words;
    private final WordlistNoiseFilter noise;
    private final RedactionService redaction;
    /** Creates an analyzer sharing the collector's noise policy. */
    public TrafficWordlistAnalyzer(WordlistDictionary words, WordlistNoiseFilter noise, RedactionService redaction) {
        this.words = Objects.requireNonNull(words); this.noise = Objects.requireNonNull(noise); this.redaction = Objects.requireNonNull(redaction);
    }
    /** Collects request field/header names, path vocabulary and optional categorical values. */
    public void request(HttpRequest request, boolean values) {
        String host = request.httpService().host();
        paths(host, request.pathWithoutQuery());
        int count = 0;
        for (var p : request.parameters()) {
            if (++count > MAX_FIELDS) break;
            WordlistDictionary.Category category = category(p.type());
            name(host, category, p.name()); if (values && category != COOKIE_NAMES) value(host, p.name(), p.value());
        }
        for (var h : request.headers()) {
            if (++count > MAX_FIELDS) break;
            if (!h.name().equalsIgnoreCase(dev.nquangit.corestra.integration.ProxyPipelineHttpClient.MARKER_HEADER)) name(host, REQUEST_HEADERS, h.name());
        }
    }
    /** Collects response names; only bounded JSON/HTML text is parsed and headers remain available for binary bodies. */
    public void response(HttpRequest request, HttpResponse response, boolean values) {
        if (response == null) return;
        String host = request.httpService().host(); int count = 0;
        for (var header : response.headers()) {
            if (++count > MAX_FIELDS) break;
            name(host, RESPONSE_HEADERS, header.name());
            if (header.name().equalsIgnoreCase("Set-Cookie")) {
                String pair = header.value().split(";", 2)[0]; int equals = pair.indexOf('=');
                if (equals > 0) name(host, COOKIE_NAMES, pair.substring(0, equals).strip());
            }
        }
        if (response.body().length() > 1_048_576) return;
        String type = Objects.requireNonNullElse(response.headerValue("Content-Type"), "").toLowerCase(Locale.ROOT);
        if (!type.contains("json") && !type.contains("html") && !type.contains("text")) return;
        String body = response.bodyToString(), start = body.stripLeading();
        if (start.startsWith("{") || start.startsWith("[")) {
            try { walk(JSON.readTree(body), host, values, new int[]{0}, 0); } catch (Exception ignored) { /* Invalid JSON is not vocabulary. */ }
        }
        if (type.contains("html")) {
            var matcher = HTML_NAME.matcher(body); int fields = 0;
            while (fields++ < MAX_FIELDS && matcher.find()) name(host, HTML_FORM_NAMES, matcher.group(2));
        }
    }
    private void walk(JsonNode node, String host, boolean values, int[] budget, int depth) {
        if (node == null || depth > 16 || budget[0]++ >= 2000) return;
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext() && budget[0] < 2000) {
                var field = fields.next(); name(host, RESPONSE_JSON_KEYS, field.getKey());
                if (values && field.getValue().isValueNode() && !field.getValue().isNull()) value(host, field.getKey(), field.getValue().asText());
                walk(field.getValue(), host, values, budget, depth + 1);
            }
        } else if (node.isArray()) for (var child : node) { if (budget[0] >= 2000) break; walk(child, host, values, budget, depth + 1); }
    }
    private void name(String host, WordlistDictionary.Category category, String name) { if (noise.cleanName(name)) words.observe(category, name, host); }
    private void value(String host, String key, String value) {
        if (key == null || value == null || !VALUE_KEYS.contains(key.toLowerCase(Locale.ROOT)) || redaction.isSensitiveKey(key)) return;
        if (value.matches("[A-Za-z][A-Za-z0-9_-]{0,39}") && !noise.dynamicSegment(value) && !noise.tainted(value)) words.observe(VALUE_WORDS, value, host);
    }
    private void paths(String host, String path) {
        if (path == null || path.length() > 2048) return;
        List<String> normalized = new ArrayList<>();
        for (String raw : path.split("/", -1)) {
            if (raw.isEmpty()) continue;
            String segment;
            try { segment = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8); } catch (IllegalArgumentException invalid) { return; }
            if (noise.tainted(segment) || segment.equals(".") || segment.equals("..")) return;
            int dot = segment.lastIndexOf('.');
            String extension = dot > 0 && dot < segment.length() - 1 && segment.substring(dot + 1).matches("[A-Za-z]{1,12}") ? segment.substring(dot + 1) : "";
            if (!extension.isEmpty()) words.observe(FILE_EXTENSIONS, extension, host);
            if (noise.dynamicSegment(segment)) { normalized.add("{id}" + (extension.isEmpty() ? "" : "." + extension)); continue; }
            if (!segment.matches("[\\p{L}\\p{N}_~.-]{1,64}")) return;
            normalized.add(segment); words.observe(PATH_SEGMENTS, segment, host);
            if (!extension.isEmpty()) words.observe(FILE_NAMES, segment, host);
        }
        words.observe(URL_PATHS, "/" + String.join("/", normalized) + (path.endsWith("/") && !normalized.isEmpty() ? "/" : ""), host);
    }
    private static WordlistDictionary.Category category(HttpParameterType type) {
        if (type == HttpParameterType.URL) return QUERY_PARAMETERS;
        if (type == HttpParameterType.BODY) return FORM_PARAMETERS;
        if (type == HttpParameterType.COOKIE) return COOKIE_NAMES;
        if (type == HttpParameterType.JSON) return JSON_KEYS;
        if (type == HttpParameterType.XML || type == HttpParameterType.XML_ATTRIBUTE) return XML_NAMES;
        return MULTIPART_NAMES;
    }
}

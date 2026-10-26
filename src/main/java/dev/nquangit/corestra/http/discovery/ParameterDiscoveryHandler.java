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
import burp.api.montoya.http.message.responses.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.integration.ProxyPipelineHttpClient;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Passive HTTP handler that populates a scoped, runtime-only parameter dictionary. */
public final class ParameterDiscoveryHandler implements HttpHandler, AutoCloseable {
    private static final int MAX_RESPONSE_BODY = 1_000_000;
    private static final int MAX_FIELDS_PER_MESSAGE = 500;
    private static final Pattern HTML_NAME = Pattern.compile(
            "(?is)<(?:input|select|textarea|button)\\b[^>]*?\\bname\\s*=\\s*([\"'])(.*?)\\1");
    private final ParameterDictionary dictionary;
    private final RedactionService redactionService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean inScopeOnly = new AtomicBoolean(true);
    private final AtomicBoolean collectExamples = new AtomicBoolean(false);
    private final AtomicBoolean includeRepeater = new AtomicBoolean(false), collectValues = new AtomicBoolean(true), paused = new AtomicBoolean(false);
    private final WordlistDictionary wordlists = new WordlistDictionary();
    private final WordlistNoiseFilter noise = new WordlistNoiseFilter();
    private final TrafficWordlistAnalyzer analyzer;
    private final Object mutationLock = new Object();
    private final AtomicLong generation = new AtomicLong(), dropped = new AtomicLong(), skipped = new AtomicLong();
    // Preserve request-side exclusions when another handler strips a proxy marker before the response.
    private final java.util.LinkedHashSet<Integer> ignoredMessages = new java.util.LinkedHashSet<>();
    private volatile boolean closed;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), r -> { Thread t = new Thread(r, "corestra-vocabulary"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());

    /** Creates the passive collector. */
    public ParameterDiscoveryHandler(ParameterDictionary dictionary, RedactionService redactionService) {
        this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
        this.redactionService = Objects.requireNonNull(redactionService, "redactionService");
        analyzer = new TrafficWordlistAnalyzer(wordlists, noise, redactionService);
    }

    /** Enables or disables Burp-scope filtering. */
    public void setInScopeOnly(boolean value) { inScopeOnly.set(value); }

    /** Enables or disables retaining examples; retained examples are key-redacted. */
    public void setCollectExamples(boolean value) {
        synchronized (mutationLock) { collectExamples.set(value); if (!value) dictionary.clearExamples(); }
    }

    /** Returns whether scope filtering is enabled. */
    public boolean inScopeOnly() { return inScopeOnly.get(); }

    /** Returns whether redacted examples are retained. */
    public boolean collectExamples() { return collectExamples.get(); }

    /** Returns the runtime wordlist corpus. */
    public WordlistDictionary wordlists() { return wordlists; }
    /** Explicitly opts Repeater traffic into collection. Scanner/Intruder/Extensions are always excluded. */
    public void setIncludeRepeater(boolean value) { includeRepeater.set(value); }
    /** Returns whether manually tested Repeater requests are included. */
    public boolean includeRepeater() { return includeRepeater.get(); }
    /** Enables categorical values such as action, role and format; never arbitrary values. */
    public void setCollectValues(boolean value) {
        synchronized (mutationLock) {
            collectValues.set(value);
            if (!value) wordlists.remove(wordlists.snapshot().stream().filter(e -> e.category() == WordlistDictionary.Category.VALUE_WORDS).toList());
        }
    }
    /** Returns whether categorical values are enabled. */
    public boolean collectValues() { return collectValues.get(); }
    /** Pauses new live observations, independently of an explicit history rescan. */
    public void setPaused(boolean value) { paused.set(value); }
    /** Returns whether live collection is paused. */
    public boolean paused() { return paused.get(); }
    /** Counts rejected/failed queue work; collection never blocks sending traffic. */
    public long dropped() { return dropped.get(); }
    /** Counts messages rejected by source/scope/noise checks. */
    public long skipped() { return skipped.get(); }

    /** Clears both dictionaries and invalidates queued work from before the clear. */
    public void clear() {
        synchronized (mutationLock) { generation.incrementAndGet(); dictionary.clear(); wordlists.clear(); skipped.set(0); dropped.set(0); }
    }

    /** Passively processes one saved Proxy record, applying the same scope/noise policy as live traffic. */
    public boolean scanStored(HttpRequest request, HttpResponse response) {
        if (closed || !eligible(request, ToolType.PROXY)) { skipped.incrementAndGet(); return false; }
        return collect(request, response, true, true, generation.get());
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        try {
            if (!paused.get() && eligible(request, request.toolSource().toolType())) {
                takeIgnored(request.messageId()); submit(request, null, true, false, request.messageId());
            } else { ignore(request.messageId()); skipped.incrementAndGet(); }
        }
        catch (RuntimeException unavailable) { dropped.incrementAndGet(); }
        return RequestToBeSentAction.continueWith(request);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        try {
            HttpRequest request = response.initiatingRequest();
            if (takeIgnored(response.messageId())) skipped.incrementAndGet();
            else if (!paused.get() && eligible(request, response.toolSource().toolType())) submit(request, response, false, true, response.messageId()); else skipped.incrementAndGet();
        } catch (RuntimeException unavailable) { dropped.incrementAndGet(); }
        return ResponseReceivedAction.continueWith(response);
    }

    private void submit(HttpRequest request, HttpResponse response, boolean requestSide, boolean responseSide, int messageId) {
        if (closed || request.body().length() > MAX_RESPONSE_BODY || (response != null && response.body().length() > MAX_RESPONSE_BODY)) {
            if (requestSide) ignore(messageId); skipped.incrementAndGet(); return;
        }
        long epoch = generation.get();
        try { worker.execute(() -> {
            try {
                if (responseSide && takeIgnored(messageId)) { skipped.incrementAndGet(); return; }
                if (!collect(request, response, requestSide, responseSide, epoch) && requestSide) ignore(messageId);
            } catch (RuntimeException failed) { if (requestSide) ignore(messageId); dropped.incrementAndGet(); }
        }); }
        catch (RejectedExecutionException busy) { if (requestSide) ignore(messageId); dropped.incrementAndGet(); }
    }

    private void ignore(int id) {
        synchronized (ignoredMessages) {
            ignoredMessages.add(id);
            while (ignoredMessages.size() > 10_000) ignoredMessages.remove(ignoredMessages.iterator().next());
        }
    }
    private boolean takeIgnored(int id) { synchronized (ignoredMessages) { return ignoredMessages.remove(id); } }

    private boolean collect(HttpRequest request, HttpResponse response, boolean requestSide, boolean responseSide, long epoch) {
        if (!noise.accepts(request)) { skipped.incrementAndGet(); return false; }
        synchronized (mutationLock) {
            if (closed || epoch != generation.get()) return false;
            dictionary.batch(() -> {
                if (requestSide) { collectRequest(request); analyzer.request(request, collectValues.get()); }
                if (responseSide && response != null) {
                    analyzer.response(request, response, collectValues.get());
                    if (response.body().length() <= MAX_RESPONSE_BODY) collectResponse(request, response.bodyToString());
                }
            });
        }
        return true;
    }

    // Package-visible synchronization seam used by collector regression tests.
    void awaitIdle() throws Exception { worker.submit(() -> { }).get(5, TimeUnit.SECONDS); }

    /** Stops background work and releases runtime data on extension unload. */
    @Override public void close() {
        closed = true; generation.incrementAndGet(); worker.shutdownNow();
        synchronized (mutationLock) { dictionary.clear(); wordlists.clear(); }
        synchronized (ignoredMessages) { ignoredMessages.clear(); }
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
            for (var child : node) { if (++count[0] >= MAX_FIELDS_PER_MESSAGE) break; walkJson(child, host, path, count, depth + 1); }
        }
    }

    private boolean eligible(HttpRequest request, ToolType toolType) {
        if (request == null || (toolType != ToolType.PROXY && !(toolType == ToolType.REPEATER && includeRepeater.get()))
                || request.httpService() == null
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

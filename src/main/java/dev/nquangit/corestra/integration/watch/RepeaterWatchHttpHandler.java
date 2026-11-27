package dev.nquangit.corestra.integration.watch;

import burp.api.montoya.core.Annotations;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestAction;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.http.extract.ExtractionResult;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.integration.AutoTriggerHttpHandler;
import dev.nquangit.corestra.integration.CorestraTrafficOrigin;
import dev.nquangit.corestra.integration.HttpMessageSnapshots;
import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchEngine;
import dev.nquangit.corestra.watch.WatchResult;
import dev.nquangit.corestra.watch.WatchSnapshot;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Single coordinator {@link HttpHandler} for Watch and Auto Trigger on live traffic.
 *
 * <p>It wraps the {@link AutoTriggerHttpHandler} (when present) so that the order of Watch and
 * auto-trigger work is fixed rather than depending on Burp's handler order. Register this handler
 * <em>instead of</em> the auto-trigger handler. Requests that are not watched are passed to the
 * delegate unchanged and its action is returned as is.</p>
 *
 * <p>For a Repeater request (never Corestra-origin traffic) matching the first enabled
 * {@link WatchDefinition} whose scope matches and whose probe is configured:</p>
 * <ol>
 *   <li>request callback: request variables are extracted from the live request (REQUEST-side
 *       rules, {@link HttpMessageSnapshots}), inherited header lines are collected, the BEFORE
 *       snapshot is captured (blocking, bounded by the probe timeout) — before any auto-trigger
 *       prerequisite runs — and then the delegate handles the request. If the delegate continues
 *       the request, pending state is stored by message id and, when the request is an
 *       auto-trigger, its downstream steps are made synchronous
 *       ({@link AutoTriggerHttpHandler#requireSynchronousDownstream}, bounded by
 *       {@code timing.flowWaitMillis}).</li>
 *   <li>response callback: the delegate handles the response first (live extraction, downstream
 *       steps, aggregation); then AFTER is captured and evaluated with
 *       {@link WatchEngine#complete} (a downstream timeout/failure becomes a note, never a skipped
 *       AFTER), the result is published to the results listener, and the delegate's response and
 *       annotations are decorated per {@link dev.nquangit.corestra.watch.WatchDisplayOptions}.</li>
 * </ol>
 *
 * <p>Callbacks never throw: on an internal error the delegate's action (or the unchanged message)
 * is returned and the problem is reported to the error consumer.</p>
 */
public final class RepeaterWatchHttpHandler implements HttpHandler, AutoCloseable {
    static final int DEFAULT_MAX_PENDING = 256;
    static final long DEFAULT_PENDING_TTL_MILLIS = 10 * 60_000L;

    private final Supplier<List<WatchDefinition>> definitions;
    private final WatchEngine engine;
    private final ExtractionService extraction;
    private final AutoTriggerHttpHandler delegate;
    private final CorestraTrafficOrigin origin;
    private final Consumer<WatchResult> results;
    private final Consumer<String> errors;
    private final Clock clock;
    private final int maxPending;
    private final long pendingTtlMillis;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentHashMap<Integer, PendingWatch> pending = new ConcurrentHashMap<>();

    /**
     * Creates the coordinator.
     *
     * @param definitions supplies the current Watch definitions
     * @param engine      captures and evaluates snapshots
     * @param extraction  applies request-variable extract rules
     * @param delegate    auto-trigger handler to wrap, or null when auto triggers are not used
     * @param origin      shared Corestra origin guard (Corestra-origin requests are never watched), or null
     * @param results     receives every completed {@link WatchResult} (e.g. the UI's recent results)
     * @param errors      receives diagnostics; never used to block traffic
     */
    public RepeaterWatchHttpHandler(Supplier<List<WatchDefinition>> definitions, WatchEngine engine,
                                    ExtractionService extraction, AutoTriggerHttpHandler delegate,
                                    CorestraTrafficOrigin origin, Consumer<WatchResult> results,
                                    Consumer<String> errors) {
        this(definitions, engine, extraction, delegate, origin, results, errors, Clock.systemUTC(),
                DEFAULT_MAX_PENDING, DEFAULT_PENDING_TTL_MILLIS);
    }

    RepeaterWatchHttpHandler(Supplier<List<WatchDefinition>> definitions, WatchEngine engine,
                             ExtractionService extraction, AutoTriggerHttpHandler delegate,
                             CorestraTrafficOrigin origin, Consumer<WatchResult> results,
                             Consumer<String> errors, Clock clock, int maxPending, long pendingTtlMillis) {
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.extraction = Objects.requireNonNull(extraction, "extraction");
        this.delegate = delegate;
        this.origin = origin;
        this.results = Objects.requireNonNull(results, "results");
        this.errors = Objects.requireNonNull(errors, "errors");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxPending < 1) throw new IllegalArgumentException("maxPending must be positive");
        if (pendingTtlMillis < 1L) throw new IllegalArgumentException("pendingTtlMillis must be positive");
        this.maxPending = maxPending;
        this.pendingTtlMillis = pendingTtlMillis;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
        Prepared prepared = null;
        try {
            prepared = prepare(request);
        } catch (RuntimeException failure) {
            report("Watch BEFORE skipped: " + describe(failure));
        }
        RequestToBeSentAction action = delegateRequest(request);
        if (prepared == null) return action;
        try {
            if (action == null || action.action() != RequestAction.CONTINUE) {
                report("Watch [" + prepared.definition.name() + "] skipped: the request was not sent (stopped by auto trigger)");
                return action;
            }
            List<String> notes = new ArrayList<>(prepared.notes);
            notes.addAll(variableChanges(prepared, action.request()));
            AutoTriggerHttpHandler.SynchronousDownstream downstream = delegate == null ? null
                    : delegate.requireSynchronousDownstream(request.messageId(),
                    prepared.definition.timing().flowWaitMillis()).orElse(null);
            store(request.messageId(), new PendingWatch(prepared.definition, prepared.variables,
                    prepared.inheritedHeaders, prepared.before, notes, downstream, clock.millis()));
        } catch (RuntimeException failure) {
            report("Watch [" + prepared.definition.name() + "] skipped: " + describe(failure));
        }
        return action;
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
        PendingWatch watch = null;
        try {
            cleanupExpired();
            if (!closed.get()) watch = pending.remove(response.messageId());
        } catch (RuntimeException failure) {
            report("Watch lookup failed: " + describe(failure));
        }
        ResponseReceivedAction action = delegateResponse(response);
        if (watch == null) return action;
        try {
            List<String> notes = new ArrayList<>(watch.notes);
            downstreamNote(watch.downstream).ifPresent(notes::add);
            WatchResult result = engine.complete(watch.definition, watch.variables, watch.inheritedHeaders,
                    watch.before, notes);
            if (result == null) {
                report("Watch [" + watch.definition.name() + "] produced no result");
                return action;
            }
            try {
                results.accept(result);
            } catch (RuntimeException listener) {
                report("Watch result listener failed: " + describe(listener));
            }
            HttpResponse base = action != null && action.response() != null ? action.response() : response;
            Annotations existing = existingAnnotations(action, response);
            WatchResponseRenderer.Decoration decoration = WatchResponseRenderer.render(result,
                    watch.definition.display(), base.bodyToString());
            return WatchResponseDecorator.apply(decoration, base, existing);
        } catch (RuntimeException failure) {
            report("Watch [" + watch.definition.name() + "] AFTER failed: " + describe(failure));
            return action;
        }
    }

    /** Drops pending watches; later callbacks only delegate. Does not close the delegate. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) pending.clear();
    }

    int pendingCount() {
        cleanupExpired();
        return pending.size();
    }

    // ── request side ─────────────────────────────────────────────────────────

    private Prepared prepare(HttpRequestToBeSent request) {
        if (closed.get()) return null;
        cleanupExpired();
        if (origin != null && origin.owns(request)) return null;
        if (request.toolSource() == null || !request.toolSource().isFromTool(ToolType.REPEATER)) return null;
        WatchDefinition definition = match(request);
        if (definition == null) return null;
        Extracted extracted = extractVariables(definition, request);
        List<String> inherited = inheritedHeaders(definition, request);
        WatchSnapshot before = engine.capture(definition, extracted.variables, inherited);
        if (before == null) before = WatchSnapshot.failed(clock.millis(), 0, "BEFORE capture returned nothing");
        return new Prepared(definition, extracted.variables, inherited, before, extracted.notes);
    }

    private WatchDefinition match(HttpRequest request) {
        List<WatchDefinition> all = definitions.get();
        if (all == null) return null;
        String host = request.httpService() == null ? "" : request.httpService().host();
        String path = request.path() == null ? "" : request.path();
        for (WatchDefinition definition : all) {
            if (definition == null || !definition.enabled() || !definition.probe().configured()) continue;
            try {
                if (WatchScopeMatcher.matches(definition.scope(), request.method(), host, path)) return definition;
            } catch (RuntimeException invalid) {
                report("Watch [" + definition.name() + "] has an invalid scope pattern: " + describe(invalid));
            }
        }
        return null;
    }

    private Extracted extractVariables(WatchDefinition definition, HttpRequest request) {
        Map<String, String> variables = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        List<ExtractRule> rules = definition.requestVariables().stream()
                .filter(rule -> rule != null && rule.enabled() && rule.sourceSide() == ExtractRule.SourceSide.REQUEST)
                .toList();
        if (rules.isEmpty()) return new Extracted(variables, notes);
        HttpMessageSnapshot snapshot = HttpMessageSnapshots.request(request, rules);
        for (ExtractRule rule : rules) {
            ExtractionResult result = extraction.extract(rule, snapshot, null);
            if (result.isSuccess()) {
                variables.put(result.targetVariable(), result.value());
            } else {
                notes.add("request variable '" + rule.targetVariableName() + "' not extracted: " + result.failureReason());
            }
        }
        return new Extracted(variables, notes);
    }

    private static List<String> inheritedHeaders(WatchDefinition definition, HttpRequest request) {
        Set<String> names = definition.probe().inheritHeaders().stream()
                .map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (names.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        for (HttpHeader header : request.headers()) {
            if (names.contains(header.name().toLowerCase(Locale.ROOT))) lines.add(header.name() + ": " + header.value());
        }
        return List.copyOf(lines);
    }

    /**
     * Notes request variables that the auto trigger changed in the request actually sent; the
     * probe and expectations keep using the values captured with BEFORE.
     */
    private List<String> variableChanges(Prepared prepared, HttpRequest sent) {
        if (delegate == null || sent == null || prepared.variables.isEmpty()) return List.of();
        try {
            Map<String, String> after = extractVariables(prepared.definition, sent).variables;
            List<String> notes = new ArrayList<>();
            for (var entry : prepared.variables.entrySet()) {
                String now = after.get(entry.getKey());
                if (now != null && !now.equals(entry.getValue())) {
                    notes.add("request variable '" + entry.getKey() + "' was changed by auto trigger to '" + now
                            + "'; expectations use the original '" + entry.getValue() + "'");
                }
            }
            return notes;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private RequestToBeSentAction delegateRequest(HttpRequestToBeSent request) {
        if (delegate == null) return RequestToBeSentAction.continueWith(request);
        try {
            return delegate.handleHttpRequestToBeSent(request);
        } catch (RuntimeException failure) {
            report("Auto trigger request handling failed: " + describe(failure));
            return RequestToBeSentAction.continueWith(request);
        }
    }

    private synchronized void store(int messageId, PendingWatch watch) {
        if (closed.get()) return;
        cleanupExpired();
        while (pending.size() >= maxPending) {
            Map.Entry<Integer, PendingWatch> oldest = pending.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().createdAtMillis)).orElse(null);
            if (oldest == null) break;
            pending.remove(oldest.getKey(), oldest.getValue());
        }
        pending.put(messageId, watch);
    }

    private void cleanupExpired() {
        long cutoff = clock.millis() - pendingTtlMillis;
        pending.entrySet().removeIf(entry -> entry.getValue().createdAtMillis <= cutoff);
    }

    // ── response side ────────────────────────────────────────────────────────

    private ResponseReceivedAction delegateResponse(HttpResponseReceived response) {
        if (delegate == null) return ResponseReceivedAction.continueWith(response);
        try {
            return delegate.handleHttpResponseReceived(response);
        } catch (RuntimeException failure) {
            report("Auto trigger response handling failed: " + describe(failure));
            return ResponseReceivedAction.continueWith(response);
        }
    }

    private static Optional<String> downstreamNote(AutoTriggerHttpHandler.SynchronousDownstream downstream) {
        if (downstream == null) return Optional.empty();
        if (!downstream.finished()) {
            return Optional.of("downstream flow: outcome unknown (auto-trigger state expired before the response)");
        }
        return downstream.problem().map(problem -> "downstream flow: " + problem);
    }

    private static Annotations existingAnnotations(ResponseReceivedAction action, HttpResponseReceived response) {
        Annotations annotations = null;
        try {
            if (action != null) annotations = action.annotations();
        } catch (RuntimeException ignored) {
            annotations = null;
        }
        if (annotations == null) {
            try {
                annotations = response.annotations();
            } catch (RuntimeException ignored) {
                annotations = null;
            }
        }
        return annotations;
    }

    private void report(String message) {
        try {
            errors.accept(message);
        } catch (RuntimeException ignored) {
            // Diagnostics must never break live traffic.
        }
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    // ── state ────────────────────────────────────────────────────────────────

    private record Extracted(Map<String, String> variables, List<String> notes) { }

    private record Prepared(WatchDefinition definition, Map<String, String> variables, List<String> inheritedHeaders,
                            WatchSnapshot before, List<String> notes) { }

    private record PendingWatch(WatchDefinition definition, Map<String, String> variables, List<String> inheritedHeaders,
                                WatchSnapshot before, List<String> notes,
                                AutoTriggerHttpHandler.SynchronousDownstream downstream, long createdAtMillis) {
        PendingWatch {
            variables = Map.copyOf(variables);
            inheritedHeaders = List.copyOf(inheritedHeaders);
            notes = List.copyOf(notes);
        }
    }
}

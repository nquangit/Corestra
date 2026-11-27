package dev.nquangit.corestra.integration;

import dev.nquangit.corestra.session.SessionTrafficContext;
import dev.nquangit.corestra.session.SessionRequestSession;
import dev.nquangit.corestra.session.SessionExecutionBinding;
import dev.nquangit.corestra.session.SessionBindingException;
import dev.nquangit.corestra.interaction.InteractionAbortedException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;
import burp.api.montoya.http.message.requests.HttpRequest;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StepTriggerRule;
import dev.nquangit.corestra.flow.control.StepActionStatus;
import dev.nquangit.corestra.flow.control.StepActionType;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.execution.FlowInputDiagnostics;
import dev.nquangit.corestra.flow.execution.FlowInputValidator;
import dev.nquangit.corestra.flow.execution.StepExecutionRecord;
import dev.nquangit.corestra.http.extract.ExtractionResult;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.http.inject.InjectionService.MutableHttpRequest;
import dev.nquangit.corestra.http.inject.RequestMutationResult;

/**
 * Montoya {@link HttpHandler} that runs per-step auto-trigger rules and aggregates responses.
 *
 * <p>For each incoming request (excluding Corestra's own EXTENSIONS-sourced requests):
 * <ol>
 *   <li>All enabled flows are scanned for steps whose {@link StepTriggerRule} matches.</li>
 *   <li>When a match is found, prerequisite steps run (Phase 1) and their execution context
 *       is stored in a correlation map keyed by the triggering request.</li>
 *   <li>The matched step's extract/inject rules apply to the live request (Phase 2).</li>
 *   <li>Post-trigger steps run once the live response arrives and its extract rules have been
 *       applied (Phase 3), so that response-extracted variables are available to downstream steps.
 *       They run asynchronously, except when the flow's {@link AggregationPlan} targets the
 *       triggered step and an included downstream step contributes to it: then the callback waits
 *       for them (bounded by {@link #DEFAULT_SYNC_DOWNSTREAM_TIMEOUT_MILLIS}) so their responses
 *       can be shown in the live response. A caller (Watch) can also require the wait for one
 *       message with {@link #requireSynchronousDownstream}, which never changes the live body.</li>
 * </ol>
 *
 * <p>When the response arrives ({@link #handleHttpResponseReceived}), the handler looks up
 * the stored context, then combines, in step order:
 * <ul>
 *   <li>prerequisite responses for steps with {@code includeInAggregation = true},</li>
 *   <li>the triggered step's live response if it also has {@code includeInAggregation = true}, and</li>
 *   <li>downstream responses (up to the trigger's stop step) with {@code includeInAggregation = true};
 *       on downstream timeout, failure or cancellation a {@code [Corestra] ...} marker line is
 *       appended and whatever completed is kept</li>
 * </ul>
 * into a single {@code flowUpdater} call. This avoids the race condition that would occur if
 * aggregation were split across two separate writes.
 *
 * <p>At most one matching step is processed per request (first match wins).
 * Missing inputs and required injection failures stop the live request. Explicit profile/session
 * bindings also stop on preparation failures; legacy unbound transport failures retain passthrough.
 */
public final class AutoTriggerHttpHandler implements HttpHandler, AutoCloseable {
    static final int DEFAULT_MAX_PENDING_TRIGGERS = 2_048;
    static final long DEFAULT_PENDING_TTL_MILLIS = 5 * 60_000L;
    /**
     * Upper bound a response callback waits for downstream steps whose responses are aggregated
     * into the live response (e.g. the Repeater view). Two ProxyPipeline read timeouts (30 s each);
     * on expiry the live response is returned with partial aggregation and a marker line.
     */
    static final long DEFAULT_SYNC_DOWNSTREAM_TIMEOUT_MILLIS = 60_000L;

    private final Supplier<List<FlowDefinition>> flowsSupplier;
    private final FlowEngine flowEngine;
    private final ExtractionService extractionService;
    private final InjectionService injectionService;
    private final Clock clock;
    private final ExecutorService phase3Executor;
    private final int maxPendingTriggers;
    private SessionFlowBindingService sessionBindings;
    private TriggerInputService triggerInputs = new TriggerInputService(
            dev.nquangit.corestra.access.workspace.AccessWorkspace::empty, Clock.systemUTC());
    private java.util.function.Consumer<String> errors = message -> System.err.println("[Corestra] " + message);
    private final long pendingTtlMillis;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long syncDownstreamTimeoutMillis = DEFAULT_SYNC_DOWNSTREAM_TIMEOUT_MILLIS;
    private final AutoTriggerPrerequisiteGate prerequisiteGate = new AutoTriggerPrerequisiteGate();
    private CorestraTrafficOrigin origin;

    /**
     * Holds the Phase-1 execution context for an in-flight triggered request so that
     * {@link #handleHttpResponseReceived} can combine prerequisite and live responses.
     * Keyed by the Montoya message ID shared by a request and its response.
     */
    private final ConcurrentHashMap<Integer, PendingTrigger> pendingTriggers = new ConcurrentHashMap<>();

    /**
     * Creates an auto-trigger handler.
     *
     * @param flowsSupplier     supplies the current set of defined flows
     * @param flowEngine        used to run prerequisite steps
     * @param extractionService used to apply extract rules to the live request
     * @param injectionService  used to apply inject rules to the live request
     */
    public AutoTriggerHttpHandler(
            Supplier<List<FlowDefinition>> flowsSupplier,
            FlowEngine flowEngine,
            ExtractionService extractionService,
            InjectionService injectionService) {
        this(flowsSupplier, flowEngine, extractionService, injectionService, Clock.systemUTC(),
                Executors.newFixedThreadPool(2, daemonThreadFactory()),
                DEFAULT_MAX_PENDING_TRIGGERS, DEFAULT_PENDING_TTL_MILLIS);
    }

    /** Creates a trigger handler that can inherit the initiating request's prepared session. */
    public AutoTriggerHttpHandler(Supplier<List<FlowDefinition>> flows, FlowEngine engine,
                                  ExtractionService extraction, InjectionService injection,
                                  SessionFlowBindingService bindings) {
        this(flows, engine, extraction, injection);
        this.sessionBindings = Objects.requireNonNull(bindings, "bindings");
    }

    /** Creates a trigger handler with current profile inputs and Burp-visible diagnostics. */
    public AutoTriggerHttpHandler(Supplier<List<FlowDefinition>> flows, FlowEngine engine,
                                  ExtractionService extraction, InjectionService injection,
                                  SessionFlowBindingService bindings, TriggerInputService inputs,
                                  java.util.function.Consumer<String> errors) {
        this(flows, engine, extraction, injection);
        this.sessionBindings = bindings;
        this.triggerInputs = Objects.requireNonNull(inputs, "inputs");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    /** Enables foreign-extension triggers while excluding native sends recognized by the shared origin guard. */
    public AutoTriggerHttpHandler(Supplier<List<FlowDefinition>> flows, FlowEngine engine,
                                  ExtractionService extraction, InjectionService injection,
                                  SessionFlowBindingService bindings, TriggerInputService inputs,
                                  java.util.function.Consumer<String> errors, CorestraTrafficOrigin origin) {
        this(flows, engine, extraction, injection, bindings, inputs, errors);
        this.origin = Objects.requireNonNull(origin);
    }

    AutoTriggerHttpHandler(
            Supplier<List<FlowDefinition>> flowsSupplier,
            FlowEngine flowEngine,
            ExtractionService extractionService,
            InjectionService injectionService,
            Clock clock,
            ExecutorService phase3Executor,
            int maxPendingTriggers,
            long pendingTtlMillis) {
        this.flowsSupplier = Objects.requireNonNull(flowsSupplier);
        this.flowEngine = Objects.requireNonNull(flowEngine);
        this.extractionService = Objects.requireNonNull(extractionService);
        this.injectionService = Objects.requireNonNull(injectionService);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.phase3Executor = Objects.requireNonNull(phase3Executor, "phase3Executor");
        if (maxPendingTriggers < 1) {
            throw new IllegalArgumentException("maxPendingTriggers must be positive");
        }
        if (pendingTtlMillis < 1L) {
            throw new IllegalArgumentException("pendingTtlMillis must be positive");
        }
        this.maxPendingTriggers = maxPendingTriggers;
        this.pendingTtlMillis = pendingTtlMillis;
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent req) {
        if (closed.get()) {
            return RequestToBeSentAction.continueWith(req);
        }
        cleanupExpired();
        if (origin != null && origin.owns(req)) return RequestToBeSentAction.continueWith(req);
        if (hasProxyPipelineMarker(req)) {
            return RequestToBeSentAction.continueWith(req.withRemovedHeader(ProxyPipelineHttpClient.MARKER_HEADER));
        }
        if (req.toolSource().toolType() == ToolType.EXTENSIONS && origin == null) {
            return RequestToBeSentAction.continueWith(req);
        }

        String toolTypeName = req.toolSource().toolType().name();
        String host = req.httpService() != null ? req.httpService().host() : "";
        String pathAndQuery = req.path() != null ? req.path() : "";
        int qIdx = pathAndQuery.indexOf('?');
        String path = qIdx >= 0 ? pathAndQuery.substring(0, qIdx) : pathAndQuery;

        List<String> allHeaders = req.headers().stream()
                .map(h -> h.name() + ": " + h.value())
                .collect(Collectors.toList());
        String body = req.bodyToString();

        var initiatingSession = sessionBindings == null ? java.util.Optional.<SessionRequestSession>empty()
                : sessionBindings.liveSession(req.messageId());
        for (FlowDefinition flow : flowsSupplier.get()) {
            if (!flow.enabled()) continue;
            List<FlowStep> steps = flow.steps();
            for (int i = 0; i < steps.size(); i++) {
                FlowStep step = steps.get(i);
                if (!step.enabled()) continue;
                StepTriggerRule rule = step.triggerRule();
                if (!rule.enabled()) continue;
                List<String> matchHeaders = allHeaders;
                if (rule.inheritSession() && sessionBindings != null) {
                    matchHeaders = initiatingSession.map(source -> SessionRequestSession.matchHeaders(source.originalTraffic().headers(), allHeaders)).orElse(allHeaders);
                }
                if (!rule.matchesRequest(toolTypeName, req.method(), host, path, matchHeaders, body)) continue;

                try {
                    HttpRequest modified = processMatch(flow, steps, i, req, allHeaders, body, initiatingSession);
                    return RequestToBeSentAction.continueWith(modified);
                } catch (TriggerInputException invalid) {
                    reportInputFailure(flow, step, req.messageId(), invalid.getMessage());
                    return RequestToBeSentAction.drop();
                } catch (SessionBindingException aborted) {
                    System.err.println("[Corestra] Trigger stopped: " + aborted.getMessage());
                    return RequestToBeSentAction.drop();
                } catch (InteractionAbortedException aborted) {
                    return RequestToBeSentAction.drop();
                } catch (RuntimeException ignored) {
                    if (rule.inputBindings().hasProfiles() || !rule.inputBindings().excludedTargetRuleIds().isEmpty()) {
                        reportInputFailure(flow, step, req.messageId(), "Profile-bound execution failed; request was stopped");
                        return RequestToBeSentAction.drop();
                    }
                    if (rule.inheritSession()) {
                        System.err.println("[Corestra] Trigger stopped: [SESSION_BINDING_ABORTED] Bound request preparation failed; request was not sent");
                        return RequestToBeSentAction.drop();
                    }
                    // Never block the live request due to a trigger failure.
                }
                return RequestToBeSentAction.continueWith(req);
            }
        }

        return RequestToBeSentAction.continueWith(req);
    }

    @Override
    public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived responseReceived) {
        try {
            if (closed.get()) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }
            cleanupExpired();
            burp.api.montoya.http.message.requests.HttpRequest initiating = responseReceived.initiatingRequest();
            if (hasProxyPipelineMarker(initiating)) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }
            PendingTrigger pending = pendingTriggers.remove(responseReceived.messageId());
            if (pending == null) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }

            // Use the exact flow/step matched before sending, even if request fields or the
            // workspace changed in flight. Endpoint keys cannot distinguish concurrent scans.
            FlowDefinition flow = pending.flow;
            List<FlowStep> steps = flow.steps();
            FlowStep triggeredStep = steps.get(pending.matchedStepIndex);
            HttpMessageSnapshot liveResponseSnapshot = HttpMessageSnapshots.response(responseReceived, triggeredStep.extractRules());
            ExecutionContext updatedCtx = applyExtractRulesToContext(
                    triggeredStep, pending.requestSnapshot, liveResponseSnapshot, pending.ctx, ExtractRule.SourceSide.RESPONSE);

            AggregationPlan agg = flow.aggregation();
            // The live response is the only message Burp displays, so it receives the synthetic
            // body only when the plan targets the triggered step.
            boolean aggregateIntoLive = agg.enabled()
                    && (agg.useTriggeredStep() || triggeredStep.id().equals(agg.finalStepId()));
            boolean hasDownstream = pending.stopIdx > pending.matchedStepIndex;

            DownstreamOutcome downstream = DownstreamOutcome.NONE;
            boolean aggregationWaits = hasDownstream && aggregateIntoLive && downstreamContributes(agg, steps, pending);
            SynchronousDownstream required = pending.synchronousDownstream;
            if (hasDownstream && (aggregationWaits || required != null)) {
                // Downstream responses belong in this response (or a caller such as Watch needs them
                // finished), so run them now — still strictly after the live-response extraction
                // above — bounded by the caller's timeout or syncDownstreamTimeoutMillis.
                long timeout = required != null ? required.timeoutMillis : syncDownstreamTimeoutMillis;
                downstream = runDownstreamForAggregation(flow, steps, pending, updatedCtx, triggeredStep,
                        responseReceived.messageId(), timeout);
                if (required != null) required.finish(true, downstream.problem());
                // A wait requested only by the caller must not change the aggregated body.
                if (!aggregationWaits) downstream = DownstreamOutcome.NONE;
            } else if (hasDownstream) {
                try {
                    phase3Executor.execute(() -> {
                    try {
                        runStepRange(flow, steps, pending.matchedStepIndex + 1, pending.stopIdx, updatedCtx);
                    } catch (Exception e) {
                        reportInputFailure(flow, triggeredStep, responseReceived.messageId(), "Downstream execution failed: " + e.getMessage());
                    }
                    });
                } catch (RejectedExecutionException ignored) {
                    // Extension unload raced this callback; downstream work is intentionally cancelled.
                }
            } else if (required != null) {
                required.finish(false, null);
            }

            if (!aggregateIntoLive) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }
            StringBuilder aggregated = new StringBuilder();
            for (int idx = 0; idx < pending.matchedStepIndex; idx++) {
                FlowStep prereq = steps.get(idx);
                if (!prereq.includeInAggregation()) continue;
                List<StepExecutionRecord> recs = updatedCtx.stepHistory().get(prereq.id());
                if (recs == null || recs.isEmpty()) continue;
                StepExecutionRecord rec = recs.getLast();
                appendAggregatedEntry(aggregated, extractBodyFromRaw(rec.receivedResponse()),
                        rec.responseStatusCode(), agg.mode(), prereq.id());
            }
            if (triggeredStep.includeInAggregation()) {
                appendAggregatedEntry(aggregated, responseReceived.bodyToString(),
                        responseReceived.statusCode(), agg.mode(), triggeredStep.id());
            }
            for (int idx = pending.matchedStepIndex + 1; idx <= pending.stopIdx; idx++) {
                FlowStep next = steps.get(idx);
                if (!next.includeInAggregation()) continue;
                StepExecutionRecord rec = downstream.lastRecords().get(next.id());
                if (rec == null) continue;
                appendAggregatedEntry(aggregated, extractBodyFromRaw(rec.receivedResponse()),
                        rec.responseStatusCode(), agg.mode(), next.id());
            }
            if (downstream.marker() != null) {
                aggregated.append(downstream.marker()).append('\n');
            }
            if (!aggregated.isEmpty()) {
                return ResponseReceivedAction.continueWith(responseReceived.withBody(aggregated.toString()));
            }
        } catch (Exception ignored) {
            // Never throw from a Burp handler callback.
        }
        return ResponseReceivedAction.continueWith(responseReceived);
    }

    static boolean hasProxyPipelineMarker(HttpRequest request) {
        return request != null && request.hasHeader(ProxyPipelineHttpClient.MARKER_HEADER);
    }

    // ── Implementation ────────────────────────────────────────────────────────

    private HttpRequest processMatch(
            FlowDefinition flow,
            List<FlowStep> steps,
            int matchedIndex,
            HttpRequestToBeSent req,
            List<String> allHeaders,
            String body,
            java.util.Optional<SessionRequestSession> initiatingSession) {

        FlowStep matchedStep = steps.get(matchedIndex);
        StepTriggerRule rule = matchedStep.triggerRule();
        int stopIdx = resolveStopIndex(steps, matchedIndex, rule.scope(), rule.stopAtStepId());

        flow = TriggerInputService.applyTargetExclusions(flow, matchedIndex, rule.inputBindings());
        steps = flow.steps();
        matchedStep = steps.get(matchedIndex);

        // Phase 1: run prerequisite steps with stored templates.
        Map<String, String> inputs = triggerInputs.resolve(rule.inputBindings());
        TriggerInputService.requireInputs(flow, matchedIndex, stopIdx, inputs);
        ExecutionContext ctx = new ExecutionContext(UUID.randomUUID().toString(), flow.id(),
                Map.of(dev.nquangit.corestra.flow.execution.FlowInputValidator.REQUIRE_INPUTS, "true",
                        dev.nquangit.corestra.flow.execution.FlowInputValidator.OUTPUT_SCOPE, "auth"),
                inputs, "", Map.of(), clock.millis(), 0L);
        if (rule.inheritSession()) {
            if (sessionBindings == null) throw new SessionBindingException("Session binding runtime is unavailable");
            var source = initiatingSession.orElseThrow(() ->
                    new SessionBindingException("No session was prepared for the triggering request"));
            ctx = sessionBindings.bind(ctx, source, rule);
            var binding = SessionExecutionBinding.read(ctx);
            binding.requireDestination(SessionTrafficContexts.fromRequest(req, binding.tool()));
            for (int index = 0; index <= stopIdx; index++) {
                if (index == matchedIndex || !steps.get(index).enabled()) continue;
                var template = steps.get(index).requestTemplate();
                binding.requireDestination(new SessionTrafficContext(binding.tool(), false,
                        template.method(), template.host(), template.pathAndQuery(), template.headerLines(), template.body(),
                        template.scheme(), template.port()));
            }
        }
        ctx = runPrerequisites(flow, steps, matchedIndex, ctx);
        if (closed.get() || Thread.currentThread().isInterrupted()) {
            throw new TriggerInputException("Trigger preparation was cancelled; live request was stopped");
        }

        // Build the request snapshot before storing the pending trigger — it is needed later
        // by handleHttpResponseReceived to apply the trigger step's response-side extract rules.
        String requestLine = req.method() + " " + (req.path() != null ? req.path() : "/")
                + " " + requestVersion(req);
        HttpMessageSnapshot requestSnapshot = new HttpMessageSnapshot(requestLine, allHeaders, body,
                HttpBodyBytes.readForExtraction(req, matchedStep.extractRules(), ExtractRule.SourceSide.REQUEST));

        // Phase 2: apply the trigger step's extract rules to the live request.
        // Only request-side rules run before injection; response extracts wait for the live response.
        HttpMessageSnapshot emptyResponse = new HttpMessageSnapshot("", List.of(), "");
        ctx = applyExtractRulesToContext(matchedStep, requestSnapshot, emptyResponse, ctx, ExtractRule.SourceSide.REQUEST);

        MutableHttpRequest mutable = new MutableHttpRequest(requestLine, allHeaders, body);
        List<String> missing = FlowInputValidator.missingStepVariables(matchedStep, ctx.variables());
        if (!missing.isEmpty()) {
            throw new TriggerInputException("Target injection failed: missing variables: " + String.join(", ", missing)
                    + "; " + FlowInputDiagnostics.describe(flow, matchedStep, ctx));
        }
        RequestMutationResult mutated;
        try {
            mutated = injectionService.applyRequired(matchedStep.injectRules(), ctx.variables(), mutable);
        } catch (IllegalArgumentException invalid) {
            throw new TriggerInputException("Target injection failed: " + invalid.getMessage()
                    + "; " + targetShape(req, body));
        }

        // Phase 3 is deferred to handleHttpResponseReceived so that response-extracted variables
        // are available to downstream steps before they execute.

        HttpRequest modified = HttpRequestMutationAdapter.apply(req, mutated);
        if (rule.inheritSession()) {
            modified = sessionBindings.apply(ctx, modified);
            if (closed.get()) throw new SessionBindingException("Extension unloaded during trigger execution");
            requestSnapshot = HttpMessageSnapshots.request(modified, matchedStep.extractRules());
        }
        // Publish only after prerequisite execution and request preparation both succeed.
        storePending(req.messageId(), new PendingTrigger(
                flow, matchedIndex, stopIdx, ctx, requestSnapshot, clock.millis()));
        return modified;
    }

    // Report enough information about a request dropped before Logger sees it, without
    // including URLs, body contents, authentication headers or parser source excerpts.
    private static String targetShape(HttpRequest request, String body) {
        String method = Objects.requireNonNullElse(request.method(), "");
        if (!method.matches("[A-Z]{1,20}")) method = "OTHER";
        String type = Objects.requireNonNullElse(request.headerValue("Content-Type"), "");
        type = type.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
        if (type.isEmpty()) type = "absent";
        else if (!type.matches("[a-z0-9.+-]{1,64}/[a-z0-9.+-]{1,64}")) type = "other";
        String shape = body == null || body.isBlank() ? "empty"
                : switch (body.stripLeading().charAt(0)) {
                    case '{' -> "object-like";
                    case '[' -> "array-like";
                    default -> "other";
                };
        return "live target [method=" + method + ", contentType=" + type + ", bodyChars="
                + (body == null ? 0 : body.length()) + ", bodyShape=" + shape + "]";
    }

    private void reportInputFailure(FlowDefinition flow, FlowStep step, int messageId, String reason) {
        try { errors.accept("Auto-trigger stopped [" + flow.name() + " / " + step.name() + "] [messageId=" + messageId + "]: " + reason); }
        catch (RuntimeException ignored) { /* Logging must not allow an unsafe request through. */ }
    }

    /**
     * Clears in-flight state and interrupts queued downstream execution during extension unload.
     */
    @Override
    public synchronized void close() {
        if (closed.compareAndSet(false, true)) {
            prerequisiteGate.close();
            pendingTriggers.clear();
            phase3Executor.shutdownNow();
        }
    }

    private ExecutionContext runPrerequisites(FlowDefinition flow, List<FlowStep> steps,
                                              int matchedIndex, ExecutionContext context) {
        boolean hasRetry = steps.subList(0, matchedIndex).stream().filter(FlowStep::enabled)
                .flatMap(step -> step.conditionActionRules().stream()).filter(rule -> rule.enabled())
                .flatMap(rule -> rule.actions().stream()).anyMatch(action -> action.enabled()
                        && (action.type() == StepActionType.RETRY_CURRENT_STEP
                        || action.type() == StepActionType.RETRY_FLOW_FROM_START));
        if (!hasRetry) return runStepRange(flow, steps, 0, matchedIndex - 1, context);
        return prerequisiteGate.run(flow.id(), () -> {
            try {
                return runStepRange(flow, steps, 0, matchedIndex - 1, context);
            } catch (TriggerInputException | SessionBindingException | InteractionAbortedException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new TriggerInputException("Prerequisite recovery failed: " + failure.getMessage());
            }
        });
    }

    int pendingTriggerCount() {
        cleanupExpired();
        return pendingTriggers.size();
    }

    private synchronized void storePending(int messageId, PendingTrigger pending) {
        if (closed.get() || Thread.currentThread().isInterrupted()) {
            throw new TriggerInputException("Trigger preparation was cancelled; live request was stopped");
        }
        cleanupExpired();
        while (pendingTriggers.size() >= maxPendingTriggers) {
            Map.Entry<Integer, PendingTrigger> oldest = pendingTriggers.entrySet().stream()
                    .min(java.util.Comparator.comparingLong(entry -> entry.getValue().createdAtEpochMillis))
                    .orElse(null);
            if (oldest == null) {
                break;
            }
            pendingTriggers.remove(oldest.getKey(), oldest.getValue());
        }
        pendingTriggers.put(messageId, pending);
    }

    private void cleanupExpired() {
        long cutoff = clock.millis() - pendingTtlMillis;
        pendingTriggers.entrySet().removeIf(entry -> entry.getValue().createdAtEpochMillis <= cutoff);
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable, "corestra-auto-trigger-phase3");
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Runs all extract rules in {@code step} against the provided message snapshots and returns
     * an updated context. Successful extractions are merged; failures are silently skipped.
     */
    ExecutionContext applyExtractRulesToContext(
            FlowStep step,
            HttpMessageSnapshot requestSnapshot,
            HttpMessageSnapshot responseSnapshot,
            ExecutionContext ctx) {
        return applyExtractRulesToContext(step, requestSnapshot, responseSnapshot, ctx, null);
    }

    private ExecutionContext applyExtractRulesToContext(
            FlowStep step, HttpMessageSnapshot requestSnapshot, HttpMessageSnapshot responseSnapshot,
            ExecutionContext ctx, ExtractRule.SourceSide side) {
        for (var rule : step.extractRules()) {
            if (side != null && rule.sourceSide() != side) continue;
            ExtractionResult result = extractionService.extract(rule, requestSnapshot, responseSnapshot);
            if (result.isSuccess()) {
                for (var output : dev.nquangit.corestra.flow.execution.FlowInputValidator.scopedOutputs(
                        Map.of(result.targetVariable(), result.value()),
                        ctx.metadata().get(dev.nquangit.corestra.flow.execution.FlowInputValidator.OUTPUT_SCOPE)).entrySet()) {
                    ctx = ctx.withVariable(output.getKey(), output.getValue());
                }
            }
        }
        return ctx;
    }

    /**
     * Returns whether any downstream step in the trigger's range can add content to the live
     * aggregated response. Selected-extract mode adds nothing for live aggregation, so it never
     * needs to wait for downstream steps.
     */
    private static boolean downstreamContributes(AggregationPlan agg, List<FlowStep> steps, PendingTrigger pending) {
        if (agg.mode() == AggregationPlan.Mode.APPEND_SELECTED_EXTRACTS) return false;
        for (int idx = pending.matchedStepIndex + 1; idx <= pending.stopIdx; idx++) {
            FlowStep step = steps.get(idx);
            if (step.enabled() && step.includeInAggregation()) return true;
        }
        return false;
    }

    /**
     * Runs the downstream range on the phase-3 executor and waits up to
     * {@link #syncDownstreamTimeoutMillis} for it. Never throws: timeouts, failures, cancellation
     * and interruption are returned as a partial outcome with a marker line. A timed-out run is
     * left to finish in the background (no mid-flow interruption) and later failures are logged.
     */
    private DownstreamOutcome runDownstreamForAggregation(FlowDefinition flow, List<FlowStep> steps,
                                                          PendingTrigger pending, ExecutionContext ctx,
                                                          FlowStep triggeredStep, int messageId, long timeout) {
        FlowDefinition sliceFlow = sliceFlow(flow, steps, pending.matchedStepIndex + 1, pending.stopIdx);
        java.util.concurrent.CompletableFuture<ExecutionResult> future;
        try {
            future = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> flowEngine.execute(sliceFlow, ctx), phase3Executor);
        } catch (RejectedExecutionException unloading) {
            return DownstreamOutcome.partial(Map.of(),
                    "[Corestra] Downstream steps were cancelled (extension unloading); aggregation is partial.",
                    "cancelled (extension unloading)");
        }
        try {
            ExecutionResult result = future.get(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
            Map<String, StepExecutionRecord> records = lastRecordByStep(result.stepRecords());
            if (result.success()) return new DownstreamOutcome(records, null, null);
            reportInputFailure(flow, triggeredStep, messageId, "Downstream execution failed: " + result.failureMessage());
            return DownstreamOutcome.partial(records,
                    "[Corestra] Downstream steps failed: " + result.failureMessage() + "; aggregation is partial.",
                    "failed: " + result.failureMessage());
        } catch (java.util.concurrent.TimeoutException slow) {
            reportInputFailure(flow, triggeredStep, messageId, "Downstream steps did not complete within "
                    + timeout + " ms; the live response was returned with partial aggregation");
            future.whenComplete((result, failure) -> {
                if (failure != null) {
                    reportInputFailure(flow, triggeredStep, messageId, "Downstream execution failed: " + failure.getMessage());
                } else if (!result.success()) {
                    reportInputFailure(flow, triggeredStep, messageId, "Downstream execution failed: " + result.failureMessage());
                }
            });
            return DownstreamOutcome.partial(Map.of(), "[Corestra] Downstream steps did not complete within "
                    + timeout + " ms; aggregation is partial.",
                    "timeout: not complete within " + timeout + " ms (steps continue in the background)");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return DownstreamOutcome.partial(Map.of(),
                    "[Corestra] Downstream steps were cancelled (callback interrupted); aggregation is partial.",
                    "cancelled (callback interrupted)");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException failed) {
            Throwable cause = failed.getCause() != null ? failed.getCause() : failed;
            reportInputFailure(flow, triggeredStep, messageId, "Downstream execution failed: " + cause.getMessage());
            return DownstreamOutcome.partial(Map.of(),
                    "[Corestra] Downstream steps failed: " + cause.getMessage() + "; aggregation is partial.",
                    "failed: " + cause.getMessage());
        }
    }

    private static Map<String, StepExecutionRecord> lastRecordByStep(List<StepExecutionRecord> records) {
        Map<String, StepExecutionRecord> last = new java.util.HashMap<>();
        if (records != null) for (StepExecutionRecord record : records) last.put(record.stepId(), record);
        return last;
    }

    /**
     * Sets how long a response callback waits for downstream steps whose responses are
     * aggregated into the live response.
     *
     * @param millis positive timeout in milliseconds
     */
    void syncDownstreamTimeoutMillis(long millis) {
        if (millis < 1L) throw new IllegalArgumentException("timeout must be positive");
        this.syncDownstreamTimeoutMillis = millis;
    }

    private static FlowDefinition sliceFlow(FlowDefinition flow, List<FlowStep> steps, int fromIdx, int toIdx) {
        List<FlowStep> slice = new ArrayList<>(steps.subList(fromIdx, toIdx + 1));
        return new FlowDefinition(
                flow.id(), flow.name(), flow.description(), flow.enabled(),
                slice, flow.rules(), AggregationPlan.disabled(), flow.triggerConfig());
    }

    ExecutionContext runStepRange(FlowDefinition flow, List<FlowStep> steps,
                                  int fromIdx, int toIdx, ExecutionContext ctx) {
        if (fromIdx > toIdx) return ctx;
        FlowDefinition sliceFlow = sliceFlow(flow, steps, fromIdx, toIdx);
        ExecutionResult result = flowEngine.execute(sliceFlow, ctx);
        if (!result.success()) {
            if (result.actionRecords().stream().anyMatch(action -> action.status() == StepActionStatus.FAILED
                    && (action.actionType() == StepActionType.RETRY_CURRENT_STEP
                    || action.actionType() == StepActionType.RETRY_FLOW_FROM_START))) {
                throw new TriggerInputException("Prerequisite retry exhausted: " + result.failureMessage());
            }
            if (result.failureMessage() != null && result.failureMessage().contains(
                    dev.nquangit.corestra.flow.execution.FlowInputValidator.FAILURE_PREFIX)) {
                throw new TriggerInputException(result.failureMessage());
            }
            if (SessionBindingException.isAborted(result.failureMessage())) {
                throw new SessionBindingException("Bound step execution failed: " + result.failureMessage());
            }
            if (InteractionAbortedException.isAborted(result.failureMessage())) {
                throw new InteractionAbortedException("Auto-trigger input was aborted");
            }
            if (SessionExecutionBinding.isBound(ctx)) {
                throw new SessionBindingException("Bound prerequisite failed; live request was stopped");
            }
            throw new IllegalStateException("Auto-trigger prerequisite execution failed: " + result.failureMessage());
        }
        // The engine's final context is authoritative. Replaying all attempt records here
        // would resurrect variables deliberately discarded by Retry flow from start.
        return result.finalContext().orElse(ctx);
    }

    /**
     * Appends new response content to the existing aggregated raw value and returns
     * the updated Base64-encoded synthetic HTTP response.
     */
    static String appendToAggregated(String currentRaw, String responseBody,
                                      int statusCode, AggregationPlan.Mode mode, String stepId) {
        String existing = decodeAggregatedBody(currentRaw);
        String toAppend = switch (mode) {
            case APPEND_RAW_RESPONSES -> responseBody == null ? "" : responseBody;
            case APPEND_NORMALIZED_SUMMARIES ->
                    "[Step: " + stepId + "] Status: " + statusCode + "\n"
                    + (responseBody == null ? "" : responseBody);
            case APPEND_SELECTED_EXTRACTS -> "";
        };
        String combined = existing.isEmpty() ? toAppend : existing + toAppend;
        return encodeAggregatedBody(combined);
    }

    /**
     * Extracts the body from a Base64-encoded synthetic HTTP response stored in lastResponseRaw.
     * Returns an empty string when the input is absent or cannot be decoded.
     */
    static String decodeAggregatedBody(String lastResponseRaw) {
        if (lastResponseRaw == null || lastResponseRaw.isEmpty()) return "";
        try {
            String raw = new String(Base64.getDecoder().decode(lastResponseRaw), StandardCharsets.UTF_8);
            int sep = raw.indexOf("\r\n\r\n");
            if (sep >= 0) return raw.substring(sep + 4);
            sep = raw.indexOf("\n\n");
            if (sep >= 0) return raw.substring(sep + 2);
            return raw;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Wraps an aggregated body string in a minimal synthetic HTTP response and Base64-encodes it.
     */
    static String encodeAggregatedBody(String body) {
        String synthetic = "HTTP/1.1 200 OK\r\n\r\n" + (body == null ? "" : body);
        return Base64.getEncoder().encodeToString(synthetic.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Extracts the body from a raw HTTP response string (e.g. from {@code StepExecutionRecord}).
     * Splits on {@code \r\n\r\n} then {@code \n\n}; returns everything after the separator.
     * Returns an empty string when the input is null/empty or has no body separator.
     */
    static String extractBodyFromRaw(String rawResponse) {
        if (rawResponse == null || rawResponse.isEmpty()) return "";
        int sep = rawResponse.indexOf("\r\n\r\n");
        if (sep >= 0) return rawResponse.substring(sep + 4);
        sep = rawResponse.indexOf("\n\n");
        if (sep >= 0) return rawResponse.substring(sep + 2);
        return "";
    }

    private static void appendAggregatedEntry(StringBuilder sb, String body,
                                               int statusCode, AggregationPlan.Mode mode, String stepId) {
        switch (mode) {
            case APPEND_RAW_RESPONSES -> sb.append(body == null ? "" : body).append("\n");
            case APPEND_NORMALIZED_SUMMARIES -> sb.append("[Step: ").append(stepId)
                    .append("] Status: ").append(statusCode).append('\n')
                    .append(body == null ? "" : body)
                    .append("\n");
            case APPEND_SELECTED_EXTRACTS -> {} // no-op: variable extracts not supported for live response
        }
    }

    static int resolveStopIndex(List<FlowStep> steps, int matchedIdx,
                                 StepTriggerRule.ExecutionScope scope, String stopAtStepId) {
        return switch (scope) {
            case MATCHED_STEP -> matchedIdx;
            case FULL_FLOW    -> steps.size() - 1;
            case STOP_AT_STEP -> {
                for (int i = 0; i < steps.size(); i++) {
                    if (steps.get(i).id().equals(stopAtStepId)) {
                        if (i < matchedIdx) {
                            System.err.println("[Corestra] Auto-trigger: stopAtStepId '" + stopAtStepId
                                    + "' is before matched step — falling back to MATCHED_STEP");
                            yield matchedIdx;
                        }
                        yield i;
                    }
                }
                System.err.println("[Corestra] Auto-trigger: stopAtStepId '" + stopAtStepId
                        + "' not found — falling back to MATCHED_STEP");
                yield matchedIdx;
            }
        };
    }

    private static String requestVersion(HttpRequest request) {
        String version = request.httpVersion();
        return version == null || version.isBlank() ? "HTTP/1.1" : version;
    }

    private static String responseVersion(HttpResponseReceived response) {
        String version = response.httpVersion();
        return version == null || version.isBlank() ? "HTTP/1.1" : version;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /**
     * Downstream step records collected for live aggregation, plus an optional partial-result marker
     * and a short problem description (null on success) for {@link SynchronousDownstream} callers.
     */
    private record DownstreamOutcome(Map<String, StepExecutionRecord> lastRecords, String marker, String problem) {
        static final DownstreamOutcome NONE = new DownstreamOutcome(Map.of(), null, null);

        static DownstreamOutcome partial(Map<String, StepExecutionRecord> records, String marker, String problem) {
            return new DownstreamOutcome(records, marker, problem);
        }
    }

    /**
     * Requests that the downstream steps of the pending trigger for {@code messageId} run
     * synchronously inside the response callback, so that when
     * {@link #handleHttpResponseReceived} returns they have finished, failed or timed out.
     *
     * <p>Must be called after {@link #handleHttpRequestToBeSent} returned for that message and
     * before its response is handled. It reuses the aggregation wait (same executor, timeout and
     * failure handling); a timed-out run keeps going in the background. It does not change the
     * live response: aggregation output stays exactly as it would be without this call.</p>
     *
     * @param messageId     Montoya message id of the triggering request
     * @param timeoutMillis positive maximum wait for the downstream steps
     * @return a handle reporting the downstream outcome, or empty when no trigger is pending for
     *         the message (the request was not an auto-trigger, was stopped, or the handler is closed)
     */
    public java.util.Optional<SynchronousDownstream> requireSynchronousDownstream(int messageId, long timeoutMillis) {
        if (timeoutMillis < 1L) throw new IllegalArgumentException("timeout must be positive");
        if (closed.get()) return java.util.Optional.empty();
        PendingTrigger pending = pendingTriggers.get(messageId);
        if (pending == null) return java.util.Optional.empty();
        SynchronousDownstream handle = new SynchronousDownstream(timeoutMillis);
        pending.synchronousDownstream = handle;
        return java.util.Optional.of(handle);
    }

    /**
     * Outcome of a downstream run requested with {@link #requireSynchronousDownstream}. Thread-safe;
     * filled in by the response callback before it returns.
     */
    public static final class SynchronousDownstream {
        private final long timeoutMillis;
        private volatile boolean finished;
        private volatile boolean ran;
        private volatile String problem;

        SynchronousDownstream(long timeoutMillis) {
            this.timeoutMillis = timeoutMillis;
        }

        private void finish(boolean ran, String problem) {
            this.ran = ran;
            this.problem = problem;
            this.finished = true;
        }

        /**
         * Returns whether the response callback processed the trigger. False when the response
         * never reached the handler or the pending trigger expired first.
         *
         * @return true once the outcome is known
         */
        public boolean finished() {
            return finished;
        }

        /**
         * Returns whether the trigger had downstream steps that were run (waited for).
         *
         * @return true when downstream steps ran synchronously
         */
        public boolean ran() {
            return ran;
        }

        /**
         * Returns a short description such as {@code "timeout: ..."}, {@code "failed: ..."} or
         * {@code "cancelled (...)"}, or empty when the downstream steps succeeded or there were none.
         *
         * @return problem description
         */
        public java.util.Optional<String> problem() {
            return java.util.Optional.ofNullable(problem);
        }

        /**
         * Returns the maximum wait requested.
         *
         * @return timeout in milliseconds
         */
        public long timeoutMillis() {
            return timeoutMillis;
        }
    }

    private static final class PendingTrigger {
        final FlowDefinition flow;
        final int matchedStepIndex;
        final int stopIdx;
        final ExecutionContext ctx;
        final HttpMessageSnapshot requestSnapshot;
        final long createdAtEpochMillis;
        volatile SynchronousDownstream synchronousDownstream;

        PendingTrigger(FlowDefinition flow, int matchedStepIndex, int stopIdx,
                       ExecutionContext ctx, HttpMessageSnapshot requestSnapshot,
                       long createdAtEpochMillis) {
            this.flow = flow;
            this.matchedStepIndex = matchedStepIndex;
            this.stopIdx = stopIdx;
            this.ctx = ctx;
            this.requestSnapshot = requestSnapshot;
            this.createdAtEpochMillis = createdAtEpochMillis;
        }
    }
}

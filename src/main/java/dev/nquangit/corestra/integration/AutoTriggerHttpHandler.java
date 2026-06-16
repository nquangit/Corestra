package dev.nquangit.corestra.integration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.HttpService;
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
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.flow.execution.FlowEngine;
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
 *   <li>Post-trigger steps run asynchronously once the live response arrives (Phase 3), so that
 *       response-extracted variables are available to downstream steps.</li>
 * </ol>
 *
 * <p>When the response arrives ({@link #handleHttpResponseReceived}), the handler looks up
 * the stored context, then combines:
 * <ul>
 *   <li>prerequisite responses for steps with {@code includeInAggregation = true}, and</li>
 *   <li>the triggered step's live response if it also has {@code includeInAggregation = true}</li>
 * </ul>
 * into a single {@code flowUpdater} call. This avoids the race condition that would occur if
 * aggregation were split across two separate writes.
 *
 * <p>At most one matching step is processed per request (first match wins).
 * Failures in prerequisite execution never block the live request.
 */
public final class AutoTriggerHttpHandler implements HttpHandler {

    private final Supplier<List<FlowDefinition>> flowsSupplier;
    private final FlowEngine flowEngine;
    private final ExtractionService extractionService;
    private final InjectionService injectionService;

    /**
     * Holds the Phase-1 execution context for an in-flight triggered request so that
     * {@link #handleHttpResponseReceived} can combine prerequisite and live responses.
     * Keyed by {@link #triggerKey(String, String, String, String)}.
     */
    private final ConcurrentHashMap<String, PendingTrigger> pendingTriggers = new ConcurrentHashMap<>();

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
        this.flowsSupplier = Objects.requireNonNull(flowsSupplier);
        this.flowEngine = Objects.requireNonNull(flowEngine);
        this.extractionService = Objects.requireNonNull(extractionService);
        this.injectionService = Objects.requireNonNull(injectionService);
    }

    @Override
    public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent req) {
        if (hasProxyPipelineMarker(req)) {
            return RequestToBeSentAction.continueWith(req.withRemovedHeader(ProxyPipelineHttpClient.MARKER_HEADER));
        }
        if (req.toolSource().toolType() == ToolType.EXTENSIONS) {
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

        for (FlowDefinition flow : flowsSupplier.get()) {
            if (!flow.enabled()) continue;
            List<FlowStep> steps = flow.steps();
            for (int i = 0; i < steps.size(); i++) {
                FlowStep step = steps.get(i);
                if (!step.enabled()) continue;
                StepTriggerRule rule = step.triggerRule();
                if (!rule.enabled()) continue;
                if (!rule.matches(toolTypeName, host, path, allHeaders, body)) continue;

                try {
                    HttpRequest modified = processMatch(flow, steps, i, req, allHeaders, body, toolTypeName, host, path);
                    return RequestToBeSentAction.continueWith(modified);
                } catch (RuntimeException ignored) {
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
            if (responseReceived.toolSource().toolType() == ToolType.EXTENSIONS) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }

            burp.api.montoya.http.message.requests.HttpRequest initiating = responseReceived.initiatingRequest();
            if (hasProxyPipelineMarker(initiating)) {
                return ResponseReceivedAction.continueWith(responseReceived);
            }
            String toolTypeName = responseReceived.toolSource().toolType().name();
            String host = initiating.httpService() != null ? initiating.httpService().host() : "";
            String method = initiating.method() != null ? initiating.method() : "";
            String pathAndQuery = initiating.path() != null ? initiating.path() : "";
            int qIdx = pathAndQuery.indexOf('?');
            String path = qIdx >= 0 ? pathAndQuery.substring(0, qIdx) : pathAndQuery;
            List<String> allHeaders = initiating.headers().stream()
                    .map(h -> h.name() + ": " + h.value())
                    .collect(Collectors.toList());
            String reqBody = initiating.bodyToString();

            PendingTrigger pending = pendingTriggers.remove(triggerKey(toolTypeName, host, method, path));

            for (FlowDefinition flow : flowsSupplier.get()) {
                if (!flow.enabled()) continue;

                List<FlowStep> steps = flow.steps();
                FlowStep triggeredStep = null;
                for (FlowStep s : steps) {
                    if (!s.enabled()) continue;
                    StepTriggerRule rule = s.triggerRule();
                    if (!rule.enabled()) continue;
                    if (!rule.matches(toolTypeName, host, path, allHeaders, reqBody)) continue;
                    triggeredStep = s;
                    break;
                }
                if (triggeredStep == null) continue;

                ExecutionContext resolvedCtx = null;

                // Apply the trigger step's extract rules to the live response, then fire Phase 3
                // with the fully populated context (request-side + response-side variables).
                if (pending != null && pending.flow.id().equals(flow.id())) {
                    String statusLine = "HTTP/1.1 " + responseReceived.statusCode();
                    List<String> responseHeaders = responseReceived.headers().stream()
                            .map(h -> h.name() + ": " + h.value())
                            .collect(Collectors.toList());
                    HttpMessageSnapshot liveResponseSnapshot = new HttpMessageSnapshot(
                            statusLine, responseHeaders, responseReceived.bodyToString());

                    ExecutionContext updatedCtx = applyExtractRulesToContext(
                            triggeredStep, pending.requestSnapshot, liveResponseSnapshot, pending.ctx);

                    resolvedCtx = updatedCtx;

                    if (pending.stopIdx > pending.matchedStepIndex) {
                        final ExecutionContext phase3Ctx = updatedCtx;
                        final FlowDefinition phase3Flow = pending.flow;
                        final List<FlowStep> phase3Steps = phase3Flow.steps();
                        final int phase3Start = pending.matchedStepIndex + 1;
                        final int phase3Stop = pending.stopIdx;
                        CompletableFuture.runAsync(() -> {
                            try {
                                runStepRange(phase3Flow, phase3Steps, phase3Start, phase3Stop, phase3Ctx);
                            } catch (Exception e) {
                                System.err.println("[Corestra] Auto-trigger Phase 3 failed: " + e.getMessage());
                            }
                        });
                    }
                }

                AggregationPlan agg = flow.aggregation();
                StringBuilder aggregated = new StringBuilder();

                // Append prerequisite responses for steps that opt in.
                if (pending != null && pending.flow.id().equals(flow.id())) {
                    for (int idx = 0; idx < Math.min(pending.matchedStepIndex, steps.size()); idx++) {
                        FlowStep prereq = steps.get(idx);
                        if (!prereq.includeInAggregation()) continue;
                        ExecutionContext ctxForHistory = resolvedCtx != null ? resolvedCtx : pending.ctx;
                        List<StepExecutionRecord> recs = ctxForHistory.stepHistory().get(prereq.id());
                        if (recs == null || recs.isEmpty()) continue;
                        StepExecutionRecord rec = recs.get(recs.size() - 1);
                        appendAggregatedEntry(aggregated, extractBodyFromRaw(rec.receivedResponse()),
                                rec.responseStatusCode(), agg.mode(), prereq.id());
                    }
                }

                // Append the triggered step's live response if it opts in.
                if (triggeredStep.includeInAggregation()) {
                    appendAggregatedEntry(aggregated, responseReceived.bodyToString(),
                            responseReceived.statusCode(), agg.mode(), triggeredStep.id());
                }

                if (aggregated.length() == 0) break;

                // Return the aggregated content as the live Burp response.
                // The flow's stored step data (lastResponseRaw, templates) is not modified.
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
            String toolTypeName,
            String host,
            String path) {

        FlowStep matchedStep = steps.get(matchedIndex);
        StepTriggerRule rule = matchedStep.triggerRule();
        int stopIdx = resolveStopIndex(steps, matchedIndex, rule.scope(), rule.stopAtStepId());

        // Phase 1: run prerequisite steps with stored templates.
        ExecutionContext ctx = ExecutionContext.start(
                UUID.randomUUID().toString(), flow.id(), Map.of(), System.currentTimeMillis());
        ctx = runStepRange(flow, steps, 0, matchedIndex - 1, ctx);

        // Build the request snapshot before storing the pending trigger — it is needed later
        // by handleHttpResponseReceived to apply the trigger step's response-side extract rules.
        String requestLine = req.method() + " " + (req.path() != null ? req.path() : "/") + " HTTP/1.1";
        HttpMessageSnapshot requestSnapshot = new HttpMessageSnapshot(requestLine, allHeaders, body);

        // Store Phase-1 context so the response handler can complete extraction and fire Phase 3.
        String method = req.method() != null ? req.method() : "";
        pendingTriggers.put(triggerKey(toolTypeName, host, method, path),
                new PendingTrigger(flow, matchedIndex, stopIdx, ctx, requestSnapshot));

        // Phase 2: apply the trigger step's extract rules to the live request.
        // Response-side rules will silently fail here (no live response yet); they are
        // re-applied in handleHttpResponseReceived once the real response arrives.
        HttpMessageSnapshot emptyResponse = new HttpMessageSnapshot("", List.of(), "");
        ctx = applyExtractRulesToContext(matchedStep, requestSnapshot, emptyResponse, ctx);

        List<String> headersWithoutHost = allHeaders.stream()
                .filter(h -> !h.toLowerCase().startsWith("host:"))
                .collect(Collectors.toList());
        MutableHttpRequest mutable = new MutableHttpRequest(requestLine, headersWithoutHost, body);
        RequestMutationResult mutated = injectionService.apply(
                matchedStep.injectRules(), ctx.variables(), mutable);

        // Phase 3 is deferred to handleHttpResponseReceived so that response-extracted variables
        // are available to downstream steps before they execute.

        return rebuildRequest(req, mutated);
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
        for (var rule : step.extractRules()) {
            ExtractionResult result = extractionService.extract(rule, requestSnapshot, responseSnapshot);
            if (result.isSuccess()) {
                ctx = ctx.withVariable(result.targetVariable(), result.value());
            }
        }
        return ctx;
    }

    ExecutionContext runStepRange(FlowDefinition flow, List<FlowStep> steps,
                                  int fromIdx, int toIdx, ExecutionContext ctx) {
        if (fromIdx > toIdx) return ctx;
        List<FlowStep> slice = new ArrayList<>(steps.subList(fromIdx, toIdx + 1));
        FlowDefinition sliceFlow = new FlowDefinition(
                flow.id(), flow.name(), flow.description(), flow.enabled(),
                slice, flow.rules(), AggregationPlan.disabled(), flow.triggerConfig());
        ExecutionResult result = flowEngine.execute(sliceFlow, ctx);
        for (StepExecutionRecord rec : result.stepRecords()) {
            for (Map.Entry<String, String> e : rec.extractedVariables().entrySet()) {
                ctx = ctx.withVariable(e.getKey(), e.getValue());
            }
            ctx = ctx.withStepRecord(rec);
        }
        return ctx;
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

    private static HttpRequest rebuildRequest(HttpRequestToBeSent original, RequestMutationResult mutated) {
        HttpService service = original.httpService();
        String host = service != null ? service.host() : "";
        int port = service != null ? service.port() : 443;
        boolean secure = service != null && service.secure();
        boolean defaultPort = (secure && port == 443) || (!secure && port == 80);

        StringBuilder raw = new StringBuilder();
        raw.append(mutated.requestLine()).append("\r\n");
        raw.append("Host: ").append(host);
        if (!defaultPort) raw.append(":").append(port);
        raw.append("\r\n");
        for (String h : mutated.headers()) raw.append(h).append("\r\n");
        raw.append("\r\n");
        if (mutated.body() != null && !mutated.body().isEmpty()) raw.append(mutated.body());

        return HttpRequest.httpRequest(service, raw.toString());
    }

    private static String triggerKey(String toolTypeName, String host, String method, String path) {
        return toolTypeName + "|" + host + "|" + method + "|" + path;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private static final class PendingTrigger {
        final FlowDefinition flow;
        final int matchedStepIndex;
        final int stopIdx;
        final ExecutionContext ctx;
        final HttpMessageSnapshot requestSnapshot;

        PendingTrigger(FlowDefinition flow, int matchedStepIndex, int stopIdx,
                       ExecutionContext ctx, HttpMessageSnapshot requestSnapshot) {
            this.flow = flow;
            this.matchedStepIndex = matchedStepIndex;
            this.stopIdx = stopIdx;
            this.ctx = ctx;
            this.requestSnapshot = requestSnapshot;
        }
    }
}

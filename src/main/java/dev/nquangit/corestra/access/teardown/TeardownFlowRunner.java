package dev.nquangit.corestra.access.teardown;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.runner.ManualAuthApplier;
import dev.nquangit.corestra.access.runner.StoredRequestHttpRequestAdapter;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.access.template.AccessScopedVariableStoreFactory;
import dev.nquangit.corestra.access.variable.DefaultRuntimeValueProvider;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.execution.StepExecutionRecord;
import dev.nquangit.corestra.http.extract.ExtractionResult;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.http.inject.RequestMutationResult;
import dev.nquangit.corestra.http.inject.InjectionService.MutableHttpRequest;
import dev.nquangit.corestra.integration.HttpClient;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs or render-validates configured teardown flows after access test execution.
 */
public final class TeardownFlowRunner {
    private final Supplier<List<FlowDefinition>> flowSupplier;
    private final FlowEngine flowEngine;
    private final ExtractionService extractionService;
    private final InjectionService injectionService;
    private final HttpClient httpClient;
    private final StoredRequestHttpRequestAdapter requestAdapter;
    private final RedactionService redactionService;
    private final Clock clock;
    private final AccessScopedVariableStoreFactory storeFactory = new AccessScopedVariableStoreFactory();
    private final ManualAuthApplier manualAuthApplier = new ManualAuthApplier(
            new TemplateRenderer(new VariableResolver(new DefaultRuntimeValueProvider())));

    /**
     * Creates a teardown flow runner.
     *
     * @param flowSupplier supplier for available flows
     * @param flowEngine flow engine used for live teardown execution
     * @param injectionService injection service used for dry-run rendering
     * @param redactionService redaction service for evidence
     * @param clock clock used for timestamps
     */
    public TeardownFlowRunner(
            Supplier<List<FlowDefinition>> flowSupplier,
            FlowEngine flowEngine,
            InjectionService injectionService,
            RedactionService redactionService,
            Clock clock) {
        this(
                flowSupplier,
                Objects.requireNonNull(flowEngine, "flowEngine"),
                null,
                injectionService,
                null,
                null,
                redactionService,
                clock);
    }

    /**
     * Creates a teardown flow runner with an explicit request adapter.
     *
     * <p>This path keeps teardown execution in the access layer's stored-request transport
     * model, which makes teardown evidence and test fakes behave like target request execution
     * without changing the shared flow engine.
     *
     * @param flowSupplier supplier for available flows
     * @param extractionService extraction service used to derive teardown variables
     * @param injectionService injection service used to render teardown requests
     * @param httpClient HTTP client used to send teardown requests
     * @param requestAdapter stored-request to Montoya request adapter
     * @param redactionService redaction service for evidence
     * @param clock clock used for timestamps
     */
    public TeardownFlowRunner(
            Supplier<List<FlowDefinition>> flowSupplier,
            ExtractionService extractionService,
            InjectionService injectionService,
            HttpClient httpClient,
            StoredRequestHttpRequestAdapter requestAdapter,
            RedactionService redactionService,
            Clock clock) {
        this(
                flowSupplier,
                null,
                Objects.requireNonNull(extractionService, "extractionService"),
                injectionService,
                Objects.requireNonNull(httpClient, "httpClient"),
                Objects.requireNonNull(requestAdapter, "requestAdapter"),
                redactionService,
                clock);
    }

    private TeardownFlowRunner(
            Supplier<List<FlowDefinition>> flowSupplier,
            FlowEngine flowEngine,
            ExtractionService extractionService,
            InjectionService injectionService,
            HttpClient httpClient,
            StoredRequestHttpRequestAdapter requestAdapter,
            RedactionService redactionService,
            Clock clock) {
        this.flowSupplier = Objects.requireNonNull(flowSupplier, "flowSupplier");
        this.flowEngine = flowEngine;
        this.extractionService = extractionService;
        this.injectionService = Objects.requireNonNull(injectionService, "injectionService");
        this.httpClient = httpClient;
        this.requestAdapter = requestAdapter;
        this.redactionService = Objects.requireNonNull(redactionService, "redactionService");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (this.flowEngine == null
                && (this.extractionService == null || this.httpClient == null || this.requestAdapter == null)) {
            throw new IllegalArgumentException("Either flowEngine or direct teardown transport dependencies are required");
        }
    }

    /**
     * Runs a configured teardown flow.
     *
     * @param testCase source test case
     * @param workspace access workspace snapshot
     * @param targetAuthProfile target auth profile
     * @param targetDataProfile target data profile, if any
     * @param env environment variables
     * @param scenario scenario variables
     * @param legacy legacy variables
     * @param renderMode render mode requested by the access runner
     * @return teardown run result
     */
    public TeardownRunResult run(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode renderMode) {
        TeardownPlan plan = plan(testCase, workspace, targetAuthProfile, targetDataProfile);
        if (!plan.success()) {
            return plan.toResult();
        }
        FlowDefinition flow = plan.flow().orElseThrow();
        AuthProfile teardownAuth = plan.authProfile().orElseThrow();
        Map<String, String> variables = initialVariables(teardownAuth, targetDataProfile, env, scenario, legacy);
        if (canRunDirectly()) {
            return runDirect(
                    flow,
                    teardownAuth,
                    targetDataProfile,
                    env,
                    scenario,
                    legacy,
                    renderMode,
                    variables,
                    plan.warnings());
        }
        ExecutionContext context = new ExecutionContext(
                "teardown-" + UUID.randomUUID(),
                flow.id(),
                metadata(testCase, teardownAuth, renderMode),
                variables,
                "",
                Map.of(),
                clock.millis(),
                0L);

        try {
            ExecutionResult execution = flowEngine.execute(flow, context);
            TeardownEvidence evidence = evidence(flow, execution);
            ArrayList<String> warnings = new ArrayList<>(plan.warnings());
            if (!execution.success()) {
                warnings.add("Teardown flow failed: "
                        + Objects.requireNonNullElse(execution.failureMessage(), "unknown failure"));
            }
            return new TeardownRunResult(
                    true,
                    execution.success(),
                    execution.success() ? "Teardown flow executed" : "Teardown flow failed",
                    warnings,
                    Optional.of(evidence));
        } catch (RuntimeException exception) {
            return new TeardownRunResult(
                    true,
                    false,
                    "Teardown flow failed: " + exception.getMessage(),
                    List.of("Teardown flow failed: " + exception.getMessage()),
                    Optional.empty());
        }
    }

    /**
     * Renders a configured teardown flow without sending HTTP traffic.
     *
     * @param testCase source test case
     * @param workspace access workspace snapshot
     * @param targetAuthProfile target auth profile
     * @param targetDataProfile target data profile, if any
     * @param env environment variables
     * @param scenario scenario variables
     * @param legacy legacy variables
     * @param renderMode render mode requested by the access runner
     * @return dry-run teardown result
     */
    public TeardownRunResult dryRun(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode renderMode) {
        TeardownPlan plan = plan(testCase, workspace, targetAuthProfile, targetDataProfile);
        if (!plan.success()) {
            return plan.toResult();
        }
        FlowDefinition flow = plan.flow().orElseThrow();
        AuthProfile teardownAuth = plan.authProfile().orElseThrow();
        Map<String, String> variables = initialVariables(teardownAuth, targetDataProfile, env, scenario, legacy);
        ArrayList<TeardownStepEvidence> steps = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>(plan.warnings());
        for (FlowStep step : flow.steps()) {
            if (!step.enabled()) {
                continue;
            }
            DryRenderedStep rendered = renderStep(
                    step,
                    teardownAuth,
                    targetDataProfile,
                    env,
                    scenario,
                    legacy,
                    renderMode,
                    variables);
            warnings.addAll(rendered.warnings().stream()
                    .map(warning -> "Teardown dry run step '" + step.id() + "': " + warning)
                    .toList());
            steps.add(new TeardownStepEvidence(
                    step.id(),
                    redactionService.redactHttpMessage(rendered.rawRequest()),
                    "",
                    0,
                    true,
                    "Dry run rendered teardown step; no HTTP request was sent",
                    clock.millis()));
        }
        TeardownEvidence evidence = new TeardownEvidence(
                flow.id(),
                flow.name(),
                true,
                "Dry run rendered teardown flow; no HTTP requests were sent",
                steps,
                clock.millis());
        return new TeardownRunResult(
                true,
                true,
                "Dry run rendered teardown flow",
                warnings,
                Optional.of(evidence));
    }

    private TeardownPlan plan(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile) {
        Objects.requireNonNull(testCase, "testCase");
        Objects.requireNonNull(workspace, "workspace");
        if (testCase.teardown().mode() == TeardownMode.NONE) {
            return TeardownPlan.notConfigured();
        }
        if (testCase.teardown().teardownFlowId().isBlank()) {
            return TeardownPlan.failed("Teardown flow id is required", List.of("Teardown flow id is required"));
        }
        FlowDefinition flow = flow(testCase.teardown().teardownFlowId()).orElse(null);
        if (flow == null) {
            return TeardownPlan.failed(
                    "Teardown flow not found: " + testCase.teardown().teardownFlowId(),
                    List.of("Teardown flow not found: " + testCase.teardown().teardownFlowId()));
        }
        if (!flow.enabled()) {
            return TeardownPlan.failed(
                    "Teardown flow is disabled: " + flow.id(),
                    List.of("Teardown flow is disabled: " + flow.id()));
        }
        AuthProfile authProfile = resolveAuthProfile(testCase, workspace, targetAuthProfile, targetDataProfile)
                .orElse(null);
        if (authProfile == null) {
            return TeardownPlan.failed(
                    "Teardown auth profile could not be resolved",
                    List.of("Teardown auth profile could not be resolved"));
        }
        if (flow.steps().stream().noneMatch(FlowStep::enabled)) {
            return TeardownPlan.failed(
                    "Teardown flow has no enabled steps: " + flow.id(),
                    List.of("Teardown flow has no enabled steps: " + flow.id()));
        }
        return TeardownPlan.ready(flow, authProfile, List.of());
    }

    private Optional<FlowDefinition> flow(String flowId) {
        String normalized = flowId == null ? "" : flowId.strip();
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        return flowSupplier.get().stream()
                .filter(candidate -> candidate.id().equals(normalized))
                .findFirst();
    }

    private Optional<AuthProfile> resolveAuthProfile(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile) {
        return switch (testCase.teardown().authBinding()) {
            case TARGET_ACTOR -> Optional.ofNullable(targetAuthProfile);
            case DATA_OWNER -> targetDataProfile == null || targetDataProfile.ownerAuthProfileId().isBlank()
                    ? Optional.empty()
                    : Optional.ofNullable(workspace.authProfiles().get(targetDataProfile.ownerAuthProfileId()));
            case FIXED_PROFILE -> testCase.teardown().teardownAuthProfileId().isBlank()
                    ? Optional.empty()
                    : Optional.ofNullable(workspace.authProfiles().get(testCase.teardown().teardownAuthProfileId()));
        };
    }

    private Map<String, String> metadata(
            AccessTestCase testCase,
            AuthProfile teardownAuth,
            RenderMode renderMode) {
        LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("testCaseId", testCase.id());
        metadata.put("teardownAuthProfileId", teardownAuth.id());
        metadata.put("renderMode", Objects.requireNonNullElse(renderMode, RenderMode.STRICT).name());
        return Map.copyOf(metadata);
    }

    private Map<String, String> initialVariables(
            AuthProfile authProfile,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy) {
        LinkedHashMap<String, String> variables = new LinkedHashMap<>();
        addFlatAndPrefixed(variables, "", env);
        addFlatAndPrefixed(variables, "env.", env);
        if (dataProfile != null) {
            addFlatAndPrefixed(variables, "", dataProfile.values());
            addFlatAndPrefixed(variables, "data.", dataProfile.values());
            variables.put("dataProfileId", dataProfile.id());
            variables.put("data.ownerAuthProfileId", dataProfile.ownerAuthProfileId());
        }
        addFlatAndPrefixed(variables, "", scenario);
        addFlatAndPrefixed(variables, "scenario.", scenario);
        addFlatAndPrefixed(variables, "", authProfile.credentials());
        addFlatAndPrefixed(variables, "credentials.", authProfile.credentials());
        addFlatAndPrefixed(variables, "", authProfile.state().variables());
        addFlatAndPrefixed(variables, "auth.", authProfile.state().variables());
        addFlatAndPrefixed(variables, "auth.header.", authProfile.state().headers());
        addFlatAndPrefixed(variables, "auth.cookie.", authProfile.state().cookies());
        addFlatAndPrefixed(variables, "", legacy);
        addFlatAndPrefixed(variables, "legacy.", legacy);
        variables.put("authProfileId", authProfile.id());
        variables.put("authProviderId", authProfile.providerId());
        return Map.copyOf(variables);
    }

    private boolean canRunDirectly() {
        return extractionService != null && httpClient != null && requestAdapter != null;
    }

    private TeardownRunResult runDirect(
            FlowDefinition flow,
            AuthProfile teardownAuth,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode renderMode,
            Map<String, String> initialVariables,
            List<String> planWarnings) {
        DirectExecution execution = executeDirect(
                flow,
                teardownAuth,
                targetDataProfile,
                env,
                scenario,
                legacy,
                renderMode,
                initialVariables);
        ArrayList<String> warnings = new ArrayList<>(planWarnings);
        warnings.addAll(execution.warnings());
        if (!execution.success()) {
            warnings.add("Teardown flow failed: " + execution.reason());
        }
        TeardownEvidence evidence = new TeardownEvidence(
                flow.id(),
                flow.name(),
                execution.success(),
                execution.success() ? "Teardown flow executed" : execution.reason(),
                execution.steps(),
                clock.millis());
        return new TeardownRunResult(
                true,
                execution.success(),
                execution.success() ? "Teardown flow executed" : "Teardown flow failed",
                warnings,
                Optional.of(evidence));
    }

    private DirectExecution executeDirect(
            FlowDefinition flow,
            AuthProfile teardownAuth,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode renderMode,
            Map<String, String> initialVariables) {
        LinkedHashMap<String, String> variables = new LinkedHashMap<>(initialVariables);
        ArrayList<TeardownStepEvidence> steps = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        for (FlowStep step : flow.steps()) {
            if (!step.enabled()) {
                continue;
            }
            DirectStepExecution stepExecution = executeDirectStep(
                    step,
                    teardownAuth,
                    targetDataProfile,
                    env,
                    scenario,
                    legacy,
                    renderMode,
                    variables);
            steps.add(stepExecution.evidence());
            warnings.addAll(stepExecution.warnings());
            variables.putAll(stepExecution.extractedVariables());
            if (!stepExecution.success() && step.errorStrategy() == FlowStep.StepErrorStrategy.FAIL_FLOW) {
                return new DirectExecution(false, stepExecution.reason(), steps, warnings);
            }
        }
        return new DirectExecution(true, "Teardown flow executed", steps, warnings);
    }

    private DirectStepExecution executeDirectStep(
            FlowStep step,
            AuthProfile teardownAuth,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode renderMode,
            Map<String, String> variables) {
        long startedAt = clock.millis();
        StoredRequestTemplate template = step.requestTemplate();
        MutableHttpRequest mutableRequest = new MutableHttpRequest(
                template.method() + " " + template.pathAndQuery() + " HTTP/1.1",
                template.headerLines(),
                template.body());
        RequestMutationResult mutation = injectionService.apply(step.injectRules(), variables, mutableRequest);
        StoredRequestTemplate renderedTemplate = applyManualAuth(
                renderedTemplate(template, mutation),
                teardownAuth,
                targetDataProfile,
                env,
                scenario,
                legacy,
                renderMode);
        String rawRequest = requestAdapter.rawRequest(renderedTemplate);

        try {
            HttpResponse response = httpClient.send(requestAdapter.toHttpRequest(renderedTemplate), step.sendModeOverride());
            String rawResponse = rawResponse(response);
            Map<String, String> extractedVariables = extractedVariables(step, renderedTemplate, response);
            TeardownStepEvidence evidence = new TeardownStepEvidence(
                    step.id(),
                    redactionService.redactHttpMessage(rawRequest),
                    redactionService.redactHttpMessage(rawResponse),
                    response.statusCode(),
                    true,
                    "Step executed",
                    Math.max(clock.millis(), startedAt));
            return new DirectStepExecution(
                    true,
                    "Step executed",
                    evidence,
                    extractedVariables,
                    mutation.diagnostics());
        } catch (RuntimeException exception) {
            String reason = "Request failed: " + exception.getMessage();
            TeardownStepEvidence evidence = new TeardownStepEvidence(
                    step.id(),
                    redactionService.redactHttpMessage(rawRequest),
                    "",
                    0,
                    false,
                    reason,
                    Math.max(clock.millis(), startedAt));
            return new DirectStepExecution(
                    false,
                    reason,
                    evidence,
                    Map.of(),
                    mutation.diagnostics());
        }
    }

    private Map<String, String> extractedVariables(
            FlowStep step,
            StoredRequestTemplate renderedTemplate,
            HttpResponse response) {
        HttpMessageSnapshot requestSnapshot = new HttpMessageSnapshot(
                renderedTemplate.method() + " " + renderedTemplate.pathAndQuery() + " HTTP/1.1",
                renderedTemplate.headerLines(),
                renderedTemplate.body());
        HttpMessageSnapshot responseSnapshot = new HttpMessageSnapshot(
                statusLine(response),
                responseHeaders(response),
                response.bodyToString());
        LinkedHashMap<String, String> extracted = new LinkedHashMap<>();
        for (ExtractRule rule : step.extractRules()) {
            ExtractionResult result = extractionService.extract(rule, requestSnapshot, responseSnapshot);
            if (result.isSuccess()) {
                extracted.put(result.targetVariable(), result.value());
            }
        }
        return Map.copyOf(extracted);
    }

    private StoredRequestTemplate applyManualAuth(
            StoredRequestTemplate request,
            AuthProfile authProfile,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode renderMode) {
        ScopedVariableStore store = storeFactory.from(
                env,
                authProfile == null ? null : authProfile.state(),
                dataProfile,
                scenario,
                legacy);
        return authProfile == null
                ? request
                : manualAuthApplier.apply(
                        request,
                        authProfile.state(),
                        store,
                        Objects.requireNonNullElse(renderMode, RenderMode.STRICT));
    }

    private StoredRequestTemplate renderedTemplate(
            StoredRequestTemplate source,
            RequestMutationResult mutation) {
        return new StoredRequestTemplate(
                source.method(),
                source.scheme(),
                source.host(),
                source.port(),
                requestTarget(mutation.requestLine(), source.pathAndQuery()),
                mutation.headers(),
                mutation.body());
    }

    private String requestTarget(String requestLine, String fallback) {
        String[] parts = Objects.requireNonNullElse(requestLine, "").split(" ", 3);
        if (parts.length >= 2 && !parts[1].isBlank()) {
            return parts[1];
        }
        return fallback;
    }

    private String rawResponse(HttpResponse response) {
        StringBuilder raw = new StringBuilder();
        raw.append(statusLine(response)).append('\n');
        for (String header : responseHeaders(response)) {
            raw.append(header).append('\n');
        }
        raw.append('\n');
        raw.append(response.bodyToString());
        return raw.toString();
    }

    private String statusLine(HttpResponse response) {
        return response.httpVersion() + " " + response.statusCode() + " " + response.reasonPhrase();
    }

    private List<String> responseHeaders(HttpResponse response) {
        return response.headers().stream()
                .map(HttpHeader::toString)
                .toList();
    }

    private void addFlatAndPrefixed(Map<String, String> target, String prefix, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        values.forEach((key, value) -> {
            if (key != null && !key.isBlank()) {
                target.put(prefix + key.strip(), Objects.requireNonNullElse(value, ""));
            }
        });
    }

    private TeardownEvidence evidence(FlowDefinition flow, ExecutionResult execution) {
        List<TeardownStepEvidence> steps = execution.stepRecords().stream()
                .map(this::stepEvidence)
                .toList();
        return new TeardownEvidence(
                flow.id(),
                flow.name(),
                execution.success(),
                execution.success()
                        ? "Teardown flow executed"
                        : Objects.requireNonNullElse(execution.failureMessage(), "Teardown flow failed"),
                steps,
                clock.millis());
    }

    private TeardownStepEvidence stepEvidence(StepExecutionRecord record) {
        return new TeardownStepEvidence(
                record.stepId(),
                redactionService.redactHttpMessage(record.renderedRequest()),
                redactionService.redactHttpMessage(record.receivedResponse()),
                record.responseStatusCode(),
                record.success(),
                record.success() ? "Step executed" : record.errorMessage(),
                record.completedAtEpochMillis());
    }

    private DryRenderedStep renderStep(
            FlowStep step,
            AuthProfile teardownAuth,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode renderMode,
            Map<String, String> variables) {
        StoredRequestTemplate template = step.requestTemplate();
        String requestLine = template.method() + " " + template.pathAndQuery() + " HTTP/1.1";
        RequestMutationResult mutation = injectionService.apply(
                step.injectRules(),
                variables,
                new MutableHttpRequest(requestLine, template.headerLines(), template.body()));
        StoredRequestTemplate renderedTemplate = applyManualAuth(
                renderedTemplate(template, mutation),
                teardownAuth,
                targetDataProfile,
                env,
                scenario,
                legacy,
                renderMode);
        return new DryRenderedStep(
                requestAdapter == null
                        ? buildRenderedRequest(
                                renderedTemplate.method() + " " + renderedTemplate.pathAndQuery() + " HTTP/1.1",
                                renderedTemplate.headerLines(),
                                renderedTemplate.body())
                        : requestAdapter.rawRequest(renderedTemplate),
                mutation.diagnostics());
    }

    private String buildRenderedRequest(String requestLine, List<String> headers, String body) {
        StringBuilder raw = new StringBuilder();
        raw.append(requestLine).append("\r\n");
        for (String header : headers) {
            raw.append(header).append("\r\n");
        }
        raw.append("\r\n");
        if (body != null && !body.isEmpty()) {
            raw.append(body);
        }
        return raw.toString();
    }

    private record DryRenderedStep(String rawRequest, List<String> warnings) {
        private DryRenderedStep {
            rawRequest = Objects.requireNonNullElse(rawRequest, "");
            warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        }
    }

    private record DirectExecution(
            boolean success,
            String reason,
            List<TeardownStepEvidence> steps,
            List<String> warnings) {
        private DirectExecution {
            reason = Objects.requireNonNullElse(reason, "");
            steps = List.copyOf(Objects.requireNonNullElse(steps, List.of()));
            warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        }
    }

    private record DirectStepExecution(
            boolean success,
            String reason,
            TeardownStepEvidence evidence,
            Map<String, String> extractedVariables,
            List<String> warnings) {
        private DirectStepExecution {
            reason = Objects.requireNonNullElse(reason, "");
            evidence = Objects.requireNonNull(evidence, "evidence");
            extractedVariables = Map.copyOf(Objects.requireNonNullElse(extractedVariables, Map.of()));
            warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        }
    }

    private record TeardownPlan(
            boolean configured,
            boolean success,
            String reason,
            Optional<FlowDefinition> flow,
            Optional<AuthProfile> authProfile,
            List<String> warnings) {
        private TeardownPlan {
            reason = Objects.requireNonNullElse(reason, "");
            flow = Objects.requireNonNull(flow, "flow");
            authProfile = Objects.requireNonNull(authProfile, "authProfile");
            warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        }

        private static TeardownPlan notConfigured() {
            return new TeardownPlan(false, true, "", Optional.empty(), Optional.empty(), List.of());
        }

        private static TeardownPlan ready(FlowDefinition flow, AuthProfile authProfile, List<String> warnings) {
            return new TeardownPlan(true, true, "", Optional.of(flow), Optional.of(authProfile), warnings);
        }

        private static TeardownPlan failed(String reason, List<String> warnings) {
            return new TeardownPlan(true, false, reason, Optional.empty(), Optional.empty(), warnings);
        }

        private TeardownRunResult toResult() {
            if (!configured) {
                return TeardownRunResult.notConfigured();
            }
            return new TeardownRunResult(true, success, reason, warnings, Optional.empty());
        }
    }
}

package dev.nquangit.corestra.access.runner;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.responses.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.runtime.AuthEnsureResult;
import dev.nquangit.corestra.access.auth.runtime.AuthManager;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.BasicResultEvaluator;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.evaluate.EvaluationResult;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.flow.AccessTargetType;
import dev.nquangit.corestra.access.flow.FlowInjectionOverride;
import dev.nquangit.corestra.access.flow.FlowInjectionOverrideTarget;
import dev.nquangit.corestra.access.flow.FlowTargetRunMode;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.id.UuidAccessIdGenerator;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertion;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertionResult;
import dev.nquangit.corestra.access.postcheck.PostCheckAuthBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckEvidence;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.FlowStepEvidence;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.scenario.ScenarioManager;
import dev.nquangit.corestra.access.scenario.ScenarioPreparationResult;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.AccessRequestTemplateRenderer;
import dev.nquangit.corestra.access.template.AccessScopedVariableStoreFactory;
import dev.nquangit.corestra.access.template.RenderedAccessRequest;
import dev.nquangit.corestra.access.teardown.TeardownConfig;
import dev.nquangit.corestra.access.teardown.TeardownEvidence;
import dev.nquangit.corestra.access.teardown.TeardownFlowRunner;
import dev.nquangit.corestra.access.teardown.TeardownMode;
import dev.nquangit.corestra.access.teardown.TeardownRunResult;
import dev.nquangit.corestra.access.variable.DefaultRuntimeValueProvider;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.RenderResult;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.http.extract.ExtractionResult;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.extract.HttpMessageSnapshot;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.http.inject.RequestMutationResult;
import dev.nquangit.corestra.integration.HttpClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Executes one manually configured access test case.
 */
public final class SingleAccessTestRunner {
    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final ExtractionService extractionService = new ExtractionService();
    private final InjectionService injectionService = new InjectionService();
    private final AccessRequestTemplateRenderer templateRenderer;
    private final AccessScopedVariableStoreFactory storeFactory;
    private final HttpClient httpClient;
    private final BasicResultEvaluator evaluator;
    private final RedactionService redactionService;
    private final ManualAuthApplier authApplier;
    private final StoredRequestHttpRequestAdapter requestAdapter;
    private final AccessIdGenerator idGenerator;
    private final AuthManager authManager;
    private final AuthProviderRequestApplier providerAuthApplier;
    private final TargetAuthFailureDetector targetAuthFailureDetector;
    private final ScenarioManager scenarioManager;
    private final TeardownFlowRunner teardownFlowRunner;

    /**
     * Creates a manual single access test runner.
     *
     * @param templateRenderer access request template renderer
     * @param storeFactory scoped variable store factory
     * @param httpClient HTTP client abstraction
     * @param evaluator basic result evaluator
     * @param redactionService redaction service
     * @param authApplier manual auth applier
     * @param requestAdapter stored request to Montoya adapter
     */
    public SingleAccessTestRunner(
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            HttpClient httpClient,
            BasicResultEvaluator evaluator,
            RedactionService redactionService,
            ManualAuthApplier authApplier,
            StoredRequestHttpRequestAdapter requestAdapter) {
        this(templateRenderer, storeFactory, httpClient, evaluator, redactionService,
                authApplier, requestAdapter, new UuidAccessIdGenerator());
    }

    /**
     * Creates a manual single access test runner.
     *
     * @param templateRenderer access request template renderer
     * @param storeFactory scoped variable store factory
     * @param httpClient HTTP client abstraction
     * @param evaluator basic result evaluator
     * @param redactionService redaction service
     * @param authApplier manual auth applier
     * @param requestAdapter stored request to Montoya adapter
     * @param idGenerator result ID generator
     */
    public SingleAccessTestRunner(
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            HttpClient httpClient,
            BasicResultEvaluator evaluator,
            RedactionService redactionService,
            ManualAuthApplier authApplier,
            StoredRequestHttpRequestAdapter requestAdapter,
            AccessIdGenerator idGenerator) {
        this(templateRenderer, storeFactory, httpClient, evaluator, redactionService,
                authApplier, requestAdapter, idGenerator, null);
    }

    /**
     * Creates a manual single access test runner.
     *
     * @param templateRenderer access request template renderer
     * @param storeFactory scoped variable store factory
     * @param httpClient HTTP client abstraction
     * @param evaluator basic result evaluator
     * @param redactionService redaction service
     * @param authApplier manual auth applier
     * @param requestAdapter stored request to Montoya adapter
     * @param idGenerator result ID generator
     * @param authManager optional auth manager for configured providers
     */
    public SingleAccessTestRunner(
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            HttpClient httpClient,
            BasicResultEvaluator evaluator,
            RedactionService redactionService,
            ManualAuthApplier authApplier,
            StoredRequestHttpRequestAdapter requestAdapter,
            AccessIdGenerator idGenerator,
            AuthManager authManager) {
        this(templateRenderer, storeFactory, httpClient, evaluator, redactionService, authApplier, requestAdapter,
                idGenerator, authManager, defaultProviderAuthApplier(), new TargetAuthFailureDetector(), null);
    }

    /**
     * Creates a manual single access test runner.
     *
     * @param templateRenderer access request template renderer
     * @param storeFactory scoped variable store factory
     * @param httpClient HTTP client abstraction
     * @param evaluator basic result evaluator
     * @param redactionService redaction service
     * @param authApplier manual auth applier
     * @param requestAdapter stored request to Montoya adapter
     * @param idGenerator result ID generator
     * @param authManager optional auth manager for configured providers
     * @param scenarioManager optional scenario manager for setup execution
     */
    public SingleAccessTestRunner(
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            HttpClient httpClient,
            BasicResultEvaluator evaluator,
            RedactionService redactionService,
            ManualAuthApplier authApplier,
            StoredRequestHttpRequestAdapter requestAdapter,
            AccessIdGenerator idGenerator,
            AuthManager authManager,
            ScenarioManager scenarioManager) {
        this(templateRenderer, storeFactory, httpClient, evaluator, redactionService, authApplier, requestAdapter,
                idGenerator, authManager, defaultProviderAuthApplier(), new TargetAuthFailureDetector(), scenarioManager);
    }

    /**
     * Creates a manual single access test runner.
     *
     * @param templateRenderer access request template renderer
     * @param storeFactory scoped variable store factory
     * @param httpClient HTTP client abstraction
     * @param evaluator basic result evaluator
     * @param redactionService redaction service
     * @param authApplier manual auth applier
     * @param requestAdapter stored request to Montoya adapter
     * @param idGenerator result ID generator
     * @param authManager optional auth manager for configured providers
     * @param scenarioManager optional scenario manager for setup execution
     * @param teardownFlowRunner optional teardown flow runner
     */
    public SingleAccessTestRunner(
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            HttpClient httpClient,
            BasicResultEvaluator evaluator,
            RedactionService redactionService,
            ManualAuthApplier authApplier,
            StoredRequestHttpRequestAdapter requestAdapter,
            AccessIdGenerator idGenerator,
            AuthManager authManager,
            ScenarioManager scenarioManager,
            TeardownFlowRunner teardownFlowRunner) {
        this(templateRenderer, storeFactory, httpClient, evaluator, redactionService, authApplier, requestAdapter,
                idGenerator, authManager, defaultProviderAuthApplier(), new TargetAuthFailureDetector(),
                scenarioManager, teardownFlowRunner);
    }

    SingleAccessTestRunner(
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            HttpClient httpClient,
            BasicResultEvaluator evaluator,
            RedactionService redactionService,
            ManualAuthApplier authApplier,
            StoredRequestHttpRequestAdapter requestAdapter,
            AccessIdGenerator idGenerator,
            AuthManager authManager,
            AuthProviderRequestApplier providerAuthApplier,
            TargetAuthFailureDetector targetAuthFailureDetector) {
        this(templateRenderer, storeFactory, httpClient, evaluator, redactionService, authApplier, requestAdapter,
                idGenerator, authManager, providerAuthApplier, targetAuthFailureDetector, null, null);
    }

    SingleAccessTestRunner(
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            HttpClient httpClient,
            BasicResultEvaluator evaluator,
            RedactionService redactionService,
            ManualAuthApplier authApplier,
            StoredRequestHttpRequestAdapter requestAdapter,
            AccessIdGenerator idGenerator,
            AuthManager authManager,
            AuthProviderRequestApplier providerAuthApplier,
            TargetAuthFailureDetector targetAuthFailureDetector,
            ScenarioManager scenarioManager) {
        this(templateRenderer, storeFactory, httpClient, evaluator, redactionService, authApplier, requestAdapter,
                idGenerator, authManager, providerAuthApplier, targetAuthFailureDetector, scenarioManager, null);
    }

    SingleAccessTestRunner(
            AccessRequestTemplateRenderer templateRenderer,
            AccessScopedVariableStoreFactory storeFactory,
            HttpClient httpClient,
            BasicResultEvaluator evaluator,
            RedactionService redactionService,
            ManualAuthApplier authApplier,
            StoredRequestHttpRequestAdapter requestAdapter,
            AccessIdGenerator idGenerator,
            AuthManager authManager,
            AuthProviderRequestApplier providerAuthApplier,
            TargetAuthFailureDetector targetAuthFailureDetector,
            ScenarioManager scenarioManager,
            TeardownFlowRunner teardownFlowRunner) {
        this.templateRenderer = Objects.requireNonNull(templateRenderer, "templateRenderer");
        this.storeFactory = Objects.requireNonNull(storeFactory, "storeFactory");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.redactionService = Objects.requireNonNull(redactionService, "redactionService");
        this.authApplier = Objects.requireNonNull(authApplier, "authApplier");
        this.requestAdapter = Objects.requireNonNull(requestAdapter, "requestAdapter");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.authManager = authManager;
        this.providerAuthApplier = Objects.requireNonNull(providerAuthApplier, "providerAuthApplier");
        this.targetAuthFailureDetector = Objects.requireNonNull(targetAuthFailureDetector, "targetAuthFailureDetector");
        this.scenarioManager = scenarioManager;
        this.teardownFlowRunner = teardownFlowRunner;
    }

    /**
     * Runs one access test case.
     *
     * @param testCase test case to run
     * @param workspace workspace snapshot containing referenced objects
     * @param env environment variables
     * @param scenario scenario variables
     * @param legacy legacy variables
     * @param renderMode render mode
     * @return run result
     */
    public AccessRunResult run(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode renderMode) {
        Objects.requireNonNull(testCase, "testCase");
        Objects.requireNonNull(workspace, "workspace");
        RenderMode mode = Objects.requireNonNullElse(renderMode, RenderMode.STRICT);
        long startedAt = System.currentTimeMillis();

        if (testCase.targetType() == AccessTargetType.FLOW_TARGET) {
            return runFlowTarget(testCase, workspace, env, scenario, legacy, mode, startedAt);
        }

        AccessRequestTemplate template = workspace.templates().get(testCase.templateId());
        if (template == null) {
            return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                    "Template not found: " + testCase.templateId(), startedAt, List.of());
        }
        AuthProfile authProfile = workspace.authProfiles().get(testCase.target().targetAuthProfileId());
        if (authProfile == null) {
            return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                    "Auth profile not found: " + testCase.target().targetAuthProfileId(), startedAt, List.of());
        }
        DataProfile dataProfile = null;
        if (!testCase.target().dataProfileId().isBlank()) {
            dataProfile = workspace.dataProfiles().get(testCase.target().dataProfileId());
            if (dataProfile == null) {
                return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                        "Data profile not found: " + testCase.target().dataProfileId(), startedAt, List.of());
            }
        }

        Map<String, String> targetScenario = Objects.requireNonNullElse(scenario, Map.of());
        List<String> scenarioWarnings = List.of();
        if (testCase.setup().mode() != SetupMode.NONE) {
            if (scenarioManager == null) {
                return notSent(testCase, RunStatus.SKIPPED, Verdict.NOT_RUN,
                        "Scenario setup is not configured", startedAt, List.of());
            }
            ScenarioPreparationResult scenarioResult = scenarioManager.prepareScenario(
                    testCase,
                    workspace,
                    Objects.requireNonNullElse(env, Map.of()),
                    Objects.requireNonNullElse(legacy, Map.of()),
                    mode);
            scenarioWarnings = scenarioResult.warnings();
            if (scenarioResult.failed()) {
                return notSent(testCase, RunStatus.FAILED, Verdict.ERROR,
                        "Scenario setup failed: " + scenarioResult.reason(), startedAt, scenarioWarnings);
            }
            targetScenario = scenarioVariables(targetScenario, scenarioResult.scenario());
        }

        AuthProvider authProvider = providerFor(authProfile, workspace);
        List<String> authWarnings = List.of();
        if (authManager != null && !isManual(authProfile)) {
            AuthEnsureResult ensureResult = authManager.ensureAuth(
                    authProfile.id(),
                    testCase.target().environmentId(),
                    env,
                    mode);
            authWarnings = ensureResult.warnings();
            if (ensureResult.failed()) {
                return notSent(testCase, RunStatus.FAILED, Verdict.ERROR,
                        "Auth ensure failed: " + ensureResult.reason(), startedAt,
                        warnings(scenarioWarnings, authWarnings));
            }
            authProfile = ensureResult.updatedProfile().orElse(authProfile);
            authProvider = providerFor(authProfile, workspace);
        }

        PreparedTargetRequest prepared = prepareTargetRequest(
                template,
                authProfile,
                authProvider,
                dataProfile,
                env,
                targetScenario,
                legacy,
                mode,
                warnings(scenarioWarnings, authWarnings));
        if (!prepared.success()) {
            return notSent(testCase, RunStatus.INVALID, Verdict.NOT_RUN,
                    prepared.reason(), startedAt, prepared.warnings());
        }

        try {
            SentTargetRequest sent = sendTargetRequest(prepared.request().orElseThrow(), prepared.rawRequest());
            if (shouldRetryTargetAuth(authProfile, authProvider, sent)) {
                AuthEnsureResult retryResult = authManager.retryAuth(
                        authProfile.id(),
                        testCase.target().environmentId(),
                        env,
                        mode,
                        authProvider.targetRetryPolicy());
                List<String> retryWarnings = warnings(
                        prepared.warnings(),
                        retryResult.warnings(),
                        List.of("Target auth failure detected; auth retry policy executed"));
                if (retryResult.failed()) {
                    return sentFailure(testCase, workspace, startedAt, sent,
                            "Auth retry failed: " + retryResult.reason(), retryWarnings,
                            authProfile, dataProfile, env, targetScenario, legacy, mode);
                }

                authProfile = retryResult.updatedProfile().orElse(authProfile);
                PreparedTargetRequest retryPrepared = prepareTargetRequest(
                        template,
                        authProfile,
                        authProvider,
                        dataProfile,
                        env,
                        targetScenario,
                        legacy,
                        mode,
                        retryWarnings);
                if (!retryPrepared.success()) {
                    return sentFailure(testCase, workspace, startedAt, sent,
                            "Auth retry request render failed: " + retryPrepared.reason(),
                            retryPrepared.warnings(),
                            authProfile, dataProfile, env, targetScenario, legacy, mode);
                }
                sent = sendTargetRequest(retryPrepared.request().orElseThrow(), retryPrepared.rawRequest());
                return completed(testCase, workspace, startedAt, sent, retryPrepared.warnings(),
                        authProfile, dataProfile, env, targetScenario, legacy, mode);
            }
            return completed(testCase, workspace, startedAt, sent, prepared.warnings(),
                    authProfile, dataProfile, env, targetScenario, legacy, mode);
        } catch (RuntimeException exception) {
            return requestFailed(testCase, workspace, startedAt, prepared.rawRequest(), prepared.warnings(), exception,
                    authProfile, dataProfile, env, targetScenario, legacy, mode);
        }
    }

    /**
     * Renders target and post-check requests without sending HTTP traffic.
     *
     * @param testCase test case to render
     * @param workspace workspace snapshot containing referenced objects
     * @param env environment variables
     * @param scenario scenario variables
     * @param legacy legacy variables
     * @param renderMode render mode
     * @return dry-run result
     */
    public AccessRunResult dryRun(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode renderMode) {
        Objects.requireNonNull(testCase, "testCase");
        Objects.requireNonNull(workspace, "workspace");
        RenderMode mode = Objects.requireNonNullElse(renderMode, RenderMode.STRICT);
        long startedAt = System.currentTimeMillis();

        if (testCase.targetType() == AccessTargetType.FLOW_TARGET) {
            return dryRunFlowTarget(testCase, workspace, env, scenario, legacy, mode, startedAt);
        }

        AccessRequestTemplate template = workspace.templates().get(testCase.templateId());
        if (template == null) {
            return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                    "Dry run: template not found: " + testCase.templateId(), startedAt, List.of());
        }
        AuthProfile authProfile = workspace.authProfiles().get(testCase.target().targetAuthProfileId());
        if (authProfile == null) {
            return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                    "Dry run: auth profile not found: " + testCase.target().targetAuthProfileId(), startedAt, List.of());
        }
        DataProfile dataProfile = null;
        if (!testCase.target().dataProfileId().isBlank()) {
            dataProfile = workspace.dataProfiles().get(testCase.target().dataProfileId());
            if (dataProfile == null) {
                return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                        "Dry run: data profile not found: " + testCase.target().dataProfileId(), startedAt, List.of());
            }
        }

        DryRunScenario dryRunScenario = dryRunScenario(testCase, workspace, scenario);
        if (dryRunScenario.failed()) {
            return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                    dryRunScenario.reason(), startedAt, dryRunScenario.warnings());
        }
        List<String> dryRunWarnings = new ArrayList<>(dryRunScenario.warnings());
        AuthProvider authProvider = providerFor(authProfile, workspace);
        if (!isManual(authProfile)) {
            dryRunWarnings.add("Dry run did not refresh or validate non-manual auth profile: " + authProfile.id());
            if (authProvider == null) {
                dryRunWarnings.add("Dry run could not resolve auth provider: " + authProfile.providerId());
            }
        }

        PreparedTargetRequest prepared = prepareTargetRequest(
                template,
                authProfile,
                authProvider,
                dataProfile,
                env,
                dryRunScenario.scenario(),
                legacy,
                mode,
                dryRunWarnings);
        if (!prepared.success()) {
            return notSent(testCase, RunStatus.INVALID, Verdict.NOT_RUN,
                    "Dry run render failed: " + prepared.reason(), startedAt, prepared.warnings());
        }

        DryRunPostCheckSummary postChecks = dryRunPostChecks(
                testCase,
                workspace,
                authProfile,
                dataProfile,
                env,
                dryRunScenario.scenario(),
                legacy,
                mode);
        TeardownRunResult teardown = dryRunTeardown(
                testCase,
                workspace,
                authProfile,
                dataProfile,
                env,
                dryRunScenario.scenario(),
                legacy,
                mode);
        long finishedAt = Math.max(System.currentTimeMillis(), startedAt);
        String resultId = idGenerator.newId("result");
        String redactedRequest = redactionService.redactHttpMessage(prepared.rawRequest());
        boolean failed = postChecks.failed() || teardownError(teardown);
        RunStatus status = failed ? RunStatus.INVALID : RunStatus.COMPLETED;
        Verdict verdict = failed ? Verdict.ERROR : Verdict.REVIEW;
        String reason = failed
                ? "Dry run completed target render but dependent render validation failed"
                : "Dry run completed: rendered target, post-check, and teardown requests without sending HTTP";
        AccessTestResult result = new AccessTestResult(
                resultId,
                testCase.id(),
                status,
                verdict,
                reason,
                startedAt,
                finishedAt,
                redactedRequest,
                "",
                warnings(prepared.warnings(), postChecks.warnings(), teardown.warnings()));
        AccessEvidence evidence = new AccessEvidence(
                testCase.id(),
                resultId,
                prepared.rawRequest(),
                redactedRequest,
                "",
                "",
                postChecks.evidence(),
                teardownEvidence(teardown),
                finishedAt);
        return new AccessRunResult(result, Optional.of(evidence), false);
    }

    private AccessRunResult runFlowTarget(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode,
            long startedAt) {
        AccessFlowTarget flowTarget = workspace.flowTargets().get(testCase.flowTargetId());
        if (flowTarget == null) {
            return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                    "Flow target not found: " + testCase.flowTargetId(), startedAt, List.of());
        }
        AuthProfile authProfile = workspace.authProfiles().get(testCase.target().targetAuthProfileId());
        if (authProfile == null) {
            return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                    "Auth profile not found: " + testCase.target().targetAuthProfileId(), startedAt, List.of());
        }
        DataProfile dataProfile = null;
        if (!testCase.target().dataProfileId().isBlank()) {
            dataProfile = workspace.dataProfiles().get(testCase.target().dataProfileId());
            if (dataProfile == null) {
                return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                        "Data profile not found: " + testCase.target().dataProfileId(), startedAt, List.of());
            }
        }

        Map<String, String> targetScenario = Objects.requireNonNullElse(scenario, Map.of());
        List<String> scenarioWarnings = List.of();
        if (testCase.setup().mode() != SetupMode.NONE) {
            if (scenarioManager == null) {
                return notSent(testCase, RunStatus.SKIPPED, Verdict.NOT_RUN,
                        "Scenario setup is not configured", startedAt, List.of());
            }
            ScenarioPreparationResult scenarioResult = scenarioManager.prepareScenario(
                    testCase,
                    workspace,
                    Objects.requireNonNullElse(env, Map.of()),
                    Objects.requireNonNullElse(legacy, Map.of()),
                    mode);
            scenarioWarnings = scenarioResult.warnings();
            if (scenarioResult.failed()) {
                return notSent(testCase, RunStatus.FAILED, Verdict.ERROR,
                        "Scenario setup failed: " + scenarioResult.reason(), startedAt, scenarioWarnings);
            }
            targetScenario = scenarioVariables(targetScenario, scenarioResult.scenario());
        }

        AuthProvider authProvider = providerFor(authProfile, workspace);
        List<String> authWarnings = List.of();
        if (authManager != null && !isManual(authProfile)) {
            AuthEnsureResult ensureResult = authManager.ensureAuth(
                    authProfile.id(),
                    testCase.target().environmentId(),
                    env,
                    mode);
            authWarnings = ensureResult.warnings();
            if (ensureResult.failed()) {
                return notSent(testCase, RunStatus.FAILED, Verdict.ERROR,
                        "Auth ensure failed: " + ensureResult.reason(), startedAt,
                        warnings(scenarioWarnings, authWarnings));
            }
            authProfile = ensureResult.updatedProfile().orElse(authProfile);
            authProvider = providerFor(authProfile, workspace);
        }

        FlowTargetExecution execution = executeFlowTarget(
                flowTarget,
                authProfile,
                authProvider,
                dataProfile,
                env,
                targetScenario,
                legacy,
                mode,
                false,
                warnings(scenarioWarnings, authWarnings));
        if (!execution.success()) {
            return flowTargetFailed(testCase, workspace, startedAt, execution, authProfile, dataProfile,
                    env, execution.scenario(), legacy, mode);
        }
        SentTargetRequest targetSent = execution.targetSent().orElseThrow();
        List<String> runWarnings = new ArrayList<>(execution.warnings());
        if (shouldRetryTargetAuth(authProfile, authProvider, targetSent)) {
            runWarnings.add("Target auth failure detected on flow target; full-flow auth retry is not supported in V1");
        }
        return flowTargetCompleted(testCase, workspace, startedAt, execution, runWarnings,
                authProfile, dataProfile, env, execution.scenario(), legacy, mode);
    }

    private AccessRunResult dryRunFlowTarget(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode,
            long startedAt) {
        AccessFlowTarget flowTarget = workspace.flowTargets().get(testCase.flowTargetId());
        if (flowTarget == null) {
            return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                    "Dry run: flow target not found: " + testCase.flowTargetId(), startedAt, List.of());
        }
        AuthProfile authProfile = workspace.authProfiles().get(testCase.target().targetAuthProfileId());
        if (authProfile == null) {
            return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                    "Dry run: auth profile not found: " + testCase.target().targetAuthProfileId(), startedAt, List.of());
        }
        DataProfile dataProfile = null;
        if (!testCase.target().dataProfileId().isBlank()) {
            dataProfile = workspace.dataProfiles().get(testCase.target().dataProfileId());
            if (dataProfile == null) {
                return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                        "Dry run: data profile not found: " + testCase.target().dataProfileId(), startedAt, List.of());
            }
        }

        DryRunScenario dryRunScenario = dryRunScenario(testCase, workspace, scenario);
        if (dryRunScenario.failed()) {
            return notSent(testCase, RunStatus.INVALID, Verdict.ERROR,
                    dryRunScenario.reason(), startedAt, dryRunScenario.warnings());
        }
        List<String> dryRunWarnings = new ArrayList<>(dryRunScenario.warnings());
        AuthProvider authProvider = providerFor(authProfile, workspace);
        if (!isManual(authProfile)) {
            dryRunWarnings.add("Dry run did not refresh or validate non-manual auth profile: " + authProfile.id());
            if (authProvider == null) {
                dryRunWarnings.add("Dry run could not resolve auth provider: " + authProfile.providerId());
            }
        }

        FlowTargetExecution execution = executeFlowTarget(
                flowTarget,
                authProfile,
                authProvider,
                dataProfile,
                env,
                dryRunScenario.scenario(),
                legacy,
                mode,
                true,
                dryRunWarnings);
        if (!execution.success()) {
            return flowTargetDryRunResult(testCase, startedAt, execution, true);
        }
        return flowTargetDryRunResult(testCase, startedAt, execution, false);
    }

    private FlowTargetExecution executeFlowTarget(
            AccessFlowTarget flowTarget,
            AuthProfile authProfile,
            AuthProvider authProvider,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode,
            boolean dryRun,
            List<String> baseWarnings) {
        ArrayList<String> warnings = new ArrayList<>(Objects.requireNonNullElse(baseWarnings, List.of()));
        ScopedVariableStore store = storeFactory.from(env, authProfile.state(), dataProfile, scenario, legacy);
        FlowDefinition flow = applyFlowOverrides(flowTarget, store, mode, warnings);
        List<FlowStep> steps = stepsForFlowTarget(flow, flowTarget);
        if (steps.isEmpty()) {
            return FlowTargetExecution.failed(
                    "Flow target has no enabled steps through target step: " + flowTarget.targetStepId(),
                    warnings,
                    List.of(),
                    false,
                    Objects.requireNonNullElse(scenario, Map.of()));
        }

        LinkedHashMap<String, String> context = initialFlowVariables(env, authProfile, dataProfile, scenario, legacy);
        LinkedHashMap<String, String> scenarioVariables = new LinkedHashMap<>(Objects.requireNonNullElse(scenario, Map.of()));
        ArrayList<FlowStepEvidence> evidence = new ArrayList<>();
        SentTargetRequest targetSent = null;
        boolean requestSent = false;
        boolean targetRendered = false;
        boolean fullFlowMode = flowTarget.runMode() == FlowTargetRunMode.FULL_FLOW_EVALUATE_STEP;

        for (FlowStep step : steps) {
            boolean isTarget = step.id().equals(flowTarget.targetStepId());
            long capturedAt = System.currentTimeMillis();
            StepPreparation preparation = prepareFlowStep(step, context, authProfile, authProvider, store, mode, warnings);
            String redactedRequest = redactionService.redactHttpMessage(preparation.rawRequest());
            if (dryRun) {
                evidence.add(new FlowStepEvidence(
                        step.id(),
                        step.name(),
                        isTarget,
                        redactedRequest,
                        "",
                        0,
                        true,
                        "Dry run rendered flow step; no HTTP request was sent",
                        Math.max(System.currentTimeMillis(), capturedAt)));
                if (isTarget) {
                    targetRendered = true;
                    if (!fullFlowMode) {
                        return FlowTargetExecution.success(warnings, evidence, Optional.empty(), false,
                                scenarioVariables, "Dry run rendered flow target through selected target step");
                    }
                }
                continue;
            }

            try {
                HttpResponse response = httpClient.send(requestAdapter.toHttpRequest(preparation.request()));
                requestSent = true;
                String rawResponse = rawResponse(response);
                SentTargetRequest sent = new SentTargetRequest(
                        preparation.request(),
                        preparation.rawRequest(),
                        response,
                        rawResponse);
                extractFlowVariables(step, preparation.request(), response, rawResponse, context, scenarioVariables);
                evidence.add(new FlowStepEvidence(
                        step.id(),
                        step.name(),
                        isTarget,
                        redactedRequest,
                        redactionService.redactHttpMessage(rawResponse),
                        response.statusCode(),
                        true,
                        "Flow step completed",
                        Math.max(System.currentTimeMillis(), capturedAt)));
                if (isTarget) {
                    targetSent = sent;
                    if (!fullFlowMode) {
                        break;
                    }
                }
            } catch (RuntimeException exception) {
                evidence.add(new FlowStepEvidence(
                        step.id(),
                        step.name(),
                        isTarget,
                        redactedRequest,
                        "",
                        0,
                        false,
                        "Flow step request failed: " + exception.getMessage(),
                        Math.max(System.currentTimeMillis(), capturedAt)));
                if (fullFlowMode && targetSent != null && !isTarget) {
                    warnings.add("Post-target flow step '" + step.name() + "' failed after selected target: "
                            + exception.getMessage());
                    break;
                }
                return FlowTargetExecution.failed(
                        "Flow target step '" + step.name() + "' failed: " + exception.getMessage(),
                        warnings,
                        evidence,
                        requestSent,
                        scenarioVariables);
            }
        }

        if (dryRun && targetRendered) {
            return FlowTargetExecution.success(
                    warnings,
                    evidence,
                    Optional.empty(),
                    false,
                    scenarioVariables,
                    fullFlowMode
                            ? "Dry run rendered full flow and selected target step"
                            : "Dry run rendered flow target through selected target step");
        }
        if (targetSent == null) {
            return FlowTargetExecution.failed(
                    "Flow target step was not executed: " + flowTarget.targetStepId(),
                    warnings,
                    evidence,
                    requestSent,
                    scenarioVariables);
        }
        return FlowTargetExecution.success(
                warnings,
                evidence,
                Optional.of(targetSent),
                requestSent,
                scenarioVariables,
                "Flow target completed");
    }

    private FlowDefinition applyFlowOverrides(
            AccessFlowTarget flowTarget,
            ScopedVariableStore store,
            RenderMode mode,
            List<String> warnings) {
        if (flowTarget.injectionOverrides().stream().noneMatch(FlowInjectionOverride::enabled)) {
            return flowTarget.flowSnapshot();
        }
        ArrayList<FlowStep> steps = new ArrayList<>();
        for (FlowStep step : flowTarget.flowSnapshot().steps()) {
            ArrayList<InjectRule> rules = new ArrayList<>();
            for (InjectRule rule : step.injectRules()) {
                InjectRule current = rule;
                for (FlowInjectionOverride override : flowTarget.injectionOverrides()) {
                    if (!override.enabled() || !matchesOverride(override, current)) {
                        continue;
                    }
                    RenderResult rendered = new TemplateRenderer(new VariableResolver(new DefaultRuntimeValueProvider()))
                            .render(override.valueTemplate(), store, mode);
                    warnings.addAll(rendered.warnings());
                    if (rendered.hasUnresolvedVariables()) {
                        warnings.add("Flow override '" + override.name()
                                + "' has unresolved variables; preserved placeholder text in override value");
                    }
                    current = new InjectRule(
                            current.id(),
                            current.name(),
                            current.enabled(),
                            current.target(),
                            current.targetSelector(),
                            InjectRule.ValueSourceType.STATIC,
                            "",
                            rendered.rendered(),
                            current.writeStrategy(),
                            current.transformChain(),
                            current.ruleType());
                }
                rules.add(current);
            }
            steps.add(step.withInjectRules(rules));
        }
        warnUnmatchedOverrides(flowTarget, warnings);
        return new FlowDefinition(
                flowTarget.flowSnapshot().id(),
                flowTarget.flowSnapshot().name(),
                flowTarget.flowSnapshot().description(),
                flowTarget.flowSnapshot().enabled(),
                steps,
                flowTarget.flowSnapshot().rules(),
                flowTarget.flowSnapshot().aggregation(),
                flowTarget.flowSnapshot().triggerConfig());
    }

    private boolean matchesOverride(FlowInjectionOverride override, InjectRule rule) {
        if (override.targetType() == FlowInjectionOverrideTarget.RULE_ID) {
            return rule.id().equals(override.targetKey());
        }
        return rule.valueSourceType() == InjectRule.ValueSourceType.VARIABLE
                && rule.valueSourceKey().equals(override.targetKey());
    }

    private void warnUnmatchedOverrides(AccessFlowTarget flowTarget, List<String> warnings) {
        for (FlowInjectionOverride override : flowTarget.injectionOverrides()) {
            if (!override.enabled()) {
                continue;
            }
            boolean matched = flowTarget.flowSnapshot().steps().stream()
                    .flatMap(step -> step.injectRules().stream())
                    .anyMatch(rule -> matchesOverride(override, rule));
            if (!matched) {
                warnings.add("Flow override '" + override.name() + "' did not match any inject rule");
            }
        }
    }

    private List<FlowStep> stepsThroughTarget(FlowDefinition flow, String targetStepId) {
        ArrayList<FlowStep> steps = new ArrayList<>();
        boolean found = false;
        for (FlowStep step : flow.steps()) {
            if (!step.enabled()) {
                if (step.id().equals(targetStepId)) {
                    found = true;
                    break;
                }
                continue;
            }
            steps.add(step);
            if (step.id().equals(targetStepId)) {
                found = true;
                break;
            }
        }
        return found ? List.copyOf(steps) : List.of();
    }

    private List<FlowStep> stepsForFlowTarget(FlowDefinition flow, AccessFlowTarget flowTarget) {
        if (flowTarget.runMode() == FlowTargetRunMode.FULL_FLOW_EVALUATE_STEP) {
            boolean targetExists = flow.steps().stream()
                    .anyMatch(step -> step.id().equals(flowTarget.targetStepId()) && step.enabled());
            if (!targetExists) {
                return List.of();
            }
            return flow.steps().stream()
                    .filter(FlowStep::enabled)
                    .toList();
        }
        return stepsThroughTarget(flow, flowTarget.targetStepId());
    }

    private StepPreparation prepareFlowStep(
            FlowStep step,
            Map<String, String> context,
            AuthProfile authProfile,
            AuthProvider authProvider,
            ScopedVariableStore store,
            RenderMode mode,
            List<String> warnings) {
        StoredRequestTemplate template = step.requestTemplate();
        InjectionService.MutableHttpRequest mutableRequest = new InjectionService.MutableHttpRequest(
                template.method() + " " + template.pathAndQuery() + " HTTP/1.1",
                template.headerLines(),
                template.body());
        RequestMutationResult mutation = injectionService.apply(step.injectRules(), context, mutableRequest);
        warnings.addAll(mutation.diagnostics().stream()
                .map(message -> "Flow step '" + step.name() + "': " + message)
                .toList());
        StoredRequestTemplate request = requestFromMutation(template, mutation);
        if (authProvider != null && !isManual(authProfile)) {
            AuthProviderApplicationResult providerResult = providerAuthApplier.apply(request, authProvider, store, mode);
            request = providerResult.request();
            warnings.addAll(providerResult.warnings());
        }
        request = authApplier.apply(request, authProfile.state(), store, mode);
        return new StepPreparation(request, requestAdapter.rawRequest(request));
    }

    private StoredRequestTemplate requestFromMutation(StoredRequestTemplate original, RequestMutationResult mutation) {
        String[] parts = mutation.requestLine().split(" ", 3);
        String method = parts.length > 0 && !parts[0].isBlank() ? parts[0] : original.method();
        String pathAndQuery = parts.length > 1 && !parts[1].isBlank() ? parts[1] : original.pathAndQuery();
        return new StoredRequestTemplate(
                method,
                original.scheme(),
                original.host(),
                original.port(),
                pathAndQuery,
                mutation.headers(),
                mutation.body());
    }

    private void extractFlowVariables(
            FlowStep step,
            StoredRequestTemplate request,
            HttpResponse response,
            String rawResponse,
            Map<String, String> context,
            Map<String, String> scenarioVariables) {
        HttpMessageSnapshot requestSnapshot = new HttpMessageSnapshot(
                request.method() + " " + request.pathAndQuery() + " HTTP/1.1",
                request.headerLines(),
                request.body());
        HttpMessageSnapshot responseSnapshot = new HttpMessageSnapshot(
                response.httpVersion() + " " + response.statusCode() + " " + response.reasonPhrase(),
                response.headers().stream().map(HttpHeader::toString).toList(),
                response.bodyToString());
        for (ExtractRule rule : step.extractRules()) {
            if (!rule.enabled()) {
                continue;
            }
            ExtractionResult result = extractionService.extract(rule, requestSnapshot, responseSnapshot);
            if (result.isSuccess()) {
                addFlowVariable(context, result.targetVariable(), result.value());
                if (result.targetVariable().startsWith("scenario.")) {
                    scenarioVariables.put(result.targetVariable().substring("scenario.".length()), result.value());
                }
            }
        }
    }

    private LinkedHashMap<String, String> initialFlowVariables(
            Map<String, String> env,
            AuthProfile authProfile,
            DataProfile dataProfile,
            Map<String, String> scenario,
            Map<String, String> legacy) {
        LinkedHashMap<String, String> variables = new LinkedHashMap<>();
        addScopedVariables(variables, "env", env);
        if (authProfile != null) {
            addScopedVariables(variables, "auth", authProfile.state().variables());
        }
        if (dataProfile != null) {
            addScopedVariables(variables, "data", dataProfile.values());
        }
        addScopedVariables(variables, "scenario", scenario);
        addScopedVariables(variables, "legacy", legacy);
        return variables;
    }

    private void addScopedVariables(Map<String, String> target, String scope, Map<String, String> values) {
        Objects.requireNonNullElse(values, Map.<String, String>of()).forEach((key, value) -> {
            String normalizedKey = key == null ? "" : key.strip();
            if (normalizedKey.isBlank()) {
                return;
            }
            String normalizedValue = value == null ? "" : value;
            target.put(scope + "." + normalizedKey, normalizedValue);
            target.putIfAbsent(normalizedKey, normalizedValue);
        });
    }

    private void addFlowVariable(Map<String, String> target, String key, String value) {
        String normalizedKey = key == null ? "" : key.strip();
        if (normalizedKey.isBlank()) {
            return;
        }
        String normalizedValue = value == null ? "" : value;
        target.put(normalizedKey, normalizedValue);
        int dot = normalizedKey.indexOf('.');
        if (dot > 0 && dot + 1 < normalizedKey.length()) {
            target.putIfAbsent(normalizedKey.substring(dot + 1), normalizedValue);
        }
    }

    private AccessRunResult flowTargetCompleted(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            long startedAt,
            FlowTargetExecution execution,
            List<String> runWarnings,
            AuthProfile authProfile,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        SentTargetRequest sent = execution.targetSent().orElseThrow();
        long finishedAt = Math.max(System.currentTimeMillis(), startedAt);
        EvaluationProfile profile = evaluationProfile(testCase, workspace).orElse(null);
        EvaluationResult evaluation = evaluator.evaluate(
                testCase.expected(),
                sent.response().statusCode(),
                sent.rawResponse(),
                profile);
        PostCheckRunSummary postChecks = runPostChecks(
                testCase,
                workspace,
                authProfile,
                dataProfile,
                env,
                scenario,
                legacy,
                mode);
        Verdict finalVerdict = finalVerdict(evaluation.verdict(), testCase.expected(), postChecks);
        String reason = postChecks.reason().isBlank()
                ? evaluation.reason()
                : evaluation.reason() + " " + postChecks.reason();
        TeardownRunResult teardown = runTeardown(
                testCase,
                workspace,
                authProfile,
                dataProfile,
                env,
                scenario,
                legacy,
                mode,
                RunStatus.COMPLETED,
                finalVerdict);
        String resultId = idGenerator.newId("result");
        String redactedRequest = redactionService.redactHttpMessage(sent.rawRequest());
        String redactedResponse = redactionService.redactHttpMessage(sent.rawResponse());
        long finishedAtAfterTeardown = Math.max(System.currentTimeMillis(), finishedAt);
        AccessTestResult result = new AccessTestResult(
                resultId,
                testCase.id(),
                RunStatus.COMPLETED,
                finalVerdict,
                "Flow target completed. " + reason,
                startedAt,
                finishedAtAfterTeardown,
                redactedRequest,
                redactedResponse,
                warnings(runWarnings, evaluation.warnings(), postChecks.warnings(), teardown.warnings()));
        AccessEvidence evidence = new AccessEvidence(
                testCase.id(),
                resultId,
                sent.rawRequest(),
                redactedRequest,
                sent.rawResponse(),
                redactedResponse,
                execution.evidence(),
                postChecks.evidence(),
                teardownEvidence(teardown),
                finishedAtAfterTeardown);
        return new AccessRunResult(result, Optional.of(evidence), true);
    }

    private AccessRunResult flowTargetFailed(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            long startedAt,
            FlowTargetExecution execution,
            AuthProfile authProfile,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        long finishedAt = Math.max(System.currentTimeMillis(), startedAt);
        TeardownRunResult teardown = execution.requestSent()
                ? runTeardown(testCase, workspace, authProfile, dataProfile, env, scenario, legacy, mode,
                RunStatus.FAILED, Verdict.ERROR)
                : TeardownRunResult.notConfigured();
        long finishedAtAfterTeardown = Math.max(System.currentTimeMillis(), finishedAt);
        AccessTestResult result = new AccessTestResult(
                idGenerator.newId("result"),
                testCase.id(),
                execution.requestSent() ? RunStatus.FAILED : RunStatus.INVALID,
                Verdict.ERROR,
                execution.reason(),
                startedAt,
                finishedAtAfterTeardown,
                targetStepEvidenceSummary(execution.evidence(), true),
                targetStepEvidenceSummary(execution.evidence(), false),
                warnings(execution.warnings(), teardown.warnings()));
        if (!execution.requestSent() && execution.evidence().isEmpty()) {
            return new AccessRunResult(result, Optional.empty(), false);
        }
        AccessEvidence evidence = new AccessEvidence(
                testCase.id(),
                result.id(),
                "",
                targetStepEvidenceSummary(execution.evidence(), true),
                "",
                targetStepEvidenceSummary(execution.evidence(), false),
                execution.evidence(),
                List.of(),
                teardownEvidence(teardown),
                finishedAtAfterTeardown);
        return new AccessRunResult(result, Optional.of(evidence), execution.requestSent());
    }

    private AccessRunResult flowTargetDryRunResult(
            AccessTestCase testCase,
            long startedAt,
            FlowTargetExecution execution,
            boolean failed) {
        long finishedAt = Math.max(System.currentTimeMillis(), startedAt);
        AccessTestResult result = new AccessTestResult(
                idGenerator.newId("result"),
                testCase.id(),
                failed ? RunStatus.INVALID : RunStatus.COMPLETED,
                failed ? Verdict.ERROR : Verdict.REVIEW,
                failed ? "Dry run flow target failed: " + execution.reason()
                        : "Dry run completed: rendered flow target through selected target step without sending HTTP",
                startedAt,
                finishedAt,
                targetStepEvidenceSummary(execution.evidence(), true),
                "",
                execution.warnings());
        AccessEvidence evidence = new AccessEvidence(
                testCase.id(),
                result.id(),
                "",
                targetStepEvidenceSummary(execution.evidence(), true),
                "",
                "",
                execution.evidence(),
                List.of(),
                List.of(),
                finishedAt);
        return new AccessRunResult(result, Optional.of(evidence), false);
    }

    private String targetStepEvidenceSummary(List<FlowStepEvidence> evidence, boolean request) {
        return evidence.stream()
                .filter(FlowStepEvidence::targetStep)
                .findFirst()
                .map(entry -> request ? entry.redactedRequestRaw() : entry.redactedResponseRaw())
                .orElse("");
    }

    /**
     * Runs post-check and teardown hooks after a caller-managed target batch.
     *
     * <p>This method intentionally does not send the target request. It exists for orchestrators such as the
     * race runner that own target-request timing but still need the established post-check and teardown paths.</p>
     *
     * @param testCase test case whose hooks should run
     * @param workspace workspace snapshot containing referenced objects
     * @param targetAuthProfile target auth profile prepared for the target request
     * @param targetDataProfile target data profile, or null
     * @param env environment variables
     * @param scenario scenario variables from target preparation
     * @param legacy legacy variables
     * @param renderMode render mode
     * @param runPostChecks whether to run enabled post-checks
     * @param runTeardown whether to run teardown
     * @param batchRunStatus target batch status
     * @param batchVerdict target batch verdict
     * @return result and evidence for after-batch hooks
     */
    public AccessRunResult runAfterBatchHooks(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode renderMode,
            boolean runPostChecks,
            boolean runTeardown,
            RunStatus batchRunStatus,
            Verdict batchVerdict) {
        Objects.requireNonNull(testCase, "testCase");
        Objects.requireNonNull(workspace, "workspace");
        long startedAt = System.currentTimeMillis();
        RenderMode mode = Objects.requireNonNullElse(renderMode, RenderMode.STRICT);
        PostCheckRunSummary postChecks = runPostChecks
                ? runPostChecks(
                        testCase,
                        workspace,
                        targetAuthProfile,
                        targetDataProfile,
                        env,
                        scenario,
                        legacy,
                        mode)
                : PostCheckRunSummary.empty();
        TeardownRunResult teardown = runTeardown
                ? runTeardown(
                        testCase,
                        workspace,
                        targetAuthProfile,
                        targetDataProfile,
                        env,
                        scenario,
                        legacy,
                        mode,
                        Objects.requireNonNullElse(batchRunStatus, RunStatus.COMPLETED),
                        Objects.requireNonNullElse(batchVerdict, Verdict.REVIEW))
                : TeardownRunResult.notConfigured();
        long finishedAt = Math.max(System.currentTimeMillis(), startedAt);
        Verdict verdict = afterBatchVerdict(postChecks, teardown);
        RunStatus status = teardownError(teardown) ? RunStatus.FAILED : RunStatus.COMPLETED;
        String reason = afterBatchReason(postChecks, teardown);
        AccessTestResult result = new AccessTestResult(
                idGenerator.newId("result"),
                testCase.id(),
                status,
                verdict,
                reason,
                startedAt,
                finishedAt,
                "",
                "",
                warnings(postChecks.warnings(), teardown.warnings()));
        AccessEvidence evidence = new AccessEvidence(
                testCase.id(),
                result.id(),
                "",
                "",
                "",
                "",
                postChecks.evidence(),
                teardownEvidence(teardown),
                finishedAt);
        boolean hookSent = !postChecks.evidence().isEmpty() || teardown.executed();
        return new AccessRunResult(result, Optional.of(evidence), hookSent);
    }

    private PreparedTargetRequest prepareTargetRequest(
            AccessRequestTemplate template,
            AuthProfile authProfile,
            AuthProvider authProvider,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode,
            List<String> baseWarnings) {
        ScopedVariableStore store = storeFactory.from(env, authProfile.state(), dataProfile, scenario, legacy);
        RenderedAccessRequest rendered = templateRenderer.render(template, store, mode);
        List<String> renderWarnings = warnings(baseWarnings, rendered.warnings());
        if (!rendered.success()) {
            return PreparedTargetRequest.invalid(
                    "Unresolved variables: " + unresolvedSummary(rendered),
                    renderWarnings);
        }

        StoredRequestTemplate request = rendered.renderedRequest();
        List<String> preparedWarnings = new ArrayList<>(renderWarnings);
        if (authProvider != null && !isManual(authProfile)) {
            AuthProviderApplicationResult providerResult = providerAuthApplier.apply(request, authProvider, store, mode);
            request = providerResult.request();
            preparedWarnings.addAll(providerResult.warnings());
        }
        request = authApplier.apply(request, authProfile.state(), store, mode);
        return PreparedTargetRequest.success(request, requestAdapter.rawRequest(request), preparedWarnings);
    }

    private SentTargetRequest sendTargetRequest(StoredRequestTemplate request, String rawRequest) {
        HttpResponse response = httpClient.send(requestAdapter.toHttpRequest(request));
        return new SentTargetRequest(request, rawRequest, response, rawResponse(response));
    }

    private boolean shouldRetryTargetAuth(
            AuthProfile authProfile,
            AuthProvider authProvider,
            SentTargetRequest sent) {
        return authManager != null
                && authProvider != null
                && authProvider.enabled()
                && !isManual(authProfile)
                && authProvider.targetRetryPolicy() != AuthRetryPolicy.NO_RETRY
                && targetAuthFailureDetector.isAuthFailure(
                        authProvider,
                        sent.response().statusCode(),
                        sent.rawResponse());
    }

    private AccessRunResult completed(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            long startedAt,
            SentTargetRequest sent,
            List<String> runWarnings,
            AuthProfile authProfile,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        long finishedAt = Math.max(System.currentTimeMillis(), startedAt);
        EvaluationProfile profile = evaluationProfile(testCase, workspace).orElse(null);
        EvaluationResult evaluation = evaluator.evaluate(
                testCase.expected(),
                sent.response().statusCode(),
                sent.rawResponse(),
                profile);
        PostCheckRunSummary postChecks = runPostChecks(
                testCase,
                workspace,
                authProfile,
                dataProfile,
                env,
                scenario,
                legacy,
                mode);
        Verdict finalVerdict = finalVerdict(evaluation.verdict(), testCase.expected(), postChecks);
        String reason = postChecks.reason().isBlank()
                ? evaluation.reason()
                : evaluation.reason() + " " + postChecks.reason();
        TeardownRunResult teardown = runTeardown(
                testCase,
                workspace,
                authProfile,
                dataProfile,
                env,
                scenario,
                legacy,
                mode,
                RunStatus.COMPLETED,
                finalVerdict);
        String resultId = idGenerator.newId("result");
        String redactedRequest = redactionService.redactHttpMessage(sent.rawRequest());
        String redactedResponse = redactionService.redactHttpMessage(sent.rawResponse());
        long finishedAtAfterTeardown = Math.max(System.currentTimeMillis(), finishedAt);
        AccessTestResult result = new AccessTestResult(
                resultId,
                testCase.id(),
                RunStatus.COMPLETED,
                finalVerdict,
                reason,
                startedAt,
                finishedAtAfterTeardown,
                redactedRequest,
                redactedResponse,
                warnings(runWarnings, evaluation.warnings(), postChecks.warnings(), teardown.warnings()));
        AccessEvidence evidence = new AccessEvidence(
                testCase.id(),
                resultId,
                sent.rawRequest(),
                redactedRequest,
                sent.rawResponse(),
                redactedResponse,
                postChecks.evidence(),
                teardownEvidence(teardown),
                finishedAtAfterTeardown);
        return new AccessRunResult(result, Optional.of(evidence), true);
    }

    private PostCheckRunSummary runPostChecks(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        if (testCase.postChecks().stream().noneMatch(PostCheckRequest::enabled)) {
            return PostCheckRunSummary.empty();
        }

        ArrayList<PostCheckEvidence> evidence = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        boolean assertionFailure = false;
        boolean reviewOrError = false;
        for (PostCheckRequest postCheck : testCase.postChecks()) {
            if (!postCheck.enabled()) {
                continue;
            }
            PostCheckExecution execution = runPostCheck(
                    postCheck,
                    testCase,
                    workspace,
                    targetAuthProfile,
                    targetDataProfile,
                    env,
                    scenario,
                    legacy,
                    mode);
            evidence.add(execution.evidence());
            warnings.addAll(execution.warnings());
            assertionFailure = assertionFailure || execution.assertionFailure();
            reviewOrError = reviewOrError || execution.reviewOrError();
        }

        String reason = "";
        if (assertionFailure) {
            reason = "Post-check assertion failure detected.";
        } else if (reviewOrError) {
            reason = "Post-check execution requires review.";
        }
        return new PostCheckRunSummary(List.copyOf(evidence), List.copyOf(warnings), assertionFailure, reviewOrError, reason);
    }

    private DryRunScenario dryRunScenario(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            Map<String, String> scenario) {
        Map<String, String> baseScenario = Objects.requireNonNullElse(scenario, Map.of());
        if (testCase.setup().mode() == SetupMode.NONE) {
            if (testCase.target().scenarioInstanceId().isBlank()) {
                return DryRunScenario.success(baseScenario, List.of());
            }
            ScenarioInstance instance = workspace.scenarios().get(testCase.target().scenarioInstanceId());
            if (instance == null) {
                return DryRunScenario.failed(
                        "Dry run: scenario not found: " + testCase.target().scenarioInstanceId(),
                        List.of());
            }
            return DryRunScenario.success(
                    scenarioVariables(baseScenario, Optional.of(instance)),
                    List.of("Dry run reused target scenario: " + testCase.target().scenarioInstanceId()));
        }
        if (testCase.setup().mode() == SetupMode.REUSE_SCENARIO) {
            String scenarioId = testCase.target().scenarioInstanceId();
            if (scenarioId.isBlank()) {
                return DryRunScenario.failed("Dry run: setup mode REUSE_SCENARIO requires a scenario id", List.of());
            }
            ScenarioInstance instance = workspace.scenarios().get(scenarioId);
            if (instance == null) {
                return DryRunScenario.failed("Dry run: scenario not found: " + scenarioId, List.of());
            }
            return DryRunScenario.success(
                    scenarioVariables(baseScenario, Optional.of(instance)),
                    List.of("Dry run reused scenario: " + scenarioId));
        }
        return DryRunScenario.success(
                baseScenario,
                List.of("Dry run did not execute setup mode " + testCase.setup().mode()
                        + "; unresolved setup-produced variables will be reported during rendering"));
    }

    private DryRunPostCheckSummary dryRunPostChecks(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        if (testCase.postChecks().stream().noneMatch(PostCheckRequest::enabled)) {
            return DryRunPostCheckSummary.empty();
        }

        ArrayList<PostCheckEvidence> evidence = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        boolean failed = false;
        for (PostCheckRequest postCheck : testCase.postChecks()) {
            if (!postCheck.enabled()) {
                continue;
            }
            PostCheckExecution execution = dryRunPostCheck(
                    postCheck,
                    workspace,
                    targetAuthProfile,
                    targetDataProfile,
                    env,
                    scenario,
                    legacy,
                    mode);
            evidence.add(execution.evidence());
            warnings.addAll(execution.warnings());
            failed = failed || execution.reviewOrError();
        }
        return new DryRunPostCheckSummary(List.copyOf(evidence), List.copyOf(warnings), failed);
    }

    private PostCheckExecution dryRunPostCheck(
            PostCheckRequest postCheck,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        long capturedAt = System.currentTimeMillis();
        AccessRequestTemplate template = workspace.templates().get(postCheck.templateId());
        if (template == null) {
            return postCheckError(postCheck, "Dry run: post-check template not found: " + postCheck.templateId(), capturedAt);
        }
        DataProfile dataProfile = resolvePostCheckDataProfile(postCheck, targetDataProfile, workspace).orElse(null);
        AuthProfile authProfile = resolvePostCheckAuthProfile(postCheck, targetAuthProfile, dataProfile, workspace)
                .orElse(null);
        if (authProfile == null) {
            return postCheckError(postCheck, "Dry run: post-check auth profile could not be resolved", capturedAt);
        }
        if (requiresData(template) && dataProfile == null) {
            return postCheckError(postCheck, "Dry run: post-check data profile could not be resolved", capturedAt);
        }

        Optional<Map<String, String>> postScenario = dryRunPostCheckScenario(postCheck, scenario, workspace);
        if (postScenario.isEmpty()) {
            return postCheckError(postCheck,
                    "Dry run: post-check scenario not found: " + postCheck.scenarioInstanceId(),
                    capturedAt);
        }

        ArrayList<String> baseWarnings = new ArrayList<>();
        AuthProvider authProvider = providerFor(authProfile, workspace);
        if (!isManual(authProfile)) {
            baseWarnings.add("Dry run did not refresh or validate non-manual post-check auth profile: "
                    + authProfile.id());
            if (authProvider == null) {
                baseWarnings.add("Dry run could not resolve post-check auth provider: " + authProfile.providerId());
            }
        }
        PreparedTargetRequest prepared = prepareTargetRequest(
                template,
                authProfile,
                authProvider,
                dataProfile,
                env,
                postScenario.orElseThrow(),
                legacy,
                mode,
                baseWarnings);
        if (!prepared.success()) {
            return new PostCheckExecution(
                    new PostCheckEvidence(
                            postCheck.id(),
                            postCheck.name(),
                            "",
                            "",
                            Verdict.ERROR,
                            "Dry run post-check render failed: " + prepared.reason(),
                            List.of(),
                            capturedAt),
                    warnings(prepared.warnings(), List.of("Dry run post-check '" + postCheck.name()
                            + "' render failed: " + prepared.reason())),
                    false,
                    true);
        }

        String redactedRequest = redactionService.redactHttpMessage(prepared.rawRequest());
        return new PostCheckExecution(
                new PostCheckEvidence(
                        postCheck.id(),
                        postCheck.name(),
                        redactedRequest,
                        "",
                        Verdict.REVIEW,
                        "Dry run rendered post-check request; no HTTP request was sent",
                        List.of(),
                        Math.max(System.currentTimeMillis(), capturedAt)),
                prepared.warnings(),
                false,
                false);
    }

    private Optional<Map<String, String>> dryRunPostCheckScenario(
            PostCheckRequest postCheck,
            Map<String, String> scenario,
            AccessWorkspace workspace) {
        if (postCheck.scenarioInstanceId().isBlank()) {
            return Optional.of(Objects.requireNonNullElse(scenario, Map.of()));
        }
        ScenarioInstance instance = workspace.scenarios().get(postCheck.scenarioInstanceId());
        if (instance == null) {
            return Optional.empty();
        }
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(Objects.requireNonNullElse(scenario, Map.of()));
        merged.putAll(instance.toVariableMap());
        return Optional.of(Map.copyOf(merged));
    }

    private PostCheckExecution runPostCheck(
            PostCheckRequest postCheck,
            AccessTestCase testCase,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        long capturedAt = System.currentTimeMillis();
        AccessRequestTemplate template = workspace.templates().get(postCheck.templateId());
        if (template == null) {
            return postCheckError(postCheck, "Post-check template not found: " + postCheck.templateId(), capturedAt);
        }
        DataProfile dataProfile = resolvePostCheckDataProfile(postCheck, targetDataProfile, workspace).orElse(null);
        AuthProfile authProfile = resolvePostCheckAuthProfile(postCheck, targetAuthProfile, dataProfile, workspace)
                .orElse(null);
        if (authProfile == null) {
            return postCheckError(postCheck, "Post-check auth profile could not be resolved", capturedAt);
        }
        if (requiresData(template) && dataProfile == null) {
            return postCheckError(postCheck, "Post-check data profile could not be resolved", capturedAt);
        }

        Map<String, String> postScenario = postCheckScenario(postCheck, scenario, workspace);
        AuthProvider authProvider = providerFor(authProfile, workspace);
        PreparedTargetRequest prepared = prepareTargetRequest(
                template,
                authProfile,
                authProvider,
                dataProfile,
                env,
                postScenario,
                legacy,
                mode,
                List.of());
        if (!prepared.success()) {
            return new PostCheckExecution(
                    new PostCheckEvidence(
                            postCheck.id(),
                            postCheck.name(),
                            "",
                            "",
                            Verdict.ERROR,
                            prepared.reason(),
                            List.of(),
                            capturedAt),
                    warnings(prepared.warnings(), List.of("Post-check '" + postCheck.name() + "' render failed: "
                            + prepared.reason())),
                    false,
                    true);
        }

        try {
            SentTargetRequest sent = sendTargetRequest(prepared.request().orElseThrow(), prepared.rawRequest());
            ResponseSnapshot snapshot = ResponseSnapshot.from(sent.response().statusCode(), sent.rawResponse());
            List<PostCheckAssertionResult> assertionResults = evaluateAssertions(postCheck, snapshot);
            boolean allPassed = assertionResults.stream().allMatch(PostCheckAssertionResult::passed);
            boolean noAssertions = assertionResults.isEmpty();
            Verdict verdict = noAssertions ? Verdict.REVIEW : allPassed ? Verdict.OK : Verdict.SUSPICIOUS;
            String reason = noAssertions ? "No enabled post-check assertions configured"
                    : allPassed ? "Post-check assertions passed" : "Post-check assertion failed";
            String redactedRequest = redactionService.redactHttpMessage(sent.rawRequest());
            String redactedResponse = redactionService.redactHttpMessage(sent.rawResponse());
            ArrayList<String> warnings = new ArrayList<>(prepared.warnings());
            if (noAssertions) {
                warnings.add("Post-check '" + postCheck.name() + "' has no enabled assertions");
            }
            if (!allPassed && !noAssertions) {
                assertionResults.stream()
                        .filter(result -> !result.passed())
                        .forEach(result -> warnings.add("Post-check '" + postCheck.name()
                                + "' assertion failed: " + result.name() + " - " + result.reason()));
            }
            return new PostCheckExecution(
                    new PostCheckEvidence(
                            postCheck.id(),
                            postCheck.name(),
                            redactedRequest,
                            redactedResponse,
                            verdict,
                            reason,
                            assertionResults,
                            Math.max(System.currentTimeMillis(), capturedAt)),
                    List.copyOf(warnings),
                    !allPassed && !noAssertions,
                    noAssertions);
        } catch (RuntimeException exception) {
            String redactedRequest = redactionService.redactHttpMessage(prepared.rawRequest());
            return new PostCheckExecution(
                    new PostCheckEvidence(
                            postCheck.id(),
                            postCheck.name(),
                            redactedRequest,
                            "",
                            Verdict.ERROR,
                            "Post-check request failed: " + exception.getMessage(),
                            List.of(),
                            Math.max(System.currentTimeMillis(), capturedAt)),
                    warnings(prepared.warnings(), List.of("Post-check '" + postCheck.name()
                            + "' request failed: " + exception.getMessage())),
                    false,
                    true);
        }
    }

    private PostCheckExecution postCheckError(PostCheckRequest postCheck, String reason, long capturedAt) {
        return new PostCheckExecution(
                new PostCheckEvidence(
                        postCheck.id(),
                        postCheck.name(),
                        "",
                        "",
                        Verdict.ERROR,
                        reason,
                        List.of(),
                        capturedAt),
                List.of("Post-check '" + postCheck.name() + "': " + reason),
                false,
                true);
    }

    private Optional<DataProfile> resolvePostCheckDataProfile(
            PostCheckRequest postCheck,
            DataProfile targetDataProfile,
            AccessWorkspace workspace) {
        if (!postCheck.dataProfileId().isBlank()) {
            return Optional.ofNullable(workspace.dataProfiles().get(postCheck.dataProfileId()));
        }
        return Optional.ofNullable(targetDataProfile);
    }

    private Optional<AuthProfile> resolvePostCheckAuthProfile(
            PostCheckRequest postCheck,
            AuthProfile targetAuthProfile,
            DataProfile dataProfile,
            AccessWorkspace workspace) {
        PostCheckAuthBinding binding = Objects.requireNonNullElse(postCheck.authBinding(), PostCheckAuthBinding.TARGET_ACTOR);
        return switch (binding) {
            case TARGET_ACTOR -> Optional.ofNullable(targetAuthProfile);
            case DATA_OWNER -> dataProfile == null || dataProfile.ownerAuthProfileId().isBlank()
                    ? Optional.empty()
                    : Optional.ofNullable(workspace.authProfiles().get(dataProfile.ownerAuthProfileId()));
            case FIXED_PROFILE -> postCheck.authProfileId().isBlank()
                    ? Optional.empty()
                    : Optional.ofNullable(workspace.authProfiles().get(postCheck.authProfileId()));
        };
    }

    private Map<String, String> postCheckScenario(
            PostCheckRequest postCheck,
            Map<String, String> scenario,
            AccessWorkspace workspace) {
        if (postCheck.scenarioInstanceId().isBlank()) {
            return Objects.requireNonNullElse(scenario, Map.of());
        }
        ScenarioInstance instance = workspace.scenarios().get(postCheck.scenarioInstanceId());
        if (instance == null) {
            return Objects.requireNonNullElse(scenario, Map.of());
        }
        LinkedHashMap<String, String> merged = new LinkedHashMap<>(Objects.requireNonNullElse(scenario, Map.of()));
        merged.putAll(instance.toVariableMap());
        return Map.copyOf(merged);
    }

    private List<PostCheckAssertionResult> evaluateAssertions(PostCheckRequest postCheck, ResponseSnapshot snapshot) {
        ArrayList<PostCheckAssertionResult> results = new ArrayList<>();
        for (PostCheckAssertion assertion : postCheck.assertions()) {
            if (!assertion.enabled()) {
                continue;
            }
            AssertionEvaluation evaluation = evaluateAssertion(assertion, snapshot);
            boolean passed = assertion.negate() ? !evaluation.passed() : evaluation.passed();
            String reason = assertion.negate() ? "negated: " + evaluation.reason() : evaluation.reason();
            results.add(new PostCheckAssertionResult(assertion.id(), assertion.name(), passed, reason));
        }
        return List.copyOf(results);
    }

    private AssertionEvaluation evaluateAssertion(PostCheckAssertion assertion, ResponseSnapshot snapshot) {
        return switch (assertion.type()) {
            case STATUS_CODE -> statusMatches(snapshot.statusCode(), assertion);
            case BODY_CONTAINS -> contains(snapshot.body(), assertion.expectedValue(), "body contains");
            case BODY_NOT_CONTAINS -> notContains(snapshot.body(), assertion.expectedValue(), "body does not contain");
            case HEADER_EXISTS -> new AssertionEvaluation(
                    snapshot.header(assertion.expression()).isPresent(),
                    "header exists: " + assertion.expression());
            case HEADER_VALUE_CONTAINS -> snapshot.header(assertion.expression())
                    .map(value -> contains(value, assertion.expectedValue(), "header contains"))
                    .orElseGet(() -> new AssertionEvaluation(false, "header missing: " + assertion.expression()));
            case JSON_PATH_EXISTS -> new AssertionEvaluation(
                    jsonValue(snapshot, assertion.expression()).isPresent(),
                    "JSON path exists: " + assertion.expression());
            case JSON_PATH_EQUALS -> jsonValue(snapshot, assertion.expression())
                    .map(node -> new AssertionEvaluation(
                            jsonComparableValue(node).equals(assertion.expectedValue()),
                            "JSON path equals " + assertion.expectedValue()))
                    .orElseGet(() -> new AssertionEvaluation(false, "JSON path missing: " + assertion.expression()));
            case JSON_PATH_NOT_EQUALS -> jsonValue(snapshot, assertion.expression())
                    .map(node -> new AssertionEvaluation(
                            !jsonComparableValue(node).equals(assertion.expectedValue()),
                            "JSON path does not equal " + assertion.expectedValue()))
                    .orElseGet(() -> new AssertionEvaluation(false, "JSON path missing: " + assertion.expression()));
        };
    }

    private AssertionEvaluation statusMatches(int statusCode, PostCheckAssertion assertion) {
        String expected = !assertion.expectedValue().isBlank() ? assertion.expectedValue() : assertion.expression();
        for (String token : expected.split(",")) {
            String value = token.strip();
            if (value.isBlank()) {
                continue;
            }
            if (value.contains("-")) {
                String[] bounds = value.split("-", 2);
                try {
                    int lower = Integer.parseInt(bounds[0].strip());
                    int upper = Integer.parseInt(bounds[1].strip());
                    if (statusCode >= lower && statusCode <= upper) {
                        return new AssertionEvaluation(true, "status " + statusCode + " in " + value);
                    }
                } catch (NumberFormatException ignored) {
                    return new AssertionEvaluation(false, "invalid status range: " + value);
                }
                continue;
            }
            try {
                if (statusCode == Integer.parseInt(value)) {
                    return new AssertionEvaluation(true, "status " + statusCode + " matched");
                }
            } catch (NumberFormatException ignored) {
                return new AssertionEvaluation(false, "invalid status code: " + value);
            }
        }
        return new AssertionEvaluation(false, "status " + statusCode + " did not match " + expected);
    }

    private AssertionEvaluation contains(String source, String expected, String label) {
        return new AssertionEvaluation(source.contains(expected), label + ": " + expected);
    }

    private AssertionEvaluation notContains(String source, String expected, String label) {
        return new AssertionEvaluation(!source.contains(expected), label + ": " + expected);
    }

    private Optional<JsonNode> jsonValue(ResponseSnapshot snapshot, String dotPath) {
        if (dotPath == null || dotPath.isBlank()) {
            return Optional.empty();
        }
        JsonNode node = snapshot.jsonBody(objectMapper).orElse(null);
        if (node == null) {
            return Optional.empty();
        }
        for (String segment : dotPath.split("\\.")) {
            if (segment.isBlank() || node == null || node.isMissingNode() || node.isNull()) {
                return Optional.empty();
            }
            node = node.get(segment.strip());
        }
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        return Optional.of(node);
    }

    private String jsonComparableValue(JsonNode node) {
        if (node.isTextual() || node.isNumber() || node.isBoolean()) {
            return node.asText();
        }
        return node.toString();
    }

    private Verdict finalVerdict(Verdict baseVerdict, ExpectedResult expected, PostCheckRunSummary postChecks) {
        if (postChecks.assertionFailure()) {
            if (baseVerdict == Verdict.ERROR) {
                return Verdict.ERROR;
            }
            if (expected == ExpectedResult.DENY || expected == ExpectedResult.READ_ONLY || expected == ExpectedResult.MASKED) {
                return Verdict.SUSPICIOUS;
            }
            return baseVerdict == Verdict.SUSPICIOUS ? Verdict.SUSPICIOUS : Verdict.REVIEW;
        }
        if (postChecks.reviewOrError() && baseVerdict == Verdict.OK) {
            return Verdict.REVIEW;
        }
        return baseVerdict;
    }

    private TeardownRunResult runTeardown(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode,
            RunStatus runStatus,
            Verdict verdict) {
        if (!shouldRunTeardown(testCase.teardown(), runStatus, verdict)) {
            return TeardownRunResult.notConfigured();
        }
        if (teardownFlowRunner == null) {
            return new TeardownRunResult(
                    true,
                    false,
                    "Teardown runner is not configured",
                    List.of("Teardown configured but teardown runner is not configured"),
                    Optional.empty());
        }
        return teardownFlowRunner.run(
                testCase,
                workspace,
                targetAuthProfile,
                targetDataProfile,
                Objects.requireNonNullElse(env, Map.of()),
                Objects.requireNonNullElse(scenario, Map.of()),
                Objects.requireNonNullElse(legacy, Map.of()),
                mode);
    }

    private TeardownRunResult dryRunTeardown(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        if (testCase.teardown().mode() == TeardownMode.NONE) {
            return TeardownRunResult.notConfigured();
        }
        if (teardownFlowRunner == null) {
            return new TeardownRunResult(
                    true,
                    false,
                    "Teardown runner is not configured",
                    List.of("Teardown configured but teardown runner is not configured"),
                    Optional.empty());
        }
        return teardownFlowRunner.dryRun(
                testCase,
                workspace,
                targetAuthProfile,
                targetDataProfile,
                Objects.requireNonNullElse(env, Map.of()),
                Objects.requireNonNullElse(scenario, Map.of()),
                Objects.requireNonNullElse(legacy, Map.of()),
                mode);
    }

    private boolean shouldRunTeardown(TeardownConfig config, RunStatus runStatus, Verdict verdict) {
        if (config.mode() != TeardownMode.RUN_FLOW) {
            return false;
        }
        if (runStatus == RunStatus.SKIPPED || verdict == Verdict.NOT_RUN) {
            return config.runOnSkipped();
        }
        if (runStatus == RunStatus.COMPLETED && (verdict == Verdict.OK || verdict == Verdict.REVIEW)) {
            return config.runOnSuccess();
        }
        return config.runOnFailure();
    }

    private boolean teardownError(TeardownRunResult teardown) {
        return teardown.executed() && !teardown.success();
    }

    private Verdict afterBatchVerdict(PostCheckRunSummary postChecks, TeardownRunResult teardown) {
        if (teardownError(teardown)) {
            return Verdict.ERROR;
        }
        if (postChecks.assertionFailure()) {
            return Verdict.SUSPICIOUS;
        }
        if (postChecks.reviewOrError()) {
            return Verdict.REVIEW;
        }
        return Verdict.OK;
    }

    private String afterBatchReason(PostCheckRunSummary postChecks, TeardownRunResult teardown) {
        if (teardownError(teardown)) {
            return "After-batch teardown failed: " + teardown.reason();
        }
        if (!postChecks.reason().isBlank()) {
            return "After-batch hooks completed. " + postChecks.reason();
        }
        if (teardown.executed()) {
            return "After-batch hooks completed; teardown " + (teardown.success() ? "succeeded" : "requires review");
        }
        return "After-batch hooks completed";
    }

    private List<TeardownEvidence> teardownEvidence(TeardownRunResult teardown) {
        return teardown.evidence().map(List::of).orElseGet(List::of);
    }

    private boolean requiresData(AccessRequestTemplate template) {
        return template.placeholders().stream()
                .anyMatch(ref -> ref.scope() == dev.nquangit.corestra.access.variable.VariableScope.DATA);
    }

    private AccessRunResult sentFailure(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            long startedAt,
            SentTargetRequest sent,
            String reason,
            List<String> runWarnings,
            AuthProfile authProfile,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        long finishedAt = Math.max(System.currentTimeMillis(), startedAt);
        TeardownRunResult teardown = runTeardown(
                testCase,
                workspace,
                authProfile,
                dataProfile,
                env,
                scenario,
                legacy,
                mode,
                RunStatus.FAILED,
                Verdict.ERROR);
        long finishedAtAfterTeardown = Math.max(System.currentTimeMillis(), finishedAt);
        String resultId = idGenerator.newId("result");
        String redactedRequest = redactionService.redactHttpMessage(sent.rawRequest());
        String redactedResponse = redactionService.redactHttpMessage(sent.rawResponse());
        AccessTestResult result = new AccessTestResult(
                resultId,
                testCase.id(),
                RunStatus.FAILED,
                Verdict.ERROR,
                reason,
                startedAt,
                finishedAtAfterTeardown,
                redactedRequest,
                redactedResponse,
                warnings(runWarnings, teardown.warnings()));
        AccessEvidence evidence = new AccessEvidence(
                testCase.id(),
                resultId,
                sent.rawRequest(),
                redactedRequest,
                sent.rawResponse(),
                redactedResponse,
                List.of(),
                teardownEvidence(teardown),
                finishedAtAfterTeardown);
        return new AccessRunResult(result, Optional.of(evidence), true);
    }

    private AccessRunResult requestFailed(
            AccessTestCase testCase,
            AccessWorkspace workspace,
            long startedAt,
            String rawRequest,
            List<String> runWarnings,
            RuntimeException exception,
            AuthProfile authProfile,
            DataProfile dataProfile,
            Map<String, String> env,
            Map<String, String> scenario,
            Map<String, String> legacy,
            RenderMode mode) {
        long finishedAt = Math.max(System.currentTimeMillis(), startedAt);
        TeardownRunResult teardown = runTeardown(
                testCase,
                workspace,
                authProfile,
                dataProfile,
                env,
                scenario,
                legacy,
                mode,
                RunStatus.FAILED,
                Verdict.ERROR);
        long finishedAtAfterTeardown = Math.max(System.currentTimeMillis(), finishedAt);
        AccessTestResult result = new AccessTestResult(
                idGenerator.newId("result"),
                testCase.id(),
                RunStatus.FAILED,
                Verdict.ERROR,
                "Request failed: " + exception.getMessage(),
                startedAt,
                finishedAtAfterTeardown,
                redactionService.redactHttpMessage(rawRequest),
                "",
                warnings(runWarnings, teardown.warnings()));
        if (teardown.evidence().isEmpty()) {
            return new AccessRunResult(result, Optional.empty(), true);
        }
        AccessEvidence evidence = new AccessEvidence(
                testCase.id(),
                result.id(),
                rawRequest,
                redactionService.redactHttpMessage(rawRequest),
                "",
                "",
                List.of(),
                teardownEvidence(teardown),
                finishedAtAfterTeardown);
        return new AccessRunResult(result, Optional.of(evidence), true);
    }

    private AuthProvider providerFor(AuthProfile authProfile, AccessWorkspace workspace) {
        if (authProfile == null || isManual(authProfile)) {
            return null;
        }
        return workspace.authProviders().get(authProfile.providerId());
    }

    private Optional<EvaluationProfile> evaluationProfile(AccessTestCase testCase, AccessWorkspace workspace) {
        String profileId = testCase.evaluationProfileId();
        if (profileId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(workspace.evaluationProfiles().get(profileId));
    }

    private Map<String, String> scenarioVariables(
            Map<String, String> inputScenario,
            Optional<ScenarioInstance> preparedScenario) {
        if (preparedScenario.isEmpty()) {
            return Objects.requireNonNullElse(inputScenario, Map.of());
        }
        Map<String, String> merged = new java.util.LinkedHashMap<>(Objects.requireNonNullElse(inputScenario, Map.of()));
        merged.putAll(preparedScenario.get().toVariableMap());
        return Map.copyOf(merged);
    }

    private AccessRunResult notSent(
            AccessTestCase testCase,
            RunStatus runStatus,
            Verdict verdict,
            String reason,
            long startedAt,
            List<String> warnings) {
        long finishedAt = Math.max(System.currentTimeMillis(), startedAt);
        AccessTestResult result = new AccessTestResult(
                idGenerator.newId("result"),
                testCase.id(),
                runStatus,
                verdict,
                reason,
                startedAt,
                finishedAt,
                "",
                "",
                warnings);
        return new AccessRunResult(result, Optional.empty(), false);
    }

    private static String unresolvedSummary(RenderedAccessRequest rendered) {
        return rendered.issues().stream()
                .map(issue -> issue.location() + " " + issue.unresolvedVariable().placeholder()
                        + " " + issue.unresolvedVariable().errorType())
                .collect(Collectors.joining("; "));
    }

    @SafeVarargs
    private static List<String> warnings(List<String>... groups) {
        List<String> warnings = new ArrayList<>();
        for (List<String> group : groups) {
            if (group != null) {
                warnings.addAll(group);
            }
        }
        return List.copyOf(warnings);
    }

    private static boolean isManual(AuthProfile authProfile) {
        return authProfile.providerId().isBlank() || AuthProfile.MANUAL_PROVIDER_ID.equals(authProfile.providerId());
    }

    private static AuthProviderRequestApplier defaultProviderAuthApplier() {
        return new AuthProviderRequestApplier(
                new TemplateRenderer(new VariableResolver(new DefaultRuntimeValueProvider())));
    }

    private static String rawResponse(HttpResponse response) {
        StringBuilder raw = new StringBuilder();
        raw.append(response.httpVersion()).append(' ')
                .append(response.statusCode()).append(' ')
                .append(response.reasonPhrase()).append('\n');
        for (HttpHeader header : response.headers()) {
            raw.append(header).append('\n');
        }
        raw.append('\n');
        raw.append(response.bodyToString());
        return raw.toString();
    }

    private record AssertionEvaluation(boolean passed, String reason) {
        private AssertionEvaluation {
            reason = Objects.requireNonNullElse(reason, "");
        }
    }

    private record PostCheckExecution(
            PostCheckEvidence evidence,
            List<String> warnings,
            boolean assertionFailure,
            boolean reviewOrError) {
        private PostCheckExecution {
            evidence = Objects.requireNonNull(evidence, "evidence");
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }
    }

    private record PostCheckRunSummary(
            List<PostCheckEvidence> evidence,
            List<String> warnings,
            boolean assertionFailure,
            boolean reviewOrError,
            String reason) {
        private PostCheckRunSummary {
            evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
            reason = Objects.requireNonNullElse(reason, "");
        }

        private static PostCheckRunSummary empty() {
            return new PostCheckRunSummary(List.of(), List.of(), false, false, "");
        }
    }

    private record DryRunScenario(
            Map<String, String> scenario,
            List<String> warnings,
            boolean failed,
            String reason) {
        private DryRunScenario {
            scenario = Map.copyOf(Objects.requireNonNullElse(scenario, Map.of()));
            warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
            reason = Objects.requireNonNullElse(reason, "");
        }

        private static DryRunScenario success(Map<String, String> scenario, List<String> warnings) {
            return new DryRunScenario(scenario, warnings, false, "");
        }

        private static DryRunScenario failed(String reason, List<String> warnings) {
            return new DryRunScenario(Map.of(), warnings, true, reason);
        }
    }

    private record DryRunPostCheckSummary(
            List<PostCheckEvidence> evidence,
            List<String> warnings,
            boolean failed) {
        private DryRunPostCheckSummary {
            evidence = List.copyOf(Objects.requireNonNullElse(evidence, List.of()));
            warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        }

        private static DryRunPostCheckSummary empty() {
            return new DryRunPostCheckSummary(List.of(), List.of(), false);
        }
    }

    private static final class ResponseSnapshot {
        private final int statusCode;
        private final Map<String, String> headers;
        private final String body;
        private JsonNode parsedJson;
        private boolean jsonParsed;

        private ResponseSnapshot(int statusCode, Map<String, String> headers, String body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        private static ResponseSnapshot from(int statusCode, String rawResponse) {
            String raw = Objects.requireNonNullElse(rawResponse, "");
            String[] sections = raw.split("\\r?\\n\\r?\\n", 2);
            String head = sections.length > 0 ? sections[0] : "";
            String body = sections.length > 1 ? sections[1] : "";
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            String[] lines = head.split("\\r?\\n");
            for (int index = 1; index < lines.length; index++) {
                String line = lines[index];
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                            line.substring(colon + 1).strip());
                }
            }
            return new ResponseSnapshot(statusCode, Map.copyOf(headers), body);
        }

        private int statusCode() {
            return statusCode;
        }

        private String body() {
            return body;
        }

        private Optional<String> header(String name) {
            if (name == null || name.isBlank()) {
                return Optional.empty();
            }
            return Optional.ofNullable(headers.get(name.strip().toLowerCase(Locale.ROOT)));
        }

        private Optional<JsonNode> jsonBody(ObjectMapper objectMapper) {
            if (!jsonParsed) {
                jsonParsed = true;
                try {
                    parsedJson = objectMapper.readTree(body);
                } catch (RuntimeException | java.io.IOException ignored) {
                    parsedJson = null;
                }
            }
            return Optional.ofNullable(parsedJson);
        }
    }

    private record PreparedTargetRequest(
            boolean success,
            String reason,
            Optional<StoredRequestTemplate> request,
            String rawRequest,
            List<String> warnings) {
        private PreparedTargetRequest {
            reason = reason == null ? "" : reason;
            request = Objects.requireNonNull(request, "request");
            warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        }

        private static PreparedTargetRequest success(
                StoredRequestTemplate request,
                String rawRequest,
                List<String> warnings) {
            return new PreparedTargetRequest(
                    true,
                    "",
                    Optional.of(Objects.requireNonNull(request, "request")),
                    Objects.requireNonNull(rawRequest, "rawRequest"),
                    warnings);
        }

        private static PreparedTargetRequest invalid(String reason, List<String> warnings) {
            return new PreparedTargetRequest(false, reason, Optional.empty(), "", warnings);
        }
    }

    private record SentTargetRequest(
            StoredRequestTemplate request,
            String rawRequest,
            HttpResponse response,
            String rawResponse) {
        private SentTargetRequest {
            request = Objects.requireNonNull(request, "request");
            rawRequest = Objects.requireNonNull(rawRequest, "rawRequest");
            response = Objects.requireNonNull(response, "response");
            rawResponse = Objects.requireNonNull(rawResponse, "rawResponse");
        }
    }

    private record StepPreparation(
            StoredRequestTemplate request,
            String rawRequest) {
        private StepPreparation {
            request = Objects.requireNonNull(request, "request");
            rawRequest = Objects.requireNonNull(rawRequest, "rawRequest");
        }
    }

    private record FlowTargetExecution(
            boolean success,
            String reason,
            List<String> warnings,
            List<FlowStepEvidence> evidence,
            Optional<SentTargetRequest> targetSent,
            boolean requestSent,
            Map<String, String> scenario) {
        private FlowTargetExecution {
            reason = Objects.requireNonNullElse(reason, "");
            warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
            evidence = List.copyOf(Objects.requireNonNullElse(evidence, List.of()));
            targetSent = Objects.requireNonNullElse(targetSent, Optional.empty());
            scenario = Map.copyOf(Objects.requireNonNullElse(scenario, Map.of()));
        }

        private static FlowTargetExecution success(
                List<String> warnings,
                List<FlowStepEvidence> evidence,
                Optional<SentTargetRequest> targetSent,
                boolean requestSent,
                Map<String, String> scenario,
                String reason) {
            return new FlowTargetExecution(true, reason, warnings, evidence, targetSent, requestSent, scenario);
        }

        private static FlowTargetExecution failed(
                String reason,
                List<String> warnings,
                List<FlowStepEvidence> evidence,
                boolean requestSent,
                Map<String, String> scenario) {
            return new FlowTargetExecution(false, reason, warnings, evidence, Optional.empty(), requestSent, scenario);
        }
    }
}

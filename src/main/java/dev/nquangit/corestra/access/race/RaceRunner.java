package dev.nquangit.corestra.access.race;

import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.runtime.AuthEnsureResult;
import dev.nquangit.corestra.access.auth.runtime.AuthManager;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.BasicResultEvaluator;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.evaluate.EvaluationResult;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.id.UuidAccessIdGenerator;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.runner.AccessRunResult;
import dev.nquangit.corestra.access.runner.AccessTargetRequestPreparer;
import dev.nquangit.corestra.access.runner.PreparedAccessRequest;
import dev.nquangit.corestra.access.runner.SingleAccessTestRunner;
import dev.nquangit.corestra.access.runner.StoredRequestHttpRequestAdapter;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.scenario.ScenarioManager;
import dev.nquangit.corestra.access.scenario.ScenarioPreparationResult;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationType;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.integration.HttpClient;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs explicitly requested access-test race batches with a synchronization gate.
 */
public final class RaceRunner {
    private final InMemoryAccessRepository repository;
    private final AccessTargetRequestPreparer requestPreparer;
    private final StoredRequestHttpRequestAdapter requestAdapter;
    private final HttpClient httpClient;
    private final BasicResultEvaluator evaluator;
    private final RedactionService redactionService;
    private final AccessIdGenerator idGenerator;
    private final SingleAccessTestRunner singleRunner;
    private final AuthManager authManager;
    private final ScenarioManager scenarioManager;
    private final Clock clock;
    private final ExecutorService executorService;

    /**
     * Creates a race runner.
     *
     * @param repository access repository
     * @param requestPreparer target request preparer
     * @param requestAdapter request adapter
     * @param httpClient HTTP transport
     * @param evaluator result evaluator
     * @param redactionService redaction service
     * @param idGenerator id generator
     * @param singleRunner optional single runner for after-batch hooks
     * @param authManager optional auth manager
     * @param scenarioManager optional scenario manager
     * @param clock clock
     * @param executorService executor service
     */
    public RaceRunner(
            InMemoryAccessRepository repository,
            AccessTargetRequestPreparer requestPreparer,
            StoredRequestHttpRequestAdapter requestAdapter,
            HttpClient httpClient,
            BasicResultEvaluator evaluator,
            RedactionService redactionService,
            AccessIdGenerator idGenerator,
            SingleAccessTestRunner singleRunner,
            AuthManager authManager,
            ScenarioManager scenarioManager,
            Clock clock,
            ExecutorService executorService) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.requestPreparer = Objects.requireNonNull(requestPreparer, "requestPreparer");
        this.requestAdapter = Objects.requireNonNull(requestAdapter, "requestAdapter");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.redactionService = Objects.requireNonNull(redactionService, "redactionService");
        this.idGenerator = Objects.requireNonNullElseGet(idGenerator, UuidAccessIdGenerator::new);
        this.singleRunner = singleRunner;
        this.authManager = authManager;
        this.scenarioManager = scenarioManager;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executorService = Objects.requireNonNullElseGet(executorService,
                () -> Executors.newFixedThreadPool(RaceRunConfig.MAX_CONCURRENCY));
    }

    /**
     * Runs or dry-runs one race configuration.
     *
     * @param config race configuration
     * @param env environment variables
     * @param legacy legacy variables
     * @param renderMode render mode
     * @return race run result
     */
    public RaceRunResult run(
            RaceRunConfig config,
            Map<String, String> env,
            Map<String, String> legacy,
            RenderMode renderMode) {
        RaceRunConfig runConfig = Objects.requireNonNull(config, "config");
        RenderMode mode = Objects.requireNonNullElse(renderMode, RenderMode.STRICT);
        Map<String, String> runEnv = Map.copyOf(Objects.requireNonNullElse(env, Map.of()));
        Map<String, String> runLegacy = Map.copyOf(Objects.requireNonNullElse(legacy, Map.of()));
        long startedAt = clock.millis();
        AccessWorkspace snapshot = repository.snapshot();
        ResolvedTarget target = resolveTarget(runConfig, snapshot, startedAt).orElse(null);
        if (target == null) {
            return invalid(runConfig, startedAt, List.of("Selected matrix row or referenced target objects are missing"));
        }

        ArrayList<String> warnings = new ArrayList<>(safetyWarnings(runConfig, target));
        if (runConfig.dryRun()) {
            return dryRun(runConfig, target, runEnv, runLegacy, mode, warnings, startedAt);
        }

        AuthProfile authProfile = ensureAuthBeforeGate(runConfig, target, runEnv, mode, warnings).orElse(null);
        if (authProfile == null) {
            return invalid(runConfig, startedAt, warnings);
        }
        AuthProvider authProvider = providerFor(authProfile, snapshot);
        if (authProvider != null && authProvider.targetRetryPolicy() != AuthRetryPolicy.NO_RETRY) {
            warnings.add("Target auth retry is disabled during race runs to preserve gate timing.");
        }

        ScenarioPlan sharedScenario = sharedScenario(runConfig, target, runEnv, runLegacy, mode, warnings).orElse(null);
        if (sharedScenario == null) {
            return invalid(runConfig, startedAt, warnings);
        }

        ArrayList<RaceWorkerResult> workerResults = new ArrayList<>();
        Map<String, String> firstScenario = sharedScenario.scenario();
        for (int iteration = 1; iteration <= runConfig.iterations(); iteration++) {
            ScenarioPlan iterationPlan = scenariosForIteration(
                    runConfig,
                    target,
                    runEnv,
                    runLegacy,
                    mode,
                    sharedScenario,
                    iteration,
                    warnings).orElse(null);
            if (iterationPlan == null) {
                return invalid(runConfig, startedAt, warnings);
            }
            if (workerResults.isEmpty() && !iterationPlan.workerScenarios().isEmpty()) {
                firstScenario = iterationPlan.workerScenarios().getFirst();
            }
            IterationOutcome outcome = runIteration(
                    runConfig,
                    target,
                    authProfile,
                    authProvider,
                    iterationPlan.workerScenarios(),
                    runEnv,
                    runLegacy,
                    mode,
                    iteration);
            workerResults.addAll(outcome.results());
            warnings.addAll(outcome.warnings());
            if (outcome.invalid()) {
                return result(runConfig, RaceRunStatus.INVALID, workerResults, warnings, startedAt);
            }
            if (runConfig.stopOnFirstError() && outcome.failed()) {
                warnings.add("Race stopped after first worker error because stopOnFirstError is enabled.");
                break;
            }
            delay(runConfig.betweenIterationDelayMillis(), warnings);
        }

        runAfterBatchHooks(runConfig, target, authProfile, firstScenario, runEnv, runLegacy, mode, workerResults, warnings);
        return result(runConfig, statusFor(workerResults), workerResults, warnings, startedAt);
    }

    private RaceRunResult dryRun(
            RaceRunConfig config,
            ResolvedTarget target,
            Map<String, String> env,
            Map<String, String> legacy,
            RenderMode mode,
            List<String> warnings,
            long startedAt) {
        ArrayList<String> runWarnings = new ArrayList<>(warnings);
        ScenarioPlan dryScenario = dryRunScenario(config, target, runWarnings);
        ArrayList<RaceWorkerResult> results = new ArrayList<>();
        AuthProvider authProvider = providerFor(target.authProfile(), target.workspace());
        if (!isManual(target.authProfile()) && config.authMode() == RaceAuthMode.ENSURE_AUTH_BEFORE_GATE) {
            runWarnings.add("Dry run did not execute auth flows before the race gate.");
        }
        for (int iteration = 1; iteration <= config.iterations(); iteration++) {
            for (int worker = 0; worker < config.concurrency(); worker++) {
                long workerStarted = clock.millis();
                PreparedAccessRequest prepared = requestPreparer.prepare(
                        target.template(),
                        target.authProfile(),
                        authProvider,
                        target.dataProfile().orElse(null),
                        env,
                        dryScenario.scenario(),
                        legacy,
                        mode,
                        runWarnings);
                RunStatus status = prepared.success() ? RunStatus.COMPLETED : RunStatus.INVALID;
                Verdict verdict = prepared.success() ? Verdict.REVIEW : Verdict.ERROR;
                String reason = prepared.success()
                        ? "Dry run rendered race target request; no HTTP request was sent"
                        : "Dry run render failed: " + prepared.reason();
                results.add(new RaceWorkerResult(
                        worker,
                        iteration,
                        status,
                        verdict,
                        0,
                        reason,
                        "",
                        workerStarted,
                        workerStarted,
                        Math.max(clock.millis(), workerStarted),
                        prepared.warnings()));
            }
        }
        RaceRunStatus status = results.stream().anyMatch(result -> result.runStatus() == RunStatus.INVALID)
                ? RaceRunStatus.INVALID
                : RaceRunStatus.COMPLETED;
        return new RaceRunResult(
                idGenerator.newId("race"),
                config.testCaseId(),
                status,
                config.concurrency(),
                config.iterations(),
                results,
                runWarnings,
                startedAt,
                Math.max(clock.millis(), startedAt));
    }

    private IterationOutcome runIteration(
            RaceRunConfig config,
            ResolvedTarget target,
            AuthProfile authProfile,
            AuthProvider authProvider,
            List<Map<String, String>> workerScenarios,
            Map<String, String> env,
            Map<String, String> legacy,
            RenderMode mode,
            int iteration) {
        CountDownLatch readyLatch = new CountDownLatch(config.concurrency());
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicBoolean abort = new AtomicBoolean(false);
        ConcurrentLinkedQueue<String> warnings = new ConcurrentLinkedQueue<>();
        ArrayList<Future<RaceWorkerResult>> futures = new ArrayList<>();
        for (int worker = 0; worker < config.concurrency(); worker++) {
            int workerIndex = worker;
            Map<String, String> scenario = workerScenarios.get(worker);
            futures.add(executorService.submit(workerTask(config, target, authProfile, authProvider,
                    scenario, env, legacy, mode, iteration, workerIndex, readyLatch, startGate, abort, warnings)));
        }

        boolean ready;
        try {
            ready = readyLatch.await(config.preparationTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            ready = false;
        }
        if (!ready) {
            warnings.add("Race preparation timed out before all workers were ready; no partial race was released.");
            abort.set(true);
        }
        if (!abort.get()) {
            delay(config.preGateDelayMillis(), warnings);
        }
        startGate.countDown();

        ArrayList<RaceWorkerResult> results = new ArrayList<>();
        for (Future<RaceWorkerResult> future : futures) {
            try {
                results.add(future.get());
            } catch (Exception exception) {
                long now = clock.millis();
                results.add(new RaceWorkerResult(
                        -1,
                        iteration,
                        RunStatus.FAILED,
                        Verdict.ERROR,
                        0,
                        "Race worker failed: " + exception.getMessage(),
                        "",
                        now,
                        now,
                        now,
                        List.of()));
            }
        }
        results.sort(Comparator.comparingInt(RaceWorkerResult::workerIndex));
        boolean invalid = !ready || results.stream().anyMatch(result -> result.runStatus() == RunStatus.INVALID);
        boolean failed = results.stream().anyMatch(result -> result.runStatus() == RunStatus.FAILED);
        return new IterationOutcome(results, List.copyOf(warnings), invalid, failed);
    }

    private Callable<RaceWorkerResult> workerTask(
            RaceRunConfig config,
            ResolvedTarget target,
            AuthProfile authProfile,
            AuthProvider authProvider,
            Map<String, String> scenario,
            Map<String, String> env,
            Map<String, String> legacy,
            RenderMode mode,
            int iteration,
            int workerIndex,
            CountDownLatch readyLatch,
            CountDownLatch startGate,
            AtomicBoolean abort,
            ConcurrentLinkedQueue<String> runWarnings) {
        return () -> {
            long workerStarted = clock.millis();
            PreparedAccessRequest prepared = requestPreparer.prepare(
                    target.template(),
                    authProfile,
                    authProvider,
                    target.dataProfile().orElse(null),
                    env,
                    scenario,
                    legacy,
                    mode,
                    List.of());
            if (!prepared.success()) {
                abort.set(true);
                readyLatch.countDown();
                long finishedAt = Math.max(clock.millis(), workerStarted);
                return new RaceWorkerResult(
                        workerIndex,
                        iteration,
                        RunStatus.INVALID,
                        Verdict.ERROR,
                        0,
                        "Race target render failed: " + prepared.reason(),
                        "",
                        workerStarted,
                        0L,
                        finishedAt,
                        prepared.warnings());
            }
            readyLatch.countDown();
            if (config.synchronizeStart()) {
                startGate.await();
            }
            long releasedAt = clock.millis();
            if (abort.get()) {
                long finishedAt = Math.max(clock.millis(), workerStarted);
                return new RaceWorkerResult(
                        workerIndex,
                        iteration,
                        RunStatus.SKIPPED,
                        Verdict.NOT_RUN,
                        0,
                        "Race worker was not sent because the batch was aborted before release.",
                        "",
                        workerStarted,
                        releasedAt,
                        finishedAt,
                        prepared.warnings());
            }
            return sendWorker(target, prepared, workerIndex, iteration, workerStarted, releasedAt, runWarnings);
        };
    }

    private RaceWorkerResult sendWorker(
            ResolvedTarget target,
            PreparedAccessRequest prepared,
            int workerIndex,
            int iteration,
            long workerStarted,
            long releasedAt,
            ConcurrentLinkedQueue<String> runWarnings) {
        try {
            StoredRequestTemplate request = prepared.request().orElseThrow();
            HttpResponse response = httpClient.send(requestAdapter.toHttpRequest(request));
            if (response == null) {
                throw new IllegalStateException("HTTP client returned no response");
            }
            String rawResponse = rawResponse(response);
            int statusCode = response.statusCode();
            EvaluationResult evaluation = evaluator.evaluate(
                    target.testCase().expected(),
                    statusCode,
                    rawResponse,
                    evaluationProfile(target.testCase(), target.workspace()).orElse(null));
            long finishedAt = Math.max(clock.millis(), workerStarted);
            String resultId = idGenerator.newId("result");
            List<String> resultWarnings = warnings(prepared.warnings(), evaluation.warnings());
            AccessTestResult result = new AccessTestResult(
                    resultId,
                    target.testCase().id(),
                    RunStatus.COMPLETED,
                    evaluation.verdict(),
                    "Race worker " + workerIndex + " iteration " + iteration + ": " + evaluation.reason(),
                    workerStarted,
                    finishedAt,
                    redactionService.redactHttpMessage(prepared.rawRequest()),
                    redactionService.redactHttpMessage(rawResponse),
                    resultWarnings);
            AccessEvidence evidence = new AccessEvidence(
                    target.testCase().id(),
                    resultId,
                    prepared.rawRequest(),
                    redactionService.redactHttpMessage(prepared.rawRequest()),
                    rawResponse,
                    redactionService.redactHttpMessage(rawResponse),
                    finishedAt);
            repository.saveResult(result);
            repository.saveEvidence(evidence);
            return new RaceWorkerResult(
                    workerIndex,
                    iteration,
                    RunStatus.COMPLETED,
                    evaluation.verdict(),
                    statusCode,
                    evaluation.reason(),
                    resultId,
                    workerStarted,
                    releasedAt,
                    finishedAt,
                    resultWarnings);
        } catch (RuntimeException exception) {
            long finishedAt = Math.max(clock.millis(), workerStarted);
            String resultId = idGenerator.newId("result");
            String reason = "Race worker request failed: " + exception.getMessage();
            AccessTestResult result = new AccessTestResult(
                    resultId,
                    target.testCase().id(),
                    RunStatus.FAILED,
                    Verdict.ERROR,
                    reason,
                    workerStarted,
                    finishedAt,
                    redactionService.redactHttpMessage(prepared.rawRequest()),
                    "",
                    prepared.warnings());
            repository.saveResult(result);
            runWarnings.add(reason);
            return new RaceWorkerResult(
                    workerIndex,
                    iteration,
                    RunStatus.FAILED,
                    Verdict.ERROR,
                    0,
                    reason,
                    resultId,
                    workerStarted,
                    releasedAt,
                    finishedAt,
                    prepared.warnings());
        }
    }

    private Optional<ResolvedTarget> resolveTarget(RaceRunConfig config, AccessWorkspace snapshot, long startedAt) {
        AccessTestCase testCase = snapshot.testCases().get(config.testCaseId());
        if (testCase == null) {
            return Optional.empty();
        }
        AccessRequestTemplate template = snapshot.templates().get(testCase.templateId());
        AuthProfile authProfile = snapshot.authProfiles().get(testCase.target().targetAuthProfileId());
        DataProfile dataProfile = testCase.target().dataProfileId().isBlank()
                ? null
                : snapshot.dataProfiles().get(testCase.target().dataProfileId());
        if (template == null || authProfile == null
                || (!testCase.target().dataProfileId().isBlank() && dataProfile == null)) {
            AccessTestResult result = new AccessTestResult(
                    idGenerator.newId("result"),
                    testCase.id(),
                    RunStatus.INVALID,
                    Verdict.ERROR,
                    "Race run could not resolve template, auth profile, or data profile.",
                    startedAt,
                    Math.max(clock.millis(), startedAt),
                    "",
                    "",
                    List.of());
            repository.saveResult(result);
            return Optional.empty();
        }
        return Optional.of(new ResolvedTarget(snapshot, testCase, template, authProfile, Optional.ofNullable(dataProfile)));
    }

    private Optional<AuthProfile> ensureAuthBeforeGate(
            RaceRunConfig config,
            ResolvedTarget target,
            Map<String, String> env,
            RenderMode mode,
            List<String> warnings) {
        AuthProfile authProfile = target.authProfile();
        if (config.authMode() == RaceAuthMode.SHARED_AUTH_PROFILE_STATE || isManual(authProfile)) {
            return Optional.of(authProfile);
        }
        if (authManager == null) {
            warnings.add("Auth manager is not configured; race used the current auth profile state.");
            return Optional.of(authProfile);
        }
        AuthEnsureResult ensureResult = authManager.ensureAuth(
                authProfile.id(),
                target.testCase().target().environmentId(),
                env,
                mode);
        warnings.addAll(ensureResult.warnings());
        if (ensureResult.failed()) {
            warnings.add("Auth ensure failed before race gate: " + ensureResult.reason());
            return Optional.empty();
        }
        return ensureResult.updatedProfile().or(() -> Optional.of(authProfile));
    }

    private Optional<ScenarioPlan> sharedScenario(
            RaceRunConfig config,
            ResolvedTarget target,
            Map<String, String> env,
            Map<String, String> legacy,
            RenderMode mode,
            List<String> warnings) {
        if (config.scenarioMode() != RaceScenarioMode.REUSE_SINGLE_SCENARIO) {
            return Optional.of(new ScenarioPlan(Map.of(), List.of()));
        }
        ScenarioPrepare prepare = prepareScenario(target, env, legacy, mode, warnings);
        if (prepare.failedFlag()) {
            return Optional.empty();
        }
        return Optional.of(new ScenarioPlan(prepare.scenario(), repeatScenario(prepare.scenario(), config.concurrency())));
    }

    private Optional<ScenarioPlan> scenariosForIteration(
            RaceRunConfig config,
            ResolvedTarget target,
            Map<String, String> env,
            Map<String, String> legacy,
            RenderMode mode,
            ScenarioPlan sharedScenario,
            int iteration,
            List<String> warnings) {
        if (config.scenarioMode() == RaceScenarioMode.REUSE_SINGLE_SCENARIO) {
            return Optional.of(sharedScenario);
        }
        ArrayList<Map<String, String>> scenarios = new ArrayList<>();
        for (int worker = 0; worker < config.concurrency(); worker++) {
            ScenarioPrepare prepare = prepareScenario(target, env, legacy, mode, warnings);
            if (prepare.failedFlag()) {
                return Optional.empty();
            }
            scenarios.add(prepare.scenario());
        }
        if (config.scenarioMode() == RaceScenarioMode.FRESH_SCENARIO_PER_WORKER && iteration > 1) {
            warnings.add("FRESH_SCENARIO_PER_WORKER currently prepares scenarios at each iteration boundary.");
        }
        return Optional.of(new ScenarioPlan(scenarios.getFirst(), List.copyOf(scenarios)));
    }

    private ScenarioPrepare prepareScenario(
            ResolvedTarget target,
            Map<String, String> env,
            Map<String, String> legacy,
            RenderMode mode,
            List<String> warnings) {
        AccessTestCase testCase = target.testCase();
        if (testCase.setup().mode() == SetupMode.NONE) {
            if (testCase.target().scenarioInstanceId().isBlank()) {
                return ScenarioPrepare.success(Map.of());
            }
            ScenarioInstance instance = target.workspace().scenarios().get(testCase.target().scenarioInstanceId());
            if (instance == null) {
                warnings.add("Race scenario not found: " + testCase.target().scenarioInstanceId());
                return ScenarioPrepare.failed();
            }
            return ScenarioPrepare.success(instance.toVariableMap());
        }
        if (scenarioManager == null) {
            warnings.add("Scenario setup is required but ScenarioManager is not configured; no race requests were sent.");
            return ScenarioPrepare.failed();
        }
        ScenarioPreparationResult result = scenarioManager.prepareScenario(testCase, target.workspace(), env, legacy, mode);
        warnings.addAll(result.warnings());
        if (result.failed()) {
            warnings.add("Scenario preparation failed before race gate: " + result.reason());
            return ScenarioPrepare.failed();
        }
        return ScenarioPrepare.success(result.scenario().map(ScenarioInstance::toVariableMap).orElseGet(Map::of));
    }

    private ScenarioPlan dryRunScenario(RaceRunConfig config, ResolvedTarget target, List<String> warnings) {
        AccessTestCase testCase = target.testCase();
        if (testCase.setup().mode() == SetupMode.NONE && !testCase.target().scenarioInstanceId().isBlank()) {
            ScenarioInstance instance = target.workspace().scenarios().get(testCase.target().scenarioInstanceId());
            if (instance == null) {
                warnings.add("Dry run could not find reusable scenario: " + testCase.target().scenarioInstanceId());
                return new ScenarioPlan(Map.of(), repeatScenario(Map.of(), config.concurrency()));
            }
            return new ScenarioPlan(instance.toVariableMap(), repeatScenario(instance.toVariableMap(), config.concurrency()));
        }
        if (testCase.setup().mode() == SetupMode.REUSE_SCENARIO) {
            ScenarioInstance instance = target.workspace().scenarios().get(testCase.target().scenarioInstanceId());
            if (instance == null) {
                warnings.add("Dry run requires a reusable scenario; setup was not executed.");
                return new ScenarioPlan(Map.of(), repeatScenario(Map.of(), config.concurrency()));
            }
            return new ScenarioPlan(instance.toVariableMap(), repeatScenario(instance.toVariableMap(), config.concurrency()));
        }
        if (testCase.setup().mode() != SetupMode.NONE) {
            warnings.add("Setup execution is required to produce scenario variables; dry-run did not execute setup.");
        }
        return new ScenarioPlan(Map.of(), repeatScenario(Map.of(), config.concurrency()));
    }

    private void runAfterBatchHooks(
            RaceRunConfig config,
            ResolvedTarget target,
            AuthProfile authProfile,
            Map<String, String> scenario,
            Map<String, String> env,
            Map<String, String> legacy,
            RenderMode mode,
            List<RaceWorkerResult> workerResults,
            List<String> warnings) {
        boolean runPostChecks = config.runPostChecksAfterBatch()
                && target.testCase().postChecks().stream().anyMatch(dev.nquangit.corestra.access.postcheck.PostCheckRequest::enabled);
        boolean runTeardown = config.runTeardownAfterBatch()
                && target.testCase().teardown().mode() != dev.nquangit.corestra.access.teardown.TeardownMode.NONE;
        if (!runPostChecks && !runTeardown) {
            return;
        }
        if (singleRunner == null) {
            warnings.add("After-batch hooks were requested but SingleAccessTestRunner is not configured.");
            return;
        }
        AccessRunResult hookResult = singleRunner.runAfterBatchHooks(
                target.testCase(),
                target.workspace(),
                authProfile,
                target.dataProfile().orElse(null),
                env,
                scenario,
                legacy,
                mode,
                runPostChecks,
                runTeardown,
                workerResults.stream().anyMatch(result -> result.runStatus() == RunStatus.FAILED)
                        ? RunStatus.FAILED
                        : RunStatus.COMPLETED,
                aggregateVerdict(workerResults));
        repository.saveResult(hookResult.result());
        hookResult.evidence().ifPresent(repository::saveEvidence);
        warnings.add("After-batch hooks result: " + hookResult.result().runStatus()
                + " / " + hookResult.result().verdict() + " - " + hookResult.result().reason());
        warnings.addAll(hookResult.result().warnings());
    }

    private List<String> safetyWarnings(RaceRunConfig config, ResolvedTarget target) {
        ArrayList<String> warnings = new ArrayList<>();
        if (target.template().operationMetadata().stateChanging()
                || target.template().operationMetadata().operationType() == OperationType.STATE_CHANGING) {
            warnings.add("Race target is marked state-changing; use only in authorized environments.");
        }
        if (target.template().operationMetadata().consumable()
                || target.template().operationMetadata().operationType() == OperationType.CONSUMABLE) {
            warnings.add("Race target is marked consumable; shared scenario values may be one-time-use.");
        }
        if (config.scenarioMode() == RaceScenarioMode.REUSE_SINGLE_SCENARIO
                && target.testCase().setup().mode() != SetupMode.NONE) {
            warnings.add("All workers will share one prepared scenario.");
        }
        if (target.testCase().teardown().mode() == dev.nquangit.corestra.access.teardown.TeardownMode.NONE) {
            warnings.add("Selected row has no teardown configured.");
        }
        if (target.testCase().postChecks().stream().noneMatch(dev.nquangit.corestra.access.postcheck.PostCheckRequest::enabled)) {
            warnings.add("Selected row has no enabled post-checks.");
        }
        if (config.concurrency() >= 5) {
            warnings.add("Concurrency is high for the conservative PR 20 race helper.");
        }
        return warnings;
    }

    private RaceRunResult invalid(RaceRunConfig config, long startedAt, List<String> warnings) {
        return new RaceRunResult(
                idGenerator.newId("race"),
                config.testCaseId(),
                RaceRunStatus.INVALID,
                config.concurrency(),
                config.iterations(),
                List.of(),
                warnings,
                startedAt,
                Math.max(clock.millis(), startedAt));
    }

    private RaceRunResult result(
            RaceRunConfig config,
            RaceRunStatus status,
            List<RaceWorkerResult> workerResults,
            List<String> warnings,
            long startedAt) {
        return new RaceRunResult(
                idGenerator.newId("race"),
                config.testCaseId(),
                status,
                config.concurrency(),
                config.iterations(),
                workerResults,
                warnings,
                startedAt,
                Math.max(clock.millis(), startedAt));
    }

    private RaceRunStatus statusFor(List<RaceWorkerResult> results) {
        if (results.stream().anyMatch(result -> result.runStatus() == RunStatus.FAILED)) {
            return RaceRunStatus.FAILED;
        }
        if (results.stream().anyMatch(result -> result.runStatus() == RunStatus.INVALID)) {
            return RaceRunStatus.INVALID;
        }
        return RaceRunStatus.COMPLETED;
    }

    private AuthProvider providerFor(AuthProfile authProfile, AccessWorkspace workspace) {
        if (authProfile == null || isManual(authProfile)) {
            return null;
        }
        return workspace.authProviders().get(authProfile.providerId());
    }

    private Optional<EvaluationProfile> evaluationProfile(AccessTestCase testCase, AccessWorkspace workspace) {
        return testCase.evaluationProfileId().isBlank()
                ? Optional.empty()
                : Optional.ofNullable(workspace.evaluationProfiles().get(testCase.evaluationProfileId()));
    }

    private Verdict aggregateVerdict(List<RaceWorkerResult> workerResults) {
        if (workerResults.stream().anyMatch(result -> result.verdict() == Verdict.ERROR)) {
            return Verdict.ERROR;
        }
        if (workerResults.stream().anyMatch(result -> result.verdict() == Verdict.SUSPICIOUS)) {
            return Verdict.SUSPICIOUS;
        }
        if (workerResults.stream().anyMatch(result -> result.verdict() == Verdict.REVIEW)) {
            return Verdict.REVIEW;
        }
        return Verdict.OK;
    }

    private void delay(long millis, ConcurrentLinkedQueue<String> warnings) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            warnings.add("Race delay was interrupted.");
        }
    }

    private void delay(long millis, List<String> warnings) {
        if (millis <= 0L) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            warnings.add("Race delay was interrupted.");
        }
    }

    private static List<Map<String, String>> repeatScenario(Map<String, String> scenario, int count) {
        ArrayList<Map<String, String>> scenarios = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            scenarios.add(Map.copyOf(Objects.requireNonNullElse(scenario, Map.of())));
        }
        return List.copyOf(scenarios);
    }

    @SafeVarargs
    private static List<String> warnings(List<String>... groups) {
        ArrayList<String> values = new ArrayList<>();
        for (List<String> group : groups) {
            if (group != null) {
                values.addAll(group);
            }
        }
        return List.copyOf(values);
    }

    private static boolean isManual(AuthProfile authProfile) {
        return authProfile.providerId().isBlank() || AuthProfile.MANUAL_PROVIDER_ID.equals(authProfile.providerId());
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

    private record ResolvedTarget(
            AccessWorkspace workspace,
            AccessTestCase testCase,
            AccessRequestTemplate template,
            AuthProfile authProfile,
            Optional<DataProfile> dataProfile) {
        private ResolvedTarget {
            workspace = Objects.requireNonNull(workspace, "workspace");
            testCase = Objects.requireNonNull(testCase, "testCase");
            template = Objects.requireNonNull(template, "template");
            authProfile = Objects.requireNonNull(authProfile, "authProfile");
            dataProfile = Objects.requireNonNull(dataProfile, "dataProfile");
        }
    }

    private record ScenarioPrepare(Map<String, String> scenario, boolean failedFlag) {
        private ScenarioPrepare {
            scenario = Map.copyOf(Objects.requireNonNullElse(scenario, Map.of()));
        }

        private static ScenarioPrepare success(Map<String, String> scenario) {
            return new ScenarioPrepare(scenario, false);
        }

        private static ScenarioPrepare failed() {
            return new ScenarioPrepare(Map.of(), true);
        }
    }

    private record ScenarioPlan(Map<String, String> scenario, List<Map<String, String>> workerScenarios) {
        private ScenarioPlan {
            scenario = Map.copyOf(Objects.requireNonNullElse(scenario, Map.of()));
            workerScenarios = List.copyOf(Objects.requireNonNullElse(workerScenarios, List.of()));
        }
    }

    private record IterationOutcome(
            List<RaceWorkerResult> results,
            List<String> warnings,
            boolean invalid,
            boolean failed) {
        private IterationOutcome {
            results = List.copyOf(Objects.requireNonNullElse(results, List.of()));
            warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        }
    }
}

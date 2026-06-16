package dev.nquangit.corestra.access.race;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.BasicResultEvaluator;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertion;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertionType;
import dev.nquangit.corestra.access.postcheck.PostCheckAuthBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.runner.AccessTargetRequestPreparer;
import dev.nquangit.corestra.access.runner.ManualAuthApplier;
import dev.nquangit.corestra.access.runner.SingleAccessTestRunner;
import dev.nquangit.corestra.access.runner.StoredRequestHttpRequestAdapter;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.scenario.ScenarioVariable;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.AccessRequestTemplateRenderer;
import dev.nquangit.corestra.access.template.AccessScopedVariableStoreFactory;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.integration.HttpClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RaceRunnerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC);

    @Test
    void dryRunSendsZeroHttpRequests() {
        InMemoryAccessRepository repository = repository(template("/accounts/{{data.accountId}}",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId"))), testCase(List.of()));
        CountingHttpClient httpClient = new CountingHttpClient(200);
        try (Harness harness = harness(repository, httpClient, false)) {
            RaceRunResult result = harness.runner.run(
                    RaceRunConfig.defaults("case-1").withDryRun(true),
                    Map.of(),
                    Map.of(),
                    RenderMode.STRICT);

            assertEquals(RaceRunStatus.COMPLETED, result.status());
            assertEquals(2, result.workerResults().size());
            assertEquals(0, httpClient.count());
            assertTrue(result.workerResults().stream().allMatch(worker -> worker.reason().contains("Dry run")));
        }
    }

    @Test
    void concurrencyTwoSendsTwoTargetRequestsThroughGate() {
        InMemoryAccessRepository repository = repository(template("/accounts/{{data.accountId}}",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId"))), testCase(List.of()));
        CountingHttpClient httpClient = new CountingHttpClient(200);
        try (Harness harness = harness(repository, httpClient, false)) {
            RaceRunResult result = harness.runner.run(
                    RaceRunConfig.defaults("case-1"),
                    Map.of(),
                    Map.of(),
                    RenderMode.STRICT);

            assertEquals(RaceRunStatus.COMPLETED, result.status());
            assertEquals(2, httpClient.count());
            assertEquals(2, result.workerResults().size());
            assertTrue(result.workerResults().stream().allMatch(worker -> worker.releasedAtEpochMillis() > 0L));
            assertEquals(1, repository.results().size());
            assertEquals(1, repository.evidence().size());
        }
    }

    @Test
    void setupRequiredWithoutScenarioManagerPreventsAllSends() {
        AccessTestCase row = new AccessTestCase(
                "case-1",
                "Race row",
                "template-1",
                "flow-1",
                "step-1",
                new dev.nquangit.corestra.access.matrix.SetupConfig(
                        dev.nquangit.corestra.access.matrix.SetupMode.RUN_PREFIX,
                        "flow-setup",
                        "target-step",
                        "",
                        dev.nquangit.corestra.access.matrix.SetupAuthBinding.DATA_OWNER,
                        dev.nquangit.corestra.access.matrix.ScenarioPolicy.FRESH_PER_TEST),
                new TargetBinding("auth-1", "data-1", "", ""),
                ExpectedResult.ALLOW,
                "",
                List.of(),
                List.of(),
                true,
                RunStatus.PENDING,
                0L);
        InMemoryAccessRepository repository = repository(template("/accounts/{{data.accountId}}",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId"))), row);
        CountingHttpClient httpClient = new CountingHttpClient(200);
        try (Harness harness = harness(repository, httpClient, false)) {
            RaceRunResult result = harness.runner.run(
                    RaceRunConfig.defaults("case-1"),
                    Map.of(),
                    Map.of(),
                    RenderMode.STRICT);

            assertEquals(RaceRunStatus.INVALID, result.status());
            assertEquals(0, httpClient.count());
            assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("ScenarioManager")));
        }
    }

    @Test
    void reuseSingleScenarioUsesSameScenarioValueForAllWorkers() {
        AccessRequestTemplate template = template(
                "/confirm/{{scenario.confirmToken}}",
                Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")));
        AccessTestCase row = new AccessTestCase(
                "case-1",
                "Race row",
                "template-1",
                "flow-1",
                "step-1",
                SetupConfig.none(),
                new TargetBinding("auth-1", "data-1", "", "scenario-1"),
                ExpectedResult.ALLOW,
                "",
                List.of(),
                List.of(),
                true,
                RunStatus.PENDING,
                0L);
        InMemoryAccessRepository repository = repository(template, row);
        repository.saveScenario(new ScenarioInstance(
                "scenario-1",
                "Confirm scenario",
                "data-1",
                Map.of("confirmToken", new ScenarioVariable(
                        "confirmToken",
                        "token-123",
                        1000L,
                        0L,
                        true,
                        false,
                        "flow-1",
                        "step-setup",
                        "data-1")),
                dev.nquangit.corestra.access.matrix.ScenarioPolicy.REUSE_EXISTING,
                1000L,
                false,
                "flow-1",
                "step-setup"));
        CountingHttpClient httpClient = new CountingHttpClient(200);
        try (Harness harness = harness(repository, httpClient, false)) {
            RaceRunResult result = harness.runner.run(
                    RaceRunConfig.defaults("case-1"),
                    Map.of(),
                    Map.of(),
                    RenderMode.STRICT);

            assertEquals(RaceRunStatus.COMPLETED, result.status());
            assertEquals(2, httpClient.count());
            assertTrue(httpClient.request(0).contains("/confirm/token-123"));
            assertTrue(httpClient.request(1).contains("/confirm/token-123"));
            assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("consumable")
                    || warning.contains("Selected row has no teardown")));
        }
    }

    @Test
    void postChecksRunOnceAfterBatch() {
        PostCheckRequest postCheck = new PostCheckRequest(
                "post-1",
                "Status check",
                true,
                "template-post",
                "",
                PostCheckAuthBinding.TARGET_ACTOR,
                "",
                "",
                List.of(new PostCheckAssertion(
                        "assert-1",
                        "Status 200",
                        true,
                        PostCheckAssertionType.STATUS_CODE,
                        "",
                        "200",
                        false)));
        InMemoryAccessRepository repository = repository(template("/accounts/{{data.accountId}}",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId"))), testCase(List.of(postCheck)));
        repository.saveTemplate(new AccessRequestTemplate(
                "template-post",
                "Post-check",
                new StoredRequestTemplate("GET", "https", "example.test", 443,
                        "/accounts/{{data.accountId}}/audit", List.of(), ""),
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")),
                OperationMetadata.unknown(),
                "flow-1",
                "step-post",
                "hash-post",
                true));
        CountingHttpClient httpClient = new CountingHttpClient(200);
        try (Harness harness = harness(repository, httpClient, true)) {
            RaceRunResult result = harness.runner.run(
                    RaceRunConfig.defaults("case-1"),
                    Map.of(),
                    Map.of(),
                    RenderMode.STRICT);

            assertEquals(RaceRunStatus.COMPLETED, result.status());
            assertEquals(3, httpClient.count());
            assertTrue(repository.evidence().stream().anyMatch(evidence -> !evidence.postCheckEvidence().isEmpty()));
        }
    }

    @Test
    void stateChangingTemplateProducesWarning() {
        AccessRequestTemplate template = new AccessRequestTemplate(
                "template-1",
                "Transfer",
                new StoredRequestTemplate("POST", "https", "example.test", 443, "/transfer", List.of(), ""),
                Set.of(),
                new OperationMetadata(dev.nquangit.corestra.access.template.OperationType.STATE_CHANGING,
                        true,
                        false,
                        "transfer"),
                "flow-1",
                "step-1",
                "hash-1",
                true);
        InMemoryAccessRepository repository = repository(template, testCase(List.of()));
        CountingHttpClient httpClient = new CountingHttpClient(200);
        try (Harness harness = harness(repository, httpClient, false)) {
            RaceRunResult result = harness.runner.run(
                    RaceRunConfig.defaults("case-1").withDryRun(true),
                    Map.of(),
                    Map.of(),
                    RenderMode.STRICT);

            assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("state-changing")));
            assertEquals(0, httpClient.count());
        }
    }

    private static Harness harness(
            InMemoryAccessRepository repository,
            CountingHttpClient httpClient,
            boolean includeSingleRunner) {
        TemplateRenderer templateRenderer = new TemplateRenderer(new VariableResolver(key -> Optional.empty()));
        AccessRequestTemplateRenderer requestRenderer = new AccessRequestTemplateRenderer(templateRenderer);
        AccessScopedVariableStoreFactory storeFactory = new AccessScopedVariableStoreFactory();
        FakeRequestAdapter adapter = new FakeRequestAdapter();
        AccessTargetRequestPreparer preparer = new AccessTargetRequestPreparer(
                requestRenderer,
                storeFactory,
                new ManualAuthApplier(templateRenderer),
                adapter);
        AtomicInteger ids = new AtomicInteger();
        AccessIdGenerator idGenerator = prefix -> prefix + "-" + ids.incrementAndGet();
        SingleAccessTestRunner singleRunner = includeSingleRunner
                ? new SingleAccessTestRunner(
                        requestRenderer,
                        storeFactory,
                        httpClient,
                        new BasicResultEvaluator(),
                        new RedactionService(),
                        new ManualAuthApplier(templateRenderer),
                        adapter,
                        idGenerator)
                : null;
        ExecutorService executor = Executors.newFixedThreadPool(RaceRunConfig.MAX_CONCURRENCY);
        RaceRunner runner = new RaceRunner(
                repository,
                preparer,
                adapter,
                httpClient,
                new BasicResultEvaluator(),
                new RedactionService(),
                idGenerator,
                singleRunner,
                null,
                null,
                CLOCK,
                executor);
        return new Harness(runner, executor);
    }

    private static InMemoryAccessRepository repository(AccessRequestTemplate template, AccessTestCase testCase) {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProfile(new AuthProfile(
                "auth-1",
                "Owner",
                "user",
                AuthProfile.MANUAL_PROVIDER_ID,
                Map.of(),
                new AuthState(Map.of("accessToken", "token-1"),
                        Map.of("Authorization", "Bearer {{auth.accessToken}}"),
                        Map.of(),
                        0L,
                        0L),
                true));
        repository.saveDataProfile(new DataProfile("data-1", "Owner data", "auth-1",
                Map.of("accountId", "acct-1"), true));
        repository.saveTemplate(template);
        repository.saveTestCase(testCase);
        return repository;
    }

    private static AccessTestCase testCase(List<PostCheckRequest> postChecks) {
        return new AccessTestCase(
                "case-1",
                "Race row",
                "template-1",
                "flow-1",
                "step-1",
                SetupConfig.none(),
                new TargetBinding("auth-1", "data-1", "", ""),
                ExpectedResult.ALLOW,
                "",
                List.of(),
                postChecks,
                true,
                RunStatus.PENDING,
                0L);
    }

    private static AccessRequestTemplate template(String path, Set<ScopedVariableRef> placeholders) {
        return new AccessRequestTemplate(
                "template-1",
                "Target",
                new StoredRequestTemplate("GET", "https", "example.test", 443, path, List.of(), ""),
                placeholders,
                OperationMetadata.unknown(),
                "flow-1",
                "step-1",
                "hash-1",
                true);
    }

    private record Harness(RaceRunner runner, ExecutorService executor) implements AutoCloseable {
        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class CountingHttpClient implements HttpClient {
        private final short statusCode;
        private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger count = new AtomicInteger();
        private final CountDownLatch releaseTogether = new CountDownLatch(2);

        private CountingHttpClient(int statusCode) {
            this.statusCode = (short) statusCode;
        }

        @Override
        public HttpResponse send(HttpRequest request) {
            requests.add(request.toString());
            int current = count.incrementAndGet();
            if (current <= 2) {
                releaseTogether.countDown();
                try {
                    releaseTogether.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return response(statusCode, "{\"ok\":true}");
        }

        private int count() {
            return count.get();
        }

        private String request(int index) {
            return requests.get(index);
        }
    }

    private static HttpResponse response(short statusCode, String body) {
        return (HttpResponse) Proxy.newProxyInstance(
                HttpResponse.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> statusCode;
                    case "reasonPhrase" -> statusCode == 403 ? "Forbidden" : "OK";
                    case "httpVersion" -> "HTTP/1.1";
                    case "headers" -> List.of();
                    case "bodyToString" -> body;
                    case "toString" -> "HTTP/1.1 " + statusCode + "\n\n" + body;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class FakeRequestAdapter extends StoredRequestHttpRequestAdapter {
        @Override
        public HttpRequest toHttpRequest(StoredRequestTemplate request) {
            return (HttpRequest) Proxy.newProxyInstance(
                    HttpRequest.class.getClassLoader(),
                    new Class<?>[]{HttpRequest.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "toString" -> rawRequest(request);
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }
}

package dev.nquangit.corestra.access.runner;

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
import dev.nquangit.corestra.access.matrix.SetupAuthBinding;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.matrix.ScenarioPolicy;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.result.Verdict;
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
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.integration.HttpClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessPlanRunnerTest {
    @Test
    void failingStartListenerDoesNotLeavePlanPermanentlyRunning() {
        QueueHttpClient client = new QueueHttpClient(403);
        InMemoryAccessRepository repository = repository();
        AccessPlanRunner runner = runner(repository, client);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> runner.run(AccessPlanRunConfig.defaults(), new AccessPlanListener() {
                    @Override
                    public void onPlanStarted(int totalRows) {
                        throw new IllegalStateException("Listener failed");
                    }
                }));
        assertFalse(runner.isRunning());
        runner.run(AccessPlanRunConfig.defaults(), null);
        assertFalse(runner.isRunning());
    }

    @Test
    void runsEnabledRowsSequentiallyAndSavesResults() {
        QueueHttpClient httpClient = new QueueHttpClient(403, 200);
        InMemoryAccessRepository repository = repository();
        repository.saveTestCase(testCase("case-1", true, ExpectedResult.DENY, SetupConfig.none()));
        repository.saveTestCase(testCase("case-2", true, ExpectedResult.ALLOW, SetupConfig.none()));

        AccessPlanSummary summary = runner(repository, httpClient).run(AccessPlanRunConfig.defaults(), null);

        assertEquals(2, httpClient.count());
        assertEquals(2, summary.total());
        assertEquals(2, summary.attempted());
        assertEquals(2, summary.completed());
        assertEquals(2, summary.ok());
        assertEquals(List.of("case-1", "case-2"), httpClient.sentCaseOrder());
        assertEquals(2, repository.results().size());
        assertEquals(RunStatus.COMPLETED, repository.testCase("case-1").orElseThrow().status());
    }

    @Test
    void skipsDisabledRowsByDefault() {
        QueueHttpClient httpClient = new QueueHttpClient(403);
        InMemoryAccessRepository repository = repository();
        repository.saveTestCase(testCase("case-1", false, ExpectedResult.DENY, SetupConfig.none()));
        repository.saveTestCase(testCase("case-2", true, ExpectedResult.DENY, SetupConfig.none()));

        AccessPlanSummary summary = runner(repository, httpClient).run(AccessPlanRunConfig.defaults(), null);

        assertEquals(1, summary.total());
        assertEquals(1, summary.attempted());
        assertEquals(1, httpClient.count());
        assertEquals(RunStatus.PENDING, repository.testCase("case-1").orElseThrow().status());
    }

    @Test
    void unsupportedSetupModeIsSkippedBySingleRunnerAndCounted() {
        QueueHttpClient httpClient = new QueueHttpClient(403);
        InMemoryAccessRepository repository = repository();
        repository.saveTestCase(testCase("case-1", true, ExpectedResult.DENY,
                new SetupConfig(SetupMode.RUN_PREFIX, "flow", "step", "auth-1",
                        SetupAuthBinding.DATA_OWNER, ScenarioPolicy.REUSE_EXISTING)));

        AccessPlanSummary summary = runner(repository, httpClient).run(AccessPlanRunConfig.defaults(), null);

        assertEquals(0, httpClient.count());
        assertEquals(1, summary.skipped());
        assertEquals(RunStatus.SKIPPED, repository.testCase("case-1").orElseThrow().status());
    }

    @Test
    void stopRequestedBeforeRunPreventsRowsFromStarting() {
        QueueHttpClient httpClient = new QueueHttpClient(403);
        InMemoryAccessRepository repository = repository();
        repository.saveTestCase(testCase("case-1", true, ExpectedResult.DENY, SetupConfig.none()));
        AccessPlanRunner runner = runner(repository, httpClient);

        runner.requestStop();
        AccessPlanSummary summary = runner.run(AccessPlanRunConfig.defaults(), null);

        assertTrue(summary.stopped());
        assertEquals(0, summary.attempted());
        assertEquals(0, httpClient.count());
    }

    @Test
    void stopRequestedAfterFirstRowPreventsSecondRow() {
        QueueHttpClient httpClient = new QueueHttpClient(403, 403);
        InMemoryAccessRepository repository = repository();
        repository.saveTestCase(testCase("case-1", true, ExpectedResult.DENY, SetupConfig.none()));
        repository.saveTestCase(testCase("case-2", true, ExpectedResult.DENY, SetupConfig.none()));
        AccessPlanRunner runner = runner(repository, httpClient);

        AccessPlanSummary summary = runner.run(AccessPlanRunConfig.defaults(), new AccessPlanListener() {
            @Override
            public void onRowFinished(String testCaseId, AccessRunResult result, int index, int total) {
                runner.requestStop();
            }
        });

        assertTrue(summary.stopped());
        assertEquals(1, summary.attempted());
        assertEquals(1, httpClient.count());
        assertEquals(RunStatus.PENDING, repository.testCase("case-2").orElseThrow().status());
    }

    @Test
    void stopOnFirstErrorStopsAfterInvalidRow() {
        QueueHttpClient httpClient = new QueueHttpClient(403);
        InMemoryAccessRepository repository = repository();
        repository.saveTestCase(testCase("case-1", true, ExpectedResult.DENY, SetupConfig.none(), "missing-data"));
        repository.saveTestCase(testCase("case-2", true, ExpectedResult.DENY, SetupConfig.none()));

        AccessPlanSummary summary = runner(repository, httpClient).run(
                new AccessPlanRunConfig(RenderMode.STRICT, Map.of(), Map.of(), Map.of(), true, false),
                null);

        assertTrue(summary.stopped());
        assertEquals(1, summary.attempted());
        assertEquals(1, summary.invalid());
        assertEquals(0, httpClient.count());
    }

    @Test
    void listenerReceivesEventsInExpectedOrder() {
        QueueHttpClient httpClient = new QueueHttpClient(403);
        InMemoryAccessRepository repository = repository();
        repository.saveTestCase(testCase("case-1", true, ExpectedResult.DENY, SetupConfig.none()));
        List<String> events = new ArrayList<>();

        AccessPlanSummary summary = runner(repository, httpClient).run(AccessPlanRunConfig.defaults(), new AccessPlanListener() {
            @Override
            public void onPlanStarted(int totalRows) {
                events.add("started:" + totalRows);
            }

            @Override
            public void onRowStarted(String testCaseId, int index, int total) {
                events.add("row-start:" + testCaseId + ":" + index + "/" + total);
            }

            @Override
            public void onRowFinished(String testCaseId, AccessRunResult result, int index, int total) {
                events.add("row-finish:" + testCaseId + ":" + result.result().verdict());
            }

            @Override
            public void onPlanFinished(AccessPlanSummary summary) {
                events.add("finished:" + summary.attempted());
            }
        });

        assertFalse(summary.stopped());
        assertEquals(List.of("started:1", "row-start:case-1:1/1", "row-finish:case-1:OK", "finished:1"), events);
    }

    @Test
    void summaryCountsSuspiciousFailedAndSkipped() {
        QueueHttpClient httpClient = new QueueHttpClient(200, true);
        InMemoryAccessRepository repository = repository();
        repository.saveTestCase(testCase("case-1", true, ExpectedResult.DENY, SetupConfig.none()));
        repository.saveTestCase(testCase("case-2", true, ExpectedResult.DENY, SetupConfig.none()));
        repository.saveTestCase(testCase("case-3", true, ExpectedResult.DENY,
                new SetupConfig(SetupMode.RUN_PREFIX, "flow", "step", "auth-1",
                        SetupAuthBinding.DATA_OWNER, ScenarioPolicy.REUSE_EXISTING)));

        AccessPlanSummary summary = runner(repository, httpClient).run(AccessPlanRunConfig.defaults(), null);

        assertEquals(3, summary.attempted());
        assertEquals(1, summary.suspicious());
        assertEquals(1, summary.failed());
        assertEquals(1, summary.skipped());
    }

    @Test
    void dryRunRendersRowsWithoutSendingHttp() {
        QueueHttpClient httpClient = new QueueHttpClient(403);
        InMemoryAccessRepository repository = repository();
        repository.saveTestCase(testCase("case-1", true, ExpectedResult.DENY, SetupConfig.none()));

        AccessPlanSummary summary = runner(repository, httpClient).run(
                new AccessPlanRunConfig(RenderMode.STRICT, Map.of(), Map.of(), Map.of(), false, false, true),
                null);

        assertEquals(0, httpClient.count());
        assertEquals(1, summary.completed());
        assertEquals(1, summary.review());
        assertEquals(RunStatus.COMPLETED, repository.results().getFirst().runStatus());
        assertEquals(Verdict.REVIEW, repository.results().getFirst().verdict());
        assertTrue(repository.evidence().getFirst().redactedRequestRaw().contains("/accounts/acct-1"));
    }

    @Test
    void planUsesSnapshotWhenRepositoryChangesBetweenRows() {
        QueueHttpClient httpClient = new QueueHttpClient(403, 403);
        InMemoryAccessRepository repository = repository();
        repository.saveDataProfile(new DataProfile(
                "data-2",
                "Data 2",
                "auth-1",
                Map.of("accountId", "acct-2"),
                true));
        repository.saveTestCase(testCase("case-1", true, ExpectedResult.DENY, SetupConfig.none(), "data-1"));
        repository.saveTestCase(testCase("case-2", true, ExpectedResult.DENY, SetupConfig.none(), "data-2"));

        AccessPlanSummary summary = runner(repository, httpClient).run(AccessPlanRunConfig.defaults(), new AccessPlanListener() {
            @Override
            public void onRowFinished(String testCaseId, AccessRunResult result, int index, int total) {
                if ("case-1".equals(testCaseId)) {
                    repository.saveDataProfile(new DataProfile(
                            "data-2",
                            "Data 2",
                            "auth-1",
                            Map.of("accountId", "acct-edited"),
                            true));
                }
            }
        });

        assertEquals(2, summary.completed());
        assertEquals(2, httpClient.count());
        assertTrue(httpClient.request(1).contains("/accounts/acct-2"));
        assertFalse(httpClient.request(1).contains("acct-edited"));
    }

    private static InMemoryAccessRepository repository() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProfile(new AuthProfile(
                "auth-1",
                "Owner",
                "admin",
                "manual",
                Map.of(),
                new AuthState(Map.of("accessToken", "token"), Map.of("Authorization", "Bearer {{auth.accessToken}}"), Map.of(), 0, 0),
                true));
        repository.saveDataProfile(new DataProfile("data-1", "Data", "auth-1", Map.of("accountId", "acct-1"), true));
        repository.saveTemplate(template());
        return repository;
    }

    private static AccessPlanRunner runner(InMemoryAccessRepository repository, QueueHttpClient httpClient) {
        TemplateRenderer templateRenderer = new TemplateRenderer(new VariableResolver(key -> Optional.empty()));
        AtomicInteger counter = new AtomicInteger();
        AccessIdGenerator ids = prefix -> prefix + "-" + counter.incrementAndGet();
        SingleAccessTestRunner singleRunner = new SingleAccessTestRunner(
                new AccessRequestTemplateRenderer(templateRenderer),
                new AccessScopedVariableStoreFactory(),
                httpClient,
                new BasicResultEvaluator(),
                new RedactionService(),
                new ManualAuthApplier(templateRenderer),
                new FakeRequestAdapter(),
                ids);
        return new AccessPlanRunner(repository, singleRunner);
    }

    private static AccessRequestTemplate template() {
        return new AccessRequestTemplate(
                "template-1",
                "Template",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/accounts/{{data.accountId}}", List.of(), ""),
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")),
                OperationMetadata.unknown(),
                "flow-1",
                "step-1",
                "hash-1",
                true);
    }

    private static AccessTestCase testCase(String id, boolean enabled, ExpectedResult expected, SetupConfig setup) {
        return testCase(id, enabled, expected, setup, "data-1");
    }

    private static AccessTestCase testCase(String id, boolean enabled, ExpectedResult expected, SetupConfig setup, String dataId) {
        return new AccessTestCase(
                id,
                id,
                "template-1",
                "flow-1",
                "step-1",
                setup,
                new TargetBinding("auth-1", dataId, "", ""),
                expected,
                List.of(),
                enabled,
                RunStatus.PENDING,
                0L);
    }

    private static final class QueueHttpClient implements HttpClient {
        private final List<Object> responses = new ArrayList<>();
        private final List<String> sentCaseOrder = new ArrayList<>();
        private final List<String> requests = new ArrayList<>();
        private int count;

        private QueueHttpClient(Object... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public HttpResponse send(HttpRequest request) {
            count++;
            sentCaseOrder.add("case-" + count);
            requests.add(request.toString());
            Object response = responses.removeFirst();
            if (response instanceof Boolean shouldThrow && shouldThrow) {
                throw new IllegalStateException("boom");
            }
            return response((int) response);
        }

        int count() {
            return count;
        }

        List<String> sentCaseOrder() {
            return sentCaseOrder;
        }

        String request(int index) {
            return requests.get(index);
        }
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

    private static HttpResponse response(int statusCode) {
        short status = (short) statusCode;
        return (HttpResponse) Proxy.newProxyInstance(
                HttpResponse.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> status;
                    case "reasonPhrase" -> statusCode == 403 ? "Forbidden" : "OK";
                    case "httpVersion" -> "HTTP/1.1";
                    case "headers" -> List.of();
                    case "bodyToString" -> "";
                    case "toString" -> "HTTP/1.1 " + statusCode + "\n\n";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}

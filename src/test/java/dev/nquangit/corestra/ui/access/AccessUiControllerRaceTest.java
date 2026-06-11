package dev.nquangit.corestra.ui.access;

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
import dev.nquangit.corestra.access.race.RaceRunConfig;
import dev.nquangit.corestra.access.race.RaceRunResult;
import dev.nquangit.corestra.access.race.RaceRunStatus;
import dev.nquangit.corestra.access.race.RaceRunner;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.runner.AccessTargetRequestPreparer;
import dev.nquangit.corestra.access.runner.ManualAuthApplier;
import dev.nquangit.corestra.access.runner.StoredRequestHttpRequestAdapter;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AccessUiControllerRaceTest {
    @Test
    void raceOptionsPanelDefaultsToTwoTargetRequests() {
        MatrixPanel.RaceOptionsPanel panel = new MatrixPanel.RaceOptionsPanel(testCase());

        RaceRunConfig config = panel.toConfig("case-1");

        assertEquals(2, config.concurrency());
        assertEquals(1, config.iterations());
        assertEquals(2, config.totalTargetRequests());
    }

    @Test
    void controllerRunRaceDryRunSendsZeroHttpRequests() {
        InMemoryAccessRepository repository = repository();
        CountingHttpClient httpClient = new CountingHttpClient();
        ExecutorService executor = Executors.newFixedThreadPool(RaceRunConfig.MAX_CONCURRENCY);
        try {
            AccessUiController controller = controller(repository, httpClient, executor);

            RaceRunResult result = controller.runRace(RaceRunConfig.defaults("case-1").withDryRun(true));

            assertEquals(RaceRunStatus.COMPLETED, result.status());
            assertEquals(0, httpClient.count());
        } finally {
            executor.shutdownNow();
        }
    }

    private static AccessUiController controller(
            InMemoryAccessRepository repository,
            CountingHttpClient httpClient,
            ExecutorService executor) {
        TemplateRenderer templateRenderer = new TemplateRenderer(new VariableResolver(key -> Optional.empty()));
        AccessRequestTemplateRenderer requestRenderer = new AccessRequestTemplateRenderer(templateRenderer);
        AccessScopedVariableStoreFactory storeFactory = new AccessScopedVariableStoreFactory();
        StoredRequestHttpRequestAdapter adapter = new FakeRequestAdapter();
        AccessIdGenerator ids = new AccessIdGenerator() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public String newId(String prefix) {
                return prefix + "-" + counter.incrementAndGet();
            }
        };
        RaceRunner raceRunner = new RaceRunner(
                repository,
                new AccessTargetRequestPreparer(
                        requestRenderer,
                        storeFactory,
                        new ManualAuthApplier(templateRenderer),
                        adapter),
                adapter,
                httpClient,
                new BasicResultEvaluator(),
                new RedactionService(),
                ids,
                null,
                null,
                null,
                Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC),
                executor);
        return new AccessUiController(
                repository,
                ids,
                requestRenderer,
                storeFactory,
                null,
                null,
                List::of,
                raceRunner);
    }

    private static InMemoryAccessRepository repository() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProfile(new AuthProfile(
                "auth-1",
                "Owner",
                "user",
                AuthProfile.MANUAL_PROVIDER_ID,
                Map.of(),
                new AuthState(Map.of("accessToken", "token-1"), Map.of(), Map.of(), 0L, 0L),
                true));
        repository.saveDataProfile(new DataProfile("data-1", "Owner data", "auth-1",
                Map.of("accountId", "acct-1"), true));
        repository.saveTemplate(new AccessRequestTemplate(
                "template-1",
                "Target",
                new StoredRequestTemplate("GET", "https", "example.test", 443,
                        "/accounts/{{data.accountId}}", List.of(), ""),
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")),
                OperationMetadata.unknown(),
                "flow-1",
                "step-1",
                "hash-1",
                true));
        repository.saveTestCase(testCase());
        return repository;
    }

    private static AccessTestCase testCase() {
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
                List.of(),
                true,
                RunStatus.PENDING,
                0L);
    }

    private static final class CountingHttpClient implements HttpClient {
        private int count;

        @Override
        public HttpResponse send(HttpRequest request) {
            count++;
            return response();
        }

        private int count() {
            return count;
        }
    }

    private static HttpResponse response() {
        return (HttpResponse) Proxy.newProxyInstance(
                HttpResponse.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> (short) 200;
                    case "reasonPhrase" -> "OK";
                    case "httpVersion" -> "HTTP/1.1";
                    case "headers" -> List.of();
                    case "bodyToString" -> "{}";
                    case "toString" -> "HTTP/1.1 200\n\n{}";
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

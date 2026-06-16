package dev.nquangit.corestra.access.runner;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.BasicResultEvaluator;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.ScenarioPolicy;
import dev.nquangit.corestra.access.matrix.SetupAuthBinding;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.scenario.ScenarioManager;
import dev.nquangit.corestra.access.scenario.ScenarioSetupFlowRunner;
import dev.nquangit.corestra.access.scenario.ScenarioSetupRunResult;
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
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowStep.StepErrorStrategy;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.ExtractRule.ExtractorType;
import dev.nquangit.corestra.flow.rule.ExtractRule.SourceSide;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import dev.nquangit.corestra.integration.HttpClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SingleAccessTestRunnerScenarioTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC);

    @Test
    void setupFailurePreventsTargetSendAndReasonIncludesSetupFailure() {
        CountingHttpClient targetHttpClient = new CountingHttpClient(403);
        ScenarioSetupRunResult setupFailure = ScenarioSetupRunResult.failed("prefix broke", Map.of(), List.of());
        SingleAccessTestRunner runner = runner(targetHttpClient, scenarioManager(new CapturingSetupRunner(setupFailure)));

        AccessRunResult result = runner.run(
                testCase(setup(SetupMode.RUN_SETUP_FLOW, "flow-setup", "", SetupAuthBinding.DATA_OWNER, "", ScenarioPolicy.FRESH_PER_TEST), ""),
                workspace(template(), false),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(RunStatus.FAILED, result.result().runStatus());
        assertEquals(Verdict.ERROR, result.result().verdict());
        assertTrue(result.result().reason().contains("Scenario setup failed"));
        assertTrue(result.result().reason().contains("prefix broke"));
        assertFalse(result.requestSent());
        assertEquals(0, targetHttpClient.count());
    }

    @Test
    void setupSuccessProvidesScenarioVariablesToTargetRender() {
        CountingHttpClient targetHttpClient = new CountingHttpClient(403);
        ScenarioSetupRunResult setupSuccess = ScenarioSetupRunResult.success(
                "setup ok",
                Map.of("confirmToken", "confirm-123"),
                Map.of("scenario.confirmToken", "confirm-123"),
                List.of());
        SingleAccessTestRunner runner = runner(targetHttpClient, scenarioManager(new CapturingSetupRunner(setupSuccess)));

        AccessRunResult result = runner.run(
                testCase(setup(SetupMode.RUN_SETUP_FLOW, "flow-setup", "", SetupAuthBinding.DATA_OWNER, "", ScenarioPolicy.FRESH_PER_TEST), ""),
                workspace(template(), false),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(RunStatus.COMPLETED, result.result().runStatus());
        assertTrue(result.requestSent());
        assertEquals(1, targetHttpClient.count());
        assertTrue(targetHttpClient.lastRequest().contains("/confirm/confirm-123"));
    }

    @Test
    void reuseScenarioProvidesVariablesToTargetRender() {
        CountingHttpClient targetHttpClient = new CountingHttpClient(403);
        SingleAccessTestRunner runner = runner(targetHttpClient, scenarioManager(new CapturingSetupRunner(
                ScenarioSetupRunResult.failed("setup should not run", Map.of(), List.of()))));

        AccessRunResult result = runner.run(
                testCase(setup(SetupMode.REUSE_SCENARIO, "", "", SetupAuthBinding.DATA_OWNER, "", ScenarioPolicy.REUSE_EXISTING), "scenario-1"),
                workspace(template(), true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(RunStatus.COMPLETED, result.result().runStatus());
        assertTrue(result.requestSent());
        assertEquals(1, targetHttpClient.count());
        assertTrue(targetHttpClient.lastRequest().contains("/confirm/reused-456"));
    }

    @Test
    void runPrefixWithFirstStepAsStopPreventsTargetSend() {
        CountingHttpClient targetHttpClient = new CountingHttpClient(403);
        ScenarioManager manager = scenarioManager(new ScenarioSetupFlowRunner(
                new FlowEngine(new ExtractionService(), new InjectionService(), new CountingHttpClient(200)),
                CLOCK), flow(List.of(step("target-step", "scenario.confirmToken"))));
        SingleAccessTestRunner runner = runner(targetHttpClient, manager);

        AccessRunResult result = runner.run(
                testCase(setup(SetupMode.RUN_PREFIX, "flow-setup", "target-step", SetupAuthBinding.DATA_OWNER, "", ScenarioPolicy.FRESH_PER_TEST), ""),
                workspace(template(), false),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(RunStatus.FAILED, result.result().runStatus());
        assertTrue(result.result().reason().contains("Scenario setup failed"));
        assertTrue(result.result().reason().contains("no executable steps"));
        assertFalse(result.requestSent());
        assertEquals(0, targetHttpClient.count());
    }

    private static SingleAccessTestRunner runner(CountingHttpClient httpClient, ScenarioManager scenarioManager) {
        TemplateRenderer templateRenderer = new TemplateRenderer(new VariableResolver(key -> Optional.empty()));
        AtomicInteger counter = new AtomicInteger();
        AccessIdGenerator ids = prefix -> prefix + "-" + counter.incrementAndGet();
        return new SingleAccessTestRunner(
                new AccessRequestTemplateRenderer(templateRenderer),
                new AccessScopedVariableStoreFactory(),
                httpClient,
                new BasicResultEvaluator(),
                new RedactionService(),
                new ManualAuthApplier(templateRenderer),
                new FakeRequestAdapter(),
                ids,
                null,
                new AuthProviderRequestApplier(templateRenderer),
                new TargetAuthFailureDetector(),
                scenarioManager);
    }

    private static ScenarioManager scenarioManager(ScenarioSetupFlowRunner setupRunner) {
        return scenarioManager(setupRunner, flow(List.of()));
    }

    private static ScenarioManager scenarioManager(ScenarioSetupFlowRunner setupRunner, FlowDefinition setupFlow) {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AtomicInteger counter = new AtomicInteger();
        AccessIdGenerator ids = prefix -> prefix + "-" + counter.incrementAndGet();
        return new ScenarioManager(repository, () -> List.of(setupFlow), setupRunner, null, ids, CLOCK);
    }

    private static AccessWorkspace workspace(AccessRequestTemplate template, boolean includeScenario) {
        AccessWorkspace.Builder builder = AccessWorkspace.builder()
                .authProfile(AuthProfile.manual("auth-target", "Target", "actor"))
                .authProfile(AuthProfile.manual("auth-owner", "Owner", "owner"))
                .dataProfile(DataProfile.enabled("data-1", "Owner data", "auth-owner", Map.of("accountId", "acct-1")))
                .template(template);
        if (includeScenario) {
            builder.scenario(scenario());
        }
        return builder.build();
    }

    private static AccessRequestTemplate template() {
        return new AccessRequestTemplate(
                "template-1",
                "Confirm transfer",
                new StoredRequestTemplate("POST", "https", "example.test", 443,
                        "/confirm/{{scenario.confirmToken}}", List.of(), ""),
                Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")),
                OperationMetadata.unknown(),
                "flow-target",
                "target-step",
                "hash-1",
                true);
    }

    private static AccessTestCase testCase(SetupConfig setup, String scenarioId) {
        return new AccessTestCase(
                "case-1",
                "Confirm transfer",
                "template-1",
                "flow-target",
                "target-step",
                setup,
                new TargetBinding("auth-target", "data-1", "env-1", scenarioId),
                ExpectedResult.DENY,
                List.of(),
                true,
                RunStatus.PENDING,
                0L);
    }

    private static SetupConfig setup(
            SetupMode mode,
            String setupFlowId,
            String untilBeforeStepId,
            SetupAuthBinding setupAuthBinding,
            String setupAuthProfileId,
            ScenarioPolicy scenarioPolicy) {
        return new SetupConfig(mode, setupFlowId, untilBeforeStepId,
                setupAuthProfileId, setupAuthBinding, scenarioPolicy);
    }

    private static ScenarioInstance scenario() {
        ScenarioVariable variable = new ScenarioVariable(
                "confirmToken",
                "reused-456",
                1000L,
                0L,
                false,
                false,
                "flow-setup",
                "step-1",
                "data-1");
        return new ScenarioInstance(
                "scenario-1",
                "Reusable setup",
                "data-1",
                Map.of(variable.key(), variable),
                ScenarioPolicy.REUSE_EXISTING,
                1000L,
                false,
                "flow-setup",
                "step-1");
    }

    private static FlowDefinition flow(List<FlowStep> steps) {
        return new FlowDefinition(
                "flow-setup",
                "Setup",
                "",
                true,
                steps,
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private static FlowStep step(String id, String targetVariableName) {
        return new FlowStep(
                id,
                id,
                true,
                "",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/" + id, List.of(), ""),
                List.of(new ExtractRule(
                        "extract-" + id,
                        "Extract " + id,
                        true,
                        SourceSide.RESPONSE,
                        ExtractorType.BODY_TEXT,
                        "",
                        targetVariableName,
                        List.of())),
                List.of(),
                List.of(),
                StepErrorStrategy.FAIL_FLOW);
    }

    private static HttpResponse response(short statusCode) {
        return (HttpResponse) Proxy.newProxyInstance(
                HttpResponse.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> statusCode;
                    case "reasonPhrase" -> statusCode == 403 ? "Forbidden" : "OK";
                    case "httpVersion" -> "HTTP/1.1";
                    case "headers" -> List.of();
                    case "bodyToString" -> "confirm-123";
                    case "toString" -> "HTTP/1.1 " + statusCode + "\n\nconfirm-123";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class CountingHttpClient implements HttpClient {
        private final List<Short> statusCodes;
        private final List<String> requests = new ArrayList<>();
        private int count;
        private String lastRequest = "";

        private CountingHttpClient(int statusCode, int... remainingStatusCodes) {
            ArrayList<Short> statuses = new ArrayList<>();
            statuses.add((short) statusCode);
            for (int remainingStatusCode : remainingStatusCodes) {
                statuses.add((short) remainingStatusCode);
            }
            this.statusCodes = List.copyOf(statuses);
        }

        @Override
        public HttpResponse send(HttpRequest request) {
            lastRequest = request.toString();
            requests.add(lastRequest);
            short statusCode = statusCodes.get(Math.min(count, statusCodes.size() - 1));
            count++;
            return response(statusCode);
        }

        private int count() {
            return count;
        }

        private String lastRequest() {
            return lastRequest;
        }
    }

    private static final class CapturingSetupRunner extends ScenarioSetupFlowRunner {
        private final ScenarioSetupRunResult result;

        private CapturingSetupRunner(ScenarioSetupRunResult result) {
            super(new FlowEngine(new ExtractionService(), new InjectionService(), new CountingHttpClient(200)), CLOCK);
            this.result = result;
        }

        @Override
        public ScenarioSetupRunResult runSetup(
                FlowDefinition flow,
                String untilBeforeStepId,
                AuthProfile setupAuthProfile,
                DataProfile dataProfile,
                Map<String, String> env,
                Map<String, String> legacy,
                RenderMode renderMode) {
            return result;
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
}

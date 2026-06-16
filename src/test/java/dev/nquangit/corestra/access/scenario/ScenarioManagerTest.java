package dev.nquangit.corestra.access.scenario;

import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
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
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.http.extract.ExtractionService;
import dev.nquangit.corestra.http.inject.InjectionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioManagerTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC);

    @Test
    void noneSetupReturnsEmptyScenario() {
        CapturingSetupRunner setupRunner = new CapturingSetupRunner(successResult());
        ScenarioPreparationResult result = manager(setupRunner, workspace()).prepareScenario(
                testCase(SetupConfig.none(), ""),
                workspace(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.success());
        assertTrue(result.scenario().isEmpty());
        assertEquals("", setupRunner.capturedAuthProfileId);
    }

    @Test
    void dataOwnerBindingUsesDataProfileOwnerAuthProfile() {
        CapturingSetupRunner setupRunner = new CapturingSetupRunner(successResult());

        ScenarioPreparationResult result = manager(setupRunner, workspace()).prepareScenario(
                setupTestCase(SetupAuthBinding.DATA_OWNER, ""),
                workspace(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.success());
        assertEquals("auth-owner", setupRunner.capturedAuthProfileId);
    }

    @Test
    void targetActorBindingUsesTargetAuthProfile() {
        CapturingSetupRunner setupRunner = new CapturingSetupRunner(successResult());

        ScenarioPreparationResult result = manager(setupRunner, workspace()).prepareScenario(
                setupTestCase(SetupAuthBinding.TARGET_ACTOR, ""),
                workspace(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.success());
        assertEquals("auth-target", setupRunner.capturedAuthProfileId);
    }

    @Test
    void fixedProfileBindingUsesSetupAuthProfileId() {
        CapturingSetupRunner setupRunner = new CapturingSetupRunner(successResult());

        ScenarioPreparationResult result = manager(setupRunner, workspace()).prepareScenario(
                setupTestCase(SetupAuthBinding.FIXED_PROFILE, "auth-fixed"),
                workspace(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.success());
        assertEquals("auth-fixed", setupRunner.capturedAuthProfileId);
    }

    @Test
    void missingResolvedSetupAuthFailsCleanly() {
        AccessWorkspace workspace = AccessWorkspace.builder()
                .authProfile(AuthProfile.manual("auth-target", "Target", "viewer"))
                .dataProfile(DataProfile.enabled("data-1", "Owner data", "missing-owner", Map.of("accountId", "acct-1")))
                .build();

        ScenarioPreparationResult result = manager(new CapturingSetupRunner(successResult()), workspace).prepareScenario(
                setupTestCase(SetupAuthBinding.DATA_OWNER, ""),
                workspace,
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.failed());
        assertTrue(result.reason().contains("Setup auth profile"));
    }

    @Test
    void reuseScenarioMissingScenarioIdFails() {
        ScenarioPreparationResult result = manager(new CapturingSetupRunner(successResult()), workspace()).prepareScenario(
                testCase(new SetupConfig(SetupMode.REUSE_SCENARIO, "", "", "",
                        SetupAuthBinding.DATA_OWNER, ScenarioPolicy.REUSE_EXISTING), ""),
                workspace(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.failed());
        assertTrue(result.reason().contains("Scenario instance id"));
    }

    @Test
    void reuseScenarioExistingScenarioSucceeds() {
        ScenarioInstance scenario = scenario();
        AccessWorkspace workspace = workspace().toBuilder().scenario(scenario).build();

        ScenarioPreparationResult result = manager(new CapturingSetupRunner(successResult()), workspace).prepareScenario(
                testCase(new SetupConfig(SetupMode.REUSE_SCENARIO, "", "", "",
                        SetupAuthBinding.DATA_OWNER, ScenarioPolicy.REUSE_EXISTING), scenario.id()),
                workspace,
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(result.success());
        assertEquals(Map.of("confirmToken", "confirm-123"), result.scenario().orElseThrow().toVariableMap());
    }

    private static ScenarioManager manager(CapturingSetupRunner setupRunner, AccessWorkspace workspace) {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.replaceWith(workspace);
        AccessIdGenerator ids = prefix -> prefix + "-1";
        return new ScenarioManager(repository, () -> List.of(flow()), setupRunner, null, ids, CLOCK);
    }

    private static ScenarioSetupRunResult successResult() {
        return ScenarioSetupRunResult.success(
                "setup ok",
                Map.of("confirmToken", "confirm-123"),
                Map.of("scenario.confirmToken", "confirm-123"),
                List.of());
    }

    private static AccessWorkspace workspace() {
        return AccessWorkspace.builder()
                .authProfile(AuthProfile.manual("auth-owner", "Owner", "admin"))
                .authProfile(AuthProfile.manual("auth-target", "Target", "viewer"))
                .authProfile(AuthProfile.manual("auth-fixed", "Fixed", "operator"))
                .dataProfile(DataProfile.enabled("data-1", "Owner data", "auth-owner", Map.of("accountId", "acct-1")))
                .build();
    }

    private static AccessTestCase setupTestCase(SetupAuthBinding binding, String fixedProfileId) {
        return testCase(new SetupConfig(SetupMode.RUN_SETUP_FLOW, "flow-setup", "",
                fixedProfileId, binding, ScenarioPolicy.FRESH_PER_TEST), "");
    }

    private static AccessTestCase testCase(SetupConfig setup, String scenarioId) {
        return new AccessTestCase(
                "case-1",
                "Transfer confirm",
                "template-1",
                "flow-target",
                "step-confirm",
                setup,
                new TargetBinding("auth-target", "data-1", "env-1", scenarioId),
                ExpectedResult.DENY,
                List.of(),
                true,
                RunStatus.PENDING,
                0L);
    }

    private static FlowDefinition flow() {
        return new FlowDefinition(
                "flow-setup",
                "Setup",
                "",
                true,
                List.of(),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private static ScenarioInstance scenario() {
        ScenarioVariable variable = new ScenarioVariable(
                "confirmToken",
                "confirm-123",
                1000L,
                0L,
                false,
                false,
                "flow-setup",
                "step-2",
                "data-1");
        return new ScenarioInstance(
                "scenario-1",
                "Transfer setup",
                "data-1",
                Map.of(variable.key(), variable),
                ScenarioPolicy.REUSE_EXISTING,
                1000L,
                false,
                "flow-setup",
                "step-confirm");
    }

    private static FlowEngine dummyEngine() {
        return new FlowEngine(new ExtractionService(), new InjectionService(), request -> response());
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
                    case "toString" -> "HTTP/1.1 200 OK\n\n{}";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class CapturingSetupRunner extends ScenarioSetupFlowRunner {
        private final ScenarioSetupRunResult result;
        private String capturedAuthProfileId = "";

        private CapturingSetupRunner(ScenarioSetupRunResult result) {
            super(dummyEngine(), CLOCK);
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
            capturedAuthProfileId = setupAuthProfile.id();
            return result;
        }
    }
}

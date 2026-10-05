package dev.nquangit.corestra.access.runner;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionMode;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.auth.runtime.AuthFlowPurpose;
import dev.nquangit.corestra.access.auth.runtime.AuthFlowRunResult;
import dev.nquangit.corestra.access.auth.runtime.AuthFlowRunner;
import dev.nquangit.corestra.access.auth.runtime.AuthManager;
import dev.nquangit.corestra.access.auth.runtime.AuthRuleEvaluator;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.BasicResultEvaluator;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.evaluate.IndicatorType;
import dev.nquangit.corestra.access.evaluate.ResponseIndicator;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.flow.AccessTargetType;
import dev.nquangit.corestra.access.flow.FlowInjectionOverride;
import dev.nquangit.corestra.access.flow.FlowInjectionOverrideTarget;
import dev.nquangit.corestra.access.flow.FlowTargetRunMode;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.SetupAuthBinding;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.matrix.ScenarioPolicy;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertion;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertionType;
import dev.nquangit.corestra.access.postcheck.PostCheckAuthBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.AccessRequestTemplateRenderer;
import dev.nquangit.corestra.access.template.AccessScopedVariableStoreFactory;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.teardown.TeardownAuthBinding;
import dev.nquangit.corestra.access.teardown.TeardownConfig;
import dev.nquangit.corestra.access.teardown.TeardownFlowRunner;
import dev.nquangit.corestra.access.teardown.TeardownMode;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.persistence.HttpSendMode;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowStep.StepErrorStrategy;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.rule.InjectRule;
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

class SingleAccessTestRunnerTest {
    @Test
    void missingTemplateReturnsInvalidResultAndDoesNotSend() {
        CountingHttpClient httpClient = new CountingHttpClient(200);
        AccessRunResult result = runner(httpClient).run(
                testCase("missing-template", "auth-1", "", SetupConfig.none()),
                workspace(false, true, false),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(RunStatus.INVALID, result.result().runStatus());
        assertEquals(Verdict.ERROR, result.result().verdict());
        assertFalse(result.requestSent());
        assertEquals(0, httpClient.count());
    }

    @Test
    void missingAuthProfileReturnsInvalidResultAndDoesNotSend() {
        CountingHttpClient httpClient = new CountingHttpClient(200);
        AccessRunResult result = runner(httpClient).run(
                testCase("template-1", "missing-auth", "", SetupConfig.none()),
                workspace(true, false, false),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(RunStatus.INVALID, result.result().runStatus());
        assertFalse(result.requestSent());
        assertEquals(0, httpClient.count());
    }

    @Test
    void missingDataProfileReturnsInvalidResultAndDoesNotSend() {
        CountingHttpClient httpClient = new CountingHttpClient(200);
        AccessRunResult result = runner(httpClient).run(
                testCase("template-1", "auth-1", "missing-data", SetupConfig.none()),
                workspace(true, true, false),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(RunStatus.INVALID, result.result().runStatus());
        assertFalse(result.requestSent());
        assertEquals(0, httpClient.count());
    }

    @Test
    void unresolvedVariableReturnsInvalidResultAndDoesNotSend() {
        CountingHttpClient httpClient = new CountingHttpClient(200);
        AccessRunResult result = runner(httpClient).run(
                testCase("template-1", "auth-1", "", SetupConfig.none()),
                workspace(true, true, false),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(RunStatus.INVALID, result.result().runStatus());
        assertEquals(Verdict.NOT_RUN, result.result().verdict());
        assertTrue(result.result().reason().contains("Unresolved variables"));
        assertEquals(0, httpClient.count());
    }

    @Test
    void unsupportedSetupModeIsSkippedAndDoesNotSend() {
        CountingHttpClient httpClient = new CountingHttpClient(200);
        SetupConfig setup = new SetupConfig(
                SetupMode.RUN_PREFIX,
                "flow-setup",
                "step-before",
                "auth-1",
                SetupAuthBinding.DATA_OWNER,
                ScenarioPolicy.REUSE_EXISTING);

        AccessRunResult result = runner(httpClient).run(
                testCase("template-1", "auth-1", "data-1", setup),
                workspace(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(RunStatus.SKIPPED, result.result().runStatus());
        assertFalse(result.requestSent());
        assertEquals(0, httpClient.count());
    }

    @Test
    void successfulRenderSendsOneRequestAndCapturesEvidence() {
        CountingHttpClient httpClient = new CountingHttpClient(403);

        AccessRunResult result = runner(httpClient).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none()),
                workspace(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, httpClient.count());
        assertTrue(result.requestSent());
        assertEquals(RunStatus.COMPLETED, result.result().runStatus());
        assertEquals(Verdict.OK, result.result().verdict());
        assertTrue(result.evidence().orElseThrow().renderedRequestRaw().contains("/accounts/acct-1"));
        assertTrue(result.evidence().orElseThrow().redactedRequestRaw().contains("Authorization: <redacted>"));
    }

    @Test
    void targetInvalidDoesNotRunPostChecks() {
        CountingHttpClient httpClient = new CountingHttpClient(200, "{\"balance\":100}");

        AccessRunResult result = runner(httpClient).run(
                testCase("template-1", "auth-1", "", SetupConfig.none(), "", List.of(postCheckStatus200())),
                workspaceWithPostChecks(true, true, false),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(RunStatus.INVALID, result.result().runStatus());
        assertEquals(0, httpClient.count());
        assertTrue(result.evidence().isEmpty());
    }

    @Test
    void targetSentRunsPostCheckAndCapturesRedactedEvidence() {
        CountingHttpClient httpClient = new CountingHttpClient(403, "{\"balance\":100}", 200);

        AccessRunResult result = runner(httpClient).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none(), "", List.of(postCheckStatus200())),
                workspaceWithPostChecks(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(2, httpClient.count());
        assertEquals(Verdict.OK, result.result().verdict());
        assertEquals(1, result.evidence().orElseThrow().postCheckEvidence().size());
        assertTrue(result.evidence().orElseThrow().postCheckEvidence().getFirst()
                .redactedRequestRaw().contains("/accounts/acct-1/audit"));
    }

    @Test
    void dryRunRendersTargetAndPostCheckWithoutSendingHttp() {
        CountingHttpClient httpClient = new CountingHttpClient(403, "{\"balance\":100}", 200);

        AccessRunResult result = runner(httpClient).dryRun(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none(), "", List.of(postCheckStatus200())),
                workspaceWithPostChecks(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(0, httpClient.count());
        assertFalse(result.requestSent());
        assertEquals(RunStatus.COMPLETED, result.result().runStatus());
        assertEquals(Verdict.REVIEW, result.result().verdict());
        assertTrue(result.evidence().orElseThrow().redactedRequestRaw().contains("/accounts/acct-1"));
        assertEquals(1, result.evidence().orElseThrow().postCheckEvidence().size());
        assertTrue(result.evidence().orElseThrow().postCheckEvidence().getFirst()
                .redactedRequestRaw().contains("/accounts/acct-1/audit"));
    }

    @Test
    void postCheckAssertionFailureAffectsWarningsAndVerdictForDeniedExpectation() {
        CountingHttpClient httpClient = new CountingHttpClient(403, "{\"balance\":100}", 200);
        PostCheckRequest postCheck = postCheck("post-1", PostCheckAuthBinding.TARGET_ACTOR, "",
                List.of(new PostCheckAssertion(
                        "assert-balance",
                        "Balance unchanged",
                        true,
                        PostCheckAssertionType.JSON_PATH_EQUALS,
                        "balance",
                        "999",
                        false)));

        AccessRunResult result = runner(httpClient).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none(), "", List.of(postCheck)),
                workspaceWithPostChecks(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(2, httpClient.count());
        assertEquals(Verdict.SUSPICIOUS, result.result().verdict());
        assertTrue(result.result().reason().contains("Post-check assertion failure"));
        assertTrue(result.result().warnings().stream().anyMatch(warning -> warning.contains("Balance unchanged")));
        assertEquals(Verdict.SUSPICIOUS, result.evidence().orElseThrow().postCheckEvidence().getFirst().verdict());
    }

    @Test
    void postCheckRenderFailureDoesNotCrashOrSendPostCheck() {
        CountingHttpClient httpClient = new CountingHttpClient(403, "{\"balance\":100}");
        PostCheckRequest postCheck = new PostCheckRequest(
                "post-bad",
                "Bad render",
                true,
                "template-bad-post",
                "",
                PostCheckAuthBinding.TARGET_ACTOR,
                "",
                "",
                List.of(new PostCheckAssertion(
                        "assert-status",
                        "Status 200",
                        true,
                        PostCheckAssertionType.STATUS_CODE,
                        "",
                        "200",
                        false)));

        AccessRunResult result = runner(httpClient).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none(), "", List.of(postCheck)),
                workspaceWithPostChecks(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, httpClient.count());
        assertEquals(Verdict.REVIEW, result.result().verdict());
        assertEquals(Verdict.ERROR, result.evidence().orElseThrow().postCheckEvidence().getFirst().verdict());
        assertTrue(result.result().warnings().stream().anyMatch(warning -> warning.contains("render failed")));
    }

    @Test
    void postCheckCanUseDataOwnerAuthBinding() {
        CountingHttpClient httpClient = new CountingHttpClient(403, "{\"balance\":100}", 200);
        PostCheckRequest postCheck = postCheck("post-owner", PostCheckAuthBinding.DATA_OWNER, "",
                List.of(statusAssertion("assert-status", "200")));

        runner(httpClient).run(
                testCase("template-1", "auth-target", "data-1", SetupConfig.none(), "", List.of(postCheck)),
                ownerTargetWorkspace(),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(2, httpClient.count());
        assertTrue(httpClient.request(1).contains("Authorization: Bearer owner-token"));
    }

    @Test
    void postCheckCanUseTargetActorAuthBinding() {
        CountingHttpClient httpClient = new CountingHttpClient(403, "{\"balance\":100}", 200);
        PostCheckRequest postCheck = postCheck("post-target", PostCheckAuthBinding.TARGET_ACTOR, "",
                List.of(statusAssertion("assert-status", "200")));

        runner(httpClient).run(
                testCase("template-1", "auth-target", "data-1", SetupConfig.none(), "", List.of(postCheck)),
                ownerTargetWorkspace(),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(2, httpClient.count());
        assertTrue(httpClient.request(1).contains("Authorization: Bearer target-token"));
    }

    @Test
    void selectedEvaluationProfileCanMarkHttp200BusinessDenyAsOk() {
        CountingHttpClient httpClient = new CountingHttpClient(200, "{\"errorCode\":\"ACCESS_DENIED\"}");
        EvaluationProfile profile = new EvaluationProfile(
                "eval-1",
                "Business API",
                true,
                List.of(),
                List.of(new ResponseIndicator(
                        "deny-1",
                        "Access denied code",
                        true,
                        IndicatorType.JSON_PATH_EQUALS,
                        "errorCode",
                        "ACCESS_DENIED")),
                List.of(),
                List.of());

        AccessRunResult result = runner(httpClient).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none(), profile.id()),
                workspace(true, true, true).withEvaluationProfile(profile),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, httpClient.count());
        assertEquals(RunStatus.COMPLETED, result.result().runStatus());
        assertEquals(Verdict.OK, result.result().verdict());
        assertTrue(result.result().reason().contains("evaluation profile"));
    }

    @Test
    void teardownNotConfiguredLeavesExistingBehaviorUnchanged() {
        CountingHttpClient httpClient = new CountingHttpClient(403, 200);

        AccessRunResult result = runnerWithTeardown(httpClient, List.of(teardownFlow())).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none()),
                workspace(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, httpClient.count());
        assertEquals(Verdict.OK, result.result().verdict());
        assertTrue(result.evidence().orElseThrow().teardownEvidence().isEmpty());
    }

    @Test
    void teardownRunsAfterSuccessfulTarget() {
        CountingHttpClient httpClient = new CountingHttpClient(403, 200);

        AccessRunResult result = runnerWithTeardown(httpClient, List.of(teardownFlow())).run(
                testCaseWithTeardown(teardownConfig(true, false, false, TeardownAuthBinding.TARGET_ACTOR)),
                workspace(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(2, httpClient.count());
        assertEquals(Verdict.OK, result.result().verdict());
        assertTrue(httpClient.request(1).contains("/cleanup"));
        assertEquals(1, result.evidence().orElseThrow().teardownEvidence().size());
    }

    @Test
    void teardownRunsAfterSuspiciousTargetWhenRunOnFailureEnabled() {
        CountingHttpClient httpClient = new CountingHttpClient(200, 200);

        AccessRunResult result = runnerWithTeardown(httpClient, List.of(teardownFlow())).run(
                testCaseWithTeardown(teardownConfig(false, true, false, TeardownAuthBinding.TARGET_ACTOR)),
                workspace(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(2, httpClient.count());
        assertEquals(Verdict.SUSPICIOUS, result.result().verdict());
        assertEquals(1, result.evidence().orElseThrow().teardownEvidence().size());
    }

    @Test
    void teardownDoesNotRunAfterSuspiciousTargetWhenRunOnFailureDisabled() {
        CountingHttpClient httpClient = new CountingHttpClient(200, 200);

        AccessRunResult result = runnerWithTeardown(httpClient, List.of(teardownFlow())).run(
                testCaseWithTeardown(teardownConfig(false, false, false, TeardownAuthBinding.TARGET_ACTOR)),
                workspace(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, httpClient.count());
        assertEquals(Verdict.SUSPICIOUS, result.result().verdict());
        assertTrue(result.evidence().orElseThrow().teardownEvidence().isEmpty());
    }

    @Test
    void teardownUsesDataOwnerAuthBinding() {
        CountingHttpClient httpClient = new CountingHttpClient(403, 200);

        runnerWithTeardown(httpClient, List.of(teardownFlow())).run(
                testCaseWithTeardown(
                        "auth-target",
                        "data-1",
                        teardownConfig(true, false, false, TeardownAuthBinding.DATA_OWNER)),
                ownerTargetWorkspace(),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(2, httpClient.count());
        assertTrue(httpClient.request(1).contains("Authorization: Bearer owner-token"));
        assertFalse(httpClient.request(1).contains("target-token"));
    }

    @Test
    void teardownReceivesScenarioVariables() {
        CountingHttpClient httpClient = new CountingHttpClient(403, 200);

        runnerWithTeardown(httpClient, List.of(teardownScenarioFlow())).run(
                testCaseWithTeardown(teardownConfig(true, false, false, TeardownAuthBinding.TARGET_ACTOR)),
                workspace(true, true, true),
                Map.of(),
                Map.of("confirmToken", "confirm-123"),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(2, httpClient.count());
        assertTrue(httpClient.request(1).contains("confirm=confirm-123"));
    }

    @Test
    void teardownFailureAddsWarningWithoutHidingTargetVerdict() {
        CountingHttpClient httpClient = new CountingHttpClient(403, -1);

        AccessRunResult result = runnerWithTeardown(httpClient, List.of(teardownFlow())).run(
                testCaseWithTeardown(teardownConfig(true, false, false, TeardownAuthBinding.TARGET_ACTOR)),
                workspace(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(2, httpClient.count());
        assertEquals(Verdict.OK, result.result().verdict());
        assertTrue(result.result().warnings().stream().anyMatch(warning -> warning.contains("Teardown flow failed")));
        assertFalse(result.evidence().orElseThrow().teardownEvidence().getFirst().success());
    }

    @Test
    void dryRunRendersTeardownWithoutSendingHttp() {
        CountingHttpClient httpClient = new CountingHttpClient(403, 200);

        AccessRunResult result = runnerWithTeardown(httpClient, List.of(teardownFlow())).dryRun(
                testCaseWithTeardown(teardownConfig(true, false, false, TeardownAuthBinding.TARGET_ACTOR)),
                workspace(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(0, httpClient.count());
        assertEquals(Verdict.REVIEW, result.result().verdict());
        assertEquals(1, result.evidence().orElseThrow().teardownEvidence().size());
        assertTrue(result.evidence().orElseThrow().teardownEvidence().getFirst()
                .steps().getFirst().redactedRequestRaw().contains("/cleanup"));
    }

    @Test
    void manualProviderWithAuthManagerKeepsCurrentBehavior() {
        CountingHttpClient httpClient = new CountingHttpClient(403);
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        RecordingAuthFlowRunner authFlowRunner = new RecordingAuthFlowRunner(
                AuthFlowRunResult.executed(true, "unexpected", 200, Map.of("accessToken", "new-token"), "", List.of()));
        AuthManager authManager = authManager(repository, authFlowRunner);

        AccessRunResult result = runner(httpClient, authManager).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none()),
                workspace(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, httpClient.count());
        assertEquals(0, authFlowRunner.count());
        assertTrue(result.requestSent());
    }

    @Test
    void nonManualProviderEnsureAuthSuccessSendsRequestWithUpdatedAuthState() {
        CountingHttpClient httpClient = new CountingHttpClient(403);
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProvider(provider());
        repository.saveAuthProfile(configuredProfile());
        RecordingAuthFlowRunner authFlowRunner = new RecordingAuthFlowRunner(
                AuthFlowRunResult.executed(true, "login ok", 200,
                        Map.of("accessToken", "token-updated", "expiresAt", "5000"),
                        "HTTP/1.1 200 OK\n\n{}",
                        List.of()));
        AuthManager authManager = authManager(repository, authFlowRunner);

        AccessRunResult result = runner(httpClient, authManager).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none()),
                configuredWorkspace(true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, authFlowRunner.count());
        assertEquals(1, httpClient.count());
        assertTrue(result.requestSent());
        assertTrue(httpClient.lastRequest().contains("Authorization: Bearer token-updated"));
    }

    @Test
    void nonManualProviderInjectionRuleAppliesToTargetRequest() {
        CountingHttpClient httpClient = new CountingHttpClient(403);
        AuthProvider provider = providerWithRules(
                List.of(headerRule("Authorization", "Bearer {{auth.accessToken}}")),
                List.of(),
                AuthFallbackPolicy.LOGIN_ONLY,
                AuthRetryPolicy.NO_RETRY);
        AuthProfile profile = configuredProfile(new AuthState(Map.of(), Map.of(), Map.of(), 0, 0));
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProvider(provider);
        repository.saveAuthProfile(profile);
        RecordingAuthFlowRunner authFlowRunner = new RecordingAuthFlowRunner(
                AuthFlowRunResult.executed(true, "login ok", 200,
                        Map.of("accessToken", "provider-token", "expiresAt", "5000"),
                        "HTTP/1.1 200 OK\n\n{}",
                        List.of()));
        AuthManager authManager = authManager(repository, authFlowRunner);

        AccessRunResult result = runner(httpClient, authManager).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none()),
                configuredWorkspace(provider, profile, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, httpClient.count());
        assertTrue(result.requestSent());
        assertTrue(httpClient.lastRequest().contains("Authorization: Bearer provider-token"));
    }

    @Test
    void manualAuthStateOverridesProviderInjectionRules() {
        CountingHttpClient httpClient = new CountingHttpClient(403);
        AuthProvider provider = providerWithRules(
                List.of(headerRule("Authorization", "Provider {{auth.accessToken}}")),
                List.of(),
                AuthFallbackPolicy.LOGIN_ONLY,
                AuthRetryPolicy.NO_RETRY);
        AuthProfile profile = configuredProfile(new AuthState(
                Map.of(),
                Map.of("Authorization", "Manual {{auth.accessToken}}"),
                Map.of(),
                0,
                0));
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProvider(provider);
        repository.saveAuthProfile(profile);
        RecordingAuthFlowRunner authFlowRunner = new RecordingAuthFlowRunner(
                AuthFlowRunResult.executed(true, "login ok", 200,
                        Map.of("accessToken", "token-updated", "expiresAt", "5000"),
                        "HTTP/1.1 200 OK\n\n{}",
                        List.of()));
        AuthManager authManager = authManager(repository, authFlowRunner);

        AccessRunResult result = runner(httpClient, authManager).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none()),
                configuredWorkspace(provider, profile, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, httpClient.count());
        assertTrue(result.requestSent());
        assertTrue(httpClient.lastRequest().contains("Authorization: Manual token-updated"));
        assertFalse(httpClient.lastRequest().contains("Provider token-updated"));
    }

    @Test
    void targetAuthFailureRefreshesRerendersAndRetriesOnce() {
        CountingHttpClient httpClient = new CountingHttpClient(401, 403);
        AuthProvider provider = providerWithRules(
                List.of(headerRule("Authorization", "Bearer {{auth.accessToken}}")),
                List.of(statusFailureRule(401)),
                AuthFallbackPolicy.MANUAL_ONLY,
                AuthRetryPolicy.REFRESH_AND_RETRY_ONCE);
        AuthProfile profile = configuredProfile(new AuthState(
                Map.of("accessToken", "token-old"),
                Map.of(),
                Map.of(),
                0,
                0));
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProvider(provider);
        repository.saveAuthProfile(profile);
        RecordingAuthFlowRunner authFlowRunner = new RecordingAuthFlowRunner(
                AuthFlowRunResult.executed(true, "refresh ok", 200,
                        Map.of("accessToken", "token-new", "expiresAt", "5000"),
                        "HTTP/1.1 200 OK\n\n{}",
                        List.of()));
        AuthManager authManager = authManager(repository, authFlowRunner);

        AccessRunResult result = runner(httpClient, authManager).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none()),
                configuredWorkspace(provider, profile, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(2, httpClient.count());
        assertEquals(1, authFlowRunner.count());
        assertTrue(httpClient.request(0).contains("Authorization: Bearer token-old"));
        assertTrue(httpClient.request(1).contains("Authorization: Bearer token-new"));
        assertEquals(RunStatus.COMPLETED, result.result().runStatus());
        assertEquals(Verdict.OK, result.result().verdict());
    }

    @Test
    void targetAuthFailureRetryFailureDoesNotSendSecondTargetRequest() {
        CountingHttpClient httpClient = new CountingHttpClient(401, 403);
        AuthProvider provider = providerWithRules(
                List.of(headerRule("Authorization", "Bearer {{auth.accessToken}}")),
                List.of(statusFailureRule(401)),
                AuthFallbackPolicy.MANUAL_ONLY,
                AuthRetryPolicy.REFRESH_AND_RETRY_ONCE);
        AuthProfile profile = configuredProfile(new AuthState(
                Map.of("accessToken", "token-old"),
                Map.of(),
                Map.of(),
                0,
                0));
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProvider(provider);
        repository.saveAuthProfile(profile);
        RecordingAuthFlowRunner authFlowRunner = new RecordingAuthFlowRunner(
                AuthFlowRunResult.executed(false, "refresh failed", 500, Map.of(),
                        "HTTP/1.1 500 Error\n\n{}",
                        List.of()));
        AuthManager authManager = authManager(repository, authFlowRunner);

        AccessRunResult result = runner(httpClient, authManager).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none()),
                configuredWorkspace(provider, profile, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, httpClient.count());
        assertEquals(1, authFlowRunner.count());
        assertEquals(RunStatus.FAILED, result.result().runStatus());
        assertTrue(result.result().reason().contains("Auth retry failed"));
        assertTrue(result.evidence().isPresent());
    }

    @Test
    void nonManualProviderEnsureAuthFailureDoesNotSendTargetRequest() {
        CountingHttpClient httpClient = new CountingHttpClient(403);
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        repository.saveAuthProvider(provider());
        repository.saveAuthProfile(configuredProfile());
        RecordingAuthFlowRunner authFlowRunner = new RecordingAuthFlowRunner(
                AuthFlowRunResult.executed(false, "login failed", 500, Map.of(), "HTTP/1.1 500 Error\n\n{}", List.of()));
        AuthManager authManager = authManager(repository, authFlowRunner);

        AccessRunResult result = runner(httpClient, authManager).run(
                testCase("template-1", "auth-1", "data-1", SetupConfig.none()),
                configuredWorkspace(true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, authFlowRunner.count());
        assertEquals(0, httpClient.count());
        assertFalse(result.requestSent());
        assertEquals(RunStatus.FAILED, result.result().runStatus());
        assertTrue(result.result().reason().contains("Auth ensure failed"));
    }

    @Test
    void flowTargetAndTeardownPreservePerRequestSendingModes() {
        CountingHttpClient httpClient = new CountingHttpClient(403, "{}", 200);
        FlowDefinition original = flowWithOverrideableTarget();
        FlowDefinition mixed = new FlowDefinition(original.id(), original.name(), original.description(), true,
                List.of(original.steps().get(0).withSendModeOverride(HttpSendMode.STANDARD),
                        original.steps().get(1).withSendModeOverride(HttpSendMode.PROXY_PIPELINE)),
                original.rules(), original.aggregation(), original.triggerConfig());
        AccessFlowTarget target = flowTarget(mixed);
        AccessRunResult result = runner(httpClient).run(flowTargetTestCase(target.id(), ExpectedResult.ALLOW),
                workspace(true, true, true).withFlowTarget(target), Map.of(), Map.of(), Map.of(), RenderMode.STRICT);
        assertEquals(RunStatus.COMPLETED, result.result().runStatus());
        assertEquals(List.of(HttpSendMode.STANDARD, HttpSendMode.PROXY_PIPELINE), httpClient.sendModes);

        CountingHttpClient teardownClient = new CountingHttpClient(403, 200);
        FlowDefinition cleanup = teardownFlow();
        cleanup = new FlowDefinition(cleanup.id(), cleanup.name(), cleanup.description(), true,
                List.of(cleanup.steps().getFirst().withSendModeOverride(HttpSendMode.PROXY_PIPELINE)),
                cleanup.rules(), cleanup.aggregation(), cleanup.triggerConfig());
        AccessRunResult cleaned = runnerWithTeardown(teardownClient, List.of(cleanup)).run(
                testCaseWithTeardown(teardownConfig(true, false, false, TeardownAuthBinding.TARGET_ACTOR)),
                workspace(true, true, true), Map.of(), Map.of(), Map.of(), RenderMode.STRICT);
        assertEquals(2, teardownClient.count());
        assertFalse(cleaned.evidence().orElseThrow().teardownEvidence().isEmpty());
        assertEquals(List.of(HttpSendMode.PROXY_PIPELINE), teardownClient.sendModes);
    }

    @Test
    void flowTargetExecutesSnapshotThroughTargetStepAndUsesTargetResponseForEvaluation() {
        CountingHttpClient httpClient = new CountingHttpClient(403, "{}", 200);
        AccessFlowTarget flowTarget = flowTarget(flowWithOverrideableTarget());

        AccessRunResult result = runner(httpClient).run(
                flowTargetTestCase(flowTarget.id(), ExpectedResult.ALLOW),
                workspace(true, true, true).withFlowTarget(flowTarget),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(2, httpClient.count());
        assertEquals(RunStatus.COMPLETED, result.result().runStatus());
        assertEquals(Verdict.OK, result.result().verdict());
        assertTrue(httpClient.request(1).contains("accountId=acct-1-x"));
        assertEquals(2, result.evidence().orElseThrow().flowStepEvidence().size());
        assertTrue(result.evidence().orElseThrow().flowStepEvidence().get(1).targetStep());
        assertEquals(InjectRule.ValueSourceType.VARIABLE,
                flowTarget.flowSnapshot().steps().get(1).injectRules().getFirst().valueSourceType());
    }

    @Test
    void dryRunFlowTargetRendersStepsWithoutSendingHttp() {
        CountingHttpClient httpClient = new CountingHttpClient(200);
        AccessFlowTarget flowTarget = flowTarget(flowWithOverrideableTarget());

        AccessRunResult result = runner(httpClient).dryRun(
                flowTargetTestCase(flowTarget.id(), ExpectedResult.REVIEW),
                workspace(true, true, true).withFlowTarget(flowTarget),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(0, httpClient.count());
        assertFalse(result.requestSent());
        assertEquals(RunStatus.COMPLETED, result.result().runStatus());
        assertEquals(2, result.evidence().orElseThrow().flowStepEvidence().size());
        assertTrue(result.evidence().orElseThrow().flowStepEvidence().get(1)
                .redactedRequestRaw().contains("accountId=acct-1-x"));
    }

    @Test
    void fullFlowTargetExecutesPastSelectedStepButEvaluatesSelectedTargetResponse() {
        CountingHttpClient httpClient = new CountingHttpClient(200, "{}", 403, 200);
        AccessFlowTarget flowTarget = flowTarget(flowWithPostTargetStep())
                .withRunMode(FlowTargetRunMode.FULL_FLOW_EVALUATE_STEP);

        AccessRunResult result = runner(httpClient).run(
                flowTargetTestCase(flowTarget.id(), ExpectedResult.DENY),
                workspace(true, true, true).withFlowTarget(flowTarget),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(3, httpClient.count());
        assertTrue(httpClient.request(2).contains("/after"));
        assertEquals(RunStatus.COMPLETED, result.result().runStatus());
        assertEquals(Verdict.OK, result.result().verdict());
        assertEquals(3, result.evidence().orElseThrow().flowStepEvidence().size());
        assertTrue(result.evidence().orElseThrow().flowStepEvidence().get(1).targetStep());
    }

    @Test
    void dryRunFullFlowTargetRendersAllEnabledStepsWithoutSendingHttp() {
        CountingHttpClient httpClient = new CountingHttpClient(200);
        AccessFlowTarget flowTarget = flowTarget(flowWithPostTargetStep())
                .withRunMode(FlowTargetRunMode.FULL_FLOW_EVALUATE_STEP);

        AccessRunResult result = runner(httpClient).dryRun(
                flowTargetTestCase(flowTarget.id(), ExpectedResult.REVIEW),
                workspace(true, true, true).withFlowTarget(flowTarget),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(0, httpClient.count());
        assertFalse(result.requestSent());
        assertEquals(RunStatus.COMPLETED, result.result().runStatus());
        assertEquals(3, result.evidence().orElseThrow().flowStepEvidence().size());
        assertTrue(result.evidence().orElseThrow().flowStepEvidence().get(1).targetStep());
        assertTrue(result.evidence().orElseThrow().flowStepEvidence().get(2)
                .redactedRequestRaw().contains("/after"));
    }

    @Test
    void missingFlowTargetReturnsInvalidAndDoesNotSend() {
        CountingHttpClient httpClient = new CountingHttpClient(200);

        AccessRunResult result = runner(httpClient).run(
                flowTargetTestCase("missing-flow-target", ExpectedResult.REVIEW),
                workspace(true, true, true),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(RunStatus.INVALID, result.result().runStatus());
        assertFalse(result.requestSent());
        assertEquals(0, httpClient.count());
        assertTrue(result.result().reason().contains("Flow target not found"));
    }

    private static SingleAccessTestRunner runner(CountingHttpClient httpClient) {
        return runner(httpClient, null);
    }

    private static SingleAccessTestRunner runner(CountingHttpClient httpClient, AuthManager authManager) {
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
                authManager,
                new AuthProviderRequestApplier(templateRenderer),
                new TargetAuthFailureDetector());
    }

    private static SingleAccessTestRunner runnerWithTeardown(
            CountingHttpClient httpClient,
            List<FlowDefinition> teardownFlows) {
        TemplateRenderer templateRenderer = new TemplateRenderer(new VariableResolver(key -> Optional.empty()));
        AtomicInteger counter = new AtomicInteger();
        AccessIdGenerator ids = prefix -> prefix + "-" + counter.incrementAndGet();
        InjectionService injectionService = new InjectionService();
        TeardownFlowRunner teardownFlowRunner = new TeardownFlowRunner(
                () -> teardownFlows,
                new ExtractionService(),
                injectionService,
                httpClient,
                new FakeRequestAdapter(),
                new RedactionService(),
                Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC));
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
                null,
                teardownFlowRunner);
    }

    private static AccessWorkspace workspace(boolean template, boolean auth, boolean data) {
        AccessWorkspace.Builder builder = AccessWorkspace.builder();
        if (template) {
            builder.template(template());
        }
        if (auth) {
            builder.authProfile(new AuthProfile(
                    "auth-1",
                    "Owner",
                    "admin",
                    "manual",
                    Map.of(),
                    new AuthState(Map.of("accessToken", "token-abc"), Map.of("Authorization", "Bearer {{auth.accessToken}}"), Map.of(), 0, 0),
                    true));
        }
        if (data) {
            builder.dataProfile(new DataProfile("data-1", "Owner data", "auth-1", Map.of("accountId", "acct-1"), true));
        }
        return builder.build();
    }

    private static AccessWorkspace workspaceWithPostChecks(boolean template, boolean auth, boolean data) {
        AccessWorkspace.Builder builder = workspace(template, auth, data).toBuilder()
                .template(postCheckTemplate())
                .template(badPostCheckTemplate());
        return builder.build();
    }

    private static AccessWorkspace ownerTargetWorkspace() {
        AuthProfile owner = new AuthProfile(
                "auth-owner",
                "Owner",
                "user",
                "manual",
                Map.of(),
                new AuthState(
                        Map.of("accessToken", "owner-token"),
                        Map.of("Authorization", "Bearer {{auth.accessToken}}"),
                        Map.of(),
                        0,
                        0),
                true);
        AuthProfile target = new AuthProfile(
                "auth-target",
                "Target",
                "user",
                "manual",
                Map.of(),
                new AuthState(
                        Map.of("accessToken", "target-token"),
                        Map.of("Authorization", "Bearer {{auth.accessToken}}"),
                        Map.of(),
                        0,
                        0),
                true);
        return AccessWorkspace.builder()
                .authProfile(owner)
                .authProfile(target)
                .dataProfile(new DataProfile("data-1", "Owner data", owner.id(), Map.of("accountId", "acct-1"), true))
                .template(template())
                .template(postCheckTemplate())
                .build();
    }

    private static AccessWorkspace configuredWorkspace(boolean data) {
        return configuredWorkspace(provider(), configuredProfile(), data);
    }

    private static AccessWorkspace configuredWorkspace(AuthProvider provider, AuthProfile profile, boolean data) {
        AccessWorkspace.Builder builder = AccessWorkspace.builder()
                .authProvider(provider)
                .authProfile(profile)
                .template(template());
        if (data) {
            builder.dataProfile(new DataProfile("data-1", "Owner data", "auth-1", Map.of("accountId", "acct-1"), true));
        }
        return builder.build();
    }

    private static AuthProfile configuredProfile() {
        return configuredProfile(new AuthState(
                Map.of(),
                Map.of("Authorization", "Bearer {{auth.accessToken}}"),
                Map.of(),
                0,
                0));
    }

    private static AuthProfile configuredProfile(AuthState state) {
        return new AuthProfile(
                "auth-1",
                "Owner",
                "admin",
                "provider-1",
                Map.of("username", "alice"),
                state,
                true);
    }

    private static AuthProvider provider() {
        return providerWithRules(List.of(), List.of(), AuthFallbackPolicy.LOGIN_ONLY, AuthRetryPolicy.NO_RETRY);
    }

    private static AuthProvider providerWithRules(
            List<AuthInjectionRule> injectionRules,
            List<AuthFailureRule> failureRules,
            AuthFallbackPolicy fallbackPolicy,
            AuthRetryPolicy retryPolicy) {
        return new AuthProvider(
                "provider-1",
                "Provider",
                "",
                true,
                "login-flow",
                "refresh-flow",
                "",
                injectionRules,
                List.of(),
                failureRules,
                fallbackPolicy,
                retryPolicy);
    }

    private static AuthInjectionRule headerRule(String target, String valueTemplate) {
        return new AuthInjectionRule(
                "rule-header",
                "Header",
                true,
                AuthInjectionLocation.HEADER,
                target,
                valueTemplate,
                AuthInjectionMode.OVERRIDE);
    }

    private static AuthFailureRule statusFailureRule(int statusCode) {
        return new AuthFailureRule(
                "failure-status",
                "Status failure",
                true,
                AuthRuleType.STATUS_CODE,
                String.valueOf(statusCode),
                "");
    }

    private static AuthManager authManager(InMemoryAccessRepository repository, AuthFlowRunner runner) {
        return new AuthManager(
                repository,
                runner,
                new AuthRuleEvaluator(),
                Clock.fixed(Instant.ofEpochMilli(1000L), ZoneOffset.UTC));
    }

    private static AccessRequestTemplate template() {
        return new AccessRequestTemplate(
                "template-1",
                "Get account",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/accounts/{{data.accountId}}", List.of(), ""),
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")),
                OperationMetadata.unknown(),
                "flow-1",
                "step-1",
                "hash-1",
                true);
    }

    private static AccessRequestTemplate postCheckTemplate() {
        return new AccessRequestTemplate(
                "template-post",
                "Check account",
                new StoredRequestTemplate(
                        "GET",
                        "https",
                        "example.test",
                        443,
                        "/accounts/{{data.accountId}}/audit",
                        List.of(),
                        ""),
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")),
                OperationMetadata.unknown(),
                "flow-1",
                "step-post",
                "hash-post",
                true);
    }

    private static AccessRequestTemplate badPostCheckTemplate() {
        return new AccessRequestTemplate(
                "template-bad-post",
                "Bad post-check",
                new StoredRequestTemplate(
                        "GET",
                        "https",
                        "example.test",
                        443,
                        "/accounts/{{data.missingValue}}/audit",
                        List.of(),
                        ""),
                Set.of(new ScopedVariableRef(VariableScope.DATA, "missingValue")),
                OperationMetadata.unknown(),
                "flow-1",
                "step-post-bad",
                "hash-post-bad",
                true);
    }

    private static AccessTestCase testCase(String templateId, String authId, String dataId, SetupConfig setup) {
        return testCase(templateId, authId, dataId, setup, "");
    }

    private static AccessTestCase testCase(
            String templateId,
            String authId,
            String dataId,
            SetupConfig setup,
            String evaluationProfileId) {
        return testCase(templateId, authId, dataId, setup, evaluationProfileId, List.of());
    }

    private static AccessTestCase testCase(
            String templateId,
            String authId,
            String dataId,
            SetupConfig setup,
            String evaluationProfileId,
            List<PostCheckRequest> postChecks) {
        return new AccessTestCase(
                "case-1",
                "Owner read",
                templateId,
                "flow-1",
                "step-1",
                setup,
                new TargetBinding(authId, dataId, "", ""),
                ExpectedResult.DENY,
                evaluationProfileId,
                List.of(),
                postChecks,
                true,
                RunStatus.PENDING,
                0L);
    }

    private static AccessTestCase flowTargetTestCase(String flowTargetId, ExpectedResult expected) {
        return new AccessTestCase(
                "case-flow",
                "Flow target row",
                AccessTargetType.FLOW_TARGET,
                "",
                flowTargetId,
                "flow-access",
                "step-target",
                SetupConfig.none(),
                new TargetBinding("auth-1", "data-1", "", ""),
                expected,
                "",
                List.of(),
                List.of(),
                TeardownConfig.none(),
                true,
                RunStatus.PENDING,
                0L);
    }

    private static AccessFlowTarget flowTarget(FlowDefinition flow) {
        return new AccessFlowTarget(
                "flowtarget-1",
                "Access flow target",
                flow,
                flow.id(),
                flow.name(),
                "hash-flow",
                "step-target",
                true,
                List.of(new FlowInjectionOverride(
                        "override-1",
                        "Override account id",
                        true,
                        FlowInjectionOverrideTarget.VALUE_SOURCE_KEY,
                        "data.accountId",
                        "{{data.accountId}}-x")));
    }

    private static FlowDefinition flowWithOverrideableTarget() {
        return new FlowDefinition(
                "flow-access",
                "Access Flow",
                "",
                true,
                List.of(
                        new FlowStep(
                                "step-prepare",
                                "Prepare",
                                true,
                                "",
                                new StoredRequestTemplate(
                                        "GET",
                                        "https",
                                        "example.test",
                                        443,
                                        "/prepare",
                                        List.of(),
                                        ""),
                                List.of(),
                                List.of(),
                                List.of(),
                                StepErrorStrategy.FAIL_FLOW),
                        new FlowStep(
                                "step-target",
                                "Target",
                                true,
                                "",
                                new StoredRequestTemplate(
                                        "GET",
                                        "https",
                                        "example.test",
                                        443,
                                        "/target",
                                        List.of(),
                                        ""),
                                List.of(),
                                List.of(new InjectRule(
                                        "inject-account",
                                        "Inject account",
                                        true,
                                        InjectRule.Target.REQUEST_QUERY_PARAMETER,
                                        "accountId",
                                        InjectRule.ValueSourceType.VARIABLE,
                                        "data.accountId",
                                        "",
                                        InjectRule.WriteStrategy.OVERWRITE)),
                                List.of(),
                                StepErrorStrategy.FAIL_FLOW)),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private static FlowDefinition flowWithPostTargetStep() {
        FlowDefinition base = flowWithOverrideableTarget();
        return new FlowDefinition(
                base.id(),
                base.name(),
                base.description(),
                base.enabled(),
                List.of(
                        base.steps().get(0),
                        base.steps().get(1),
                        new FlowStep(
                                "step-after",
                                "After",
                                true,
                                "",
                                new StoredRequestTemplate(
                                        "GET",
                                        "https",
                                        "example.test",
                                        443,
                                        "/after",
                                        List.of(),
                                        ""),
                                List.of(),
                                List.of(),
                                List.of(),
                                StepErrorStrategy.FAIL_FLOW)),
                base.rules(),
                base.aggregation(),
                base.triggerConfig());
    }

    private static AccessTestCase testCaseWithTeardown(TeardownConfig teardown) {
        return testCaseWithTeardown("auth-1", "data-1", teardown);
    }

    private static AccessTestCase testCaseWithTeardown(
            String authId,
            String dataId,
            TeardownConfig teardown) {
        return new AccessTestCase(
                "case-1",
                "Owner read",
                "template-1",
                "flow-1",
                "step-1",
                SetupConfig.none(),
                new TargetBinding(authId, dataId, "", ""),
                ExpectedResult.DENY,
                "",
                List.of(),
                List.of(),
                teardown,
                true,
                RunStatus.PENDING,
                0L);
    }

    private static TeardownConfig teardownConfig(
            boolean runOnSuccess,
            boolean runOnFailure,
            boolean runOnSkipped,
            TeardownAuthBinding authBinding) {
        return new TeardownConfig(
                TeardownMode.RUN_FLOW,
                "flow-teardown",
                authBinding,
                "",
                runOnSuccess,
                runOnFailure,
                runOnSkipped);
    }

    private static FlowDefinition teardownFlow() {
        return teardownFlow(List.of(new InjectRule(
                "inject-auth",
                "Auth header",
                true,
                InjectRule.Target.REQUEST_HEADER,
                "Authorization",
                InjectRule.ValueSourceType.VARIABLE,
                "auth.accessToken",
                "",
                InjectRule.WriteStrategy.OVERWRITE)));
    }

    private static FlowDefinition teardownScenarioFlow() {
        return teardownFlow(List.of(new InjectRule(
                "inject-confirm",
                "Confirm token",
                true,
                InjectRule.Target.REQUEST_QUERY_PARAMETER,
                "confirm",
                InjectRule.ValueSourceType.VARIABLE,
                "scenario.confirmToken",
                "",
                InjectRule.WriteStrategy.OVERWRITE)));
    }

    private static FlowDefinition teardownFlow(List<InjectRule> injectRules) {
        return new FlowDefinition(
                "flow-teardown",
                "Cleanup Flow",
                "",
                true,
                List.of(new FlowStep(
                        "cleanup-step",
                        "Cleanup",
                        true,
                        "",
                        new StoredRequestTemplate("DELETE", "https", "example.test", 443, "/cleanup", List.of(), ""),
                        List.of(),
                        injectRules,
                        List.of(),
                        StepErrorStrategy.FAIL_FLOW)),
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private static PostCheckRequest postCheckStatus200() {
        return postCheck("post-1", PostCheckAuthBinding.TARGET_ACTOR, "", List.of(statusAssertion("assert-status", "200")));
    }

    private static PostCheckRequest postCheck(
            String id,
            PostCheckAuthBinding binding,
            String authProfileId,
            List<PostCheckAssertion> assertions) {
        return new PostCheckRequest(
                id,
                "Check account",
                true,
                "template-post",
                authProfileId,
                binding,
                "",
                "",
                assertions);
    }

    private static PostCheckAssertion statusAssertion(String id, String expectedStatus) {
        return new PostCheckAssertion(
                id,
                "Status " + expectedStatus,
                true,
                PostCheckAssertionType.STATUS_CODE,
                "",
                expectedStatus,
                false);
    }

    private static final class CountingHttpClient implements HttpClient {
        private final List<Short> statusCodes;
        private final String responseBody;
        private final List<String> requests = new ArrayList<>();
        private final List<HttpSendMode> sendModes = new ArrayList<>();
        private int count;
        private String lastRequest = "";

        private CountingHttpClient(int statusCode, int... remainingStatusCodes) {
            this(statusCode, "{\"accessToken\":\"secret\"}", remainingStatusCodes);
        }

        private CountingHttpClient(int statusCode, String responseBody, int... remainingStatusCodes) {
            ArrayList<Short> statuses = new ArrayList<>();
            statuses.add((short) statusCode);
            for (int remainingStatusCode : remainingStatusCodes) {
                statuses.add((short) remainingStatusCode);
            }
            this.statusCodes = List.copyOf(statuses);
            this.responseBody = responseBody;
        }

        @Override
        public HttpResponse send(HttpRequest request, HttpSendMode mode) {
            sendModes.add(mode);
            return send(request);
        }

        @Override
        public HttpResponse send(HttpRequest request) {
            lastRequest = request.toString();
            requests.add(lastRequest);
            short statusCode = statusCodes.get(Math.min(count, statusCodes.size() - 1));
            count++;
            if (statusCode < 0) {
                throw new IllegalStateException("boom");
            }
            return response(statusCode, responseBody);
        }

        private int count() {
            return count;
        }

        private String lastRequest() {
            return lastRequest;
        }

        private String request(int index) {
            return requests.get(index);
        }
    }

    private static final class RecordingAuthFlowRunner implements AuthFlowRunner {
        private final AuthFlowRunResult result;
        private int count;

        private RecordingAuthFlowRunner(AuthFlowRunResult result) {
            this.result = result;
        }

        @Override
        public AuthFlowRunResult runAuthFlow(
                String flowId,
                AuthProfile profile,
                AuthProvider provider,
                Map<String, String> env,
                AuthFlowPurpose purpose,
                RenderMode renderMode) {
            count++;
            return result;
        }

        private int count() {
            return count;
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

package dev.nquangit.corestra.ui.access;

import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionMode;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.dependency.SetupSuggestionResult;
import dev.nquangit.corestra.access.evaluate.BasicResultEvaluator;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.evaluate.IndicatorType;
import dev.nquangit.corestra.access.evaluate.MaskingRule;
import dev.nquangit.corestra.access.evaluate.ResponseIndicator;
import dev.nquangit.corestra.access.evaluate.SensitiveFieldRule;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.flow.AccessTargetType;
import dev.nquangit.corestra.access.flow.FlowMatrixRowsFromFlowRequest;
import dev.nquangit.corestra.access.flow.FlowMatrixRowsFromFlowResult;
import dev.nquangit.corestra.access.flow.FlowStepMatrixSelection;
import dev.nquangit.corestra.access.flow.FlowTargetRunMode;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.importer.ImportFormat;
import dev.nquangit.corestra.access.importer.ImportOptions;
import dev.nquangit.corestra.access.importer.ImportResult;
import dev.nquangit.corestra.access.importer.ImportSource;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.MatrixGenerationRequest;
import dev.nquangit.corestra.access.matrix.MatrixGenerationResult;
import dev.nquangit.corestra.access.matrix.MatrixGenerationStrategy;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.ScenarioPolicy;
import dev.nquangit.corestra.access.matrix.SetupAuthBinding;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckAuthBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.repository.InMemoryAccessRepository;
import dev.nquangit.corestra.access.report.FindingDraft;
import dev.nquangit.corestra.access.report.SavedFindingDraft;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.runner.AccessPlanPreview;
import dev.nquangit.corestra.access.runner.AccessPlanRunConfig;
import dev.nquangit.corestra.access.runner.AccessPlanRunner;
import dev.nquangit.corestra.access.runner.AccessPlanSummary;
import dev.nquangit.corestra.access.runner.ManualAuthApplier;
import dev.nquangit.corestra.access.runner.PlanValidationSeverity;
import dev.nquangit.corestra.access.runner.SingleAccessTestRunner;
import dev.nquangit.corestra.access.runner.StoredRequestHttpRequestAdapter;
import dev.nquangit.corestra.access.security.RedactionService;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.AccessRequestTemplateRenderer;
import dev.nquangit.corestra.access.template.AccessScopedVariableStoreFactory;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.template.OperationType;
import dev.nquangit.corestra.access.teardown.TeardownConfig;
import dev.nquangit.corestra.access.validation.AccessValidationReport;
import dev.nquangit.corestra.access.validation.AccessValidationSeverity;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.VariableErrorType;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowRuleSet;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowStep.StepErrorStrategy;
import dev.nquangit.corestra.flow.FlowTriggerConfig;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.ExtractRule.ExtractorType;
import dev.nquangit.corestra.flow.rule.ExtractRule.SourceSide;
import dev.nquangit.corestra.integration.HttpClient;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JButton;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccessUiControllerTest {
    @Test
    void authProfileFormModelConvertsToAuthProfile() {
        AuthProfileFormModel form = new AuthProfileFormModel(
                "Owner",
                "admin",
                true,
                Map.of("accessToken", "token-abc"),
                Map.of("Authorization", "Bearer token-abc"),
                Map.of("SESSION", "cookie-abc"));

        AuthProfile profile = form.toAuthProfile("auth-1");

        assertEquals("auth-1", profile.id());
        assertEquals("Owner", profile.name());
        assertEquals("admin", profile.role());
        assertEquals(AuthProfile.MANUAL_PROVIDER_ID, profile.providerId());
        assertTrue(profile.enabled());
        assertEquals(Map.of("accessToken", "token-abc"), profile.state().variables());
        assertEquals(Map.of("Authorization", "Bearer token-abc"), profile.state().headers());
        assertEquals(Map.of("SESSION", "cookie-abc"), profile.state().cookies());
    }

    @Test
    void authProfileFormUsesBoundedTabbedValueSections() throws Exception {
        Class<?> formClass = Class.forName(
                "dev.nquangit.corestra.ui.access.AuthProfilesPanel$AuthProfileFormPanel");
        Constructor<?> constructor = formClass.getDeclaredConstructor(List.class, AuthProfileFormModel.class);
        constructor.setAccessible(true);
        JPanel form = (JPanel) constructor.newInstance(List.of(AuthProviderOption.manual()), null);

        assertEquals(820, form.getPreferredSize().width);
        assertEquals(620, form.getPreferredSize().height);

        JTabbedPane tabs = findTabbedPane(form);
        assertEquals(4, tabs.getTabCount());
        assertEquals("Credentials", tabs.getTitleAt(0));
        assertEquals("Auth Variables", tabs.getTitleAt(1));
        assertEquals("Headers", tabs.getTitleAt(2));
        assertEquals("Cookies", tabs.getTitleAt(3));
    }

    @Test
    void authenticationIsASharedRootWorkspaceAndAccessTestingStartsWithDataProfiles() {
        AccessUiController controller = controller(new InMemoryAccessRepository());
        AuthenticationPanel authentication = new AuthenticationPanel(controller);
        AccessTestingPanel accessTesting = new AccessTestingPanel(controller);

        JTabbedPane authenticationTabs = findTabbedPane(authentication);
        assertEquals(List.of("Auth Providers", "Auth Profiles"), tabTitles(authenticationTabs));

        JTabbedPane accessTabs = findTabbedPane(accessTesting);
        assertEquals(List.of(
                        "Data Profiles", "Templates", "Flow Targets", "Evaluation Profiles",
                        "Matrix", "Results", "Guide"),
                tabTitles(accessTabs));

        AtomicBoolean navigated = new AtomicBoolean();
        accessTesting.setAuthenticationNavigator(() -> navigated.set(true));
        findButton(accessTesting, "Manage Authentication…").doClick();
        assertTrue(navigated.get());
    }

    @Test
    void matrixRowFormUsesBoundedTabbedSectionsAndPreservesFlowTargetRows() throws Exception {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        FlowDefinition flow = flow(List.of(flowStep("target", "/target", List.of())));
        repository.saveAuthProfile(AuthProfile.manual("auth-1", "Owner", "admin"));
        repository.saveTemplate(template("template-1", "/accounts", List.of(), "", Set.of()));
        repository.saveFlowTarget(new AccessFlowTarget(
                "flowtarget-1",
                "Access Flow Target",
                flow,
                flow.id(),
                flow.name(),
                "hash-1",
                "target",
                true,
                List.of()));
        MatrixRowFormModel model = new MatrixRowFormModel(
                AccessTargetType.FLOW_TARGET,
                "flowtarget-1",
                "Flow row",
                "template-1",
                "auth-1",
                "",
                "env-prod",
                SetupMode.NONE,
                "",
                "",
                SetupAuthBinding.DATA_OWNER,
                "",
                ScenarioPolicy.REUSE_EXISTING,
                "",
                ExpectedResult.REVIEW,
                "",
                List.of(),
                TeardownConfig.none(),
                false);
        Class<?> formClass = Class.forName(
                "dev.nquangit.corestra.ui.access.MatrixPanel$MatrixRowFormPanel");
        Constructor<?> constructor = formClass.getDeclaredConstructor(AccessUiController.class, MatrixRowFormModel.class);
        constructor.setAccessible(true);
        JPanel form = (JPanel) constructor.newInstance(controller, model);

        assertEquals(900, form.getPreferredSize().width);
        assertEquals(640, form.getPreferredSize().height);

        JTabbedPane tabs = findTabbedPane(form);
        assertEquals(4, tabs.getTabCount());
        assertEquals("Target", tabs.getTitleAt(0));
        assertEquals("Setup", tabs.getTitleAt(1));
        assertEquals("Post-checks", tabs.getTitleAt(2));
        assertEquals("Teardown", tabs.getTitleAt(3));

        Method toModel = formClass.getDeclaredMethod("toModel");
        toModel.setAccessible(true);
        MatrixRowFormModel rendered = (MatrixRowFormModel) toModel.invoke(form);
        assertEquals(AccessTargetType.FLOW_TARGET, rendered.targetType());
        assertEquals("flowtarget-1", rendered.flowTargetId());
        assertEquals("template-1", rendered.templateId());
        assertEquals("auth-1", rendered.targetAuthProfileId());
        assertFalse(rendered.enabled());
    }

    @Test
    void authProviderFormModelConvertsToAuthProvider() {
        AuthInjectionRule injectionRule = new AuthInjectionRule(
                "inj-1",
                "Bearer token",
                true,
                AuthInjectionLocation.HEADER,
                "Authorization",
                "Bearer {{auth.accessToken}}",
                AuthInjectionMode.OVERRIDE);
        AuthSuccessRule successRule = new AuthSuccessRule(
                "success-1",
                "HTTP 200",
                true,
                AuthRuleType.STATUS_CODE,
                "200",
                "");
        AuthFailureRule failureRule = new AuthFailureRule(
                "failure-1",
                "HTTP 401",
                true,
                AuthRuleType.STATUS_CODE,
                "401",
                "");
        AuthProviderFormModel form = new AuthProviderFormModel(
                "Session Auth",
                "Uses login flow",
                true,
                "login-flow",
                "refresh-flow",
                "validate-flow",
                AuthFallbackPolicy.REFRESH_THEN_LOGIN,
                AuthRetryPolicy.REFRESH_AND_RETRY_ONCE,
                List.of(injectionRule),
                List.of(successRule),
                List.of(failureRule));

        AuthProvider provider = form.toAuthProvider("provider-1");

        assertEquals("provider-1", provider.id());
        assertEquals("Session Auth", provider.name());
        assertEquals("Uses login flow", provider.description());
        assertEquals("login-flow", provider.loginFlowId());
        assertEquals("refresh-flow", provider.refreshFlowId());
        assertEquals("validate-flow", provider.validateFlowId());
        assertEquals(AuthFallbackPolicy.REFRESH_THEN_LOGIN, provider.fallbackPolicy());
        assertEquals(AuthRetryPolicy.REFRESH_AND_RETRY_ONCE, provider.targetRetryPolicy());
        assertEquals(List.of(injectionRule), provider.injectionRules());
        assertEquals(List.of(successRule), provider.successRules());
        assertEquals(List.of(failureRule), provider.failureRules());
    }

    @Test
    void capturedTemplatesCanBeEnabledSoMatrixGenerationProducesRows() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        AccessRequestTemplate captured = controller.saveCapturedRequestTemplate(
                "Captured",
                new StoredRequestTemplate("GET", "https", "example.test", 443, "/a", List.of(), ""),
                OperationMetadata.unknown());
        assertFalse(captured.enabled());

        assertEquals(1, controller.setTemplatesEnabled(List.of(captured.id(), "missing"), true));
        assertTrue(repository.template(captured.id()).orElseThrow().enabled());
        assertEquals(0, controller.setTemplatesEnabled(List.of(captured.id()), true));
        assertEquals(1, controller.setTemplatesEnabled(List.of(captured.id()), false));
        assertFalse(repository.template(captured.id()).orElseThrow().enabled());
    }

    @Test
    void capturedTemplateCanBeParameterizedAndClassifiedFromInsights() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        repository.saveAuthProfile(new AuthProfile("auth-owner", "Owner", "user", AuthProfile.MANUAL_PROVIDER_ID,
                Map.of(), dev.nquangit.corestra.access.auth.AuthState.empty(), true));
        AccessRequestTemplate captured = controller.saveCapturedRequestTemplate(
                "Delete order",
                new StoredRequestTemplate("DELETE", "https", "example.test", 443, "/api/orders/4821", List.of(), ""),
                OperationMetadata.unknown());

        var insight = controller.templateInsights().getFirst();
        assertEquals(captured.id(), insight.templateId());
        assertTrue(insight.operationDiffers());
        assertEquals(1, insight.literalIdentifiers().size());

        AccessRequestTemplate classified = controller.applyInferredOperation(captured.id());
        assertEquals(OperationType.STATE_CHANGING, classified.operationMetadata().operationType());

        AccessUiController.TemplateParameterization result =
                controller.parameterizeTemplate(captured.id(), "auth-owner", "");
        assertEquals("/api/orders/{{data.orderId}}", result.template().baseRequest().pathAndQuery());
        assertEquals(Map.of("orderId", "4821"), result.dataProfile().values());
        assertEquals("auth-owner", result.dataProfile().ownerAuthProfileId());
        assertTrue(result.template().placeholders().contains(new ScopedVariableRef(VariableScope.DATA, "orderId")));
        assertEquals(OperationType.STATE_CHANGING, result.template().operationMetadata().operationType());
        assertThrows(IllegalArgumentException.class,
                () -> controller.parameterizeTemplate(captured.id(), "auth-owner", ""));
    }

    @Test
    void authProviderDropdownOptionsIncludeManualAndConfiguredProviders() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        controller.saveAuthProvider("", authProviderForm("Session Auth"));

        List<AuthProviderOption> options = controller.authProviderOptions();

        assertEquals(AuthProfile.MANUAL_PROVIDER_ID, options.getFirst().id());
        assertEquals("provider-1", options.get(1).id());
        assertTrue(options.get(1).label().contains("Session Auth"));
    }

    @Test
    void authProfileFormCanReferenceConfiguredAuthProvider() {
        AuthProfileFormModel form = new AuthProfileFormModel(
                "Owner",
                "admin",
                "provider-1",
                true,
                Map.of(),
                Map.of(),
                Map.of());

        AuthProfile profile = form.toAuthProfile("auth-1");

        assertEquals("provider-1", profile.providerId());
    }

    @Test
    void authProfileFormPreservesPerActorCredentialsAndCachedExpiry() {
        dev.nquangit.corestra.access.auth.AuthProfile original = new dev.nquangit.corestra.access.auth.AuthProfile(
                "auth-1", "Alice", "user", "provider-1",
                Map.of("username", "alice", "password", "secret"),
                new dev.nquangit.corestra.access.auth.AuthState(
                        Map.of("accessToken", "token-a"), Map.of(), Map.of(), 9_000L, 1_000L), true);

        AuthProfileFormModel model = AuthProfileFormModel.from(original);
        dev.nquangit.corestra.access.auth.AuthProfile saved = model.toAuthProfile("auth-1");

        assertEquals(original.credentials(), saved.credentials());
        assertEquals(9_000L, saved.state().expiresAtEpochMillis());
        assertEquals(1_000L, saved.state().lastUpdatedAtEpochMillis());
    }

    @Test
    void clearAuthProfileStatePreservesActorCredentials() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        AuthProfile original = new AuthProfile(
                "auth-1", "Alice", "user", "provider-1",
                Map.of("username", "alice", "password", "secret"),
                new dev.nquangit.corestra.access.auth.AuthState(
                        Map.of("accessToken", "token-a"),
                        Map.of("Authorization", "Bearer token-a"),
                        Map.of("SESSION", "cookie-a"),
                        9_000L,
                        1_000L),
                true);
        repository.saveAuthProfile(original);

        AuthProfile cleared = controller.clearAuthProfileState(original.id());

        assertEquals(original.credentials(), cleared.credentials());
        assertTrue(cleared.state().variables().isEmpty());
        assertTrue(cleared.state().headers().isEmpty());
        assertTrue(cleared.state().cookies().isEmpty());
        assertEquals(0L, cleared.state().expiresAtEpochMillis());
        assertEquals(cleared, repository.authProfile(original.id()).orElseThrow());
    }

    private static JTabbedPane findTabbedPane(Component component) {
        if (component instanceof JTabbedPane tabs) {
            return tabs;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                try {
                    return findTabbedPane(child);
                } catch (AssertionError ignored) {
                    // Keep searching siblings.
                }
            }
        }
        throw new AssertionError("No JTabbedPane found");
    }

    private static List<String> tabTitles(JTabbedPane tabs) {
        java.util.ArrayList<String> titles = new java.util.ArrayList<>();
        for (int index = 0; index < tabs.getTabCount(); index++) {
            titles.add(tabs.getTitleAt(index));
        }
        return List.copyOf(titles);
    }

    private static JButton findButton(Component component, String text) {
        if (component instanceof JButton button && text.equals(button.getText())) {
            return button;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                try {
                    return findButton(child, text);
                } catch (AssertionError ignored) {
                    // Keep searching siblings.
                }
            }
        }
        throw new AssertionError("No button found: " + text);
    }

    @Test
    void savingAuthProviderDoesNotRunAnyAuthFlow() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        CountingHttpClient httpClient = new CountingHttpClient();
        AccessUiController controller = controller(repository, httpClient);

        AuthProvider provider = controller.saveAuthProvider("", authProviderForm("Session Auth"));
        controller.saveAuthProvider(provider.id(), authProviderForm("Session Auth Updated"));

        assertEquals(0, httpClient.count());
        assertEquals("Session Auth Updated", controller.authProviders().getFirst().name());
    }

    @Test
    void dataProfileFormModelConvertsToDataProfile() {
        DataProfileFormModel form = new DataProfileFormModel(
                "Owner data",
                "auth-owner",
                true,
                Map.of("accountId", "acct-1"));

        DataProfile profile = form.toDataProfile("data-1");

        assertEquals("data-1", profile.id());
        assertEquals("Owner data", profile.name());
        assertEquals("auth-owner", profile.ownerAuthProfileId());
        assertTrue(profile.enabled());
        assertEquals(Map.of("accountId", "acct-1"), profile.values());
    }

    @Test
    void evaluationProfileFormModelConvertsToEvaluationProfile() {
        EvaluationProfileFormModel form = new EvaluationProfileFormModel(
                "Business API",
                true,
                List.of(new ResponseIndicator(
                        "allow-1",
                        "Success flag",
                        true,
                        IndicatorType.JSON_PATH_EQUALS,
                        "success",
                        "true")),
                List.of(new ResponseIndicator(
                        "deny-1",
                        "Access denied code",
                        true,
                        IndicatorType.JSON_PATH_EQUALS,
                        "errorCode",
                        "ACCESS_DENIED")),
                List.of(new SensitiveFieldRule("sensitive-1", "SSN", true, "user.ssn")),
                List.of(new MaskingRule("mask-1", "Masked SSN", true, "user.ssn", "\\*+")));

        EvaluationProfile profile = form.toEvaluationProfile("eval-1");

        assertEquals("eval-1", profile.id());
        assertEquals("Business API", profile.name());
        assertTrue(profile.enabled());
        assertEquals(IndicatorType.JSON_PATH_EQUALS, profile.allowIndicators().getFirst().type());
        assertEquals("ACCESS_DENIED", profile.denyIndicators().getFirst().expectedValue());
        assertEquals("user.ssn", profile.sensitiveFieldRules().getFirst().jsonPath());
        assertEquals("\\*+", profile.maskedFieldRules().getFirst().maskedValuePattern());
    }

    @Test
    void evaluationProfileRuleTextRoundTripsThroughFormModel() {
        List<ResponseIndicator> allow = EvaluationProfileFormModel.parseIndicators(
                "JSON_PATH_EQUALS|success|true|Success flag",
                "allow");
        List<SensitiveFieldRule> sensitive = EvaluationProfileFormModel.parseSensitiveRules("user.ssn|SSN");
        List<MaskingRule> masks = EvaluationProfileFormModel.parseMaskingRules("user.ssn|\\*+|Masked SSN");

        assertEquals("Success flag", allow.getFirst().name());
        assertEquals("user.ssn", sensitive.getFirst().jsonPath());
        assertEquals("\\*+", masks.getFirst().maskedValuePattern());
        assertTrue(EvaluationProfileFormModel.indicatorsText(allow).contains("JSON_PATH_EQUALS|success|true"));
        assertTrue(EvaluationProfileFormModel.sensitiveRulesText(sensitive).contains("user.ssn|SSN"));
        assertTrue(EvaluationProfileFormModel.maskingRulesText(masks).contains("user.ssn|\\*+|Masked SSN"));
    }

    @Test
    void saveEvaluationProfileListsFindsAndDeletesItWithoutRunningTraffic() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        CountingHttpClient httpClient = new CountingHttpClient();
        AccessUiController controller = controller(repository, httpClient);

        EvaluationProfile profile = controller.saveEvaluationProfile("", new EvaluationProfileFormModel(
                "Business API",
                true,
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        assertEquals(0, httpClient.count());
        assertEquals("eval-1", profile.id());
        assertEquals(List.of(profile), controller.evaluationProfiles());
        assertEquals(profile, controller.evaluationProfile(profile.id()).orElseThrow());
        assertTrue(controller.deleteEvaluationProfile(profile.id()));
        assertTrue(controller.evaluationProfiles().isEmpty());
    }

    @Test
    void createFindingDraftUsesSelectedResultAndRedactedEvidence() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        AuthProfile target = AuthProfile.manual("auth-target", "Target", "user");
        DataProfile dataProfile = DataProfile.enabled("data-1", "Owner Data", "auth-target", Map.of("accountId", "acct-1"));
        AccessRequestTemplate template = template(
                "template-1",
                "/accounts/{{data.accountId}}",
                List.of(),
                "",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")));
        AccessTestResult result = new AccessTestResult(
                "result-1",
                "case-1",
                RunStatus.COMPLETED,
                Verdict.SUSPICIOUS,
                "Expected deny but received status 200",
                1000L,
                1100L,
                "",
                "",
                List.of());
        repository.saveAuthProfile(target);
        repository.saveDataProfile(dataProfile);
        repository.saveTemplate(template);
        repository.saveTestCase(new AccessTestCase(
                "case-1",
                "Target reads owner data",
                template.id(),
                "flow-1",
                "step-1",
                dev.nquangit.corestra.access.matrix.SetupConfig.none(),
                new dev.nquangit.corestra.access.matrix.TargetBinding(target.id(), dataProfile.id(), "", ""),
                ExpectedResult.DENY,
                List.of(),
                true,
                RunStatus.COMPLETED,
                1100L));
        repository.saveResult(result);
        repository.saveEvidence(new AccessEvidence(
                "case-1",
                result.id(),
                "GET /accounts/acct-1\nAuthorization: Bearer raw-secret",
                "GET /accounts/acct-1\nAuthorization: <redacted>",
                "HTTP/1.1 200 OK\n\nraw-secret",
                "HTTP/1.1 200 OK\n\n<redacted>",
                1100L));

        FindingDraft draft = controller.createFindingDraft(result.id());

        assertEquals(ExpectedResult.DENY, draft.expectedResult());
        assertEquals(Verdict.SUSPICIOUS, draft.actualVerdict());
        assertTrue(draft.evidence().redacted());
        assertTrue(draft.evidence().requestEvidence().contains("<redacted>"));
        assertFalse(draft.evidence().requestEvidence().contains("raw-secret"));

        SavedFindingDraft saved = controller.saveFindingDraft(result.id(), "# Edited draft");

        assertEquals("case-1", saved.testCaseId());
        assertEquals(result.id(), saved.resultId());
        assertEquals("# Edited draft", controller.savedFindingDraft(result.id()).orElseThrow().markdown());
    }

    @Test
    void matrixRowCreationDefaultsStatusToPending() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        AuthProfile authProfile = AuthProfile.manual("auth-1", "Owner", "admin");
        AccessRequestTemplate template = template("template-1", "/accounts", List.of(), "", Set.of());
        repository.saveAuthProfile(authProfile);
        repository.saveTemplate(template);

        AccessTestCase testCase = controller.saveTestCase("", new MatrixRowFormModel(
                "Owner can read account",
                template.id(),
                authProfile.id(),
                "",
                "env-dev",
                ExpectedResult.ALLOW,
                true));

        assertEquals("case-1", testCase.id());
        assertEquals(RunStatus.PENDING, testCase.status());
        assertEquals("env-dev", testCase.target().environmentId());
        assertTrue(testCase.enabled());
    }

    @Test
    void matrixRowCreationPersistsSetupAndScenarioFields() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        AuthProfile authProfile = AuthProfile.manual("auth-1", "Owner", "admin");
        AccessRequestTemplate template = template("template-1", "/confirm/{{scenario.confirmToken}}",
                List.of(), "", Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")));
        repository.saveAuthProfile(authProfile);
        repository.saveTemplate(template);

        AccessTestCase testCase = controller.saveTestCase("", new MatrixRowFormModel(
                "Owner confirms transfer",
                template.id(),
                authProfile.id(),
                "",
                "env-dev",
                SetupMode.RUN_PREFIX,
                "flow-setup",
                "step-target",
                SetupAuthBinding.FIXED_PROFILE,
                "auth-setup",
                ScenarioPolicy.FRESH_PER_TEST,
                "scenario-1",
                ExpectedResult.ALLOW,
                "",
                List.of(),
                true));

        assertEquals(SetupMode.RUN_PREFIX, testCase.setup().mode());
        assertEquals("flow-setup", testCase.setup().setupFlowId());
        assertEquals("step-target", testCase.setup().untilBeforeStepId());
        assertEquals(SetupAuthBinding.FIXED_PROFILE, testCase.setup().setupAuthBinding());
        assertEquals("auth-setup", testCase.setup().setupAuthProfileId());
        assertEquals(ScenarioPolicy.FRESH_PER_TEST, testCase.setup().scenarioPolicy());
        assertEquals("scenario-1", testCase.target().scenarioInstanceId());
    }

    @Test
    void generateMatrixDoesNotSaveRowsUntilConfirmed() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        repository.saveAuthProfile(AuthProfile.manual("auth-owner", "Owner", "user"));
        repository.saveAuthProfile(AuthProfile.manual("auth-peer", "Peer", "user"));
        repository.saveTemplate(template(
                "template-1",
                "/accounts/{{data.accountId}}",
                List.of(),
                "",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId"))));
        repository.saveDataProfile(DataProfile.enabled(
                "data-1",
                "Owner data",
                "auth-owner",
                Map.of("accountId", "acct-1")));

        MatrixGenerationResult generated = controller.generateMatrix(new MatrixGenerationRequest(
                MatrixGenerationStrategy.OWNER_VS_OTHERS,
                Set.of("template-1"),
                Set.of("data-1"),
                ExpectedResult.REVIEW,
                ExpectedResult.REVIEW,
                false));

        assertEquals(2, generated.rows().size());
        assertTrue(controller.testCases().isEmpty());
        assertTrue(generated.rows().stream().allMatch(row -> !row.enabled()));

        MatrixGenerationResult saved = controller.saveGeneratedMatrixRows(generated.rows());

        assertEquals(2, saved.rows().size());
        assertEquals(2, controller.testCases().size());
        assertTrue(controller.testCases().stream().allMatch(row -> row.status() == RunStatus.PENDING));
        MatrixGenerationResult duplicateSave = controller.saveGeneratedMatrixRows(generated.rows());
        assertEquals(0, duplicateSave.rows().size());
        assertEquals(2, duplicateSave.duplicateCount());
    }

    @Test
    void generateAdvancedMatrixReturnsPreviewMetadataWithoutSavingOrSendingHttp() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        CountingHttpClient httpClient = new CountingHttpClient();
        AccessUiController controller = controller(repository, httpClient);
        repository.saveAuthProfile(AuthProfile.manual("auth-owner", "Owner", "USER"));
        repository.saveAuthProfile(AuthProfile.manual("auth-other", "Other Tenant User", "USER"));
        repository.saveTemplate(template(
                "template-1",
                "/accounts/{{data.accountId}}",
                List.of(),
                "",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId"))));
        repository.saveDataProfile(DataProfile.enabled(
                "data-owner",
                "Owner data",
                "auth-owner",
                Map.of("accountId", "acct-1", "tenantId", "tenant-a", "branchId", "branch-a")));
        repository.saveDataProfile(DataProfile.enabled(
                "data-other",
                "Other metadata",
                "auth-other",
                Map.of("accountId", "acct-2", "tenantId", "tenant-b", "branchId", "branch-b")));

        MatrixGenerationResult generated = controller.generateMatrix(new MatrixGenerationRequest(
                MatrixGenerationStrategy.CROSS_TENANT,
                Set.of("template-1"),
                Set.of("data-owner"),
                Set.of("auth-owner", "auth-other"),
                "tenantId",
                "branchId",
                "MAKER",
                "CHECKER",
                "ADMIN",
                ExpectedResult.REVIEW,
                ExpectedResult.REVIEW,
                ExpectedResult.DENY,
                ExpectedResult.DENY,
                ExpectedResult.DENY,
                ExpectedResult.REVIEW,
                ExpectedResult.REVIEW,
                true,
                false,
                false,
                true,
                false,
                false));

        assertEquals(0, httpClient.count());
        assertTrue(controller.testCases().isEmpty());
        assertEquals(2, generated.rows().size());
        assertEquals(2, generated.generatedRows().size());
        assertTrue(generated.rows().stream().allMatch(row -> !row.enabled()));
        assertTrue(generated.generatedRows().stream()
                .allMatch(row -> row.generationStrategy() == MatrixGenerationStrategy.CROSS_TENANT));
        assertTrue(generated.generatedRows().stream()
                .anyMatch(row -> row.generationReason().contains("tenant")));
    }

    @Test
    void importTemplatesPreviewRequiresConfirmedSaveAndDoesNotSendHttp() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        CountingHttpClient httpClient = new CountingHttpClient();
        AccessUiController controller = controller(repository, httpClient);

        ImportResult preview = controller.previewImportedTemplates(new ImportSource("api.json", """
                {
                  "openapi": "3.0.3",
                  "servers": [{"url": "https://api.example.test"}],
                  "paths": {
                    "/accounts/{accountId}": {
                      "get": {"operationId": "getAccount"}
                    }
                  }
                }
                """, ImportFormat.OPENAPI_JSON), ImportOptions.defaults());

        assertEquals(0, httpClient.count());
        assertTrue(controller.templates().isEmpty());
        assertEquals(1, preview.templates().size());
        assertFalse(preview.templates().getFirst().enabled());

        ImportResult saved = controller.saveImportedTemplates(preview.templates());

        assertEquals(0, httpClient.count());
        assertEquals(1, saved.templates().size());
        assertEquals(1, controller.templates().size());
        assertFalse(controller.templates().getFirst().enabled());
    }

    @Test
    void flowStepCanBeConvertedToDisabledAccessTemplateWithoutSendingHttp() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        CountingHttpClient httpClient = new CountingHttpClient();
        AccessUiController controller = controller(repository, httpClient);
        FlowDefinition flow = flow(List.of(flowStep("target", "/accounts/{{data.accountId}}", List.of())));
        FlowStep step = flow.steps().getFirst();

        AccessRequestTemplate template = controller.createTemplateFromFlowStep(flow, step, OperationMetadata.unknown());

        assertEquals(0, httpClient.count());
        assertEquals("tmpl-1", template.id());
        assertEquals(flow.id(), template.sourceFlowId());
        assertEquals(step.id(), template.sourceStepId());
        assertEquals("/accounts/{{data.accountId}}", template.baseRequest().pathAndQuery());
        assertFalse(template.enabled());
        assertEquals(List.of(template), controller.templates());
    }

    @Test
    void flowStepCanBeAddedAsDisabledMatrixTargetWithoutSendingHttp() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        CountingHttpClient httpClient = new CountingHttpClient();
        AccessUiController controller = controller(repository, httpClient);
        AuthProfile authProfile = AuthProfile.manual("auth-1", "Owner", "admin");
        FlowDefinition flow = flow(List.of(flowStep("target", "/accounts", List.of())));
        FlowStep step = flow.steps().getFirst();
        repository.saveAuthProfile(authProfile);

        AccessTestCase row = controller.addFlowStepAsMatrixTarget(
                flow,
                step,
                authProfile.id(),
                "",
                ExpectedResult.REVIEW);

        assertEquals(0, httpClient.count());
        assertEquals("case-2", row.id());
        assertFalse(row.enabled());
        assertEquals(RunStatus.PENDING, row.status());
        assertEquals(flow.id(), row.flowId());
        assertEquals(step.id(), row.targetStepId());
        assertEquals(authProfile.id(), row.target().targetAuthProfileId());
        assertEquals(1, controller.templates().size());
        assertFalse(controller.templates().getFirst().enabled());
    }

    @Test
    void createMatrixRowsFromFlowCreatesDisabledRowsForSelectedStepsAndSkipsDuplicates() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        FlowDefinition flow = flow(List.of(
                flowStep("prepare", "/prepare", List.of()),
                flowStep("read", "/accounts", List.of()),
                flowStep("update", "/accounts/update", List.of())));
        AccessUiController controller = controller(repository, List.of(flow));
        repository.saveAuthProfile(AuthProfile.manual("auth-1", "Owner", "admin"));
        repository.saveDataProfile(DataProfile.enabled("data-1", "Owner data", "auth-1",
                Map.of("accountId", "acct-1")));
        FlowMatrixRowsFromFlowRequest request = new FlowMatrixRowsFromFlowRequest(
                flow.id(),
                "auth-1",
                "data-1",
                List.of(
                        new FlowStepMatrixSelection("prepare", ExpectedResult.REVIEW,
                                FlowTargetRunMode.STOP_AT_STEP, false),
                        new FlowStepMatrixSelection("read", ExpectedResult.DENY,
                                FlowTargetRunMode.STOP_AT_STEP, true),
                        new FlowStepMatrixSelection("update", ExpectedResult.ALLOW,
                                FlowTargetRunMode.FULL_FLOW_EVALUATE_STEP, true)),
                true);

        FlowMatrixRowsFromFlowResult created = controller.createMatrixRowsFromFlow(request);

        assertEquals(2, created.rows().size());
        assertEquals(0, created.duplicateCount());
        assertTrue(created.warnings().isEmpty());
        assertEquals(2, repository.testCases().size());
        assertEquals(2, repository.flowTargets().size());

        AccessTestCase readRow = repository.testCases().stream()
                .filter(row -> row.targetStepId().equals("read"))
                .findFirst()
                .orElseThrow();
        AccessTestCase updateRow = repository.testCases().stream()
                .filter(row -> row.targetStepId().equals("update"))
                .findFirst()
                .orElseThrow();
        assertEquals(AccessTargetType.FLOW_TARGET, readRow.targetType());
        assertEquals(ExpectedResult.DENY, readRow.expected());
        assertEquals("auth-1", readRow.target().targetAuthProfileId());
        assertEquals("data-1", readRow.target().dataProfileId());
        assertEquals(SetupMode.NONE, readRow.setup().mode());
        assertFalse(readRow.enabled());
        assertEquals(ExpectedResult.ALLOW, updateRow.expected());
        assertFalse(updateRow.enabled());

        AccessFlowTarget readTarget = repository.flowTarget(readRow.flowTargetId()).orElseThrow();
        AccessFlowTarget updateTarget = repository.flowTarget(updateRow.flowTargetId()).orElseThrow();
        assertEquals(FlowTargetRunMode.STOP_AT_STEP, readTarget.runMode());
        assertEquals(FlowTargetRunMode.FULL_FLOW_EVALUATE_STEP, updateTarget.runMode());
        assertEquals("read", readTarget.targetStepId());
        assertEquals("update", updateTarget.targetStepId());

        FlowMatrixRowsFromFlowResult duplicate = controller.createMatrixRowsFromFlow(request);

        assertEquals(0, duplicate.rows().size());
        assertEquals(2, duplicate.duplicateCount());
        assertEquals(2, repository.testCases().size());
        assertEquals(2, repository.flowTargets().size());
    }

    @Test
    void ownerVsOthersRowsCanBeCreatedFromTemplateWithoutSendingHttp() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        CountingHttpClient httpClient = new CountingHttpClient();
        AccessUiController controller = controller(repository, httpClient);
        repository.saveAuthProfile(AuthProfile.manual("auth-owner", "Owner", "user"));
        repository.saveAuthProfile(AuthProfile.manual("auth-peer", "Peer", "user"));
        repository.saveTemplate(template(
                "template-1",
                "/accounts/{{data.accountId}}",
                List.of(),
                "",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId"))));
        repository.saveDataProfile(DataProfile.enabled(
                "data-1",
                "Owner data",
                "auth-owner",
                Map.of("accountId", "acct-1")));

        MatrixGenerationResult saved =
                controller.createOwnerVsOthersRowsForTemplate("template-1", ExpectedResult.REVIEW);

        assertEquals(0, httpClient.count());
        assertEquals(2, saved.rows().size());
        assertTrue(saved.rows().stream().allMatch(row -> !row.enabled()));
        assertEquals(2, controller.testCases().size());
    }

    @Test
    void setupSuggestionCanBeAppliedToSelectedTestCase() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        FlowDefinition flow = flow(List.of(
                flowStep("setup", "/setup", List.of(extract("scenario.confirmToken"))),
                flowStep("target", "/confirm/{{scenario.confirmToken}}", List.of())));
        AccessUiController controller = controller(repository, List.of(flow));
        AuthProfile authProfile = AuthProfile.manual("auth-1", "Owner", "admin");
        AccessRequestTemplate template = template(
                "template-1",
                "/confirm/{{scenario.confirmToken}}",
                List.of(),
                "",
                Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")));
        repository.saveAuthProfile(authProfile);
        repository.saveTemplate(template);
        AccessTestCase testCase = controller.saveTestCase("", new MatrixRowFormModel(
                "Confirm transfer",
                template.id(),
                authProfile.id(),
                "",
                "",
                ExpectedResult.ALLOW,
                true));

        SetupSuggestionResult suggestion = controller.suggestSetupFromFlow(flow.id(), "target", RenderMode.STRICT);
        AccessTestCase updated = controller.applySetupSuggestion(testCase.id(), suggestion);

        assertTrue(suggestion.success());
        assertEquals(SetupMode.RUN_PREFIX, updated.setup().mode());
        assertEquals(flow.id(), updated.setup().setupFlowId());
        assertEquals("target", updated.setup().untilBeforeStepId());
        assertEquals(ScenarioPolicy.FRESH_PER_TEST, updated.setup().scenarioPolicy());
        assertTrue(controller.results().isEmpty());
    }

    @Test
    void setupSuggestionWarningsAreSurfaced() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        FlowDefinition flow = flow(List.of(
                flowStep("setup-1", "/setup-1", List.of(extract("scenario.confirmToken"))),
                flowStep("setup-2", "/setup-2", List.of(extract("scenario.confirmToken"))),
                flowStep("target", "/confirm/{{scenario.confirmToken}}", List.of())));
        AccessUiController controller = controller(repository, List.of(flow));

        SetupSuggestionResult suggestion = controller.suggestSetupFromFlow(flow.id(), "target", RenderMode.STRICT);

        assertTrue(suggestion.success());
        assertTrue(suggestion.warnings().stream().anyMatch(warning -> warning.contains("Multiple producers")));
        assertEquals("setup-2", suggestion.producerStepIds()
                .get(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken")));
    }

    @Test
    void previewRendersSelectedAuthDataAndTemplateSuccessfully() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        AuthProfile authProfile = new AuthProfileFormModel(
                "Owner",
                "admin",
                true,
                Map.of("accessToken", "token-abc"),
                Map.of(),
                Map.of()).toAuthProfile("auth-1");
        DataProfile dataProfile = new DataProfileFormModel(
                "Owner data",
                "auth-1",
                true,
                Map.of("accountId", "acct-1")).toDataProfile("data-1");
        AccessRequestTemplate template = template(
                "template-1",
                "/accounts/{{data.accountId}}",
                List.of("Authorization: Bearer {{auth.accessToken}}"),
                "",
                Set.of(
                        new ScopedVariableRef(VariableScope.DATA, "accountId"),
                        new ScopedVariableRef(VariableScope.AUTH, "accessToken")));
        repository.saveAuthProfile(authProfile);
        repository.saveDataProfile(dataProfile);
        repository.saveTemplate(template);

        AccessPreview preview = controller.preview(
                template.id(),
                authProfile.id(),
                dataProfile.id(),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertTrue(preview.renderedRequest().success());
        assertEquals("/accounts/acct-1", preview.renderedRequest().renderedRequest().pathAndQuery());
        assertEquals(List.of("Authorization: Bearer token-abc"),
                preview.renderedRequest().renderedRequest().headerLines());
    }

    @Test
    void previewReportsUnresolvedVariables() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        AuthProfile authProfile = AuthProfile.manual("auth-1", "Owner", "admin");
        AccessRequestTemplate template = template(
                "template-1",
                "/accounts/{{data.accountId}}",
                List.of(),
                "",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")));
        repository.saveAuthProfile(authProfile);
        repository.saveTemplate(template);

        AccessPreview preview = controller.preview(
                template.id(),
                authProfile.id(),
                "",
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertFalse(preview.renderedRequest().success());
        assertEquals("/accounts/{{data.accountId}}", preview.renderedRequest().renderedRequest().pathAndQuery());
        assertEquals(VariableErrorType.MISSING_VARIABLE,
                preview.renderedRequest().unresolvedVariables().getFirst().errorType());
        assertEquals("path", preview.renderedRequest().issues().getFirst().location());
    }

    @Test
    void previewCompatibilityModeResolvesLegacyFallback() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        AuthProfile authProfile = AuthProfile.manual("auth-1", "Owner", "admin");
        AccessRequestTemplate template = template("template-1", "/session/{{token}}", List.of(), "", Set.of());
        repository.saveAuthProfile(authProfile);
        repository.saveTemplate(template);

        AccessPreview preview = controller.preview(
                template.id(),
                authProfile.id(),
                "",
                Map.of(),
                Map.of(),
                Map.of("token", "legacy-token"),
                RenderMode.COMPATIBILITY);

        assertTrue(preview.renderedRequest().success());
        assertEquals("/session/legacy-token", preview.renderedRequest().renderedRequest().pathAndQuery());
        assertTrue(preview.renderedRequest().warnings().getFirst().contains("legacy.token"));
    }

    @Test
    void runSelectedRowSavesResultEvidenceAndUpdatesTestCaseStatus() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        CountingHttpClient httpClient = new CountingHttpClient();
        AccessUiController controller = controller(repository, httpClient);
        AuthProfile authProfile = new AuthProfileFormModel(
                "Owner",
                "admin",
                true,
                Map.of("accessToken", "token-abc"),
                Map.of("Authorization", "Bearer {{auth.accessToken}}"),
                Map.of()).toAuthProfile("auth-1");
        DataProfile dataProfile = new DataProfileFormModel(
                "Owner data",
                "auth-1",
                true,
                Map.of("accountId", "acct-1")).toDataProfile("data-1");
        AccessRequestTemplate template = template(
                "template-1",
                "/accounts/{{data.accountId}}",
                List.of(),
                "",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")));
        repository.saveAuthProfile(authProfile);
        repository.saveDataProfile(dataProfile);
        repository.saveTemplate(template);
        AccessTestCase testCase = controller.saveTestCase("", new MatrixRowFormModel(
                "Owner should be denied",
                template.id(),
                authProfile.id(),
                dataProfile.id(),
                "",
                ExpectedResult.DENY,
                true));

        AccessTestResult result = controller.runTestCase(
                testCase.id(),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT);

        assertEquals(1, httpClient.count());
        assertEquals(RunStatus.COMPLETED, result.runStatus());
        assertEquals(Verdict.OK, result.verdict());
        assertEquals(List.of(result), controller.results());
        assertEquals(RunStatus.COMPLETED, repository.testCase(testCase.id()).orElseThrow().status());
        assertTrue(controller.evidenceByResultId(result.id()).orElseThrow()
                .redactedRequestRaw().contains("Authorization: <redacted>"));
    }

    @Test
    void singleRowDryRunSavesResultEvidenceWithoutSendingHttp() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        CountingHttpClient httpClient = new CountingHttpClient();
        AccessUiController controller = controller(repository, httpClient);
        AuthProfile authProfile = new AuthProfileFormModel(
                "Owner",
                "admin",
                true,
                Map.of("accessToken", "token-abc"),
                Map.of("Authorization", "Bearer {{auth.accessToken}}"),
                Map.of()).toAuthProfile("auth-1");
        DataProfile dataProfile = new DataProfileFormModel(
                "Owner data",
                "auth-1",
                true,
                Map.of("accountId", "acct-1")).toDataProfile("data-1");
        AccessRequestTemplate template = template(
                "template-1",
                "/accounts/{{data.accountId}}",
                List.of(),
                "",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")));
        repository.saveAuthProfile(authProfile);
        repository.saveDataProfile(dataProfile);
        repository.saveTemplate(template);
        AccessTestCase testCase = controller.saveTestCase("", new MatrixRowFormModel(
                "Owner dry run",
                template.id(),
                authProfile.id(),
                dataProfile.id(),
                "",
                ExpectedResult.REVIEW,
                true));

        AccessTestResult result = controller.runTestCase(
                testCase.id(),
                Map.of(),
                Map.of(),
                Map.of(),
                RenderMode.STRICT,
                true);

        assertEquals(0, httpClient.count());
        assertEquals(RunStatus.COMPLETED, result.runStatus());
        assertEquals(Verdict.REVIEW, result.verdict());
        assertTrue(result.reason().contains("Dry run completed"));
        assertEquals(List.of(result), controller.results());
        assertEquals(RunStatus.COMPLETED, repository.testCase(testCase.id()).orElseThrow().status());
        AccessEvidence evidence = controller.evidenceByResultId(result.id()).orElseThrow();
        assertTrue(evidence.redactedRequestRaw().contains("Authorization: <redacted>"));
        assertTrue(evidence.responseRaw().isBlank());
    }

    @Test
    void planRunUsesControllerPlanRunnerAndSavesResults() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        CountingHttpClient httpClient = new CountingHttpClient();
        AccessUiController controller = controllerWithPlan(repository, httpClient);
        AuthProfile authProfile = new AuthProfileFormModel(
                "Owner",
                "admin",
                true,
                Map.of("accessToken", "token-abc"),
                Map.of("Authorization", "Bearer {{auth.accessToken}}"),
                Map.of()).toAuthProfile("auth-1");
        DataProfile dataProfile = new DataProfileFormModel(
                "Owner data",
                "auth-1",
                true,
                Map.of("accountId", "acct-1")).toDataProfile("data-1");
        AccessRequestTemplate template = template(
                "template-1",
                "/accounts/{{data.accountId}}",
                List.of(),
                "",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")));
        repository.saveAuthProfile(authProfile);
        repository.saveDataProfile(dataProfile);
        repository.saveTemplate(template);
        controller.saveTestCase("", new MatrixRowFormModel(
                "Owner should be denied",
                template.id(),
                authProfile.id(),
                dataProfile.id(),
                "",
                ExpectedResult.DENY,
                true));

        AccessPlanSummary summary = controller.runPlan(AccessPlanRunConfig.defaults(), null);

        assertEquals(1, summary.total());
        assertEquals(1, summary.completed());
        assertEquals(1, httpClient.count());
        assertEquals(1, controller.results().size());
        assertEquals(summary, controller.latestPlanSummary().orElseThrow());
    }

    @Test
    void previewPlanReportsSafetyCountsAndSetupDiagnostics() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controllerWithPlan(repository, new CountingHttpClient());
        AuthProfile authProfile = new AuthProfileFormModel(
                "Owner",
                "admin",
                true,
                Map.of("accessToken", "token-abc"),
                Map.of("Authorization", "Bearer {{auth.accessToken}}"),
                Map.of()).toAuthProfile("auth-1");
        DataProfile dataProfile = new DataProfileFormModel(
                "Owner data",
                "auth-1",
                true,
                Map.of("accountId", "acct-1")).toDataProfile("data-1");
        AccessRequestTemplate targetTemplate = new AccessRequestTemplate(
                "template-1",
                "Transfer",
                new StoredRequestTemplate("POST", "https", "example.test", 443,
                        "/accounts/{{data.accountId}}/transfer", List.of(), ""),
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")),
                new OperationMetadata(OperationType.STATE_CHANGING, true, false, "transfer"),
                "flow-1",
                "step-1",
                "hash-1",
                true);
        AccessRequestTemplate postTemplate = template(
                "template-post",
                "/accounts/{{data.accountId}}/audit",
                List.of(),
                "",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")));
        repository.saveAuthProfile(authProfile);
        repository.saveDataProfile(dataProfile);
        repository.saveTemplate(targetTemplate);
        repository.saveTemplate(postTemplate);
        controller.saveTestCase("", new MatrixRowFormModel(
                "Transfer denied",
                targetTemplate.id(),
                authProfile.id(),
                dataProfile.id(),
                "",
                SetupMode.RUN_PREFIX,
                "",
                "",
                SetupAuthBinding.DATA_OWNER,
                "",
                ScenarioPolicy.REUSE_EXISTING,
                "",
                ExpectedResult.DENY,
                "",
                List.of(new PostCheckRequest(
                        "post-1",
                        "Audit",
                        true,
                        postTemplate.id(),
                        "",
                        PostCheckAuthBinding.TARGET_ACTOR,
                        "",
                        "",
                        List.of())),
                true));

        AccessPlanPreview preview = controller.previewPlan(AccessPlanRunConfig.defaults());

        assertEquals(1, preview.summary().enabledRows());
        assertEquals(1, preview.summary().setupRows());
        assertEquals(1, preview.summary().postCheckRows());
        assertEquals(1, preview.summary().stateChangingOrConsumableRows());
        assertTrue(preview.issues().stream().anyMatch(issue ->
                issue.severity() == PlanValidationSeverity.ERROR
                        && issue.message().contains("RUN_PREFIX requires a setup flow id")));
        assertTrue(preview.issues().stream().anyMatch(issue ->
                issue.severity() == PlanValidationSeverity.ERROR
                        && issue.message().contains("untilBeforeStepId")));
    }

    @Test
    void previewPlanReportsMissingReferencesAndUnresolvedPlaceholders() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controllerWithPlan(repository, new CountingHttpClient());
        repository.saveAuthProfile(AuthProfile.manual("auth-1", "Owner", "admin"));
        repository.saveTemplate(template(
                "template-unresolved",
                "/confirm/{{scenario.confirmToken}}",
                List.of(),
                "",
                Set.of(new ScopedVariableRef(VariableScope.SCENARIO, "confirmToken"))));
        repository.saveTestCase(new AccessTestCase(
                "case-missing",
                "Missing refs",
                "missing-template",
                "",
                "",
                new SetupConfig(SetupMode.REUSE_SCENARIO, "", "", "", SetupAuthBinding.DATA_OWNER,
                        ScenarioPolicy.REUSE_EXISTING),
                new TargetBinding("missing-auth", "missing-data", "", "missing-scenario"),
                ExpectedResult.REVIEW,
                "",
                List.of(),
                List.of(),
                true,
                RunStatus.PENDING,
                0L));
        repository.saveTestCase(new AccessTestCase(
                "case-unresolved",
                "Unresolved",
                "template-unresolved",
                "",
                "",
                SetupConfig.none(),
                new TargetBinding("auth-1", "", "", ""),
                ExpectedResult.REVIEW,
                "",
                List.of(),
                List.of(),
                true,
                RunStatus.PENDING,
                0L));

        AccessPlanPreview preview = controller.previewPlan(AccessPlanRunConfig.defaults());

        assertTrue(preview.hasErrors());
        assertTrue(preview.issues().stream().anyMatch(issue -> issue.message().contains("Missing template")));
        assertTrue(preview.issues().stream().anyMatch(issue -> issue.message().contains("Missing target auth")));
        assertTrue(preview.issues().stream().anyMatch(issue -> issue.message().contains("Missing data profile")));
        assertTrue(preview.issues().stream().anyMatch(issue -> issue.message().contains("Missing scenario")));
        assertTrue(preview.issues().stream().anyMatch(issue -> issue.category().equals("Unresolved")
                && issue.message().contains("scenario.confirmToken")));
    }

    @Test
    void validateAccessWorkspaceReportsIssuesWithoutSendingHttp() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        CountingHttpClient httpClient = new CountingHttpClient();
        AccessUiController controller = controller(repository, httpClient);
        repository.saveTestCase(new AccessTestCase(
                "case-invalid",
                "Invalid row",
                "missing-template",
                "",
                "",
                SetupConfig.none(),
                new TargetBinding("missing-auth", "missing-data", "", ""),
                ExpectedResult.REVIEW,
                "",
                List.of(),
                List.of(),
                true,
                RunStatus.PENDING,
                0L));

        AccessValidationReport report = controller.validateAccessWorkspace();

        assertTrue(report.hasBlockingErrors());
        assertEquals(0, httpClient.count());
        assertTrue(report.issues().stream().anyMatch(issue ->
                issue.severity() == AccessValidationSeverity.ERROR
                        && issue.message().contains("missing-template")));
        assertTrue(report.issues().stream().anyMatch(issue ->
                issue.severity() == AccessValidationSeverity.ERROR
                        && issue.message().contains("missing-auth")));
        assertTrue(report.issues().stream().anyMatch(issue ->
                issue.severity() == AccessValidationSeverity.ERROR
                        && issue.message().contains("missing-data")));
    }

    @Test
    void validateAccessWorkspaceUsesCurrentRepositorySnapshot() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);
        repository.saveTestCase(new AccessTestCase(
                "case-snapshot",
                "Snapshot row",
                "template-1",
                "",
                "",
                SetupConfig.none(),
                new TargetBinding("auth-1", "data-1", "", ""),
                ExpectedResult.ALLOW,
                "",
                List.of(),
                List.of(),
                true,
                RunStatus.PENDING,
                0L));

        AccessValidationReport missingReferences = controller.validateAccessWorkspace();

        repository.saveAuthProfile(AuthProfile.manual("auth-1", "Owner", "admin"));
        repository.saveDataProfile(new DataProfile("data-1", "Owner data", "auth-1",
                Map.of("accountId", "acct-1"), true));
        repository.saveTemplate(template("template-1", "/accounts/{{data.accountId}}", List.of(), "",
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId"))));
        AccessValidationReport fixedReferences = controller.validateAccessWorkspace();

        assertTrue(missingReferences.hasBlockingErrors());
        assertFalse(fixedReferences.hasBlockingErrors());
    }

    @Test
    void flowiseCredentialSaveCreatesAndUpdatesProfileWithoutLosingAuthState() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);

        AuthProfile created = controller.saveCredentialValue(
                "", "Alice", "user", "manual", "credentials.username", "alice");
        repository.saveAuthProfile(new AuthProfile(
                created.id(), created.name(), created.role(), created.providerId(), created.credentials(),
                new dev.nquangit.corestra.access.auth.AuthState(
                        Map.of("accessToken", "token"), Map.of(), Map.of(), 0L, 10L), true));

        AuthProfile updated = controller.saveCredentialValue(
                created.id(), "ignored", "ignored", "missing-provider",
                "credentials.password", "secret");

        assertEquals(Map.of("username", "alice", "password", "secret"), updated.credentials());
        assertEquals("token", updated.state().variables().get("accessToken"));
        assertEquals("Alice", updated.name());
    }

    @Test
    void flowiseProfileSaveValidatesNamespacesAndOwnerReferences() {
        InMemoryAccessRepository repository = new InMemoryAccessRepository();
        AccessUiController controller = controller(repository);

        assertThrows(IllegalArgumentException.class, () -> controller.saveCredentialValue(
                "", "Alice", "", "manual", "data.username", "alice"));
        assertThrows(IllegalArgumentException.class, () -> controller.saveDataValue(
                "", "Account", "missing-auth", "data.accountId", "123"));

        AuthProfile owner = controller.saveCredentialValue(
                "", "Alice", "", "manual", "credentials.username", "alice");
        DataProfile data = controller.saveDataValue(
                "", "Alice account", owner.id(), "data.accountId", "123");
        DataProfile updated = controller.saveDataValue(
                data.id(), "ignored", "missing-auth", "data.customerId", "C-1");

        assertEquals(owner.id(), updated.ownerAuthProfileId());
        assertEquals(Map.of("accountId", "123", "customerId", "C-1"), updated.values());
    }

    private static AccessUiController controller(InMemoryAccessRepository repository) {
        AtomicInteger ids = new AtomicInteger();
        AccessIdGenerator idGenerator = prefix -> prefix + "-" + ids.incrementAndGet();
        return new AccessUiController(
                repository,
                idGenerator,
                new AccessRequestTemplateRenderer(new TemplateRenderer(new VariableResolver(key -> Optional.empty()))),
                new AccessScopedVariableStoreFactory());
    }

    private static AccessUiController controller(InMemoryAccessRepository repository, List<FlowDefinition> flows) {
        AtomicInteger ids = new AtomicInteger();
        AccessIdGenerator idGenerator = prefix -> prefix + "-" + ids.incrementAndGet();
        return new AccessUiController(
                repository,
                idGenerator,
                new AccessRequestTemplateRenderer(new TemplateRenderer(new VariableResolver(key -> Optional.empty()))),
                new AccessScopedVariableStoreFactory(),
                null,
                null,
                () -> flows);
    }

    private static AccessUiController controller(InMemoryAccessRepository repository, CountingHttpClient httpClient) {
        AtomicInteger ids = new AtomicInteger();
        AccessIdGenerator idGenerator = prefix -> prefix + "-" + ids.incrementAndGet();
        TemplateRenderer templateRenderer = new TemplateRenderer(new VariableResolver(key -> Optional.empty()));
        AccessRequestTemplateRenderer requestTemplateRenderer = new AccessRequestTemplateRenderer(templateRenderer);
        AccessScopedVariableStoreFactory storeFactory = new AccessScopedVariableStoreFactory();
        SingleAccessTestRunner runner = new SingleAccessTestRunner(
                requestTemplateRenderer,
                storeFactory,
                httpClient,
                new BasicResultEvaluator(),
                new RedactionService(),
                new ManualAuthApplier(templateRenderer),
                new FakeRequestAdapter(),
                idGenerator);
        return new AccessUiController(repository, idGenerator, requestTemplateRenderer, storeFactory, runner);
    }

    private static AccessUiController controllerWithPlan(InMemoryAccessRepository repository, CountingHttpClient httpClient) {
        AtomicInteger ids = new AtomicInteger();
        AccessIdGenerator idGenerator = prefix -> prefix + "-" + ids.incrementAndGet();
        TemplateRenderer templateRenderer = new TemplateRenderer(new VariableResolver(key -> Optional.empty()));
        AccessRequestTemplateRenderer requestTemplateRenderer = new AccessRequestTemplateRenderer(templateRenderer);
        AccessScopedVariableStoreFactory storeFactory = new AccessScopedVariableStoreFactory();
        SingleAccessTestRunner runner = new SingleAccessTestRunner(
                requestTemplateRenderer,
                storeFactory,
                httpClient,
                new BasicResultEvaluator(),
                new RedactionService(),
                new ManualAuthApplier(templateRenderer),
                new FakeRequestAdapter(),
                idGenerator);
        return new AccessUiController(
                repository,
                idGenerator,
                requestTemplateRenderer,
                storeFactory,
                runner,
                new AccessPlanRunner(repository, runner));
    }

    private static AuthProviderFormModel authProviderForm(String name) {
        return new AuthProviderFormModel(
                name,
                "",
                true,
                "login-flow",
                "refresh-flow",
                "validate-flow",
                AuthFallbackPolicy.MANUAL_ONLY,
                AuthRetryPolicy.NO_RETRY,
                List.of(),
                List.of(),
                List.of());
    }

    private static AccessRequestTemplate template(
            String id,
            String path,
            List<String> headers,
            String body,
            Set<ScopedVariableRef> placeholders) {
        return new AccessRequestTemplate(
                id,
                "Template",
                new StoredRequestTemplate("GET", "https", "example.test", 443, path, headers, body),
                placeholders,
                OperationMetadata.unknown(),
                "flow-1",
                "step-1",
                "hash-1",
            true);
    }

    private static FlowDefinition flow(List<FlowStep> steps) {
        return new FlowDefinition(
                "flow-setup",
                "Setup Flow",
                "",
                true,
                steps,
                FlowRuleSet.empty(),
                AggregationPlan.disabled(),
                FlowTriggerConfig.none());
    }

    private static FlowStep flowStep(String id, String path, List<ExtractRule> extractRules) {
        return new FlowStep(
                id,
                id,
                true,
                "",
                new StoredRequestTemplate("GET", "https", "example.test", 443, path, List.of(), ""),
                extractRules,
                List.of(),
                List.of(),
                StepErrorStrategy.FAIL_FLOW);
    }

    private static ExtractRule extract(String targetVariableName) {
        return new ExtractRule(
                "extract-" + targetVariableName.replace('.', '-'),
                "Extract",
                true,
                SourceSide.RESPONSE,
                ExtractorType.BODY_TEXT,
                "",
                targetVariableName,
                List.of());
    }

    private static final class CountingHttpClient implements HttpClient {
        private int count;

        @Override
        public HttpResponse send(HttpRequest request) {
            count++;
            return response();
        }

        int count() {
            return count;
        }
    }

    private static HttpResponse response() {
        return (HttpResponse) Proxy.newProxyInstance(
                HttpResponse.class.getClassLoader(),
                new Class<?>[]{HttpResponse.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "statusCode" -> (short) 403;
                    case "reasonPhrase" -> "Forbidden";
                    case "httpVersion" -> "HTTP/1.1";
                    case "headers" -> List.of();
                    case "bodyToString" -> "{\"accessToken\":\"secret\"}";
                    case "toString" -> "HTTP/1.1 403 Forbidden\n\n";
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

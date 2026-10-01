package dev.nquangit.corestra.access.validation;

import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.auth.analysis.AuthProviderFlowAnalyzer;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.evaluate.IndicatorType;
import dev.nquangit.corestra.access.evaluate.ResponseIndicator;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.flow.AccessTargetType;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertion;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertionType;
import dev.nquangit.corestra.access.postcheck.PostCheckAuthBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.scenario.ScenarioInstance;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.AccessRequestTemplateRenderer;
import dev.nquangit.corestra.access.template.AccessScopedVariableStoreFactory;
import dev.nquangit.corestra.access.template.OperationType;
import dev.nquangit.corestra.access.template.RenderedAccessRequest;
import dev.nquangit.corestra.access.template.TemplateRenderIssue;
import dev.nquangit.corestra.access.teardown.TeardownMode;
import dev.nquangit.corestra.access.variable.DefaultRuntimeValueProvider;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.variable.ScopedVariableStore;
import dev.nquangit.corestra.access.variable.TemplateRenderer;
import dev.nquangit.corestra.access.variable.UnresolvedVariable;
import dev.nquangit.corestra.access.variable.VariableErrorType;
import dev.nquangit.corestra.access.variable.VariableResolver;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.FlowDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Performs read-only validation and diagnostics for an Access Testing workspace.
 */
public final class AccessProjectValidator {
    private final TemplateRenderer stringRenderer = new TemplateRenderer(
            new VariableResolver(new DefaultRuntimeValueProvider()));
    private final AccessRequestTemplateRenderer requestRenderer = new AccessRequestTemplateRenderer(stringRenderer);
    private final AccessScopedVariableStoreFactory storeFactory = new AccessScopedVariableStoreFactory();
    private final AuthProviderFlowAnalyzer authProviderFlowAnalyzer = new AuthProviderFlowAnalyzer();

    /**
     * Validates an access workspace without mutating it.
     *
     * @param workspace workspace snapshot
     * @return validation report
     */
    public AccessValidationReport validate(AccessWorkspace workspace) {
        return validateInternal(workspace, List.of(), false);
    }

    /**
     * Validates an access workspace and checks AuthProvider contracts against current Flowise definitions.
     *
     * @param workspace workspace snapshot
     * @param flows current Flowise definitions
     * @return validation report
     */
    public AccessValidationReport validate(AccessWorkspace workspace, List<FlowDefinition> flows) {
        return validateInternal(workspace, flows, true);
    }

    private AccessValidationReport validateInternal(
            AccessWorkspace workspace,
            List<FlowDefinition> flows,
            boolean validateFlowContracts) {
        Objects.requireNonNull(workspace, "workspace");
        ValidationState state = new ValidationState();
        validateAuthProfiles(workspace, state);
        validateAuthProviders(workspace, state);
        if (validateFlowContracts) {
            validateAuthProviderFlowContracts(workspace, flows, state);
        }
        validateDataProfiles(workspace, state);
        validateTemplates(workspace, state);
        validateMatrixRows(workspace, state);
        validateScenarios(workspace, state);
        validateEvaluationProfiles(workspace, state);
        validateExportSecurity(workspace, state);
        return new AccessValidationReport(state.issues());
    }

    private void validateAuthProviderFlowContracts(
            AccessWorkspace workspace,
            List<FlowDefinition> flows,
            ValidationState state) {
        List<FlowDefinition> availableFlows = List.copyOf(Objects.requireNonNullElse(flows, List.of()));
        for (AuthProvider provider : workspace.authProviders().values()) {
            authProviderFlowAnalyzer.analyze(provider, availableFlows).issues().stream()
                    .filter(issue -> !issue.title().endsWith("Flow is required"))
                    .forEach(issue -> state.add(
                            issue.severity() == AuthProviderFlowAnalyzer.Severity.ERROR
                                    ? AccessValidationSeverity.ERROR
                                    : AccessValidationSeverity.WARNING,
                            AccessValidationCategory.AUTH_PROVIDER,
                            issue.title(),
                            "AuthProvider '" + provider.name() + "': " + issue.detail(),
                            "AuthProvider",
                            provider.id(),
                            "Open Authentication > Auth Providers and review Flow Contract."));
        }
    }

    private void validateAuthProfiles(AccessWorkspace workspace, ValidationState state) {
        for (AuthProfile profile : workspace.authProfiles().values()) {
            boolean manual = isManual(profile);
            if (!manual) {
                AuthProvider provider = workspace.authProviders().get(profile.providerId());
                if (provider == null) {
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.AUTH_PROFILE,
                            "Auth provider is missing",
                            "Auth profile '" + profile.name() + "' references missing provider '" + profile.providerId() + "'.",
                            "AuthProfile", profile.id(),
                            "Select an existing AuthProvider or change the profile to manual auth.");
                } else if (!provider.enabled()) {
                    state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.AUTH_PROVIDER,
                            "Referenced auth provider is disabled",
                            "Auth profile '" + profile.name() + "' references disabled provider '" + provider.name() + "'.",
                            "AuthProvider", provider.id(),
                            "Enable the provider or switch the auth profile to another provider.");
                }
            }

            if (!profile.enabled() && enabledRowsUsingAuth(workspace, profile.id()) > 0) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.AUTH_PROFILE,
                        "Disabled auth profile is referenced",
                        "Disabled auth profile '" + profile.name() + "' is referenced by enabled matrix rows.",
                        "AuthProfile", profile.id(),
                        "Enable the auth profile or disable/update the affected matrix rows.");
            }

            if (manual && authStateEmpty(profile.state()) && enabledRowsUsingAuth(workspace, profile.id()) > 0) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.AUTH_PROFILE,
                        "Manual auth profile has no auth material",
                        "Manual auth profile '" + profile.name() + "' is used by enabled rows but has no variables, headers, or cookies.",
                        "AuthProfile", profile.id(),
                        "Add auth headers/cookies/variables, or confirm the actor is intentionally anonymous.");
            }
        }
    }

    private void validateAuthProviders(AccessWorkspace workspace, ValidationState state) {
        for (AuthProvider provider : workspace.authProviders().values()) {
            if (!provider.enabled() && workspace.authProfiles().values().stream()
                    .anyMatch(profile -> provider.id().equals(profile.providerId()))) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.AUTH_PROVIDER,
                        "Disabled provider is referenced",
                        "AuthProvider '" + provider.name() + "' is disabled but referenced by one or more auth profiles.",
                        "AuthProvider", provider.id(),
                        "Enable the provider or update the referencing profiles.");
            }
            if (requiresLogin(provider) && provider.loginFlowId().isBlank()) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.AUTH_PROVIDER,
                        "Login flow is required",
                        "AuthProvider '" + provider.name() + "' has a login fallback/retry policy but no loginFlowId.",
                        "AuthProvider", provider.id(),
                        "Set loginFlowId or use MANUAL_ONLY/NO_RETRY policies.");
            }
            if (requiresRefresh(provider) && provider.refreshFlowId().isBlank()) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.AUTH_PROVIDER,
                        "Refresh flow is recommended",
                        "AuthProvider '" + provider.name() + "' has a refresh policy but no refreshFlowId.",
                        "AuthProvider", provider.id(),
                        "Set refreshFlowId or choose a policy that does not refresh.");
            }
            if (requiresValidate(provider) && provider.validateFlowId().isBlank()) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.AUTH_PROVIDER,
                        "Validate flow is recommended",
                        "AuthProvider '" + provider.name() + "' validates before refresh/login but no validateFlowId is set.",
                        "AuthProvider", provider.id(),
                        "Set validateFlowId or choose a policy that does not validate.");
            }
            validateAuthInjectionRules(provider, state);
        }
    }

    private void validateAuthInjectionRules(AuthProvider provider, ValidationState state) {
        for (AuthInjectionRule rule : provider.injectionRules()) {
            if (!rule.enabled()) {
                continue;
            }
            if (rule.target().isBlank()) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.AUTH_PROVIDER,
                        "Auth injection target is blank",
                        "Enabled auth injection rule '" + rule.name() + "' has no target.",
                        "AuthProvider", provider.id(),
                        "Set the header/cookie/query target or disable the rule.");
            }
            if (rule.location() == AuthInjectionLocation.BODY_JSON
                    || rule.location() == AuthInjectionLocation.BODY_FORM
                    || rule.location() == AuthInjectionLocation.PATH) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.AUTH_PROVIDER,
                        "Auth injection location is not supported at runtime",
                        "Auth injection rule '" + rule.name() + "' uses " + rule.location()
                                + ", which the current target auth applier reports as unsupported.",
                        "AuthProvider", provider.id(),
                        "Use HEADER, COOKIE, or QUERY for target auth injection in this version.");
            }
            malformedPlaceholders(rule.valueTemplate()).forEach(unresolved ->
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.AUTH_PROVIDER,
                            "Auth injection template is malformed",
                            "Rule '" + rule.name() + "' has invalid placeholder "
                                    + unresolved.placeholder() + ": " + unresolved.message(),
                            "AuthProvider", provider.id(),
                            "Use scoped placeholders such as {{auth.accessToken}}."));
            malformedPlaceholders(rule.target()).forEach(unresolved ->
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.AUTH_PROVIDER,
                            "Auth injection target template is malformed",
                            "Rule '" + rule.name() + "' has invalid target placeholder "
                                    + unresolved.placeholder() + ": " + unresolved.message(),
                            "AuthProvider", provider.id(),
                            "Use a literal target name or a valid scoped placeholder."));
        }
    }

    private void validateDataProfiles(AccessWorkspace workspace, ValidationState state) {
        for (DataProfile profile : workspace.dataProfiles().values()) {
            if (profile.ownerAuthProfileId().isBlank()) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.DATA_PROFILE,
                        "Data profile has no owner auth profile",
                        "Data profile '" + profile.name() + "' does not declare ownerAuthProfileId.",
                        "DataProfile", profile.id(),
                        "Set the auth profile that owns this object data.");
            } else if (!workspace.authProfiles().containsKey(profile.ownerAuthProfileId())) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.DATA_PROFILE,
                        "Data owner auth profile is missing",
                        "Data profile '" + profile.name() + "' references missing owner auth profile '"
                                + profile.ownerAuthProfileId() + "'.",
                        "DataProfile", profile.id(),
                        "Select an existing owner auth profile.");
            }
            if (profile.values().isEmpty()) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.DATA_PROFILE,
                        "Data profile has no values",
                        "Data profile '" + profile.name() + "' has no stable object values.",
                        "DataProfile", profile.id(),
                        "Add values such as accountId, customerId, cardId, or object id.");
            }
            if (!profile.enabled() && enabledRowsUsingData(workspace, profile.id()) > 0) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.DATA_PROFILE,
                        "Disabled data profile is referenced",
                        "Disabled data profile '" + profile.name() + "' is referenced by enabled matrix rows.",
                        "DataProfile", profile.id(),
                        "Enable the data profile or update/disable the affected matrix rows.");
            }
        }
    }

    private void validateTemplates(AccessWorkspace workspace, ValidationState state) {
        for (AccessRequestTemplate template : workspace.templates().values()) {
            validateTemplateSyntax(template, state);
            if (!template.enabled() && enabledRowsUsingTemplate(workspace, template.id()) > 0) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.TEMPLATE,
                        "Disabled template is referenced",
                        "Disabled template '" + template.name() + "' is referenced by enabled matrix rows.",
                        "Template", template.id(),
                        "Enable the template or update/disable the affected rows.");
            }
        }
    }

    private void validateTemplateSyntax(AccessRequestTemplate template, ValidationState state) {
        StoredRequestTemplate request = template.baseRequest();
        RenderedAccessRequest rendered = requestRenderer.render(
                template,
                ScopedVariableStore.empty(),
                RenderMode.STRICT);
        for (TemplateRenderIssue issue : rendered.issues()) {
            VariableErrorType type = issue.unresolvedVariable().errorType();
            if (type == VariableErrorType.MALFORMED_PLACEHOLDER
                    || type == VariableErrorType.NESTED_PLACEHOLDER
                    || type == VariableErrorType.INVALID_SCOPE
                    || type == VariableErrorType.INVALID_KEY
                    || type == VariableErrorType.AMBIGUOUS_VARIABLE) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.TEMPLATE,
                        "Template placeholder is invalid",
                        "Template '" + template.name() + "' has invalid placeholder at "
                                + issue.location() + ": " + issue.unresolvedVariable().message(),
                        "Template", template.id(),
                        "Use valid scoped placeholders such as {{auth.accessToken}} or {{data.accountId}}.");
            }
        }
        if (containsSensitiveHeader(request)) {
            state.add(AccessValidationSeverity.INFO, AccessValidationCategory.EXPORT_SECURITY,
                    "Template contains auth-related headers",
                    "Template '" + template.name() + "' contains Authorization, Cookie, or Set-Cookie headers.",
                    "Template", template.id(),
                    "Use sanitized export when sharing projects.");
        }
    }

    private void validateMatrixRows(AccessWorkspace workspace, ValidationState state) {
        for (AccessTestCase row : workspace.testCases().values()) {
            AccessRequestTemplate template = row.targetType() == AccessTargetType.REQUEST_TEMPLATE
                    ? workspace.templates().get(row.templateId())
                    : null;
            AccessFlowTarget flowTarget = row.targetType() == AccessTargetType.FLOW_TARGET
                    ? workspace.flowTargets().get(row.flowTargetId())
                    : null;
            AuthProfile authProfile = workspace.authProfiles().get(row.target().targetAuthProfileId());
            DataProfile dataProfile = row.target().dataProfileId().isBlank()
                    ? null
                    : workspace.dataProfiles().get(row.target().dataProfileId());

            if (row.enabled()) {
                if (row.targetType() == AccessTargetType.FLOW_TARGET) {
                    if (flowTarget == null) {
                        state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                                "Matrix row references missing flow target",
                                "Enabled row '" + row.name() + "' references missing flow target '"
                                        + row.flowTargetId() + "'.",
                                "AccessTestCase", row.id(),
                                "Select an existing flow target or disable the row.");
                    }
                } else if (template == null) {
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                            "Matrix row references missing template",
                            "Enabled row '" + row.name() + "' references missing template '" + row.templateId() + "'.",
                            "AccessTestCase", row.id(),
                            "Select an existing template or disable the row.");
                }
                if (authProfile == null) {
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                            "Matrix row references missing auth profile",
                            "Enabled row '" + row.name() + "' references missing auth profile '"
                                    + row.target().targetAuthProfileId() + "'.",
                            "AccessTestCase", row.id(),
                            "Select an existing target auth profile or disable the row.");
                }
                if (!row.target().dataProfileId().isBlank() && dataProfile == null) {
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                            "Matrix row references missing data profile",
                            "Enabled row '" + row.name() + "' references missing data profile '"
                                    + row.target().dataProfileId() + "'.",
                            "AccessTestCase", row.id(),
                            "Select an existing data profile or clear the data binding.");
                }
            }

            validateRowEvaluation(row, workspace, state);
            validateRowSetup(row, workspace, state);
            validatePostChecks(row, workspace, authProfile, dataProfile, state);
            validateTeardown(row, workspace, state);
            validateConsumableTemplatePolicy(row, template, state);
            if (row.targetType() == AccessTargetType.REQUEST_TEMPLATE) {
                validateRowRender(row, template, authProfile, dataProfile, workspace, state);
            }
        }
    }

    private void validateRowEvaluation(AccessTestCase row, AccessWorkspace workspace, ValidationState state) {
        if (!row.evaluationProfileId().isBlank()
                && !workspace.evaluationProfiles().containsKey(row.evaluationProfileId())) {
            state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.EVALUATION_PROFILE,
                    "Evaluation profile is missing",
                    "Row '" + row.name() + "' references missing evaluation profile '"
                            + row.evaluationProfileId() + "'.",
                    "AccessTestCase", row.id(),
                    "Select an existing evaluation profile or clear the field.");
        }
        if (row.expected() == ExpectedResult.CUSTOM
                && row.assertions().isEmpty()
                && row.evaluationProfileId().isBlank()) {
            state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.MATRIX_ROW,
                    "CUSTOM expected result has no evaluator",
                    "Row '" + row.name() + "' expects CUSTOM but has no assertions or evaluation profile.",
                    "AccessTestCase", row.id(),
                    "Add response assertions or select an evaluation profile.");
        }
    }

    private void validateRowSetup(AccessTestCase row, AccessWorkspace workspace, ValidationState state) {
        switch (row.setup().mode()) {
            case NONE -> {
                if (!row.target().scenarioInstanceId().isBlank()
                        && !workspace.scenarios().containsKey(row.target().scenarioInstanceId())) {
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.SCENARIO,
                            "Scenario is missing",
                            "Row '" + row.name() + "' references missing scenario '"
                                    + row.target().scenarioInstanceId() + "'.",
                            "AccessTestCase", row.id(),
                            "Select an existing scenario or clear the scenario id.");
                }
            }
            case REUSE_SCENARIO -> {
                if (row.target().scenarioInstanceId().isBlank()) {
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.SCENARIO,
                            "REUSE_SCENARIO requires a scenario id",
                            "Row '" + row.name() + "' uses REUSE_SCENARIO but has no scenarioInstanceId.",
                            "AccessTestCase", row.id(),
                            "Select a scenario instance or choose another setup mode.");
                } else if (!workspace.scenarios().containsKey(row.target().scenarioInstanceId())) {
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.SCENARIO,
                            "Reusable scenario is missing",
                            "Row '" + row.name() + "' references missing scenario '"
                                    + row.target().scenarioInstanceId() + "'.",
                            "AccessTestCase", row.id(),
                            "Select an existing scenario instance.");
                }
            }
            case RUN_PREFIX -> {
                if (row.setup().setupFlowId().isBlank() || row.setup().untilBeforeStepId().isBlank()) {
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                            "RUN_PREFIX setup is incomplete",
                            "Row '" + row.name() + "' uses RUN_PREFIX but setupFlowId or untilBeforeStepId is blank.",
                            "AccessTestCase", row.id(),
                            "Set setupFlowId and untilBeforeStepId.");
                }
            }
            case RUN_SETUP_FLOW -> {
                if (row.setup().setupFlowId().isBlank()) {
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                            "RUN_SETUP_FLOW setup is incomplete",
                            "Row '" + row.name() + "' uses RUN_SETUP_FLOW but setupFlowId is blank.",
                            "AccessTestCase", row.id(),
                            "Set setupFlowId.");
                }
            }
        }
        if (row.setup().setupAuthBinding() == dev.nquangit.corestra.access.matrix.SetupAuthBinding.FIXED_PROFILE) {
            if (row.setup().setupAuthProfileId().isBlank()
                    || !workspace.authProfiles().containsKey(row.setup().setupAuthProfileId())) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                        "Fixed setup auth profile is missing",
                        "Row '" + row.name() + "' uses fixed setup auth but setupAuthProfileId is blank or missing.",
                        "AccessTestCase", row.id(),
                        "Select an existing setup auth profile.");
            }
        }
    }

    private void validatePostChecks(
            AccessTestCase row,
            AccessWorkspace workspace,
            AuthProfile targetAuthProfile,
            DataProfile targetDataProfile,
            ValidationState state) {
        for (PostCheckRequest postCheck : row.postChecks()) {
            if (!postCheck.enabled()) {
                continue;
            }
            if (!workspace.templates().containsKey(postCheck.templateId())) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                        "Post-check template is missing",
                        "Post-check '" + postCheck.name() + "' references missing template '"
                                + postCheck.templateId() + "'.",
                        "AccessTestCase", row.id(),
                        "Select an existing post-check template or disable the post-check.");
            }
            if (postCheck.assertions().isEmpty()) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.POST_CHECK,
                        "Post-check has no assertions",
                        "Post-check '" + postCheck.name() + "' is enabled but has no assertions.",
                        "AccessTestCase", row.id(),
                        "Add at least one assertion or disable the post-check.");
            }
            if (!postCheck.dataProfileId().isBlank()
                    && !workspace.dataProfiles().containsKey(postCheck.dataProfileId())) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                        "Post-check data profile is missing",
                        "Post-check '" + postCheck.name() + "' references missing data profile '"
                                + postCheck.dataProfileId() + "'.",
                        "AccessTestCase", row.id(),
                        "Select an existing data profile or clear the override.");
            }
            if (postCheck.authBinding() == PostCheckAuthBinding.FIXED_PROFILE
                    && (postCheck.authProfileId().isBlank()
                    || !workspace.authProfiles().containsKey(postCheck.authProfileId()))) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                        "Post-check fixed auth profile is missing",
                        "Post-check '" + postCheck.name() + "' uses FIXED_PROFILE but the auth profile is blank or missing.",
                        "AccessTestCase", row.id(),
                        "Select an existing fixed auth profile.");
            }
            if (postCheck.authBinding() == PostCheckAuthBinding.TARGET_ACTOR && targetAuthProfile == null) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                        "Post-check target auth cannot resolve",
                        "Post-check '" + postCheck.name() + "' uses TARGET_ACTOR but the row target auth profile is missing.",
                        "AccessTestCase", row.id(),
                        "Fix the row target auth profile.");
            }
            if (postCheck.authBinding() == PostCheckAuthBinding.DATA_OWNER) {
                DataProfile dataProfile = postCheck.dataProfileId().isBlank()
                        ? targetDataProfile
                        : workspace.dataProfiles().get(postCheck.dataProfileId());
                if (dataProfile == null || dataProfile.ownerAuthProfileId().isBlank()
                        || !workspace.authProfiles().containsKey(dataProfile.ownerAuthProfileId())) {
                    state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                            "Post-check data-owner auth cannot resolve",
                            "Post-check '" + postCheck.name() + "' uses DATA_OWNER but owner auth is missing.",
                            "AccessTestCase", row.id(),
                            "Bind a data profile with an existing owner auth profile.");
                }
            }
            validatePostCheckAssertions(row, postCheck, state);
        }
    }

    private void validatePostCheckAssertions(
            AccessTestCase row,
            PostCheckRequest postCheck,
            ValidationState state) {
        for (PostCheckAssertion assertion : postCheck.assertions()) {
            if (!assertion.enabled()) {
                continue;
            }
            if (assertionRequiresExpression(assertion.type()) && assertion.expression().isBlank()) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                        "Post-check assertion expression is blank",
                        "Assertion '" + assertion.name() + "' in post-check '" + postCheck.name()
                                + "' requires an expression.",
                        "AccessTestCase", row.id(),
                        "Set the assertion expression or disable the assertion.");
            }
            if (assertionRequiresExpectedValue(assertion.type()) && assertion.expectedValue().isBlank()) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                        "Post-check assertion expected value is blank",
                        "Assertion '" + assertion.name() + "' in post-check '" + postCheck.name()
                                + "' requires an expected value.",
                        "AccessTestCase", row.id(),
                        "Set the expected value or disable the assertion.");
            }
        }
    }

    private void validateTeardown(AccessTestCase row, AccessWorkspace workspace, ValidationState state) {
        if (row.teardown().mode() != TeardownMode.RUN_FLOW) {
            return;
        }
        if (row.teardown().teardownFlowId().isBlank()) {
            state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.TEARDOWN,
                    "Teardown flow is missing",
                    "Row '" + row.name() + "' has teardown enabled but no teardownFlowId.",
                    "AccessTestCase", row.id(),
                    "Set teardownFlowId or disable teardown.");
        }
        if (row.teardown().authBinding() == dev.nquangit.corestra.access.teardown.TeardownAuthBinding.FIXED_PROFILE
                && (row.teardown().teardownAuthProfileId().isBlank()
                || !workspace.authProfiles().containsKey(row.teardown().teardownAuthProfileId()))) {
            state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.TEARDOWN,
                    "Teardown fixed auth profile is missing",
                    "Row '" + row.name() + "' uses fixed teardown auth but the auth profile is blank or missing.",
                    "AccessTestCase", row.id(),
                    "Select an existing teardown auth profile.");
        }
        if (!row.teardown().runOnSuccess() && !row.teardown().runOnFailure()) {
            state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.TEARDOWN,
                    "Teardown will rarely run",
                    "Row '" + row.name() + "' has teardown configured but both runOnSuccess and runOnFailure are false.",
                    "AccessTestCase", row.id(),
                    "Enable runOnSuccess or runOnFailure, or disable teardown.");
        }
    }

    private void validateConsumableTemplatePolicy(
            AccessTestCase row,
            AccessRequestTemplate template,
            ValidationState state) {
        if (template == null) {
            return;
        }
        boolean consumable = template.operationMetadata().consumable()
                || template.operationMetadata().operationType() == OperationType.CONSUMABLE;
        if (consumable && row.setup().scenarioPolicy()
                != dev.nquangit.corestra.access.matrix.ScenarioPolicy.FRESH_PER_TEST) {
            state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.TEMPLATE,
                    "Consumable template should use fresh setup data",
                    "Row '" + row.name() + "' targets consumable template '" + template.name()
                            + "' without FRESH_PER_TEST scenario policy.",
                    "AccessTestCase", row.id(),
                    "Use FRESH_PER_TEST when exercising one-time tokens or consumable workflows.");
        }
    }

    private void validateRowRender(
            AccessTestCase row,
            AccessRequestTemplate template,
            AuthProfile authProfile,
            DataProfile dataProfile,
            AccessWorkspace workspace,
            ValidationState state) {
        if (!row.enabled() || template == null || authProfile == null) {
            return;
        }
        Map<String, String> scenario = scenarioValues(row, workspace).orElse(Map.of());
        ScopedVariableStore store = storeFactory.from(Map.of(), authProfile.state(), dataProfile, scenario, Map.of());
        RenderedAccessRequest rendered = requestRenderer.render(template, store, RenderMode.STRICT);
        for (TemplateRenderIssue issue : rendered.issues()) {
            state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.TEMPLATE,
                    "Target template has unresolved placeholders",
                    "Row '" + row.name() + "' cannot fully render template '" + template.name()
                            + "' at " + issue.location() + ": " + issue.unresolvedVariable().message(),
                    "AccessTestCase", row.id(),
                    "Provide the referenced auth/data/scenario value or fix the placeholder.");
        }
    }

    private void validateScenarios(AccessWorkspace workspace, ValidationState state) {
        for (ScenarioInstance scenario : workspace.scenarios().values()) {
            if (scenario.variables().isEmpty()) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.SCENARIO,
                        "Scenario has no variables",
                        "Scenario '" + scenario.name() + "' contains no variables.",
                        "Scenario", scenario.id(),
                        "Recreate the scenario or remove it if unused.");
            }
            if (scenario.ownerDataProfileId().isBlank()) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.SCENARIO,
                        "Scenario has no owner data profile",
                        "Scenario '" + scenario.name() + "' has no ownerDataProfileId.",
                        "Scenario", scenario.id(),
                        "Bind the scenario to the data profile it was prepared for.");
            } else if (!workspace.dataProfiles().containsKey(scenario.ownerDataProfileId())) {
                state.add(AccessValidationSeverity.ERROR, AccessValidationCategory.SCENARIO,
                        "Scenario owner data profile is missing",
                        "Scenario '" + scenario.name() + "' references missing owner data profile '"
                                + scenario.ownerDataProfileId() + "'.",
                        "Scenario", scenario.id(),
                        "Select an existing owner data profile.");
            }
            if (scenario.consumed() && enabledRowsUsingScenario(workspace, scenario.id()) > 0) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.SCENARIO,
                        "Consumed scenario is referenced",
                        "Consumed scenario '" + scenario.name() + "' is referenced by enabled matrix rows.",
                        "Scenario", scenario.id(),
                        "Use a fresh scenario or update the row setup policy.");
            }
        }
    }

    private void validateEvaluationProfiles(AccessWorkspace workspace, ValidationState state) {
        for (EvaluationProfile profile : workspace.evaluationProfiles().values()) {
            if (profile.allowIndicators().isEmpty()
                    && profile.denyIndicators().isEmpty()
                    && profile.sensitiveFieldRules().isEmpty()
                    && profile.maskedFieldRules().isEmpty()) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.EVALUATION_PROFILE,
                        "Evaluation profile has no rules",
                        "Evaluation profile '" + profile.name() + "' has no allow, deny, sensitive, or masking rules.",
                        "EvaluationProfile", profile.id(),
                        "Add indicators or leave rows on the basic status evaluator.");
            }
            validateIndicators(profile.id(), profile.allowIndicators(), "allow", state);
            validateIndicators(profile.id(), profile.denyIndicators(), "deny", state);
        }
    }

    private void validateIndicators(
            String profileId,
            List<ResponseIndicator> indicators,
            String group,
            ValidationState state) {
        for (ResponseIndicator indicator : indicators) {
            if (!indicator.enabled()) {
                continue;
            }
            if (indicatorExpressionMissing(indicator)) {
                state.add(AccessValidationSeverity.WARNING, AccessValidationCategory.EVALUATION_PROFILE,
                        "Evaluation indicator is incomplete",
                        "Enabled " + group + " indicator '" + indicator.name()
                                + "' has a blank selector or expected value for " + indicator.type() + ".",
                        "EvaluationProfile", profileId,
                        "Fill in the selector/value or disable the indicator.");
            }
        }
    }

    private void validateExportSecurity(AccessWorkspace workspace, ValidationState state) {
        for (AuthProfile profile : workspace.authProfiles().values()) {
            if (!profile.credentials().isEmpty()
                    || !profile.state().variables().isEmpty()
                    || !profile.state().headers().isEmpty()
                    || !profile.state().cookies().isEmpty()) {
                state.add(AccessValidationSeverity.INFO, AccessValidationCategory.EXPORT_SECURITY,
                        "Auth material may be exported",
                        "Auth profile '" + profile.name() + "' contains credentials, variables, headers, or cookies.",
                        "AuthProfile", profile.id(),
                        "Use sanitized export before sharing a project file.");
            }
        }
    }

    private List<UnresolvedVariable> malformedPlaceholders(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return stringRenderer.render(value, ScopedVariableStore.empty(), RenderMode.STRICT)
                .unresolvedVariables()
                .stream()
                .filter(unresolved -> unresolved.errorType() != VariableErrorType.MISSING_VARIABLE)
                .toList();
    }

    private Optional<Map<String, String>> scenarioValues(AccessTestCase row, AccessWorkspace workspace) {
        if (row.target().scenarioInstanceId().isBlank()) {
            return Optional.of(Map.of());
        }
        ScenarioInstance scenario = workspace.scenarios().get(row.target().scenarioInstanceId());
        return scenario == null ? Optional.empty() : Optional.of(scenario.toVariableMap());
    }

    private boolean containsSensitiveHeader(StoredRequestTemplate request) {
        for (String header : request.headerLines()) {
            String lower = header.toLowerCase(Locale.ROOT);
            if (lower.startsWith("authorization:")
                    || lower.startsWith("cookie:")
                    || lower.startsWith("set-cookie:")) {
                return true;
            }
        }
        return false;
    }

    private boolean indicatorExpressionMissing(ResponseIndicator indicator) {
        IndicatorType type = indicator.type();
        return switch (type) {
            case STATUS_CODE_IN -> indicator.selector().isBlank() && indicator.expectedValue().isBlank();
            case BODY_CONTAINS, BODY_NOT_CONTAINS -> indicator.expectedValue().isBlank();
            case HEADER_EXISTS, JSON_PATH_EXISTS, JSON_PATH_NOT_EXISTS -> indicator.selector().isBlank();
            case HEADER_VALUE_CONTAINS, JSON_PATH_EQUALS ->
                    indicator.selector().isBlank() || indicator.expectedValue().isBlank();
        };
    }

    private boolean assertionRequiresExpression(PostCheckAssertionType type) {
        return switch (type) {
            case STATUS_CODE, BODY_CONTAINS, BODY_NOT_CONTAINS -> false;
            case HEADER_EXISTS, HEADER_VALUE_CONTAINS, JSON_PATH_EXISTS, JSON_PATH_EQUALS, JSON_PATH_NOT_EQUALS -> true;
        };
    }

    private boolean assertionRequiresExpectedValue(PostCheckAssertionType type) {
        return switch (type) {
            case STATUS_CODE, BODY_CONTAINS, BODY_NOT_CONTAINS, HEADER_VALUE_CONTAINS, JSON_PATH_EQUALS,
                    JSON_PATH_NOT_EQUALS -> true;
            case HEADER_EXISTS, JSON_PATH_EXISTS -> false;
        };
    }

    private int enabledRowsUsingAuth(AccessWorkspace workspace, String authProfileId) {
        int count = 0;
        for (AccessTestCase row : workspace.testCases().values()) {
            if (row.enabled() && authProfileId.equals(row.target().targetAuthProfileId())) {
                count++;
            }
        }
        return count;
    }

    private int enabledRowsUsingData(AccessWorkspace workspace, String dataProfileId) {
        int count = 0;
        for (AccessTestCase row : workspace.testCases().values()) {
            if (row.enabled() && dataProfileId.equals(row.target().dataProfileId())) {
                count++;
            }
        }
        return count;
    }

    private int enabledRowsUsingTemplate(AccessWorkspace workspace, String templateId) {
        int count = 0;
        for (AccessTestCase row : workspace.testCases().values()) {
            if (row.enabled() && templateId.equals(row.templateId())) {
                count++;
            }
        }
        return count;
    }

    private int enabledRowsUsingScenario(AccessWorkspace workspace, String scenarioId) {
        int count = 0;
        for (AccessTestCase row : workspace.testCases().values()) {
            if (row.enabled() && scenarioId.equals(row.target().scenarioInstanceId())) {
                count++;
            }
        }
        return count;
    }

    private boolean requiresLogin(AuthProvider provider) {
        return provider.fallbackPolicy() != AuthFallbackPolicy.MANUAL_ONLY
                || provider.targetRetryPolicy() == AuthRetryPolicy.LOGIN_AND_RETRY_ONCE
                || provider.targetRetryPolicy() == AuthRetryPolicy.REFRESH_THEN_LOGIN_AND_RETRY_ONCE;
    }

    private boolean requiresRefresh(AuthProvider provider) {
        return provider.fallbackPolicy() == AuthFallbackPolicy.VALIDATE_THEN_REFRESH_THEN_LOGIN
                || provider.fallbackPolicy() == AuthFallbackPolicy.REFRESH_THEN_LOGIN
                || provider.targetRetryPolicy() == AuthRetryPolicy.REFRESH_AND_RETRY_ONCE
                || provider.targetRetryPolicy() == AuthRetryPolicy.REFRESH_THEN_LOGIN_AND_RETRY_ONCE;
    }

    private boolean requiresValidate(AuthProvider provider) {
        return provider.fallbackPolicy() == AuthFallbackPolicy.VALIDATE_THEN_REFRESH_THEN_LOGIN;
    }

    private boolean isManual(AuthProfile profile) {
        return profile.providerId().isBlank() || AuthProfile.MANUAL_PROVIDER_ID.equals(profile.providerId());
    }

    private boolean authStateEmpty(AuthState state) {
        return state.variables().isEmpty() && state.headers().isEmpty() && state.cookies().isEmpty();
    }

    private static final class ValidationState {
        private final ArrayList<AccessValidationIssue> issues = new ArrayList<>();
        private int nextId = 1;

        private void add(
                AccessValidationSeverity severity,
                AccessValidationCategory category,
                String title,
                String message,
                String relatedObjectType,
                String relatedObjectId,
                String suggestedFix) {
            issues.add(new AccessValidationIssue(
                    "access-validation-" + nextId++,
                    severity,
                    category,
                    title,
                    message,
                    relatedObjectType,
                    relatedObjectId,
                    suggestedFix));
        }

        private List<AccessValidationIssue> issues() {
            return List.copyOf(issues);
        }
    }
}

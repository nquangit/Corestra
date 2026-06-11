package dev.nquangit.corestra.access.validation;

import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthState;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.ScenarioPolicy;
import dev.nquangit.corestra.access.matrix.SetupAuthBinding;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertion;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertionType;
import dev.nquangit.corestra.access.postcheck.PostCheckAuthBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.template.OperationType;
import dev.nquangit.corestra.access.teardown.TeardownAuthBinding;
import dev.nquangit.corestra.access.teardown.TeardownConfig;
import dev.nquangit.corestra.access.teardown.TeardownMode;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessProjectValidatorTest {
    private final AccessProjectValidator validator = new AccessProjectValidator();

    @Test
    void emptyWorkspaceHasNoBlockingErrors() {
        AccessValidationReport report = validator.validate(AccessWorkspace.empty());

        assertFalse(report.hasBlockingErrors());
        assertEquals(0, report.errorCount());
    }

    @Test
    void enabledRowWithMissingReferencesProducesErrors() {
        AccessTestCase row = accessCase(
                "case-missing",
                "Missing references",
                "missing-template",
                SetupConfig.none(),
                new TargetBinding("missing-auth", "missing-data", "", ""),
                List.of(),
                TeardownConfig.none(),
                true);
        AccessWorkspace workspace = AccessWorkspace.empty().withTestCase(row);

        AccessValidationReport report = validator.validate(workspace);

        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                "missing template");
        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                "missing auth profile");
        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                "missing data profile");
    }

    @Test
    void nonManualAuthProfileMissingProviderProducesError() {
        AuthProfile profile = new AuthProfile(
                "auth-provider-backed",
                "Provider backed user",
                "member",
                "missing-provider",
                Map.of(),
                AuthState.empty(),
                true);
        AccessWorkspace workspace = AccessWorkspace.empty().withAuthProfile(profile);

        AccessValidationReport report = validator.validate(workspace);

        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.AUTH_PROFILE,
                "provider is missing");
    }

    @Test
    void disabledTemplateReferencedByEnabledRowProducesWarning() {
        AccessRequestTemplate disabledTemplate = template("tpl-disabled", "Disabled target", false, List.of());
        AccessWorkspace workspace = validWorkspace()
                .withTemplate(disabledTemplate)
                .withTestCase(accessCase(
                        "case-disabled-template",
                        "References disabled template",
                        disabledTemplate.id(),
                        SetupConfig.none(),
                        validTarget(),
                        List.of(),
                        TeardownConfig.none(),
                        true));

        AccessValidationReport report = validator.validate(workspace);

        assertHasIssue(report, AccessValidationSeverity.WARNING, AccessValidationCategory.TEMPLATE,
                "disabled template");
    }

    @Test
    void setupPostCheckAndTeardownMisconfigurationsProduceRelevantIssues() {
        SetupConfig badSetup = new SetupConfig(
                SetupMode.RUN_PREFIX,
                "",
                "",
                "",
                SetupAuthBinding.FIXED_PROFILE,
                ScenarioPolicy.REUSE_EXISTING);
        PostCheckRequest brokenPostCheck = new PostCheckRequest(
                "post-broken",
                "Broken post-check",
                true,
                "missing-post-template",
                "",
                PostCheckAuthBinding.FIXED_PROFILE,
                "missing-post-data",
                "",
                List.of(new PostCheckAssertion(
                        "post-assertion",
                        "Broken assertion",
                        true,
                        PostCheckAssertionType.JSON_PATH_EQUALS,
                        "",
                        "",
                        false)));
        PostCheckRequest emptyPostCheck = new PostCheckRequest(
                "post-empty",
                "No assertions",
                true,
                "tpl-target",
                "",
                PostCheckAuthBinding.TARGET_ACTOR,
                "",
                "",
                List.of());
        TeardownConfig badTeardown = new TeardownConfig(
                TeardownMode.RUN_FLOW,
                "",
                TeardownAuthBinding.FIXED_PROFILE,
                "",
                false,
                false,
                false);
        AccessTestCase row = accessCase(
                "case-misconfigured",
                "Misconfigured row",
                "tpl-target",
                badSetup,
                validTarget(),
                List.of(brokenPostCheck, emptyPostCheck),
                badTeardown,
                true);
        AccessWorkspace workspace = validWorkspace().withTestCase(row);

        AccessValidationReport report = validator.validate(workspace);

        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                "run_prefix setup is incomplete");
        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.MATRIX_ROW,
                "fixed setup auth profile");
        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                "post-check template is missing");
        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                "post-check data profile is missing");
        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                "post-check fixed auth profile");
        assertHasIssue(report, AccessValidationSeverity.WARNING, AccessValidationCategory.POST_CHECK,
                "post-check has no assertions");
        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                "assertion expression is blank");
        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.POST_CHECK,
                "assertion expected value is blank");
        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.TEARDOWN,
                "teardown flow is missing");
        assertHasIssue(report, AccessValidationSeverity.ERROR, AccessValidationCategory.TEARDOWN,
                "teardown fixed auth profile");
        assertHasIssue(report, AccessValidationSeverity.WARNING, AccessValidationCategory.TEARDOWN,
                "teardown will rarely run");
    }

    @Test
    void evaluationProfileWithoutRulesWarns() {
        EvaluationProfile profile = new EvaluationProfile(
                "eval-empty",
                "Empty evaluator",
                true,
                List.of(),
                List.of(),
                List.of(),
                List.of());
        AccessWorkspace workspace = AccessWorkspace.empty().withEvaluationProfile(profile);

        AccessValidationReport report = validator.validate(workspace);

        assertHasIssue(report, AccessValidationSeverity.WARNING, AccessValidationCategory.EVALUATION_PROFILE,
                "no rules");
    }

    @Test
    void exportSecurityReportsAuthMaterialAndSensitiveTemplateHeaders() {
        AuthProfile authProfile = new AuthProfile(
                "auth-sensitive",
                "Sensitive user",
                "admin",
                AuthProfile.MANUAL_PROVIDER_ID,
                Map.of("username", "alice"),
                new AuthState(Map.of("accessToken", "token-1"), Map.of("X-Api-Key", "secret"), Map.of(), 0L, 0L),
                true);
        AccessRequestTemplate sensitiveTemplate = template(
                "tpl-sensitive",
                "Sensitive headers",
                true,
                List.of("Authorization: Bearer static-token", "Cookie: SESSION=s1"));
        AccessWorkspace workspace = AccessWorkspace.empty()
                .withAuthProfile(authProfile)
                .withTemplate(sensitiveTemplate);

        AccessValidationReport report = validator.validate(workspace);

        assertHasIssue(report, AccessValidationSeverity.INFO, AccessValidationCategory.EXPORT_SECURITY,
                "auth material");
        assertHasIssue(report, AccessValidationSeverity.INFO, AccessValidationCategory.EXPORT_SECURITY,
                "auth-related headers");
    }

    @Test
    void validateDoesNotMutateWorkspace() {
        AccessWorkspace workspace = validWorkspace()
                .withEvaluationProfile(new EvaluationProfile(
                        "eval-empty",
                        "Empty evaluator",
                        true,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of()))
                .withTestCase(accessCase(
                        "case-target",
                        "Target request",
                        "tpl-target",
                        SetupConfig.none(),
                        validTarget(),
                        List.of(),
                        TeardownConfig.none(),
                        true));
        AccessWorkspace before = workspace.toBuilder().build();

        validator.validate(workspace);

        assertEquals(before, workspace);
    }

    private static AccessWorkspace validWorkspace() {
        AuthProfile authProfile = new AuthProfile(
                "auth-target",
                "Target user",
                "member",
                AuthProfile.MANUAL_PROVIDER_ID,
                Map.of(),
                new AuthState(Map.of("accessToken", "token-1"), Map.of(), Map.of(), 0L, 0L),
                true);
        DataProfile dataProfile = DataProfile.enabled(
                "data-target",
                "Target data",
                authProfile.id(),
                Map.of("accountId", "acct-1"));
        AccessRequestTemplate template = template("tpl-target", "Target request", true, List.of());
        AuthProvider provider = new AuthProvider(
                "provider-unused",
                "Unused provider",
                "",
                true,
                "",
                "",
                "",
                List.of(),
                List.of(),
                List.of(),
                AuthFallbackPolicy.MANUAL_ONLY,
                AuthRetryPolicy.NO_RETRY);

        return AccessWorkspace.builder()
                .authProvider(provider)
                .authProfile(authProfile)
                .dataProfile(dataProfile)
                .template(template)
                .build();
    }

    private static TargetBinding validTarget() {
        return new TargetBinding("auth-target", "data-target", "", "");
    }

    private static AccessRequestTemplate template(
            String id,
            String name,
            boolean enabled,
            List<String> extraHeaders) {
        return new AccessRequestTemplate(
                id,
                name,
                new StoredRequestTemplate(
                        "GET",
                        "https",
                        "example.test",
                        443,
                        "/accounts",
                        extraHeaders,
                        ""),
                Set.of(),
                new OperationMetadata(OperationType.READ_ONLY, false, false, ""),
                "",
                "",
                "",
                enabled);
    }

    private static AccessTestCase accessCase(
            String id,
            String name,
            String templateId,
            SetupConfig setup,
            TargetBinding target,
            List<PostCheckRequest> postChecks,
            TeardownConfig teardown,
            boolean enabled) {
        return new AccessTestCase(
                id,
                name,
                templateId,
                "flow-1",
                "step-1",
                setup,
                target,
                ExpectedResult.REVIEW,
                "",
                List.of(),
                postChecks,
                teardown,
                enabled,
                RunStatus.PENDING,
                0L);
    }

    private static void assertHasIssue(
            AccessValidationReport report,
            AccessValidationSeverity severity,
            AccessValidationCategory category,
            String titleSnippet) {
        String normalizedSnippet = titleSnippet.toLowerCase(Locale.ROOT);

        assertTrue(
                report.issues().stream().anyMatch(issue ->
                        issue.severity() == severity
                                && issue.category() == category
                                && issue.title().toLowerCase(Locale.ROOT).contains(normalizedSnippet)),
                () -> "Expected " + severity + " " + category + " issue with title containing '"
                        + titleSnippet + "' but saw " + issueTitles(report));
    }

    private static List<String> issueTitles(AccessValidationReport report) {
        return report.issues().stream()
                .map(issue -> issue.severity() + " " + issue.category() + " " + issue.title())
                .toList();
    }
}

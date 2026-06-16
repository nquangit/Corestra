package dev.nquangit.corestra.access.report;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Builds concise finding drafts from access test results and captured evidence.
 */
public final class FindingDraftBuilder {
    private static final String REQUEST_UNAVAILABLE = "[redacted request evidence unavailable]";
    private static final String RESPONSE_UNAVAILABLE = "[redacted response evidence unavailable]";

    /**
     * Builds a draft from a result without captured evidence.
     *
     * @param result access test result
     * @param workspace access workspace snapshot
     * @return finding draft
     */
    public FindingDraft build(AccessTestResult result, AccessWorkspace workspace) {
        return build(result, Optional.empty(), workspace);
    }

    /**
     * Builds a draft from a result and optional captured evidence.
     *
     * <p>PR 14 intentionally uses redacted evidence only. Raw request and response
     * fields are not read by this builder.
     *
     * @param result access test result
     * @param evidence optional captured evidence
     * @param workspace access workspace snapshot
     * @return finding draft
     */
    public FindingDraft build(
            AccessTestResult result,
            Optional<AccessEvidence> evidence,
            AccessWorkspace workspace) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(workspace, "workspace");

        Optional<AccessTestCase> testCase = Optional.ofNullable(workspace.testCases().get(result.testCaseId()));
        Optional<AccessRequestTemplate> template = testCase
                .map(AccessTestCase::templateId)
                .map(workspace.templates()::get);
        Optional<AuthProfile> targetAuth = testCase
                .map(AccessTestCase::target)
                .map(target -> target.targetAuthProfileId())
                .map(workspace.authProfiles()::get);
        Optional<DataProfile> dataProfile = testCase
                .map(AccessTestCase::target)
                .map(target -> target.dataProfileId())
                .map(workspace.dataProfiles()::get);
        Optional<AuthProfile> owner = dataProfile
                .map(DataProfile::ownerAuthProfileId)
                .map(workspace.authProfiles()::get);

        ExpectedResult expected = testCase.map(AccessTestCase::expected).orElse(ExpectedResult.REVIEW);
        String subject = template.map(AccessRequestTemplate::name)
                .or(() -> testCase.map(AccessTestCase::name))
                .orElse(result.testCaseId());

        EvidenceBundle evidenceBundle = evidenceBundle(result, evidence);
        return new FindingDraft(
                title(result.verdict(), subject),
                severity(result.verdict()),
                template.map(AccessRequestTemplate::name).orElse("Unknown template"),
                template.map(AccessRequestTemplate::baseRequest).map(this::endpoint).orElse("Unknown endpoint"),
                targetAuth.map(this::profileLabel).orElse(missingTargetAuth(testCase)),
                dataProfile.map(DataProfile::name).orElse(missingDataProfile(testCase)),
                owner.map(this::profileLabel).orElse(missingOwner(dataProfile)),
                expected,
                result.verdict(),
                result.reason().isBlank() ? "No evaluation reason captured." : result.reason(),
                evidenceBundle,
                postCheckSummaries(evidence),
                reproductionSteps(testCase, template, targetAuth, dataProfile, expected, result.verdict()),
                "Impact: describe the business impact, affected users, and exposed or modified data.",
                "Remediation: describe the authorization check or access-control change needed.",
                warnings(result, testCase, template, evidence, evidenceBundle));
    }

    private EvidenceBundle evidenceBundle(AccessTestResult result, Optional<AccessEvidence> evidence) {
        String request = evidence.map(AccessEvidence::redactedRequestRaw)
                .filter(value -> !value.isBlank())
                .orElse(firstNonBlank(result.renderedRequestSummary(), REQUEST_UNAVAILABLE));
        String response = evidence.map(AccessEvidence::redactedResponseRaw)
                .filter(value -> !value.isBlank())
                .orElse(firstNonBlank(result.responseSummary(), RESPONSE_UNAVAILABLE));
        if (evidence.isPresent() && !evidence.get().flowStepEvidence().isEmpty()) {
            request = flowStepSummary(evidence.get()) + "\nTarget request:\n" + request;
        }
        return new EvidenceBundle(request, response, true);
    }

    private String flowStepSummary(AccessEvidence evidence) {
        StringBuilder summary = new StringBuilder("Flow steps:\n");
        evidence.flowStepEvidence().forEach(step -> summary.append("- ")
                .append(step.targetStep() ? "[target] " : "")
                .append(step.stepName())
                .append(" status=")
                .append(step.responseStatusCode())
                .append(" success=")
                .append(step.success())
                .append(step.reason().isBlank() ? "" : " reason=" + step.reason())
                .append('\n'));
        return summary.toString();
    }

    private List<String> postCheckSummaries(Optional<AccessEvidence> evidence) {
        if (evidence.isEmpty() || evidence.get().postCheckEvidence().isEmpty()) {
            return List.of();
        }
        ArrayList<String> summaries = new ArrayList<>();
        evidence.get().postCheckEvidence().forEach(entry -> {
            summaries.add(entry.name() + ": " + entry.verdict() + " - " + entry.reason());
            entry.assertionResults().forEach(assertion -> summaries.add("  "
                    + (assertion.passed() ? "PASS" : "FAIL")
                    + " " + assertion.name() + ": " + assertion.reason()));
        });
        return List.copyOf(summaries);
    }

    private List<String> reproductionSteps(
            Optional<AccessTestCase> testCase,
            Optional<AccessRequestTemplate> template,
            Optional<AuthProfile> targetAuth,
            Optional<DataProfile> dataProfile,
            ExpectedResult expected,
            Verdict verdict) {
        ArrayList<String> steps = new ArrayList<>();
        steps.add("Open the access test result for matrix row \""
                + testCase.map(AccessTestCase::name).orElse("unknown") + "\".");
        steps.add("Render and send template \""
                + template.map(AccessRequestTemplate::name).orElse("unknown")
                + "\" as target actor \""
                + targetAuth.map(this::profileLabel).orElse("unknown")
                + "\" against data profile \""
                + dataProfile.map(DataProfile::name).orElse("unknown")
                + "\".");
        steps.add("Compare the expected result " + expected + " with the actual verdict " + verdict + ".");
        steps.add("Review the redacted request and response evidence below.");
        return List.copyOf(steps);
    }

    private List<String> warnings(
            AccessTestResult result,
            Optional<AccessTestCase> testCase,
            Optional<AccessRequestTemplate> template,
            Optional<AccessEvidence> evidence,
            EvidenceBundle evidenceBundle) {
        ArrayList<String> warnings = new ArrayList<>(result.warnings());
        if (testCase.isEmpty()) {
            warnings.add("Source test case not found: " + result.testCaseId());
        }
        if (template.isEmpty()) {
            warnings.add("Source template was not found.");
        }
        if (evidence.isEmpty()) {
            warnings.add("Captured evidence was not found; result summaries were used.");
        }
        if (!evidenceBundle.redacted()) {
            warnings.add("Evidence is not marked as redacted.");
        }
        return List.copyOf(warnings);
    }

    private String endpoint(StoredRequestTemplate request) {
        return request.method() + " " + request.scheme() + "://" + request.host() + ":"
                + request.port() + request.pathAndQuery();
    }

    private String title(Verdict verdict, String subject) {
        return switch (verdict) {
            case SUSPICIOUS -> "Potential broken access control: " + subject;
            case OK -> "Review/manual evidence: " + subject;
            case REVIEW -> "Review required: " + subject;
            case ERROR -> "Execution error evidence: " + subject;
            case NOT_RUN -> "Not-run access test evidence: " + subject;
        };
    }

    private FindingSeverity severity(Verdict verdict) {
        return switch (verdict) {
            case SUSPICIOUS -> FindingSeverity.HIGH;
            case REVIEW, ERROR -> FindingSeverity.MEDIUM;
            case NOT_RUN -> FindingSeverity.LOW;
            case OK -> FindingSeverity.INFO;
        };
    }

    private String profileLabel(AuthProfile profile) {
        return profile.role().isBlank()
                ? profile.name()
                : profile.name() + " (" + profile.role() + ")";
    }

    private String missingTargetAuth(Optional<AccessTestCase> testCase) {
        return testCase.map(AccessTestCase::target)
                .map(target -> target.targetAuthProfileId().isBlank()
                        ? "No target auth profile configured"
                        : "Missing auth profile: " + target.targetAuthProfileId())
                .orElse("Unknown target auth profile");
    }

    private String missingDataProfile(Optional<AccessTestCase> testCase) {
        return testCase.map(AccessTestCase::target)
                .map(target -> target.dataProfileId().isBlank()
                        ? "No data profile configured"
                        : "Missing data profile: " + target.dataProfileId())
                .orElse("Unknown data profile");
    }

    private String missingOwner(Optional<DataProfile> dataProfile) {
        return dataProfile
                .map(profile -> profile.ownerAuthProfileId().isBlank()
                        ? "No object owner configured"
                        : "Missing owner auth profile: " + profile.ownerAuthProfileId())
                .orElse("Unknown object owner");
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}

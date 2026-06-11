package dev.nquangit.corestra.access.report;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertionResult;
import dev.nquangit.corestra.access.postcheck.PostCheckEvidence;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.Verdict;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindingDraftBuilderTest {
    private final FindingDraftBuilder builder = new FindingDraftBuilder();
    private final MarkdownFindingExporter exporter = new MarkdownFindingExporter();

    @Test
    void suspiciousResultGeneratesMarkdownDraftWithRedactedEvidenceAndExpectedVsActual() {
        AccessTestResult result = result(Verdict.SUSPICIOUS, ExpectedResult.DENY);
        AccessEvidence evidence = evidence();

        FindingDraft draft = builder.build(result, Optional.of(evidence), workspace(ExpectedResult.DENY));
        String markdown = exporter.export(draft);

        assertEquals(FindingSeverity.HIGH, draft.severity());
        assertTrue(draft.titleSuggestion().contains("Potential broken access control"));
        assertTrue(draft.evidence().redacted());
        assertTrue(markdown.contains("Affected endpoint"));
        assertTrue(markdown.contains("GET https://example.test:443/accounts/{{data.accountId}}"));
        assertTrue(markdown.contains("Expected result:** DENY"));
        assertTrue(markdown.contains("Actual verdict:** SUSPICIOUS"));
        assertTrue(markdown.contains("Expected deny but received status 200"));
        assertTrue(markdown.contains("Authorization: <redacted>"));
        assertTrue(markdown.contains("\"email\":\"alice@example.test\""));
        assertFalse(markdown.contains("raw-secret-token"));
        assertFalse(markdown.contains("raw-session-secret"));
    }

    @Test
    void okResultCanStillGenerateReviewManualEvidenceTitle() {
        FindingDraft draft = builder.build(
                result(Verdict.OK, ExpectedResult.DENY),
                Optional.of(evidence()),
                workspace(ExpectedResult.DENY));

        assertTrue(draft.titleSuggestion().startsWith("Review/manual evidence:"));
        assertEquals(FindingSeverity.INFO, draft.severity());
    }

    @Test
    void missingEvidenceUsesSanitizedResultSummariesWithoutRawFallback() {
        AccessTestResult result = new AccessTestResult(
                "result-1",
                "case-1",
                RunStatus.COMPLETED,
                Verdict.SUSPICIOUS,
                "Expected deny but received status 200",
                1000L,
                1100L,
                "GET /accounts/<redacted>",
                "HTTP/1.1 200 OK\n\n{\"id\":\"acct-1\"}",
                List.of());

        FindingDraft draft = builder.build(result, Optional.empty(), workspace(ExpectedResult.DENY));
        String markdown = exporter.export(draft);

        assertTrue(markdown.contains("GET /accounts/<redacted>"));
        assertTrue(markdown.contains("Captured evidence was not found"));
        assertFalse(markdown.contains("raw-secret"));
    }

    @Test
    void findingDraftIncludesPostCheckSummaryWhenEvidenceExists() {
        AccessEvidence evidence = new AccessEvidence(
                "case-1",
                "result-1",
                "GET /accounts/acct-1 HTTP/1.1",
                "GET /accounts/acct-1 HTTP/1.1",
                "HTTP/1.1 403 Forbidden",
                "HTTP/1.1 403 Forbidden",
                List.of(new PostCheckEvidence(
                        "post-1",
                        "Check balance",
                        "GET /accounts/acct-1/audit HTTP/1.1",
                        "HTTP/1.1 200 OK\n\n{\"balance\":999}",
                        Verdict.SUSPICIOUS,
                        "Post-check assertion failed",
                        List.of(new PostCheckAssertionResult(
                                "assert-1",
                                "Balance unchanged",
                                false,
                                "JSON path equals 100")),
                        1200L)),
                1200L);

        FindingDraft draft = builder.build(result(Verdict.SUSPICIOUS, ExpectedResult.DENY),
                Optional.of(evidence), workspace(ExpectedResult.DENY));
        String markdown = exporter.export(draft);

        assertTrue(markdown.contains("Post-check Summary"));
        assertTrue(markdown.contains("Check balance: SUSPICIOUS"));
        assertTrue(markdown.contains("FAIL Balance unchanged"));
    }

    @Test
    void markdownExporterEscapesEvidenceFences() {
        FindingDraft draft = new FindingDraft(
                "Review/manual evidence: Fence",
                FindingSeverity.INFO,
                "Template",
                "GET https://example.test:443/fence",
                "Owner",
                "Data",
                "Owner",
                ExpectedResult.REVIEW,
                Verdict.OK,
                "OK",
                new EvidenceBundle("GET /```", "HTTP/1.1 200 OK", true),
                List.of(),
                List.of("Review evidence."),
                "Impact placeholder",
                "Remediation placeholder",
                List.of());

        String markdown = exporter.export(draft);

        assertTrue(markdown.contains("`` `"));
    }

    private static AccessWorkspace workspace(ExpectedResult expected) {
        AuthProfile owner = AuthProfile.manual("auth-owner", "Owner", "user");
        AuthProfile target = AuthProfile.manual("auth-target", "Peer", "user");
        DataProfile data = DataProfile.enabled(
                "data-1",
                "Owner Account",
                owner.id(),
                Map.of("accountId", "acct-1"));
        AccessRequestTemplate template = new AccessRequestTemplate(
                "template-1",
                "Read account",
                new StoredRequestTemplate(
                        "GET",
                        "https",
                        "example.test",
                        443,
                        "/accounts/{{data.accountId}}",
                        List.of(),
                        ""),
                Set.of(),
                OperationMetadata.unknown(),
                "flow-1",
                "step-1",
                "hash-1",
                true);
        AccessTestCase testCase = new AccessTestCase(
                "case-1",
                "Peer reads owner account",
                template.id(),
                "flow-1",
                "step-1",
                SetupConfig.none(),
                new TargetBinding(target.id(), data.id(), "", ""),
                expected,
                List.of(),
                true,
                RunStatus.COMPLETED,
                1100L);
        return AccessWorkspace.builder()
                .authProfile(owner)
                .authProfile(target)
                .dataProfile(data)
                .template(template)
                .testCase(testCase)
                .build();
    }

    private static AccessTestResult result(Verdict verdict, ExpectedResult expected) {
        return new AccessTestResult(
                "result-1",
                "case-1",
                RunStatus.COMPLETED,
                verdict,
                expected == ExpectedResult.DENY
                        ? "Expected deny but received status 200"
                        : "Allowed as expected",
                1000L,
                1100L,
                "GET /accounts/<redacted>",
                "HTTP/1.1 200 OK",
                List.of());
    }

    private static AccessEvidence evidence() {
        return new AccessEvidence(
                "case-1",
                "result-1",
                "GET /accounts/acct-1 HTTP/1.1\nAuthorization: Bearer raw-secret-token",
                "GET /accounts/acct-1 HTTP/1.1\nAuthorization: <redacted>",
                "HTTP/1.1 200 OK\nSet-Cookie: session=raw-session-secret\n\n{\"email\":\"alice@example.test\"}",
                "HTTP/1.1 200 OK\nSet-Cookie: <redacted>\n\n{\"email\":\"alice@example.test\"}",
                1100L);
    }
}

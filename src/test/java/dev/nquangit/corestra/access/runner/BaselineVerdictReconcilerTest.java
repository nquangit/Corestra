package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.RunStatus;
import dev.nquangit.corestra.access.matrix.SetupConfig;
import dev.nquangit.corestra.access.matrix.TargetBinding;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.result.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaselineVerdictReconcilerTest {
    private final BaselineVerdictReconciler reconciler = new BaselineVerdictReconciler();

    @Test
    void denyOkBecomesReviewWhenOwnerBaselineAlsoFailed() {
        AccessTestCase owner = row("owner", "template-1", "auth-owner", "data-1", ExpectedResult.ALLOW);
        AccessTestCase other = row("other", "template-1", "auth-other", "data-1", ExpectedResult.DENY);

        Map<String, AccessTestResult> adjusted = reconciler.reconcile(List.of(other, owner), Map.of(
                "other", result("other", Verdict.OK, "Denied as expected with status 404"),
                "owner", result("owner", Verdict.SUSPICIOUS, "Expected allow but received status 404")));

        assertEquals(1, adjusted.size());
        AccessTestResult downgraded = adjusted.get("other");
        assertEquals(Verdict.REVIEW, downgraded.verdict());
        assertEquals("result-other", downgraded.id());
        assertTrue(downgraded.reason().contains("baseline"));
        assertTrue(downgraded.warnings().stream().anyMatch(warning -> warning.contains("owner")));
    }

    @Test
    void denyOkIsKeptWhenBaselineSucceeded() {
        AccessTestCase owner = row("owner", "template-1", "auth-owner", "data-1", ExpectedResult.ALLOW);
        AccessTestCase other = row("other", "template-1", "auth-other", "data-1", ExpectedResult.DENY);

        assertTrue(reconciler.reconcile(List.of(owner, other), Map.of(
                "other", result("other", Verdict.OK, "Denied"),
                "owner", result("owner", Verdict.OK, "Allowed"))).isEmpty());
    }

    @Test
    void baselineIsMatchedPerTargetAndDataProfile() {
        AccessTestCase ownerData2 = row("owner2", "template-1", "auth-owner", "data-2", ExpectedResult.ALLOW);
        AccessTestCase ownerOtherTemplate = row("owner3", "template-2", "auth-owner", "data-1", ExpectedResult.ALLOW);
        AccessTestCase other = row("other", "template-1", "auth-other", "data-1", ExpectedResult.DENY);

        assertTrue(reconciler.reconcile(List.of(ownerData2, ownerOtherTemplate, other), Map.of(
                "other", result("other", Verdict.OK, "Denied"),
                "owner2", result("owner2", Verdict.SUSPICIOUS, "404"),
                "owner3", result("owner3", Verdict.SUSPICIOUS, "404"))).isEmpty());
    }

    @Test
    void failedBaselineRunAlsoMakesTheDenialInconclusive() {
        AccessTestCase owner = row("owner", "template-1", "auth-owner", "data-1", ExpectedResult.ALLOW);
        AccessTestCase other = row("other", "template-1", "auth-other", "data-1", ExpectedResult.DENY);
        AccessTestResult failed = new AccessTestResult(
                "result-owner", "owner", RunStatus.FAILED, Verdict.ERROR, "Connection refused", 1L, 2L, "", "", List.of());

        assertEquals(Verdict.REVIEW, reconciler.reconcile(List.of(owner, other), Map.of(
                "other", result("other", Verdict.OK, "Denied"),
                "owner", failed)).get("other").verdict());
    }

    @Test
    void withoutBaselineInRunNothingChanges() {
        AccessTestCase other = row("other", "template-1", "auth-other", "data-1", ExpectedResult.DENY);

        assertTrue(reconciler.reconcile(List.of(other), Map.of(
                "other", result("other", Verdict.OK, "Denied"))).isEmpty());
    }

    private static AccessTestCase row(String id, String templateId, String authId, String dataId, ExpectedResult expected) {
        return new AccessTestCase(
                id,
                id,
                templateId,
                "",
                "",
                SetupConfig.none(),
                new TargetBinding(authId, dataId, "", ""),
                expected,
                "",
                List.of(),
                List.of(),
                true,
                RunStatus.PENDING,
                0L);
    }

    private static AccessTestResult result(String rowId, Verdict verdict, String reason) {
        return new AccessTestResult(
                "result-" + rowId, rowId, RunStatus.COMPLETED, verdict, reason, 1L, 2L, "", "", List.of());
    }
}

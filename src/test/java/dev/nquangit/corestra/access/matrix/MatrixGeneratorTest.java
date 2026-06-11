package dev.nquangit.corestra.access.matrix;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.template.OperationMetadata;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.StoredRequestTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatrixGeneratorTest {
    @Test
    void ownerVsOthersGeneratesOwnerPeerAdminAndAnonymousRows() {
        MatrixGenerator generator = generator();
        MatrixGenerationRequest request = new MatrixGenerationRequest(
                MatrixGenerationStrategy.OWNER_VS_OTHERS,
                Set.of("template-1"),
                Set.of("data-1"),
                ExpectedResult.ALLOW,
                ExpectedResult.REVIEW,
                false);

        MatrixGenerationResult result = generator.generate(request, workspace(List.of()));

        assertEquals(4, result.rows().size());
        assertExpected(result, "auth-owner", ExpectedResult.ALLOW);
        assertExpected(result, "auth-peer", ExpectedResult.DENY);
        assertExpected(result, "auth-admin", ExpectedResult.ALLOW);
        assertExpected(result, "auth-anon", ExpectedResult.DENY);
        assertTrue(result.rows().stream().allMatch(row -> !row.enabled()));
        assertTrue(result.rows().stream().allMatch(row -> row.status() == RunStatus.PENDING));
        assertEquals(0, result.duplicateCount());
    }

    @Test
    void anonymousAccessUsesSelectedTemplateAndDataOnly() {
        MatrixGenerator generator = generator();
        MatrixGenerationRequest request = new MatrixGenerationRequest(
                MatrixGenerationStrategy.ANONYMOUS_ACCESS,
                Set.of("template-2"),
                Set.of("data-2"),
                ExpectedResult.REVIEW,
                ExpectedResult.REVIEW,
                false);

        MatrixGenerationResult result = generator.generate(request, workspace(List.of()));

        assertEquals(1, result.rows().size());
        AccessTestCase row = result.rows().getFirst();
        assertEquals("template-2", row.templateId());
        assertEquals("auth-anon", row.target().targetAuthProfileId());
        assertEquals("data-2", row.target().dataProfileId());
        assertEquals(ExpectedResult.DENY, row.expected());
        assertFalse(row.enabled());
    }

    @Test
    void roleEscalationGeneratesLowerRoleRowsAgainstOtherOwnedData() {
        MatrixGenerator generator = generator();
        MatrixGenerationRequest request = new MatrixGenerationRequest(
                MatrixGenerationStrategy.ROLE_ESCALATION,
                Set.of("template-1"),
                Set.of("data-1"),
                ExpectedResult.REVIEW,
                ExpectedResult.DENY,
                false);

        MatrixGenerationResult result = generator.generate(request, workspace(List.of()));

        assertEquals(1, result.rows().size());
        AccessTestCase row = result.rows().getFirst();
        assertEquals("auth-peer", row.target().targetAuthProfileId());
        assertEquals("data-1", row.target().dataProfileId());
        assertEquals(ExpectedResult.DENY, row.expected());
    }

    @Test
    void duplicateDetectionSkipsSameTemplateAuthDataAndSetupConfig() {
        MatrixGenerator generator = generator();
        AccessTestCase existing = existing("template-1", "auth-peer", "data-1", SetupConfig.none());
        MatrixGenerationRequest request = new MatrixGenerationRequest(
                MatrixGenerationStrategy.OWNER_VS_OTHERS,
                Set.of("template-1"),
                Set.of("data-1"),
                ExpectedResult.REVIEW,
                ExpectedResult.REVIEW,
                false);

        MatrixGenerationResult result = generator.generate(request, workspace(List.of(existing)));

        assertEquals(3, result.rows().size());
        assertEquals(1, result.duplicateCount());
        assertEquals(1, result.skippedCount());
        assertTrue(result.skipReasons().stream().anyMatch(skip -> skip.reason().contains("Duplicate generated row")));
        assertTrue(result.rows().stream()
                .noneMatch(row -> "auth-peer".equals(row.target().targetAuthProfileId())));
    }

    @Test
    void duplicateDetectionAllowsSameTargetWithDifferentSetupConfig() {
        MatrixGenerator generator = generator();
        SetupConfig setup = new SetupConfig(
                SetupMode.RUN_PREFIX,
                "flow-setup",
                "step-target",
                "",
                SetupAuthBinding.DATA_OWNER,
                ScenarioPolicy.REUSE_EXISTING);
        AccessTestCase existing = existing("template-1", "auth-peer", "data-1", setup);
        MatrixGenerationRequest request = new MatrixGenerationRequest(
                MatrixGenerationStrategy.OWNER_VS_OTHERS,
                Set.of("template-1"),
                Set.of("data-1"),
                ExpectedResult.REVIEW,
                ExpectedResult.REVIEW,
                false);

        MatrixGenerationResult result = generator.generate(request, workspace(List.of(existing)));

        assertEquals(4, result.rows().size());
        assertEquals(0, result.duplicateCount());
        assertTrue(result.rows().stream()
                .anyMatch(row -> "auth-peer".equals(row.target().targetAuthProfileId())));
    }

    @Test
    void duplicateDetectionIncludesScenarioInstanceId() {
        MatrixGenerator generator = generator();
        AccessTestCase existing = existingWithScenario(
                "template-1",
                "auth-peer",
                "data-1",
                SetupConfig.none(),
                "scenario-1");
        MatrixGenerationRequest request = new MatrixGenerationRequest(
                MatrixGenerationStrategy.OWNER_VS_OTHERS,
                Set.of("template-1"),
                Set.of("data-1"),
                ExpectedResult.REVIEW,
                ExpectedResult.REVIEW,
                false);

        MatrixGenerationResult result = generator.generate(request, workspace(List.of(existing)));

        assertEquals(4, result.rows().size());
        assertEquals(0, result.duplicateCount());
    }

    @Test
    void makerCheckerGeneratesSelfDenyCheckerReviewCrossTenantDenyAndAdminReview() {
        MatrixGenerator generator = generator();
        MatrixGenerationRequest request = advancedRequest(
                MatrixGenerationStrategy.MAKER_CHECKER,
                Set.of("template-1"),
                Set.of("data-maker-a"),
                Set.of("auth-maker-a", "auth-checker-a", "auth-checker-x", "auth-admin"));

        MatrixGenerationResult result = generator.generate(request, advancedWorkspace(List.of()));

        assertEquals(4, result.rows().size());
        assertExpected(result, "auth-maker-a", ExpectedResult.DENY);
        assertExpected(result, "auth-checker-a", ExpectedResult.REVIEW);
        assertExpected(result, "auth-checker-x", ExpectedResult.DENY);
        assertExpected(result, "auth-admin", ExpectedResult.REVIEW);
        assertTrue(result.generatedRows().stream()
                .allMatch(row -> row.generationStrategy() == MatrixGenerationStrategy.MAKER_CHECKER));
    }

    @Test
    void crossTenantUsesTenantMetadataAndReportsMissingMetadataSkips() {
        MatrixGenerator generator = generator();
        MatrixGenerationRequest request = advancedRequest(
                MatrixGenerationStrategy.CROSS_TENANT,
                Set.of("template-1"),
                Set.of("data-maker-a", "data-missing"),
                Set.of("auth-maker-a", "auth-checker-x", "auth-no-data"));

        MatrixGenerationResult result = generator.generate(request, advancedWorkspace(List.of()));

        assertEquals(2, result.rows().size());
        assertExpected(result, "auth-maker-a", ExpectedResult.REVIEW);
        assertExpected(result, "auth-checker-x", ExpectedResult.DENY);
        assertEquals(2, result.skippedCount());
        assertTrue(result.skipReasons().stream().anyMatch(skip -> skip.objectType().equals("DataProfile")));
        assertTrue(result.skipReasons().stream().anyMatch(skip -> skip.objectType().equals("AuthProfile")));
    }

    @Test
    void crossBranchUsesBranchMetadata() {
        MatrixGenerator generator = generator();
        MatrixGenerationRequest request = advancedRequest(
                MatrixGenerationStrategy.CROSS_BRANCH,
                Set.of("template-1"),
                Set.of("data-maker-a"),
                Set.of("auth-checker-a", "auth-checker-b"));

        MatrixGenerationResult result = generator.generate(request, advancedWorkspace(List.of()));

        assertEquals(2, result.rows().size());
        assertExpected(result, "auth-checker-a", ExpectedResult.REVIEW);
        assertExpected(result, "auth-checker-b", ExpectedResult.DENY);
        assertTrue(result.generatedRows().stream()
                .anyMatch(row -> row.generationReason().contains("branch")));
    }

    @Test
    void crossRoleGeneratesRowsForDifferentOwnerRolesOnly() {
        MatrixGenerator generator = generator();
        MatrixGenerationRequest request = advancedRequest(
                MatrixGenerationStrategy.CROSS_ROLE,
                Set.of("template-1"),
                Set.of("data-manager"),
                Set.of("auth-maker-a", "auth-checker-a", "auth-admin", "auth-anon"));

        MatrixGenerationResult result = generator.generate(request, advancedWorkspace(List.of()));

        assertEquals(2, result.rows().size());
        assertExpected(result, "auth-maker-a", ExpectedResult.DENY);
        assertExpected(result, "auth-checker-a", ExpectedResult.DENY);
        assertTrue(result.rows().stream().noneMatch(row -> "auth-admin".equals(row.target().targetAuthProfileId())));
        assertTrue(result.rows().stream().noneMatch(row -> "auth-anon".equals(row.target().targetAuthProfileId())));
    }

    @Test
    void objectOwnershipVariantsRespectSameRoleAndMetadataFilters() {
        MatrixGenerator generator = generator();
        MatrixGenerationRequest request = advancedRequest(
                MatrixGenerationStrategy.OBJECT_OWNERSHIP_VARIANTS,
                Set.of("template-1"),
                Set.of("data-maker-a"),
                Set.of("auth-maker-a", "auth-maker-b", "auth-checker-a", "auth-checker-x",
                        "auth-admin", "auth-anon"));

        MatrixGenerationResult result = generator.generate(request, advancedWorkspace(List.of()));

        assertEquals(4, result.rows().size());
        assertExpected(result, "auth-maker-a", ExpectedResult.ALLOW);
        assertExpected(result, "auth-maker-b", ExpectedResult.DENY);
        assertExpected(result, "auth-admin", ExpectedResult.REVIEW);
        assertExpected(result, "auth-anon", ExpectedResult.DENY);
        assertTrue(result.rows().stream().noneMatch(row -> "auth-checker-a".equals(row.target().targetAuthProfileId())));
        assertTrue(result.rows().stream().noneMatch(row -> "auth-checker-x".equals(row.target().targetAuthProfileId())));
        assertTrue(result.skippedCount() >= 2);
    }

    private static void assertExpected(MatrixGenerationResult result, String authProfileId, ExpectedResult expected) {
        AccessTestCase row = result.rows().stream()
                .filter(candidate -> authProfileId.equals(candidate.target().targetAuthProfileId()))
                .findFirst()
                .orElseThrow();
        assertEquals(expected, row.expected());
    }

    private static MatrixGenerator generator() {
        AtomicInteger counter = new AtomicInteger();
        AccessIdGenerator ids = prefix -> prefix + "-" + counter.incrementAndGet();
        return new MatrixGenerator(ids);
    }

    private static MatrixGenerationRequest advancedRequest(
            MatrixGenerationStrategy strategy,
            Set<String> templateIds,
            Set<String> dataProfileIds,
            Set<String> authProfileIds) {
        return new MatrixGenerationRequest(
                strategy,
                templateIds,
                dataProfileIds,
                authProfileIds,
                "tenantId",
                "branchId",
                "MAKER",
                "CHECKER",
                "ADMIN",
                ExpectedResult.REVIEW,
                ExpectedResult.DENY,
                ExpectedResult.DENY,
                ExpectedResult.DENY,
                ExpectedResult.DENY,
                ExpectedResult.REVIEW,
                ExpectedResult.REVIEW,
                true,
                false,
                false,
                strategy == MatrixGenerationStrategy.CROSS_TENANT,
                strategy == MatrixGenerationStrategy.CROSS_BRANCH,
                false);
    }

    private static AccessWorkspace workspace(List<AccessTestCase> existingRows) {
        AccessWorkspace.Builder builder = AccessWorkspace.builder()
                .authProfile(AuthProfile.manual("auth-owner", "Owner", "user"))
                .authProfile(AuthProfile.manual("auth-peer", "Peer", "user"))
                .authProfile(AuthProfile.manual("auth-admin", "Admin", "admin"))
                .authProfile(AuthProfile.manual("auth-anon", "Anonymous", "anonymous"))
                .dataProfile(DataProfile.enabled("data-1", "Owner data", "auth-owner", Map.of("accountId", "acct-1")))
                .dataProfile(DataProfile.enabled("data-2", "Second data", "auth-owner", Map.of("accountId", "acct-2")))
                .template(template("template-1", "/accounts/{{data.accountId}}"))
                .template(template("template-2", "/profile/{{data.accountId}}"));
        existingRows.forEach(builder::testCase);
        return builder.build();
    }

    private static AccessWorkspace advancedWorkspace(List<AccessTestCase> existingRows) {
        AccessWorkspace.Builder builder = AccessWorkspace.builder()
                .authProfile(AuthProfile.manual("auth-maker-a", "Maker A", "MAKER"))
                .authProfile(AuthProfile.manual("auth-maker-b", "Maker B", "MAKER"))
                .authProfile(AuthProfile.manual("auth-checker-a", "Checker A", "CHECKER"))
                .authProfile(AuthProfile.manual("auth-checker-b", "Checker B", "CHECKER"))
                .authProfile(AuthProfile.manual("auth-checker-x", "Checker X", "CHECKER"))
                .authProfile(AuthProfile.manual("auth-manager", "Manager", "MANAGER"))
                .authProfile(AuthProfile.manual("auth-admin", "Admin", "ADMIN"))
                .authProfile(AuthProfile.manual("auth-anon", "Anonymous", "anonymous"))
                .authProfile(AuthProfile.manual("auth-no-data", "No Metadata", "MAKER"))
                .dataProfile(DataProfile.enabled("data-maker-a", "Maker A data", "auth-maker-a",
                        Map.of("accountId", "acct-a", "tenantId", "tenant-a", "branchId", "branch-a")))
                .dataProfile(DataProfile.enabled("data-maker-b", "Maker B data", "auth-maker-b",
                        Map.of("accountId", "acct-b", "tenantId", "tenant-a", "branchId", "branch-a")))
                .dataProfile(DataProfile.enabled("data-checker-a", "Checker A data", "auth-checker-a",
                        Map.of("accountId", "acct-c", "tenantId", "tenant-a", "branchId", "branch-a")))
                .dataProfile(DataProfile.enabled("data-checker-b", "Checker B data", "auth-checker-b",
                        Map.of("accountId", "acct-d", "tenantId", "tenant-a", "branchId", "branch-b")))
                .dataProfile(DataProfile.enabled("data-checker-x", "Checker X data", "auth-checker-x",
                        Map.of("accountId", "acct-x", "tenantId", "tenant-x", "branchId", "branch-x")))
                .dataProfile(DataProfile.enabled("data-manager", "Manager data", "auth-manager",
                        Map.of("accountId", "acct-m", "tenantId", "tenant-a", "branchId", "branch-a")))
                .dataProfile(DataProfile.enabled("data-missing", "Missing metadata", "auth-maker-a",
                        Map.of("accountId", "acct-missing")))
                .template(template("template-1", "/accounts/{{data.accountId}}"));
        existingRows.forEach(builder::testCase);
        return builder.build();
    }

    private static AccessRequestTemplate template(String id, String path) {
        return new AccessRequestTemplate(
                id,
                "Template " + id,
                new StoredRequestTemplate("GET", "https", "example.test", 443, path, List.of(), ""),
                Set.of(new ScopedVariableRef(VariableScope.DATA, "accountId")),
                OperationMetadata.unknown(),
                "flow-1",
                "step-1",
                "hash-" + id,
                true);
    }

    private static AccessTestCase existing(
            String templateId,
            String authProfileId,
            String dataProfileId,
            SetupConfig setup) {
        return existingWithScenario(templateId, authProfileId, dataProfileId, setup, "");
    }

    private static AccessTestCase existingWithScenario(
            String templateId,
            String authProfileId,
            String dataProfileId,
            SetupConfig setup,
            String scenarioInstanceId) {
        return new AccessTestCase(
                "case-existing",
                "Existing",
                templateId,
                "flow-1",
                "step-1",
                setup,
                new TargetBinding(authProfileId, dataProfileId, "", scenarioInstanceId),
                ExpectedResult.DENY,
                List.of(),
                true,
                RunStatus.PENDING,
                0L);
    }
}

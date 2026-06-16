package dev.nquangit.corestra.access.matrix;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Describes a request to generate suggested access matrix rows.
 */
public final class MatrixGenerationRequest {
    private final MatrixGenerationStrategy strategy;
    private final Set<String> templateIds;
    private final Set<String> dataProfileIds;
    private final Set<String> authProfileIds;
    private final String tenantKey;
    private final String branchKey;
    private final String makerRole;
    private final String checkerRole;
    private final String adminRole;
    private final ExpectedResult adminExpectedResult;
    private final ExpectedResult roleEscalationExpectedResult;
    private final ExpectedResult defaultCrossTenantExpected;
    private final ExpectedResult defaultCrossBranchExpected;
    private final ExpectedResult defaultMakerSelfApproveExpected;
    private final ExpectedResult defaultCheckerApproveExpected;
    private final ExpectedResult defaultReviewExpected;
    private final boolean sameRoleOnly;
    private final boolean sameTenantOnly;
    private final boolean sameBranchOnly;
    private final boolean includeCrossTenant;
    private final boolean includeCrossBranch;
    private final boolean generatedRowsEnabled;

    /**
     * Creates a matrix generation request.
     *
     * <p>Empty template or data selections mean "all enabled" for the selected strategy.</p>
     *
     * @param strategy generation strategy
     * @param templateIds selected template ids, or empty for all enabled templates
     * @param dataProfileIds selected data profile ids, or empty for all enabled data profiles
     * @param adminExpectedResult expected result for admin rows in owner-vs-others generation
     * @param roleEscalationExpectedResult expected result for lower-role rows in role-escalation generation
     * @param generatedRowsEnabled whether generated rows should be enabled
     */
    public MatrixGenerationRequest(
            MatrixGenerationStrategy strategy,
            Set<String> templateIds,
            Set<String> dataProfileIds,
            ExpectedResult adminExpectedResult,
            ExpectedResult roleEscalationExpectedResult,
            boolean generatedRowsEnabled) {
        this(strategy, templateIds, dataProfileIds, Set.of(), "tenantId", "branchId", "MAKER", "CHECKER", "ADMIN",
                adminExpectedResult, roleEscalationExpectedResult, ExpectedResult.DENY, ExpectedResult.DENY,
                ExpectedResult.DENY, ExpectedResult.REVIEW, ExpectedResult.REVIEW, true, false, false,
                strategy == MatrixGenerationStrategy.CROSS_TENANT, strategy == MatrixGenerationStrategy.CROSS_BRANCH,
                generatedRowsEnabled);
    }

    /**
     * Creates an advanced matrix generation request.
     *
     * @param strategy generation strategy
     * @param templateIds selected template ids, or empty for all enabled templates
     * @param dataProfileIds selected data profile ids, or empty for all enabled data profiles
     * @param authProfileIds selected auth profile ids, or empty for all enabled auth profiles
     * @param tenantKey metadata key for tenant id
     * @param branchKey metadata key for branch id
     * @param makerRole maker role label
     * @param checkerRole checker role label
     * @param adminRole admin role label
     * @param adminExpectedResult expected result for admin rows
     * @param roleEscalationExpectedResult expected result for cross-role rows
     * @param defaultCrossTenantExpected expected result for cross-tenant rows
     * @param defaultCrossBranchExpected expected result for cross-branch rows
     * @param defaultMakerSelfApproveExpected expected result for maker self-approval
     * @param defaultCheckerApproveExpected expected result for checker approval
     * @param defaultReviewExpected default uncertain expected result
     * @param sameRoleOnly whether object ownership variants stay within one role
     * @param sameTenantOnly whether object ownership variants stay within one tenant
     * @param sameBranchOnly whether object ownership variants stay within one branch
     * @param includeCrossTenant whether object ownership variants include cross-tenant pairs
     * @param includeCrossBranch whether object ownership variants include cross-branch pairs
     * @param generatedRowsEnabled whether generated rows should be enabled
     */
    public MatrixGenerationRequest(
            MatrixGenerationStrategy strategy,
            Set<String> templateIds,
            Set<String> dataProfileIds,
            Set<String> authProfileIds,
            String tenantKey,
            String branchKey,
            String makerRole,
            String checkerRole,
            String adminRole,
            ExpectedResult adminExpectedResult,
            ExpectedResult roleEscalationExpectedResult,
            ExpectedResult defaultCrossTenantExpected,
            ExpectedResult defaultCrossBranchExpected,
            ExpectedResult defaultMakerSelfApproveExpected,
            ExpectedResult defaultCheckerApproveExpected,
            ExpectedResult defaultReviewExpected,
            boolean sameRoleOnly,
            boolean sameTenantOnly,
            boolean sameBranchOnly,
            boolean includeCrossTenant,
            boolean includeCrossBranch,
            boolean generatedRowsEnabled) {
        this.strategy = Objects.requireNonNullElse(strategy, MatrixGenerationStrategy.OWNER_VS_OTHERS);
        this.templateIds = copyIds(templateIds);
        this.dataProfileIds = copyIds(dataProfileIds);
        this.authProfileIds = copyIds(authProfileIds);
        this.tenantKey = normalizeOptionalText(tenantKey, "tenantId");
        this.branchKey = normalizeOptionalText(branchKey, "branchId");
        this.makerRole = normalizeOptionalText(makerRole, "MAKER");
        this.checkerRole = normalizeOptionalText(checkerRole, "CHECKER");
        this.adminRole = normalizeOptionalText(adminRole, "ADMIN");
        this.adminExpectedResult = Objects.requireNonNullElse(adminExpectedResult, ExpectedResult.REVIEW);
        this.roleEscalationExpectedResult = Objects.requireNonNullElse(roleEscalationExpectedResult, ExpectedResult.REVIEW);
        this.defaultCrossTenantExpected = Objects.requireNonNullElse(defaultCrossTenantExpected, ExpectedResult.DENY);
        this.defaultCrossBranchExpected = Objects.requireNonNullElse(defaultCrossBranchExpected, ExpectedResult.DENY);
        this.defaultMakerSelfApproveExpected = Objects.requireNonNullElse(defaultMakerSelfApproveExpected, ExpectedResult.DENY);
        this.defaultCheckerApproveExpected = Objects.requireNonNullElse(defaultCheckerApproveExpected, ExpectedResult.REVIEW);
        this.defaultReviewExpected = Objects.requireNonNullElse(defaultReviewExpected, ExpectedResult.REVIEW);
        this.sameRoleOnly = sameRoleOnly;
        this.sameTenantOnly = sameTenantOnly;
        this.sameBranchOnly = sameBranchOnly;
        this.includeCrossTenant = includeCrossTenant || this.strategy == MatrixGenerationStrategy.CROSS_TENANT;
        this.includeCrossBranch = includeCrossBranch || this.strategy == MatrixGenerationStrategy.CROSS_BRANCH;
        this.generatedRowsEnabled = generatedRowsEnabled;
    }

    /**
     * Creates a default request for one strategy.
     *
     * @param strategy generation strategy
     * @return default generation request
     */
    public static MatrixGenerationRequest forStrategy(MatrixGenerationStrategy strategy) {
        return new MatrixGenerationRequest(strategy, Set.of(), Set.of(),
                ExpectedResult.REVIEW, ExpectedResult.REVIEW, false);
    }

    /**
     * Returns the generation strategy.
     *
     * @return generation strategy
     */
    public MatrixGenerationStrategy strategy() {
        return strategy;
    }

    /**
     * Returns selected template ids.
     *
     * @return immutable selected template ids, or empty for all enabled templates
     */
    public Set<String> templateIds() {
        return templateIds;
    }

    /**
     * Returns selected template ids.
     *
     * @return immutable selected template ids, or empty for all enabled templates
     */
    public Set<String> selectedTemplateIds() {
        return templateIds;
    }

    /**
     * Returns selected data profile ids.
     *
     * @return immutable selected data profile ids, or empty for all enabled data profiles
     */
    public Set<String> dataProfileIds() {
        return dataProfileIds;
    }

    /**
     * Returns selected data profile ids.
     *
     * @return immutable selected data profile ids, or empty for all enabled data profiles
     */
    public Set<String> selectedDataProfileIds() {
        return dataProfileIds;
    }

    /**
     * Returns selected auth profile ids.
     *
     * @return immutable selected auth profile ids, or empty for all enabled auth profiles
     */
    public Set<String> authProfileIds() {
        return authProfileIds;
    }

    /**
     * Returns selected auth profile ids.
     *
     * @return immutable selected auth profile ids, or empty for all enabled auth profiles
     */
    public Set<String> selectedAuthProfileIds() {
        return authProfileIds;
    }

    /**
     * Returns the tenant metadata key.
     *
     * @return tenant metadata key
     */
    public String tenantKey() {
        return tenantKey;
    }

    /**
     * Returns the branch metadata key.
     *
     * @return branch metadata key
     */
    public String branchKey() {
        return branchKey;
    }

    /**
     * Returns the maker role label.
     *
     * @return maker role label
     */
    public String makerRole() {
        return makerRole;
    }

    /**
     * Returns the checker role label.
     *
     * @return checker role label
     */
    public String checkerRole() {
        return checkerRole;
    }

    /**
     * Returns the admin role label.
     *
     * @return admin role label
     */
    public String adminRole() {
        return adminRole;
    }

    /**
     * Returns the expected result for admin rows in owner-vs-others generation.
     *
     * @return admin expected result
     */
    public ExpectedResult adminExpectedResult() {
        return adminExpectedResult;
    }

    /**
     * Returns the expected result for role-escalation rows.
     *
     * @return role-escalation expected result
     */
    public ExpectedResult roleEscalationExpectedResult() {
        return roleEscalationExpectedResult;
    }

    /**
     * Returns expected result for cross-tenant rows.
     *
     * @return cross-tenant expected result
     */
    public ExpectedResult defaultCrossTenantExpected() {
        return defaultCrossTenantExpected;
    }

    /**
     * Returns expected result for cross-branch rows.
     *
     * @return cross-branch expected result
     */
    public ExpectedResult defaultCrossBranchExpected() {
        return defaultCrossBranchExpected;
    }

    /**
     * Returns expected result for maker self-approval rows.
     *
     * @return maker self-approval expected result
     */
    public ExpectedResult defaultMakerSelfApproveExpected() {
        return defaultMakerSelfApproveExpected;
    }

    /**
     * Returns expected result for checker approval rows.
     *
     * @return checker approval expected result
     */
    public ExpectedResult defaultCheckerApproveExpected() {
        return defaultCheckerApproveExpected;
    }

    /**
     * Returns default expected result for uncertain rows.
     *
     * @return default review expected result
     */
    public ExpectedResult defaultReviewExpected() {
        return defaultReviewExpected;
    }

    /**
     * Returns whether object ownership variants stay within one role.
     *
     * @return true when same role only
     */
    public boolean sameRoleOnly() {
        return sameRoleOnly;
    }

    /**
     * Returns whether object ownership variants stay within one tenant.
     *
     * @return true when same tenant only
     */
    public boolean sameTenantOnly() {
        return sameTenantOnly;
    }

    /**
     * Returns whether object ownership variants stay within one branch.
     *
     * @return true when same branch only
     */
    public boolean sameBranchOnly() {
        return sameBranchOnly;
    }

    /**
     * Returns whether object ownership variants may include cross-tenant pairs.
     *
     * @return true when cross-tenant pairs are included
     */
    public boolean includeCrossTenant() {
        return includeCrossTenant;
    }

    /**
     * Returns whether object ownership variants may include cross-branch pairs.
     *
     * @return true when cross-branch pairs are included
     */
    public boolean includeCrossBranch() {
        return includeCrossBranch;
    }

    /**
     * Returns whether generated rows should be enabled.
     *
     * @return true when generated rows should be enabled
     */
    public boolean generatedRowsEnabled() {
        return generatedRowsEnabled;
    }

    private static Set<String> copyIds(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> copied = new LinkedHashSet<>();
        ids.forEach(id -> {
            if (id != null && !id.isBlank()) {
                copied.add(id.strip());
            }
        });
        return Set.copyOf(copied);
    }

    private static String normalizeOptionalText(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.strip();
    }
}

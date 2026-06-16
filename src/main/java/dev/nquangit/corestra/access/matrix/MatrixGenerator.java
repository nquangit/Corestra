package dev.nquangit.corestra.access.matrix;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.id.AccessIdGenerator;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.VariableScope;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates suggested access matrix rows from existing auth profiles, data profiles, and templates.
 */
public final class MatrixGenerator {
    private final AccessIdGenerator idGenerator;

    /**
     * Creates a matrix generator.
     *
     * @param idGenerator id generator used for suggested row ids
     */
    public MatrixGenerator(AccessIdGenerator idGenerator) {
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    /**
     * Generates suggested matrix rows for the supplied request.
     *
     * @param request generation request
     * @param workspace access workspace snapshot
     * @return generated matrix result
     */
    public MatrixGenerationResult generate(MatrixGenerationRequest request, AccessWorkspace workspace) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(workspace, "workspace");
        GenerationState state = new GenerationState(workspace.testCases().values());
        List<AccessRequestTemplate> templates = selectedTemplates(request, workspace);
        List<DataProfile> dataProfiles = selectedDataProfiles(request, workspace);
        List<DataProfile> allEnabledDataProfiles = enabledDataProfiles(workspace);
        List<AuthProfile> authProfiles = selectedAuthProfiles(request, workspace);
        List<String> warnings = new ArrayList<>();

        if (templates.isEmpty()) {
            warnings.add("No enabled access request template was selected.");
        }

        switch (request.strategy()) {
            case OWNER_VS_OTHERS ->
                    generateOwnerVsOthers(request, templates, dataProfiles, authProfiles, state, warnings);
            case ANONYMOUS_ACCESS ->
                    generateAnonymousAccess(request, templates, dataProfiles, authProfiles, state, warnings);
            case ROLE_ESCALATION ->
                    generateRoleEscalation(request, templates, dataProfiles, authProfiles, state, warnings);
            case MAKER_CHECKER ->
                    generateMakerChecker(request, templates, dataProfiles, allEnabledDataProfiles, authProfiles,
                            state, warnings);
            case CROSS_TENANT ->
                    generateCrossTenant(request, templates, dataProfiles, allEnabledDataProfiles, authProfiles,
                            state, warnings);
            case CROSS_BRANCH ->
                    generateCrossBranch(request, templates, dataProfiles, allEnabledDataProfiles, authProfiles,
                            state, warnings);
            case CROSS_ROLE ->
                    generateCrossRole(request, templates, dataProfiles, authProfiles, state, warnings);
            case OBJECT_OWNERSHIP_VARIANTS ->
                    generateObjectOwnershipVariants(request, templates, dataProfiles, allEnabledDataProfiles,
                            authProfiles, state, warnings);
        }

        return new MatrixGenerationResult(
                state.rows(),
                state.duplicateCount(),
                warnings,
                state.skippedCount(),
                state.skipReasons());
    }

    /**
     * Returns whether a candidate duplicates any existing matrix row by matrix duplicate key.
     *
     * @param candidate candidate row
     * @param existingRows existing rows
     * @return true when a duplicate exists
     */
    public boolean isDuplicate(AccessTestCase candidate, List<AccessTestCase> existingRows) {
        Objects.requireNonNull(candidate, "candidate");
        if (existingRows == null || existingRows.isEmpty()) {
            return false;
        }
        MatrixDuplicateKey key = MatrixDuplicateKey.from(candidate);
        return existingRows.stream().map(MatrixDuplicateKey::from).anyMatch(key::equals);
    }

    private void generateOwnerVsOthers(
            MatrixGenerationRequest request,
            List<AccessRequestTemplate> templates,
            List<DataProfile> dataProfiles,
            List<AuthProfile> authProfiles,
            GenerationState state,
            List<String> warnings) {
        if (dataProfiles.isEmpty()) {
            warnings.add("OWNER_VS_OTHERS requires at least one enabled data profile.");
            return;
        }
        for (AccessRequestTemplate template : templates) {
            for (DataProfile dataProfile : dataProfiles) {
                AuthProfile owner = authById(authProfiles, dataProfile.ownerAuthProfileId());
                if (owner != null) {
                    state.add(row(template, owner, dataProfile, ExpectedResult.ALLOW, request.generatedRowsEnabled(),
                                    "Owner allowed"),
                            MatrixGenerationStrategy.OWNER_VS_OTHERS,
                            "Owner auth profile owns selected data",
                            GenerationConfidence.HIGH);
                } else {
                    warnings.add("Data profile has no enabled owner auth profile: " + dataProfile.name());
                }

                String ownerRole = owner == null ? "" : owner.role();
                for (AuthProfile authProfile : authProfiles) {
                    if (owner != null && owner.id().equals(authProfile.id())) {
                        continue;
                    }
                    if (isAnonymous(authProfile)) {
                        state.add(row(template, authProfile, dataProfile, ExpectedResult.DENY,
                                        request.generatedRowsEnabled(), "Anonymous denied"),
                                MatrixGenerationStrategy.OWNER_VS_OTHERS,
                                "Anonymous access to owned data",
                                GenerationConfidence.HIGH);
                    } else if (isAdmin(authProfile)) {
                        state.add(row(template, authProfile, dataProfile, request.adminExpectedResult(),
                                        request.generatedRowsEnabled(), "Admin review"),
                                MatrixGenerationStrategy.OWNER_VS_OTHERS,
                                "Admin access policy depends on application rules",
                                GenerationConfidence.REVIEW_REQUIRED);
                    } else if (sameUserRole(ownerRole, authProfile)) {
                        state.add(row(template, authProfile, dataProfile, ExpectedResult.DENY,
                                        request.generatedRowsEnabled(), "Peer denied"),
                                MatrixGenerationStrategy.OWNER_VS_OTHERS,
                                "Same-role peer attempting access to another owner's data",
                                GenerationConfidence.HIGH);
                    }
                }
            }
        }
    }

    private void generateAnonymousAccess(
            MatrixGenerationRequest request,
            List<AccessRequestTemplate> templates,
            List<DataProfile> dataProfiles,
            List<AuthProfile> authProfiles,
            GenerationState state,
            List<String> warnings) {
        List<AuthProfile> anonymousProfiles = authProfiles.stream().filter(this::isAnonymous).toList();
        if (anonymousProfiles.isEmpty()) {
            warnings.add("ANONYMOUS_ACCESS requires an enabled anonymous auth profile.");
            return;
        }
        for (AccessRequestTemplate template : templates) {
            List<DataProfile> targets = dataTargetsForTemplate(template, dataProfiles, warnings);
            for (AuthProfile authProfile : anonymousProfiles) {
                for (DataProfile dataProfile : targets) {
                    state.add(row(template, authProfile, dataProfile, ExpectedResult.DENY, request.generatedRowsEnabled(),
                                    "Anonymous denied"),
                            MatrixGenerationStrategy.ANONYMOUS_ACCESS,
                            "Anonymous actor against selected target",
                            GenerationConfidence.HIGH);
                }
            }
        }
    }

    private void generateRoleEscalation(
            MatrixGenerationRequest request,
            List<AccessRequestTemplate> templates,
            List<DataProfile> dataProfiles,
            List<AuthProfile> authProfiles,
            GenerationState state,
            List<String> warnings) {
        List<AuthProfile> lowerRoleProfiles = authProfiles.stream()
                .filter(authProfile -> !isAdmin(authProfile))
                .filter(authProfile -> !isAnonymous(authProfile))
                .toList();
        if (lowerRoleProfiles.isEmpty()) {
            warnings.add("ROLE_ESCALATION requires at least one enabled non-admin auth profile.");
            return;
        }
        for (AccessRequestTemplate template : templates) {
            List<DataProfile> targets = dataTargetsForTemplate(template, dataProfiles, warnings);
            for (AuthProfile authProfile : lowerRoleProfiles) {
                for (DataProfile dataProfile : targets) {
                    if (dataProfile != null && authProfile.id().equals(dataProfile.ownerAuthProfileId())) {
                        continue;
                    }
                    state.add(row(template, authProfile, dataProfile, request.roleEscalationExpectedResult(),
                                    request.generatedRowsEnabled(), "Role escalation"),
                            MatrixGenerationStrategy.ROLE_ESCALATION,
                            "Non-admin role attempting selected protected template",
                            GenerationConfidence.MEDIUM);
                }
            }
        }
    }

    private void generateMakerChecker(
            MatrixGenerationRequest request,
            List<AccessRequestTemplate> templates,
            List<DataProfile> dataProfiles,
            List<DataProfile> allDataProfiles,
            List<AuthProfile> authProfiles,
            GenerationState state,
            List<String> warnings) {
        if (dataProfiles.isEmpty()) {
            warnings.add("MAKER_CHECKER requires data profiles with maker-owned business objects.");
            return;
        }
        List<AuthProfile> makers = authProfiles.stream()
                .filter(profile -> roleMatches(profile, request.makerRole()))
                .toList();
        List<AuthProfile> checkers = authProfiles.stream()
                .filter(profile -> roleMatches(profile, request.checkerRole()))
                .toList();
        List<AuthProfile> admins = authProfiles.stream()
                .filter(profile -> isConfiguredAdmin(profile, request))
                .toList();
        if (makers.isEmpty()) {
            warnings.add("MAKER_CHECKER found no enabled auth profile with maker role " + request.makerRole() + ".");
        }
        if (checkers.isEmpty()) {
            warnings.add("MAKER_CHECKER found no enabled auth profile with checker role " + request.checkerRole() + ".");
        }
        for (AccessRequestTemplate template : templates) {
            for (DataProfile dataProfile : dataProfiles) {
                AuthProfile owner = authById(authProfiles, dataProfile.ownerAuthProfileId());
                if (owner == null) {
                    state.skip("DataProfile", dataProfile.id(),
                            "Maker-checker data has no enabled owner auth profile: " + dataProfile.name());
                    continue;
                }
                if (!roleMatches(owner, request.makerRole())) {
                    state.skip("DataProfile", dataProfile.id(),
                            "Data owner is not configured maker role: " + owner.name());
                    continue;
                }
                state.add(row(template, owner, dataProfile, request.defaultMakerSelfApproveExpected(),
                                request.generatedRowsEnabled(), "Maker self-approval"),
                        MatrixGenerationStrategy.MAKER_CHECKER,
                        "Maker attempting to approve or act on maker-owned object",
                        GenerationConfidence.HIGH);

                for (AuthProfile checker : checkers) {
                    if (checker.id().equals(owner.id())) {
                        continue;
                    }
                    ExpectedChoice choice = makerCheckerExpected(request, dataProfile, checker, allDataProfiles);
                    state.add(row(template, checker, dataProfile, choice.expected(), request.generatedRowsEnabled(),
                                    choice.name()),
                            MatrixGenerationStrategy.MAKER_CHECKER,
                            choice.reason(),
                            choice.confidence());
                }
                for (AuthProfile admin : admins) {
                    if (admin.id().equals(owner.id())) {
                        continue;
                    }
                    state.add(row(template, admin, dataProfile, request.defaultReviewExpected(),
                                    request.generatedRowsEnabled(), "Admin maker-checker review"),
                            MatrixGenerationStrategy.MAKER_CHECKER,
                            "Admin override in maker-checker flow requires policy review",
                            GenerationConfidence.REVIEW_REQUIRED);
                }
            }
        }
    }

    private void generateCrossTenant(
            MatrixGenerationRequest request,
            List<AccessRequestTemplate> templates,
            List<DataProfile> dataProfiles,
            List<DataProfile> allDataProfiles,
            List<AuthProfile> authProfiles,
            GenerationState state,
            List<String> warnings) {
        if (dataProfiles.isEmpty()) {
            warnings.add("CROSS_TENANT requires data profiles with tenant metadata.");
            return;
        }
        for (AccessRequestTemplate template : templates) {
            for (DataProfile dataProfile : dataProfiles) {
                ProfileMetadata dataMetadata = metadataForData(dataProfile, request);
                if (dataMetadata.tenantId().isBlank()) {
                    state.skip("DataProfile", dataProfile.id(),
                            "Missing tenant metadata key " + request.tenantKey());
                    continue;
                }
                for (AuthProfile authProfile : authProfiles) {
                    if (isAnonymous(authProfile)) {
                        continue;
                    }
                    ProfileMetadata authMetadata = metadataForAuth(authProfile, allDataProfiles, request);
                    if (authMetadata.tenantId().isBlank()) {
                        state.skip("AuthProfile", authProfile.id(),
                                "Missing tenant metadata from owned data profiles for " + authProfile.name());
                        continue;
                    }
                    boolean sameTenant = dataMetadata.tenantId().equals(authMetadata.tenantId());
                    boolean admin = isConfiguredAdmin(authProfile, request);
                    ExpectedResult expected = sameTenant || admin
                            ? request.defaultReviewExpected()
                            : request.defaultCrossTenantExpected();
                    String name = sameTenant ? "Same-tenant baseline" : "Cross-tenant access";
                    String reason = sameTenant
                            ? "Same-tenant actor baseline for selected object"
                            : "Actor tenant differs from object tenant";
                    GenerationConfidence confidence = sameTenant || admin
                            ? GenerationConfidence.REVIEW_REQUIRED
                            : GenerationConfidence.HIGH;
                    state.add(row(template, authProfile, dataProfile, expected, request.generatedRowsEnabled(), name),
                            MatrixGenerationStrategy.CROSS_TENANT,
                            admin && !sameTenant ? "Cross-tenant admin access requires manual policy review" : reason,
                            confidence);
                }
            }
        }
    }

    private void generateCrossBranch(
            MatrixGenerationRequest request,
            List<AccessRequestTemplate> templates,
            List<DataProfile> dataProfiles,
            List<DataProfile> allDataProfiles,
            List<AuthProfile> authProfiles,
            GenerationState state,
            List<String> warnings) {
        if (dataProfiles.isEmpty()) {
            warnings.add("CROSS_BRANCH requires data profiles with branch metadata.");
            return;
        }
        for (AccessRequestTemplate template : templates) {
            for (DataProfile dataProfile : dataProfiles) {
                ProfileMetadata dataMetadata = metadataForData(dataProfile, request);
                if (dataMetadata.branchId().isBlank()) {
                    state.skip("DataProfile", dataProfile.id(),
                            "Missing branch metadata key " + request.branchKey());
                    continue;
                }
                for (AuthProfile authProfile : authProfiles) {
                    if (isAnonymous(authProfile)) {
                        continue;
                    }
                    ProfileMetadata authMetadata = metadataForAuth(authProfile, allDataProfiles, request);
                    if (authMetadata.branchId().isBlank()) {
                        state.skip("AuthProfile", authProfile.id(),
                                "Missing branch metadata from owned data profiles for " + authProfile.name());
                        continue;
                    }
                    boolean sameBranch = dataMetadata.branchId().equals(authMetadata.branchId());
                    boolean admin = isConfiguredAdmin(authProfile, request);
                    ExpectedResult expected = sameBranch || admin
                            ? request.defaultReviewExpected()
                            : request.defaultCrossBranchExpected();
                    String name = sameBranch ? "Same-branch baseline" : "Cross-branch access";
                    String reason = sameBranch
                            ? "Same-branch actor baseline for selected object"
                            : "Actor branch differs from object branch";
                    GenerationConfidence confidence = sameBranch || admin
                            ? GenerationConfidence.REVIEW_REQUIRED
                            : GenerationConfidence.HIGH;
                    state.add(row(template, authProfile, dataProfile, expected, request.generatedRowsEnabled(), name),
                            MatrixGenerationStrategy.CROSS_BRANCH,
                            admin && !sameBranch ? "Cross-branch admin access requires manual policy review" : reason,
                            confidence);
                }
            }
        }
    }

    private void generateCrossRole(
            MatrixGenerationRequest request,
            List<AccessRequestTemplate> templates,
            List<DataProfile> dataProfiles,
            List<AuthProfile> authProfiles,
            GenerationState state,
            List<String> warnings) {
        List<AuthProfile> candidateProfiles = authProfiles.stream()
                .filter(profile -> !isAnonymous(profile))
                .filter(profile -> !isConfiguredAdmin(profile, request))
                .toList();
        if (candidateProfiles.isEmpty()) {
            warnings.add("CROSS_ROLE requires at least one enabled non-admin auth profile.");
            return;
        }
        for (AccessRequestTemplate template : templates) {
            List<DataProfile> targets = dataTargetsForTemplate(template, dataProfiles, warnings);
            for (DataProfile dataProfile : targets) {
                AuthProfile owner = dataProfile == null ? null : authById(authProfiles, dataProfile.ownerAuthProfileId());
                for (AuthProfile authProfile : candidateProfiles) {
                    if (owner != null && owner.id().equals(authProfile.id())) {
                        continue;
                    }
                    if (owner != null && !owner.role().isBlank()
                            && owner.role().equalsIgnoreCase(authProfile.role())) {
                        continue;
                    }
                    state.add(row(template, authProfile, dataProfile, request.roleEscalationExpectedResult(),
                                    request.generatedRowsEnabled(), "Cross-role access"),
                            MatrixGenerationStrategy.CROSS_ROLE,
                            "Selected actor role differs from object owner role or selected protected context",
                            GenerationConfidence.LOW);
                }
            }
        }
    }

    private void generateObjectOwnershipVariants(
            MatrixGenerationRequest request,
            List<AccessRequestTemplate> templates,
            List<DataProfile> dataProfiles,
            List<DataProfile> allDataProfiles,
            List<AuthProfile> authProfiles,
            GenerationState state,
            List<String> warnings) {
        if (dataProfiles.isEmpty()) {
            warnings.add("OBJECT_OWNERSHIP_VARIANTS requires at least one enabled data profile.");
            return;
        }
        for (AccessRequestTemplate template : templates) {
            for (DataProfile dataProfile : dataProfiles) {
                AuthProfile owner = authById(authProfiles, dataProfile.ownerAuthProfileId());
                if (owner == null) {
                    state.skip("DataProfile", dataProfile.id(),
                            "Ownership variant data has no enabled owner auth profile: " + dataProfile.name());
                    continue;
                }
                state.add(row(template, owner, dataProfile, ExpectedResult.ALLOW, request.generatedRowsEnabled(),
                                "Owner baseline"),
                        MatrixGenerationStrategy.OBJECT_OWNERSHIP_VARIANTS,
                        "Owner baseline for object ownership comparison",
                        GenerationConfidence.HIGH);
                for (AuthProfile authProfile : authProfiles) {
                    if (owner.id().equals(authProfile.id())) {
                        continue;
                    }
                    if (isAnonymous(authProfile)) {
                        state.add(row(template, authProfile, dataProfile, ExpectedResult.DENY,
                                        request.generatedRowsEnabled(), "Anonymous object access"),
                                MatrixGenerationStrategy.OBJECT_OWNERSHIP_VARIANTS,
                                "Anonymous actor against owned object",
                                GenerationConfidence.HIGH);
                        continue;
                    }
                    if (isConfiguredAdmin(authProfile, request)) {
                        state.add(row(template, authProfile, dataProfile, request.defaultReviewExpected(),
                                        request.generatedRowsEnabled(), "Admin object review"),
                                MatrixGenerationStrategy.OBJECT_OWNERSHIP_VARIANTS,
                                "Admin access to owned object requires policy review",
                                GenerationConfidence.REVIEW_REQUIRED);
                        continue;
                    }
                    if (request.sameRoleOnly() && !sameUserRole(owner.role(), authProfile)) {
                        state.skip("AuthProfile", authProfile.id(),
                                "Skipped object ownership variant because role differs from owner role.");
                        continue;
                    }
                    ProfileMetadata ownerMetadata = metadataForData(dataProfile, request);
                    ProfileMetadata authMetadata = metadataForAuth(authProfile, allDataProfiles, request);
                    if (!metadataAllowed(request, ownerMetadata, authMetadata, state, authProfile)) {
                        continue;
                    }
                    GenerationConfidence confidence = metadataEquivalent(ownerMetadata, authMetadata)
                            ? GenerationConfidence.HIGH
                            : GenerationConfidence.MEDIUM;
                    state.add(row(template, authProfile, dataProfile, ExpectedResult.DENY,
                                    request.generatedRowsEnabled(), "Cross-owner access"),
                            MatrixGenerationStrategy.OBJECT_OWNERSHIP_VARIANTS,
                            "Non-owner actor attempting access to another owner's object",
                            confidence);
                }
            }
        }
    }

    private ExpectedChoice makerCheckerExpected(
            MatrixGenerationRequest request,
            DataProfile dataProfile,
            AuthProfile checker,
            List<DataProfile> allDataProfiles) {
        ProfileMetadata objectMetadata = metadataForData(dataProfile, request);
        ProfileMetadata checkerMetadata = metadataForAuth(checker, allDataProfiles, request);
        if (bothPresentAndDifferent(objectMetadata.tenantId(), checkerMetadata.tenantId())) {
            return new ExpectedChoice(
                    request.defaultCrossTenantExpected(),
                    "Cross-tenant checker approval",
                    "Checker tenant differs from maker-owned object tenant",
                    GenerationConfidence.MEDIUM);
        }
        if (bothPresentAndDifferent(objectMetadata.branchId(), checkerMetadata.branchId())) {
            return new ExpectedChoice(
                    request.defaultCrossBranchExpected(),
                    "Cross-branch checker approval",
                    "Checker branch differs from maker-owned object branch",
                    GenerationConfidence.MEDIUM);
        }
        return new ExpectedChoice(
                request.defaultCheckerApproveExpected(),
                "Checker approval",
                "Checker role acting on maker-owned object",
                GenerationConfidence.REVIEW_REQUIRED);
    }

    private boolean metadataAllowed(
            MatrixGenerationRequest request,
            ProfileMetadata ownerMetadata,
            ProfileMetadata authMetadata,
            GenerationState state,
            AuthProfile authProfile) {
        if (request.sameTenantOnly()
                && !sameRequiredMetadata(ownerMetadata.tenantId(), authMetadata.tenantId())) {
            state.skip("AuthProfile", authProfile.id(),
                    "Skipped object ownership variant because tenant metadata is missing or different.");
            return false;
        }
        if (request.sameBranchOnly()
                && !sameRequiredMetadata(ownerMetadata.branchId(), authMetadata.branchId())) {
            state.skip("AuthProfile", authProfile.id(),
                    "Skipped object ownership variant because branch metadata is missing or different.");
            return false;
        }
        if (!request.includeCrossTenant()
                && bothPresentAndDifferent(ownerMetadata.tenantId(), authMetadata.tenantId())) {
            state.skip("AuthProfile", authProfile.id(),
                    "Skipped object ownership variant because cross-tenant pairs are disabled.");
            return false;
        }
        if (!request.includeCrossBranch()
                && bothPresentAndDifferent(ownerMetadata.branchId(), authMetadata.branchId())) {
            state.skip("AuthProfile", authProfile.id(),
                    "Skipped object ownership variant because cross-branch pairs are disabled.");
            return false;
        }
        return true;
    }

    private AccessTestCase row(
            AccessRequestTemplate template,
            AuthProfile authProfile,
            DataProfile dataProfile,
            ExpectedResult expected,
            boolean enabled,
            String reason) {
        String dataName = dataProfile == null ? "no data" : dataProfile.name();
        return new AccessTestCase(
                idGenerator.newId("case"),
                reason + ": " + template.name() + " as " + authProfile.name() + " on " + dataName,
                template.id(),
                template.sourceFlowId(),
                template.sourceStepId(),
                SetupConfig.none(),
                new TargetBinding(authProfile.id(), dataProfile == null ? "" : dataProfile.id(), "", ""),
                expected,
                List.of(),
                enabled,
                RunStatus.PENDING,
                0L);
    }

    private List<AccessRequestTemplate> selectedTemplates(MatrixGenerationRequest request, AccessWorkspace workspace) {
        Set<String> selectedIds = request.templateIds();
        return workspace.templates().values().stream()
                .filter(AccessRequestTemplate::enabled)
                .filter(template -> selectedIds.isEmpty() || selectedIds.contains(template.id()))
                .toList();
    }

    private List<DataProfile> selectedDataProfiles(MatrixGenerationRequest request, AccessWorkspace workspace) {
        Set<String> selectedIds = request.dataProfileIds();
        return workspace.dataProfiles().values().stream()
                .filter(DataProfile::enabled)
                .filter(dataProfile -> selectedIds.isEmpty() || selectedIds.contains(dataProfile.id()))
                .toList();
    }

    private List<DataProfile> enabledDataProfiles(AccessWorkspace workspace) {
        return workspace.dataProfiles().values().stream()
                .filter(DataProfile::enabled)
                .toList();
    }

    private List<AuthProfile> selectedAuthProfiles(MatrixGenerationRequest request, AccessWorkspace workspace) {
        Set<String> selectedIds = request.authProfileIds();
        return workspace.authProfiles().values().stream()
                .filter(AuthProfile::enabled)
                .filter(authProfile -> selectedIds.isEmpty() || selectedIds.contains(authProfile.id()))
                .toList();
    }

    private List<DataProfile> dataTargetsForTemplate(
            AccessRequestTemplate template,
            List<DataProfile> dataProfiles,
            List<String> warnings) {
        if (!dataProfiles.isEmpty()) {
            return dataProfiles;
        }
        if (templateUsesData(template)) {
            warnings.add("Template requires data but no enabled data profile was selected: " + template.name());
            return List.of();
        }
        return Collections.singletonList(null);
    }

    private boolean templateUsesData(AccessRequestTemplate template) {
        return template.placeholders().stream().map(ScopedVariableRef::scope).anyMatch(VariableScope.DATA::equals);
    }

    private AuthProfile authById(List<AuthProfile> authProfiles, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String normalizedId = id.strip();
        return authProfiles.stream()
                .filter(profile -> profile.id().equals(normalizedId))
                .findFirst()
                .orElse(null);
    }

    private ProfileMetadata metadataForData(DataProfile dataProfile, MatrixGenerationRequest request) {
        if (dataProfile == null) {
            return new ProfileMetadata("", "");
        }
        return new ProfileMetadata(
                valueForKey(dataProfile, request.tenantKey()),
                valueForKey(dataProfile, request.branchKey()));
    }

    private ProfileMetadata metadataForAuth(
            AuthProfile authProfile,
            List<DataProfile> allDataProfiles,
            MatrixGenerationRequest request) {
        String tenantId = "";
        String branchId = "";
        for (DataProfile dataProfile : allDataProfiles) {
            if (!authProfile.id().equals(dataProfile.ownerAuthProfileId())) {
                continue;
            }
            if (tenantId.isBlank()) {
                tenantId = valueForKey(dataProfile, request.tenantKey());
            }
            if (branchId.isBlank()) {
                branchId = valueForKey(dataProfile, request.branchKey());
            }
            if (!tenantId.isBlank() && !branchId.isBlank()) {
                break;
            }
        }
        return new ProfileMetadata(tenantId, branchId);
    }

    private String valueForKey(DataProfile dataProfile, String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        return Objects.requireNonNullElse(dataProfile.values().get(key), "").strip();
    }

    private boolean metadataEquivalent(ProfileMetadata left, ProfileMetadata right) {
        boolean tenantEquivalent = left.tenantId().isBlank()
                || right.tenantId().isBlank()
                || left.tenantId().equals(right.tenantId());
        boolean branchEquivalent = left.branchId().isBlank()
                || right.branchId().isBlank()
                || left.branchId().equals(right.branchId());
        return tenantEquivalent && branchEquivalent;
    }

    private boolean sameRequiredMetadata(String left, String right) {
        return !left.isBlank() && left.equals(right);
    }

    private boolean bothPresentAndDifferent(String left, String right) {
        return !left.isBlank() && !right.isBlank() && !left.equals(right);
    }

    private boolean sameUserRole(String ownerRole, AuthProfile authProfile) {
        if (isAdmin(authProfile) || isAnonymous(authProfile)) {
            return false;
        }
        if (ownerRole == null || ownerRole.isBlank()) {
            return true;
        }
        return ownerRole.equalsIgnoreCase(authProfile.role());
    }

    private boolean roleMatches(AuthProfile authProfile, String role) {
        return role != null && !role.isBlank() && authProfile.role().equalsIgnoreCase(role.strip());
    }

    private boolean isConfiguredAdmin(AuthProfile authProfile, MatrixGenerationRequest request) {
        return roleMatches(authProfile, request.adminRole()) || isAdmin(authProfile);
    }

    private boolean isAdmin(AuthProfile authProfile) {
        String text = searchableProfileText(authProfile);
        return text.contains("admin")
                || text.contains("superuser")
                || text.contains("root");
    }

    private boolean isAnonymous(AuthProfile authProfile) {
        String text = searchableProfileText(authProfile);
        return text.contains("anonymous")
                || text.contains("anon")
                || text.contains("unauthenticated")
                || text.contains("guest");
    }

    private String searchableProfileText(AuthProfile authProfile) {
        return (authProfile.id() + " " + authProfile.name() + " " + authProfile.role())
                .toLowerCase(Locale.ROOT);
    }

    private record ProfileMetadata(String tenantId, String branchId) {
    }

    private record ExpectedChoice(
            ExpectedResult expected,
            String name,
            String reason,
            GenerationConfidence confidence) {
    }

    private record MatrixDuplicateKey(
            String targetType,
            String templateId,
            String flowTargetId,
            String targetAuthProfileId,
            String dataProfileId,
            String scenarioInstanceId,
            SetupConfig setup) {
        private static MatrixDuplicateKey from(AccessTestCase testCase) {
            return new MatrixDuplicateKey(
                    testCase.targetType().name(),
                    testCase.templateId(),
                    testCase.flowTargetId(),
                    testCase.target().targetAuthProfileId(),
                    testCase.target().dataProfileId(),
                    testCase.target().scenarioInstanceId(),
                    testCase.setup());
        }
    }

    private final class GenerationState {
        private final Map<MatrixDuplicateKey, GeneratedMatrixRow> rowsByKey = new LinkedHashMap<>();
        private final Set<MatrixDuplicateKey> existingKeys = new LinkedHashSet<>();
        private final List<MatrixGenerationSkipReason> skipReasons = new ArrayList<>();
        private int duplicateCount;

        private GenerationState(Iterable<AccessTestCase> existingRows) {
            for (AccessTestCase row : existingRows) {
                existingKeys.add(MatrixDuplicateKey.from(row));
            }
        }

        private void add(
                AccessTestCase row,
                MatrixGenerationStrategy strategy,
                String reason,
                GenerationConfidence confidence) {
            MatrixDuplicateKey key = MatrixDuplicateKey.from(row);
            if (existingKeys.contains(key) || rowsByKey.containsKey(key)) {
                duplicateCount++;
                skip("AccessTestCase", row.id(), "Duplicate generated row: " + reason);
                return;
            }
            rowsByKey.put(key, new GeneratedMatrixRow(row, strategy, reason, confidence));
        }

        private void skip(String objectType, String objectId, String reason) {
            skipReasons.add(new MatrixGenerationSkipReason(objectType, objectId, reason));
        }

        private List<GeneratedMatrixRow> rows() {
            return List.copyOf(rowsByKey.values());
        }

        private int duplicateCount() {
            return duplicateCount;
        }

        private int skippedCount() {
            return skipReasons.size();
        }

        private List<MatrixGenerationSkipReason> skipReasons() {
            return List.copyOf(skipReasons);
        }
    }
}

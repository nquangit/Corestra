package dev.nquangit.corestra.access.persistence;

import dev.nquangit.corestra.access.workspace.AccessWorkspace;

import java.util.Objects;

/**
 * Creates Access Testing workspace snapshots for project persistence.
 */
public final class AccessWorkspaceConfiguration {
    private AccessWorkspaceConfiguration() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Returns a workspace containing reusable configuration only.
     *
     * @param workspace source workspace
     * @return configuration-only workspace
     */
    public static AccessWorkspace configurationOnly(AccessWorkspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        AccessWorkspace.Builder builder = AccessWorkspace.builder();
        workspace.authProviders().values().forEach(builder::authProvider);
        workspace.authProfiles().values().forEach(builder::authProfile);
        workspace.dataProfiles().values().forEach(builder::dataProfile);
        workspace.templates().values().forEach(builder::template);
        workspace.flowTargets().values().forEach(builder::flowTarget);
        workspace.evaluationProfiles().values().forEach(builder::evaluationProfile);
        return builder.build();
    }

    /**
     * Returns a workspace containing project-persistent state and no runtime scenarios.
     *
     * @param workspace source workspace
     * @return project-state workspace
     */
    public static AccessWorkspace projectState(AccessWorkspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        AccessWorkspace.Builder builder = configurationOnly(workspace).toBuilder();
        workspace.testCases().values().forEach(builder::testCase);
        workspace.results().values().forEach(builder::result);
        return builder.build();
    }

    /**
     * Returns a project document containing persisted state and no runtime scenarios.
     *
     * @param document source access document
     * @return project-state document
     */
    public static AccessWorkspaceDocument projectState(AccessWorkspaceDocument document) {
        Objects.requireNonNull(document, "document");
        return new AccessWorkspaceDocument(
                projectState(document.workspace()),
                document.evidence(),
                document.latestSummary().orElse(null),
                document.reportDrafts());
    }

    /**
     * Returns whether a workspace contains any reusable Access Testing configuration.
     *
     * @param workspace source workspace
     * @return true when configuration should be persisted
     */
    public static boolean hasConfiguration(AccessWorkspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        return !workspace.authProviders().isEmpty()
                || !workspace.authProfiles().isEmpty()
                || !workspace.dataProfiles().isEmpty()
                || !workspace.templates().isEmpty()
                || !workspace.flowTargets().isEmpty()
                || !workspace.evaluationProfiles().isEmpty();
    }

    /**
     * Returns whether a document contains any project-persistent Access Testing state.
     *
     * @param document source document
     * @return true when project state should be persisted
     */
    public static boolean hasProjectState(AccessWorkspaceDocument document) {
        Objects.requireNonNull(document, "document");
        AccessWorkspace workspace = document.workspace();
        return hasConfiguration(workspace)
                || !workspace.testCases().isEmpty()
                || !workspace.results().isEmpty()
                || !document.evidence().isEmpty()
                || document.latestSummary().isPresent()
                || !document.reportDrafts().isEmpty();
    }
}

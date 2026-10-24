package dev.nquangit.corestra.persistence;

import dev.nquangit.corestra.access.persistence.AccessWorkspaceDocument;
import dev.nquangit.corestra.access.report.SavedFindingDraft;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.runner.AccessPlanSummary;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.session.SessionProfile;
import dev.nquangit.corestra.session.SessionProfilesDocument;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable project-file document containing metadata, flows, and optional access-testing data.
 */
public final class ProjectDocument {
    private final int schemaVersion;
    private final ProjectMetadata metadata;
    private final ProjectSettings settings;
    private final List<FlowDefinition> flows;
    private final List<FlowDefinition> manualFlows;
    private final AccessWorkspace accessWorkspace;
    private final List<AccessEvidence> accessEvidence;
    private final AccessPlanSummary latestAccessPlanSummary;
    private final Map<String, SavedFindingDraft> accessReportDrafts;
    private final List<SessionProfile> sessionProfiles;

    /**
     * Creates a flow-only project document.
     *
     * @param schemaVersion project schema version
     * @param metadata project metadata
     * @param flows flow definitions
     */
    public ProjectDocument(int schemaVersion, ProjectMetadata metadata, List<FlowDefinition> flows) {
        this(schemaVersion, metadata, ProjectSettings.defaults(), flows, AccessWorkspace.empty(), List.of());
    }

    /**
     * Creates a project document.
     *
     * @param schemaVersion project schema version
     * @param metadata project metadata
     * @param flows flow definitions
     * @param accessWorkspace access-testing workspace snapshot
     * @param accessEvidence optional access evidence entries
     */
    public ProjectDocument(
            int schemaVersion,
            ProjectMetadata metadata,
            List<FlowDefinition> flows,
            AccessWorkspace accessWorkspace,
            List<AccessEvidence> accessEvidence) {
        this(schemaVersion, metadata, ProjectSettings.defaults(), flows, accessWorkspace, accessEvidence, null, Map.of());
    }

    /**
     * Creates a project document.
     *
     * @param schemaVersion project schema version
     * @param metadata project metadata
     * @param settings project settings
     * @param flows flow definitions
     * @param accessWorkspace access-testing workspace snapshot
     * @param accessEvidence optional access evidence entries
     */
    public ProjectDocument(
            int schemaVersion,
            ProjectMetadata metadata,
            ProjectSettings settings,
            List<FlowDefinition> flows,
            AccessWorkspace accessWorkspace,
            List<AccessEvidence> accessEvidence) {
        this(schemaVersion, metadata, settings, flows, accessWorkspace, accessEvidence, null, Map.of());
    }

    /**
     * Creates a project document.
     *
     * @param schemaVersion project schema version
     * @param metadata project metadata
     * @param flows flow definitions
     * @param accessWorkspace access-testing workspace snapshot
     * @param accessEvidence optional access evidence entries
     * @param latestAccessPlanSummary latest access plan summary
     * @param accessReportDrafts saved report drafts keyed by test case id
     */
    public ProjectDocument(
            int schemaVersion,
            ProjectMetadata metadata,
            List<FlowDefinition> flows,
            AccessWorkspace accessWorkspace,
            List<AccessEvidence> accessEvidence,
            AccessPlanSummary latestAccessPlanSummary,
            Map<String, SavedFindingDraft> accessReportDrafts) {
        this(schemaVersion, metadata, ProjectSettings.defaults(), flows, accessWorkspace, accessEvidence,
                latestAccessPlanSummary, accessReportDrafts);
    }

    /**
     * Creates a project document.
     *
     * @param schemaVersion project schema version
     * @param metadata project metadata
     * @param flows flow definitions
     * @param accessDocument access project document
     */
    public ProjectDocument(
            int schemaVersion,
            ProjectMetadata metadata,
            List<FlowDefinition> flows,
            AccessWorkspaceDocument accessDocument) {
        this(schemaVersion, metadata, ProjectSettings.defaults(), flows, accessDocument);
    }

    /**
     * Creates a project document.
     *
     * @param schemaVersion project schema version
     * @param metadata project metadata
     * @param settings project settings
     * @param flows flow definitions
     * @param accessDocument access project document
     */
    public ProjectDocument(
            int schemaVersion,
            ProjectMetadata metadata,
            ProjectSettings settings,
            List<FlowDefinition> flows,
            AccessWorkspaceDocument accessDocument) {
        this(
                schemaVersion,
                metadata,
                settings,
                flows,
                Objects.requireNonNull(accessDocument, "accessDocument").workspace(),
                accessDocument.evidence(),
                accessDocument.latestSummary().orElse(null),
                accessDocument.reportDrafts(),
                SessionProfilesDocument.defaults().profiles());
    }

    /**
     * Creates a project document.
     *
     * @param schemaVersion project schema version
     * @param metadata project metadata
     * @param settings project settings
     * @param flows flow definitions
     * @param accessWorkspace access-testing workspace snapshot
     * @param accessEvidence optional access evidence entries
     * @param latestAccessPlanSummary latest access plan summary
     * @param accessReportDrafts saved report drafts keyed by test case id
     */
    public ProjectDocument(
            int schemaVersion,
            ProjectMetadata metadata,
            ProjectSettings settings,
            List<FlowDefinition> flows,
            AccessWorkspace accessWorkspace,
            List<AccessEvidence> accessEvidence,
            AccessPlanSummary latestAccessPlanSummary,
            Map<String, SavedFindingDraft> accessReportDrafts) {
        this(schemaVersion, metadata, settings, flows, accessWorkspace, accessEvidence,
                latestAccessPlanSummary, accessReportDrafts, SessionProfilesDocument.defaults().profiles());
    }

    /**
     * Creates a project document.
     *
     * @param schemaVersion project schema version
     * @param metadata project metadata
     * @param settings project settings
     * @param flows flow definitions
     * @param accessWorkspace access-testing workspace snapshot
     * @param accessEvidence optional access evidence entries
     * @param latestAccessPlanSummary latest access plan summary
     * @param accessReportDrafts saved report drafts keyed by test case id
     * @param sessionProfiles session handler profiles
     */
    public ProjectDocument(
            int schemaVersion,
            ProjectMetadata metadata,
            ProjectSettings settings,
            List<FlowDefinition> flows,
            AccessWorkspace accessWorkspace,
            List<AccessEvidence> accessEvidence,
            AccessPlanSummary latestAccessPlanSummary,
            Map<String, SavedFindingDraft> accessReportDrafts,
            List<SessionProfile> sessionProfiles) {
        this(schemaVersion, metadata, settings, flows, accessWorkspace, accessEvidence, latestAccessPlanSummary, accessReportDrafts, sessionProfiles, List.of());
    }

    /** Creates a project document with independent manual draft configuration, excluding runtime variables/results. */
    public ProjectDocument(int schemaVersion, ProjectMetadata metadata, ProjectSettings settings, List<FlowDefinition> flows,
                           AccessWorkspace accessWorkspace, List<AccessEvidence> accessEvidence, AccessPlanSummary latestAccessPlanSummary,
                           Map<String, SavedFindingDraft> accessReportDrafts, List<SessionProfile> sessionProfiles, List<FlowDefinition> manualFlows) {
        this.manualFlows = List.copyOf(manualFlows);
        this.schemaVersion = schemaVersion;
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.settings = Objects.requireNonNullElse(settings, ProjectSettings.defaults());
        this.flows = List.copyOf(Objects.requireNonNull(flows, "flows"));
        this.accessWorkspace = Objects.requireNonNull(accessWorkspace, "accessWorkspace");
        this.accessEvidence = List.copyOf(Objects.requireNonNull(accessEvidence, "accessEvidence"));
        this.latestAccessPlanSummary = latestAccessPlanSummary;
        this.accessReportDrafts = Map.copyOf(Objects.requireNonNull(accessReportDrafts, "accessReportDrafts"));
        this.sessionProfiles = SessionProfilesDocument.ensureGeneral(sessionProfiles);
    }

    /**
     * Returns the project schema version.
     *
     * @return project schema version
     */
    public int schemaVersion() { return schemaVersion; }

    /**
     * Returns project metadata.
     *
     * @return project metadata
     */
    public ProjectMetadata metadata() { return metadata; }

    /**
     * Returns project settings.
     *
     * @return project settings
     */
    public ProjectSettings settings() { return settings; }

    /**
     * Returns flow definitions.
     *
     * @return immutable flow definitions
     */
    public List<FlowDefinition> flows() { return flows; }

    /** Returns independent manual drafts; runtime variables and exchanges are not project configuration. */
    public List<FlowDefinition> manualFlows() { return manualFlows; }

    /** Adds manual configuration to a document while preserving every other subsystem. */
    public ProjectDocument withManualFlows(List<FlowDefinition> drafts) {
        return new ProjectDocument(schemaVersion, metadata, settings, flows, accessWorkspace, accessEvidence,
                latestAccessPlanSummary, accessReportDrafts, sessionProfiles, drafts);
    }

    /**
     * Returns access-testing workspace data.
     *
     * @return access workspace snapshot
     */
    public AccessWorkspace accessWorkspace() { return accessWorkspace; }

    /**
     * Returns access evidence entries.
     *
     * @return immutable access evidence entries
     */
    public List<AccessEvidence> accessEvidence() { return accessEvidence; }

    /**
     * Returns the latest access plan summary.
     *
     * @return latest access plan summary, or empty
     */
    public Optional<AccessPlanSummary> latestAccessPlanSummary() {
        return Optional.ofNullable(latestAccessPlanSummary);
    }

    /**
     * Returns saved access report drafts keyed by test case id.
     *
     * @return immutable report draft map
     */
    public Map<String, SavedFindingDraft> accessReportDrafts() {
        return accessReportDrafts;
    }

    /**
     * Returns Session Handler profiles.
     *
     * @return immutable session profiles
     */
    public List<SessionProfile> sessionProfiles() {
        return sessionProfiles;
    }

    /**
     * Returns the access project document.
     *
     * @return access project document
     */
    public AccessWorkspaceDocument accessDocument() {
        return new AccessWorkspaceDocument(
                accessWorkspace,
                accessEvidence,
                latestAccessPlanSummary,
                accessReportDrafts);
    }

    /**
     * Returns the Session Handler project document.
     *
     * @return session profiles document
     */
    public SessionProfilesDocument sessionsDocument() {
        return new SessionProfilesDocument(sessionProfiles);
    }
}

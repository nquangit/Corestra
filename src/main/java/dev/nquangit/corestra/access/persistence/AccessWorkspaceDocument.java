package dev.nquangit.corestra.access.persistence;

import dev.nquangit.corestra.access.report.SavedFindingDraft;
import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.runner.AccessPlanSummary;
import dev.nquangit.corestra.access.workspace.AccessWorkspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Carries an access workspace and its optional evidence entries as project-file data.
 */
public final class AccessWorkspaceDocument {
    private final AccessWorkspace workspace;
    private final List<AccessEvidence> evidence;
    private final AccessPlanSummary latestSummary;
    private final Map<String, SavedFindingDraft> reportDrafts;

    /**
     * Creates access project data.
     *
     * @param workspace access workspace snapshot
     * @param evidence optional evidence entries
     */
    public AccessWorkspaceDocument(AccessWorkspace workspace, List<AccessEvidence> evidence) {
        this(workspace, evidence, null, Map.of());
    }

    /**
     * Creates access project data.
     *
     * @param workspace access workspace snapshot
     * @param evidence optional evidence entries
     * @param latestSummary latest plan summary, or null when no plan has run
     * @param reportDrafts saved report drafts keyed by test case id
     */
    public AccessWorkspaceDocument(
            AccessWorkspace workspace,
            List<AccessEvidence> evidence,
            AccessPlanSummary latestSummary,
            Map<String, SavedFindingDraft> reportDrafts) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        this.latestSummary = latestSummary;
        this.reportDrafts = copyReportDrafts(reportDrafts);
    }

    /**
     * Returns an empty access project document.
     *
     * @return an empty access project document
     */
    public static AccessWorkspaceDocument empty() {
        return new AccessWorkspaceDocument(AccessWorkspace.empty(), List.of());
    }

    /**
     * Returns the access workspace snapshot.
     *
     * @return access workspace snapshot
     */
    public AccessWorkspace workspace() {
        return workspace;
    }

    /**
     * Returns evidence entries keyed externally by result id.
     *
     * @return immutable evidence entries
     */
    public List<AccessEvidence> evidence() {
        return evidence;
    }

    /**
     * Returns the latest plan summary.
     *
     * @return latest plan summary, or empty
     */
    public Optional<AccessPlanSummary> latestSummary() {
        return Optional.ofNullable(latestSummary);
    }

    /**
     * Returns saved report drafts keyed by test case id.
     *
     * @return immutable report draft map
     */
    public Map<String, SavedFindingDraft> reportDrafts() {
        return reportDrafts;
    }

    private static Map<String, SavedFindingDraft> copyReportDrafts(Map<String, SavedFindingDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, SavedFindingDraft> copied = new LinkedHashMap<>();
        drafts.forEach((key, value) -> copied.put(normalizeRequiredText(key, "report draft key"),
                Objects.requireNonNull(value, "report draft")));
        return Map.copyOf(copied);
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}

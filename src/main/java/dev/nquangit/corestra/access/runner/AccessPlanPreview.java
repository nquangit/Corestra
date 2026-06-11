package dev.nquangit.corestra.access.runner;

import java.util.List;
import java.util.Objects;

/**
 * Pre-run preview containing safety summary and diagnostics for an access plan.
 */
public final class AccessPlanPreview {
    private final PlanSafetySummary summary;
    private final List<PlanValidationIssue> issues;

    /**
     * Creates a plan preview.
     *
     * @param summary safety summary
     * @param issues validation issues
     */
    public AccessPlanPreview(PlanSafetySummary summary, List<PlanValidationIssue> issues) {
        this.summary = Objects.requireNonNull(summary, "summary");
        this.issues = List.copyOf(Objects.requireNonNullElse(issues, List.of()));
    }

    /** @return safety summary */
    public PlanSafetySummary summary() {
        return summary;
    }

    /** @return immutable validation issues */
    public List<PlanValidationIssue> issues() {
        return issues;
    }

    /** @return true when any error issue exists */
    public boolean hasErrors() {
        return issues.stream().anyMatch(issue -> issue.severity() == PlanValidationSeverity.ERROR);
    }
}

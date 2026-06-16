package dev.nquangit.corestra.access.runner;

/**
 * Immutable progress snapshot for a sequential access plan run.
 *
 * @param currentIndex one-based index of the current or last completed row
 * @param total total rows selected for this plan
 * @param currentTestCaseId current or last completed test case id
 * @param state plan state
 */
public record AccessPlanProgress(int currentIndex, int total, String currentTestCaseId, AccessPlanRunState state) {
    /**
     * Creates progress snapshot.
     *
     * @param currentIndex one-based index of the current or last completed row
     * @param total total rows selected for this plan
     * @param currentTestCaseId current or last completed test case id
     * @param state plan state
     */
    public AccessPlanProgress {
        if (currentIndex < 0) {
            throw new IllegalArgumentException("currentIndex must not be negative");
        }
        if (total < 0) {
            throw new IllegalArgumentException("total must not be negative");
        }
        currentTestCaseId = currentTestCaseId == null ? "" : currentTestCaseId;
        state = state == null ? AccessPlanRunState.IDLE : state;
    }
}

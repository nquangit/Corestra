package dev.nquangit.corestra.access.runner;

/**
 * Receives sequential access plan progress events.
 */
public interface AccessPlanListener {
    /**
     * Called when a plan run starts.
     *
     * @param totalRows total rows selected for execution
     */
    default void onPlanStarted(int totalRows) {
    }

    /**
     * Called before one row starts.
     *
     * @param testCaseId test case id
     * @param index one-based row index
     * @param total total rows selected for execution
     */
    default void onRowStarted(String testCaseId, int index, int total) {
    }

    /**
     * Called after one row finishes.
     *
     * @param testCaseId test case id
     * @param result access run result
     * @param index one-based row index
     * @param total total rows selected for execution
     */
    default void onRowFinished(String testCaseId, AccessRunResult result, int index, int total) {
    }

    /**
     * Called when a plan stops before all selected rows are started.
     *
     * @param summary plan summary
     */
    default void onPlanStopped(AccessPlanSummary summary) {
    }

    /**
     * Called when a plan finishes without a stop request.
     *
     * @param summary plan summary
     */
    default void onPlanFinished(AccessPlanSummary summary) {
    }
}

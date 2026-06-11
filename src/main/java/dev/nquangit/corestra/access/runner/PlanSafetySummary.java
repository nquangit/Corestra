package dev.nquangit.corestra.access.runner;

/**
 * Summarizes safety-relevant properties of a plan before it starts.
 */
public final class PlanSafetySummary {
    private final int enabledRows;
    private final int setupRows;
    private final int postCheckRows;
    private final int stateChangingOrConsumableRows;

    /**
     * Creates a plan safety summary.
     *
     * @param enabledRows enabled rows selected for the plan
     * @param setupRows rows with non-NONE setup
     * @param postCheckRows rows with enabled post-checks
     * @param stateChangingOrConsumableRows rows with state-changing or consumable templates
     */
    public PlanSafetySummary(
            int enabledRows,
            int setupRows,
            int postCheckRows,
            int stateChangingOrConsumableRows) {
        this.enabledRows = enabledRows;
        this.setupRows = setupRows;
        this.postCheckRows = postCheckRows;
        this.stateChangingOrConsumableRows = stateChangingOrConsumableRows;
    }

    /** @return enabled rows selected for the plan */
    public int enabledRows() {
        return enabledRows;
    }

    /** @return rows with setup.mode != NONE */
    public int setupRows() {
        return setupRows;
    }

    /** @return rows with enabled post-checks */
    public int postCheckRows() {
        return postCheckRows;
    }

    /** @return rows with state-changing or consumable templates */
    public int stateChangingOrConsumableRows() {
        return stateChangingOrConsumableRows;
    }
}

package dev.nquangit.corestra.access.flow;

/**
 * Describes the kind of target a Matrix row executes.
 */
public enum AccessTargetType {
    /**
     * Executes one reusable Access request template.
     */
    REQUEST_TEMPLATE,

    /**
     * Executes a saved Flowise flow snapshot through a selected target step.
     */
    FLOW_TARGET
}

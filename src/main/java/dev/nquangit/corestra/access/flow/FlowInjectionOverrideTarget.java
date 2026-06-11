package dev.nquangit.corestra.access.flow;

/**
 * Identifies how a flow injection override should match Flowise inject rules.
 */
public enum FlowInjectionOverrideTarget {
    /**
     * Matches inject rules by their variable source key.
     */
    VALUE_SOURCE_KEY,

    /**
     * Matches one inject rule by its stable rule id.
     */
    RULE_ID
}

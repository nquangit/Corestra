package dev.nquangit.corestra.access.template;

/**
 * Classifies the kind of operation represented by an access request template.
 */
public enum OperationType {
    READ_ONLY,
    STATE_CHANGING,
    CONSUMABLE,
    IDEMPOTENT,
    UNKNOWN
}

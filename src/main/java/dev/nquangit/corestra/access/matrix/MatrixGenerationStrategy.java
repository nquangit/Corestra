package dev.nquangit.corestra.access.matrix;

/**
 * Defines matrix row generation strategies.
 */
public enum MatrixGenerationStrategy {
    OWNER_VS_OTHERS,
    ANONYMOUS_ACCESS,
    ROLE_ESCALATION,
    MAKER_CHECKER,
    CROSS_TENANT,
    CROSS_BRANCH,
    CROSS_ROLE,
    OBJECT_OWNERSHIP_VARIANTS
}

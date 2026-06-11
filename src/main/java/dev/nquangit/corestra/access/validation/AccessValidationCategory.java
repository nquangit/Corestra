package dev.nquangit.corestra.access.validation;

/**
 * Functional area associated with an access workspace validation issue.
 */
public enum AccessValidationCategory {
    /**
     * Auth profile configuration.
     */
    AUTH_PROFILE,

    /**
     * Auth provider configuration.
     */
    AUTH_PROVIDER,

    /**
     * Data profile configuration.
     */
    DATA_PROFILE,

    /**
     * Access request template configuration.
     */
    TEMPLATE,

    /**
     * Matrix row configuration.
     */
    MATRIX_ROW,

    /**
     * Scenario setup or reuse configuration.
     */
    SCENARIO,

    /**
     * Evaluation profile configuration.
     */
    EVALUATION_PROFILE,

    /**
     * Post-check configuration.
     */
    POST_CHECK,

    /**
     * Teardown configuration.
     */
    TEARDOWN,

    /**
     * Finding/reporting configuration.
     */
    REPORTING,

    /**
     * Export security awareness issue.
     */
    EXPORT_SECURITY
}

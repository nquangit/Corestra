package dev.nquangit.corestra.access.validation;

/**
 * Severity assigned to an access workspace validation issue.
 */
public enum AccessValidationSeverity {
    /**
     * Blocking misconfiguration that should be fixed before running affected tests.
     */
    ERROR,

    /**
     * Risky or incomplete configuration that may still be intentional.
     */
    WARNING,

    /**
     * Informational security or usability note.
     */
    INFO
}

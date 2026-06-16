package dev.nquangit.corestra.access.runner;

import java.util.Objects;

/**
 * Describes one diagnostic found before running an access plan.
 */
public final class PlanValidationIssue {
    private final String testCaseId;
    private final String testCaseName;
    private final PlanValidationSeverity severity;
    private final String category;
    private final String message;

    /**
     * Creates a validation issue.
     *
     * @param testCaseId source row id
     * @param testCaseName source row name
     * @param severity issue severity
     * @param category issue category
     * @param message human-readable issue message
     */
    public PlanValidationIssue(
            String testCaseId,
            String testCaseName,
            PlanValidationSeverity severity,
            String category,
            String message) {
        this.testCaseId = normalize(testCaseId);
        this.testCaseName = normalize(testCaseName);
        this.severity = Objects.requireNonNullElse(severity, PlanValidationSeverity.WARNING);
        this.category = normalize(category);
        this.message = normalize(message);
    }

    /** @return source row id */
    public String testCaseId() {
        return testCaseId;
    }

    /** @return source row name */
    public String testCaseName() {
        return testCaseName;
    }

    /** @return issue severity */
    public PlanValidationSeverity severity() {
        return severity;
    }

    /** @return issue category */
    public String category() {
        return category;
    }

    /** @return issue message */
    public String message() {
        return message;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}

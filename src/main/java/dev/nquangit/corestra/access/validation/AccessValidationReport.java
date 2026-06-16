package dev.nquangit.corestra.access.validation;

import java.util.List;
import java.util.Objects;

/**
 * Immutable report produced by validating an access workspace.
 */
public final class AccessValidationReport {
    private final List<AccessValidationIssue> issues;
    private final int errorCount;
    private final int warningCount;
    private final int infoCount;
    private final boolean hasBlockingErrors;

    /**
     * Creates a validation report.
     *
     * @param issues validation issues
     */
    public AccessValidationReport(List<AccessValidationIssue> issues) {
        this.issues = List.copyOf(Objects.requireNonNullElse(issues, List.of()));
        this.errorCount = count(AccessValidationSeverity.ERROR);
        this.warningCount = count(AccessValidationSeverity.WARNING);
        this.infoCount = count(AccessValidationSeverity.INFO);
        this.hasBlockingErrors = errorCount > 0;
    }

    /** @return validation issues */
    public List<AccessValidationIssue> issues() {
        return issues;
    }

    /** @return number of errors */
    public int errorCount() {
        return errorCount;
    }

    /** @return number of warnings */
    public int warningCount() {
        return warningCount;
    }

    /** @return number of info issues */
    public int infoCount() {
        return infoCount;
    }

    /** @return true when the report contains blocking errors */
    public boolean hasBlockingErrors() {
        return hasBlockingErrors;
    }

    private int count(AccessValidationSeverity severity) {
        int count = 0;
        for (AccessValidationIssue issue : issues) {
            if (issue.severity() == severity) {
                count++;
            }
        }
        return count;
    }
}

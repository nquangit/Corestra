package dev.nquangit.corestra.access.importer;

import dev.nquangit.corestra.access.template.AccessRequestTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Result of importing access request templates.
 */
public final class ImportResult {
    private final List<AccessRequestTemplate> templates;
    private final List<ImportWarning> warnings;
    private final List<ImportError> errors;
    private final int skippedCount;

    /**
     * Creates an import result.
     *
     * @param templates imported templates
     * @param warnings non-fatal warnings
     * @param errors import errors
     * @param skippedCount skipped item count
     */
    public ImportResult(
            List<AccessRequestTemplate> templates,
            List<ImportWarning> warnings,
            List<ImportError> errors,
            int skippedCount) {
        this.templates = List.copyOf(Objects.requireNonNull(templates, "templates"));
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        this.errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        this.skippedCount = Math.max(0, skippedCount);
    }

    /**
     * Returns imported templates.
     *
     * @return immutable imported templates
     */
    public List<AccessRequestTemplate> templates() {
        return templates;
    }

    /**
     * Returns import warnings.
     *
     * @return immutable warnings
     */
    public List<ImportWarning> warnings() {
        return warnings;
    }

    /**
     * Returns import errors.
     *
     * @return immutable errors
     */
    public List<ImportError> errors() {
        return errors;
    }

    /**
     * Returns skipped item count.
     *
     * @return skipped item count
     */
    public int skippedCount() {
        return skippedCount;
    }

    /**
     * Returns whether no templates were imported.
     *
     * @return true when no templates were imported
     */
    public boolean empty() {
        return templates.isEmpty();
    }
}

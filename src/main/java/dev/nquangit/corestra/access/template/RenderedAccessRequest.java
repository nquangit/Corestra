package dev.nquangit.corestra.access.template;

import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.access.variable.UnresolvedVariable;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Contains a rendered access request and the diagnostics produced while rendering it.
 */
public final class RenderedAccessRequest {
    private final String templateId;
    private final StoredRequestTemplate originalTemplate;
    private final StoredRequestTemplate renderedRequest;
    private final List<ScopedVariableRef> resolvedVariables;
    private final List<UnresolvedVariable> unresolvedVariables;
    private final List<String> warnings;
    private final List<TemplateRenderIssue> issues;
    private final boolean success;

    /**
     * Creates a rendered access request.
     *
     * @param templateId source access template identifier
     * @param originalTemplate original immutable request template
     * @param renderedRequest rendered request template
     * @param resolvedVariables variables resolved while rendering
     * @param unresolvedVariables unresolved variables preserved in the rendered request
     * @param warnings non-fatal warnings
     * @param issues unresolved variables with request-part locations
     * @param success whether rendering completed without unresolved variables
     */
    public RenderedAccessRequest(
            String templateId,
            StoredRequestTemplate originalTemplate,
            StoredRequestTemplate renderedRequest,
            List<ScopedVariableRef> resolvedVariables,
            List<UnresolvedVariable> unresolvedVariables,
            List<String> warnings,
            List<TemplateRenderIssue> issues,
            boolean success) {
        this.templateId = normalizeRequiredText(templateId, "templateId");
        this.originalTemplate = Objects.requireNonNull(originalTemplate, "originalTemplate");
        this.renderedRequest = Objects.requireNonNull(renderedRequest, "renderedRequest");
        this.resolvedVariables = List.copyOf(Objects.requireNonNull(resolvedVariables, "resolvedVariables"));
        this.unresolvedVariables = List.copyOf(Objects.requireNonNull(unresolvedVariables, "unresolvedVariables"));
        this.warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        this.success = success && this.unresolvedVariables.isEmpty();
    }

    /**
     * Returns the source access template identifier.
     *
     * @return source template identifier
     */
    public String templateId() {
        return templateId;
    }

    /**
     * Returns the original immutable request template.
     *
     * @return original request template
     */
    public StoredRequestTemplate originalTemplate() {
        return originalTemplate;
    }

    /**
     * Returns the rendered request template.
     *
     * @return rendered request template
     */
    public StoredRequestTemplate renderedRequest() {
        return renderedRequest;
    }

    /**
     * Returns variables resolved while rendering.
     *
     * @return immutable resolved variables
     */
    public List<ScopedVariableRef> resolvedVariables() {
        return resolvedVariables;
    }

    /**
     * Returns unresolved variables preserved in the rendered request.
     *
     * @return immutable unresolved variables
     */
    public List<UnresolvedVariable> unresolvedVariables() {
        return unresolvedVariables;
    }

    /**
     * Returns non-fatal warnings.
     *
     * @return immutable warnings
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Returns unresolved variables with request-part locations.
     *
     * @return immutable render issues
     */
    public List<TemplateRenderIssue> issues() {
        return issues;
    }

    /**
     * Returns whether rendering completed without unresolved variables.
     *
     * @return true when no unresolved variables were reported
     */
    public boolean success() {
        return success;
    }

    /**
     * Compares this rendered request to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RenderedAccessRequest that)) {
            return false;
        }
        return success == that.success
                && Objects.equals(templateId, that.templateId)
                && Objects.equals(originalTemplate, that.originalTemplate)
                && Objects.equals(renderedRequest, that.renderedRequest)
                && Objects.equals(resolvedVariables, that.resolvedVariables)
                && Objects.equals(unresolvedVariables, that.unresolvedVariables)
                && Objects.equals(warnings, that.warnings)
                && Objects.equals(issues, that.issues);
    }

    /**
     * Returns the hash code for this rendered request.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(templateId, originalTemplate, renderedRequest,
                resolvedVariables, unresolvedVariables, warnings, issues, success);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "RenderedAccessRequest{" +
                "templateId='" + templateId + '\'' +
                ", originalTemplate=" + originalTemplate +
                ", renderedRequest=" + renderedRequest +
                ", resolvedVariables=" + resolvedVariables +
                ", unresolvedVariables=" + unresolvedVariables +
                ", warnings=" + warnings +
                ", issues=" + issues +
                ", success=" + success +
                '}';
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }
}

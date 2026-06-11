package dev.nquangit.corestra.access.report;

import java.util.Objects;

/**
 * Exports finding drafts as Markdown.
 */
public final class MarkdownFindingExporter {
    /**
     * Returns the export format.
     *
     * @return Markdown format
     */
    public FindingExportFormat format() {
        return FindingExportFormat.MARKDOWN;
    }

    /**
     * Exports a draft as Markdown.
     *
     * @param draft finding draft
     * @return Markdown text
     */
    public String export(FindingDraft draft) {
        Objects.requireNonNull(draft, "draft");
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(draft.titleSuggestion()).append("\n\n");
        markdown.append("**Severity:** ").append(draft.severity()).append("\n\n");

        markdown.append("## Summary\n\n");
        line(markdown, "Affected template", draft.affectedTemplate());
        line(markdown, "Affected endpoint", draft.affectedEndpoint());
        line(markdown, "Target auth profile", draft.targetAuthProfile());
        line(markdown, "Data profile / object owner", draft.dataProfile() + " / " + draft.objectOwner());
        line(markdown, "Expected result", draft.expectedResult().name());
        line(markdown, "Actual verdict", draft.actualVerdict().name());
        line(markdown, "Evaluation reason", draft.evaluationReason());

        markdown.append("\n## Reproduction Steps\n\n");
        for (int index = 0; index < draft.reproductionSteps().size(); index++) {
            markdown.append(index + 1).append(". ")
                    .append(draft.reproductionSteps().get(index))
                    .append('\n');
        }

        markdown.append("\n## Evidence\n\n");
        markdown.append("Evidence is redacted by default: ")
                .append(draft.evidence().redacted() ? "yes" : "no")
                .append("\n\n");
        if (!draft.postCheckSummaries().isEmpty()) {
            markdown.append("### Post-check Summary\n\n");
            for (String summary : draft.postCheckSummaries()) {
                markdown.append("- ").append(summary).append('\n');
            }
            markdown.append('\n');
        }
        markdown.append("### Request\n\n");
        evidence(markdown, draft.evidence().requestEvidence(), "No redacted request evidence captured.");
        markdown.append("\n### Response\n\n");
        evidence(markdown, draft.evidence().responseEvidence(), "No redacted response evidence captured.");

        markdown.append("\n## Impact\n\n");
        markdown.append(draft.impact()).append("\n\n");
        markdown.append("## Remediation\n\n");
        markdown.append(draft.remediation()).append('\n');

        if (!draft.warnings().isEmpty()) {
            markdown.append("\n## Notes\n\n");
            for (String warning : draft.warnings()) {
                markdown.append("- ").append(warning).append('\n');
            }
        }
        return markdown.toString();
    }

    private static void line(StringBuilder markdown, String label, String value) {
        markdown.append("- **").append(label).append(":** ")
                .append(value == null || value.isBlank() ? "Unknown" : value)
                .append('\n');
    }

    private static void evidence(StringBuilder markdown, String value, String fallback) {
        if (value == null || value.isBlank()) {
            markdown.append(fallback).append('\n');
            return;
        }
        markdown.append("```http\n")
                .append(value.replace("```", "`` `"))
                .append("\n```\n");
    }
}

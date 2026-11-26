package dev.nquangit.corestra.intel.flow;

import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.intel.SuggestionConfidence;

import java.util.Objects;

/**
 * One "extract here, inject there" proposal found by matching captured values.
 *
 * @param producerStepId step whose captured response (or existing extractor) yields the value
 * @param producerStepName display name of the producer step
 * @param variableName flow variable carrying the value
 * @param newExtractRule extractor to add to the producer step, or null when an existing extractor already yields it
 * @param injectRule inject rule to add to the target step
 * @param location human-readable place in the target request
 * @param valuePreview masked preview of the matched value
 * @param confidence how distinctive the match is
 * @param reason why the suggestion was made
 */
public record CorrelationSuggestion(
        String producerStepId,
        String producerStepName,
        String variableName,
        ExtractRule newExtractRule,
        InjectRule injectRule,
        String location,
        String valuePreview,
        SuggestionConfidence confidence,
        String reason) {

    /** Validates required fields. */
    public CorrelationSuggestion {
        Objects.requireNonNull(producerStepId, "producerStepId");
        Objects.requireNonNull(variableName, "variableName");
        Objects.requireNonNull(injectRule, "injectRule");
        Objects.requireNonNull(confidence, "confidence");
        producerStepName = Objects.requireNonNullElse(producerStepName, "");
        location = Objects.requireNonNullElse(location, "");
        valuePreview = Objects.requireNonNullElse(valuePreview, "");
        reason = Objects.requireNonNullElse(reason, "");
    }

    /**
     * Returns whether an existing extractor already provides the variable.
     *
     * @return true when no new extractor is needed
     */
    public boolean reusesExistingExtractor() {
        return newExtractRule == null;
    }

    /**
     * Returns a one-line label for review lists.
     *
     * @return display label
     */
    public String displayLabel() {
        String source = reusesExistingExtractor()
                ? "existing {{" + variableName + "}}"
                : "new " + newExtractRule.extractorType() + " " + newExtractRule.sourceSelector() + " → {{" + variableName + "}}";
        return "[" + confidence + "] " + location + "  ←  " + producerStepName + ": " + source
                + "  (" + valuePreview + ")";
    }
}

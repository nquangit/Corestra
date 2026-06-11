package dev.nquangit.corestra.access.report;

import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.result.Verdict;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Editable finding draft generated from one access test result and its evidence.
 */
public final class FindingDraft {
    private final String titleSuggestion;
    private final FindingSeverity severity;
    private final String affectedTemplate;
    private final String affectedEndpoint;
    private final String targetAuthProfile;
    private final String dataProfile;
    private final String objectOwner;
    private final ExpectedResult expectedResult;
    private final Verdict actualVerdict;
    private final String evaluationReason;
    private final EvidenceBundle evidence;
    private final List<String> postCheckSummaries;
    private final List<String> reproductionSteps;
    private final String impact;
    private final String remediation;
    private final List<String> warnings;

    /**
     * Creates a finding draft.
     *
     * @param titleSuggestion suggested title
     * @param severity suggested severity
     * @param affectedTemplate affected template name
     * @param affectedEndpoint affected endpoint
     * @param targetAuthProfile target actor profile
     * @param dataProfile data profile
     * @param objectOwner owning actor profile
     * @param expectedResult expected access result
     * @param actualVerdict actual evaluation verdict
     * @param evaluationReason evaluation reason
     * @param evidence request and response evidence
     * @param postCheckSummaries post-check summary lines
     * @param reproductionSteps reproduction steps
     * @param impact impact placeholder
     * @param remediation remediation placeholder
     * @param warnings non-fatal draft warnings
     */
    public FindingDraft(
            String titleSuggestion,
            FindingSeverity severity,
            String affectedTemplate,
            String affectedEndpoint,
            String targetAuthProfile,
            String dataProfile,
            String objectOwner,
            ExpectedResult expectedResult,
            Verdict actualVerdict,
            String evaluationReason,
            EvidenceBundle evidence,
            List<String> postCheckSummaries,
            List<String> reproductionSteps,
            String impact,
            String remediation,
            List<String> warnings) {
        this.titleSuggestion = normalize(titleSuggestion);
        this.severity = Objects.requireNonNullElse(severity, FindingSeverity.INFO);
        this.affectedTemplate = normalize(affectedTemplate);
        this.affectedEndpoint = normalize(affectedEndpoint);
        this.targetAuthProfile = normalize(targetAuthProfile);
        this.dataProfile = normalize(dataProfile);
        this.objectOwner = normalize(objectOwner);
        this.expectedResult = Objects.requireNonNullElse(expectedResult, ExpectedResult.REVIEW);
        this.actualVerdict = Objects.requireNonNullElse(actualVerdict, Verdict.REVIEW);
        this.evaluationReason = normalize(evaluationReason);
        this.evidence = Objects.requireNonNullElseGet(evidence, () -> new EvidenceBundle("", "", true));
        this.postCheckSummaries = copyStrings(postCheckSummaries);
        this.reproductionSteps = copyStrings(reproductionSteps);
        this.impact = normalize(impact);
        this.remediation = normalize(remediation);
        this.warnings = copyStrings(warnings);
    }

    /**
     * Returns the suggested title.
     *
     * @return suggested title
     */
    public String titleSuggestion() {
        return titleSuggestion;
    }

    /**
     * Returns the suggested severity.
     *
     * @return suggested severity
     */
    public FindingSeverity severity() {
        return severity;
    }

    /**
     * Returns the affected template name.
     *
     * @return affected template name
     */
    public String affectedTemplate() {
        return affectedTemplate;
    }

    /**
     * Returns the affected endpoint.
     *
     * @return affected endpoint
     */
    public String affectedEndpoint() {
        return affectedEndpoint;
    }

    /**
     * Returns the target actor profile.
     *
     * @return target actor profile
     */
    public String targetAuthProfile() {
        return targetAuthProfile;
    }

    /**
     * Returns the data profile.
     *
     * @return data profile
     */
    public String dataProfile() {
        return dataProfile;
    }

    /**
     * Returns the object owner.
     *
     * @return object owner
     */
    public String objectOwner() {
        return objectOwner;
    }

    /**
     * Returns the expected access result.
     *
     * @return expected access result
     */
    public ExpectedResult expectedResult() {
        return expectedResult;
    }

    /**
     * Returns the actual evaluation verdict.
     *
     * @return actual verdict
     */
    public Verdict actualVerdict() {
        return actualVerdict;
    }

    /**
     * Returns the evaluation reason.
     *
     * @return evaluation reason
     */
    public String evaluationReason() {
        return evaluationReason;
    }

    /**
     * Returns the evidence bundle.
     *
     * @return evidence bundle
     */
    public EvidenceBundle evidence() {
        return evidence;
    }

    /**
     * Returns post-check summary lines.
     *
     * @return immutable post-check summary lines
     */
    public List<String> postCheckSummaries() {
        return postCheckSummaries;
    }

    /**
     * Returns reproduction steps.
     *
     * @return immutable reproduction steps
     */
    public List<String> reproductionSteps() {
        return reproductionSteps;
    }

    /**
     * Returns the impact placeholder.
     *
     * @return impact placeholder
     */
    public String impact() {
        return impact;
    }

    /**
     * Returns the remediation placeholder.
     *
     * @return remediation placeholder
     */
    public String remediation() {
        return remediation;
    }

    /**
     * Returns draft warnings.
     *
     * @return immutable warning list
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * Compares this draft to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FindingDraft that)) {
            return false;
        }
        return Objects.equals(titleSuggestion, that.titleSuggestion)
                && severity == that.severity
                && Objects.equals(affectedTemplate, that.affectedTemplate)
                && Objects.equals(affectedEndpoint, that.affectedEndpoint)
                && Objects.equals(targetAuthProfile, that.targetAuthProfile)
                && Objects.equals(dataProfile, that.dataProfile)
                && Objects.equals(objectOwner, that.objectOwner)
                && expectedResult == that.expectedResult
                && actualVerdict == that.actualVerdict
                && Objects.equals(evaluationReason, that.evaluationReason)
                && Objects.equals(evidence, that.evidence)
                && Objects.equals(postCheckSummaries, that.postCheckSummaries)
                && Objects.equals(reproductionSteps, that.reproductionSteps)
                && Objects.equals(impact, that.impact)
                && Objects.equals(remediation, that.remediation)
                && Objects.equals(warnings, that.warnings);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(titleSuggestion, severity, affectedTemplate, affectedEndpoint, targetAuthProfile,
                dataProfile, objectOwner, expectedResult, actualVerdict, evaluationReason, evidence,
                postCheckSummaries, reproductionSteps, impact, remediation, warnings);
    }

    private static List<String> copyStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<String> copied = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                copied.add(value.strip());
            }
        }
        return List.copyOf(copied);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}

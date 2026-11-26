package dev.nquangit.corestra.intel.access;

import dev.nquangit.corestra.access.matrix.MatrixGenerationStrategy;

import java.util.List;
import java.util.Objects;

/**
 * Access-control risk assessment and test recommendations for one request template.
 *
 * @param templateId template id
 * @param templateName template display name
 * @param score risk score from 0 to 100
 * @param level risk band derived from the score
 * @param reasons contributions to the score, most important first
 * @param identifiers object identifiers in the request
 * @param operation inferred operation metadata
 * @param operationDiffers whether the inferred metadata differs from the template's current metadata
 * @param strategies matrix strategies worth running, with missing prerequisites
 * @param sensitiveFieldPaths JSON paths of sensitive fields seen in the captured response
 * @param readinessIssues what blocks matrix generation for this template right now
 */
public record AccessInsight(
        String templateId,
        String templateName,
        int score,
        RiskLevel level,
        List<String> reasons,
        List<IdentifierOccurrence> identifiers,
        OperationClassifier.OperationSuggestion operation,
        boolean operationDiffers,
        List<StrategyRecommendation> strategies,
        List<String> sensitiveFieldPaths,
        List<String> readinessIssues) {

    /** Freezes collections and validates fields. */
    public AccessInsight {
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(operation, "operation");
        templateName = Objects.requireNonNullElse(templateName, "");
        reasons = List.copyOf(reasons);
        identifiers = List.copyOf(identifiers);
        strategies = List.copyOf(strategies);
        sensitiveFieldPaths = List.copyOf(sensitiveFieldPaths);
        readinessIssues = List.copyOf(readinessIssues);
    }

    /**
     * Returns literal (not yet parameterized) identifiers.
     *
     * @return literal identifiers
     */
    public List<IdentifierOccurrence> literalIdentifiers() {
        return identifiers.stream().filter(identifier -> !identifier.parameterized()).toList();
    }

    /** Risk band. */
    public enum RiskLevel { HIGH, MEDIUM, LOW }

    /**
     * A recommended matrix strategy.
     *
     * @param strategy strategy to run
     * @param reason why it fits this template
     * @param missingPrerequisites workspace setup still needed before it can generate rows
     */
    public record StrategyRecommendation(
            MatrixGenerationStrategy strategy,
            String reason,
            List<String> missingPrerequisites) {
        /** Freezes prerequisites. */
        public StrategyRecommendation {
            Objects.requireNonNull(strategy, "strategy");
            reason = Objects.requireNonNullElse(reason, "");
            missingPrerequisites = List.copyOf(missingPrerequisites);
        }

        /**
         * Returns whether the strategy can generate rows now.
         *
         * @return true when nothing is missing
         */
        public boolean ready() {
            return missingPrerequisites.isEmpty();
        }
    }
}

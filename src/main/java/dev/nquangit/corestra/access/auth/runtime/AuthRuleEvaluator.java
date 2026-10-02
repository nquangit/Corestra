package dev.nquangit.corestra.access.auth.runtime;

import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Evaluates configured AuthProvider success and failure rules against an auth flow result.
 */
public final class AuthRuleEvaluator {
    /**
     * Evaluates auth rules for a flow run.
     *
     * @param provider auth provider whose rules should be evaluated
     * @param purpose flow purpose
     * @param runResult flow run result
     * @return evaluation result
     */
    public AuthRuleEvaluationResult evaluate(
            AuthProvider provider,
            AuthFlowPurpose purpose,
            AuthFlowRunResult runResult) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(runResult, "runResult");
        if (!runResult.executed()) {
            return AuthRuleEvaluationResult.failure(runResult.reason(), runResult.warnings());
        }

        List<String> warnings = new ArrayList<>(runResult.warnings());
        for (AuthFailureRule rule : provider.failureRules()) {
            if (rule.enabled() && matches(rule.type(), rule.expression(), rule.expectedValue(), runResult, warnings)) {
                return AuthRuleEvaluationResult.failure(
                        "Auth " + purpose.name().toLowerCase(Locale.ROOT) + " failure rule matched: " + rule.name(),
                        warnings);
            }
        }

        List<AuthSuccessRule> successRules = provider.successRules().stream()
                .filter(AuthSuccessRule::enabled)
                .toList();
        if (!successRules.isEmpty()) {
            for (AuthSuccessRule rule : successRules) {
                if (matches(rule.type(), rule.expression(), rule.expectedValue(), runResult, warnings)) {
                    return AuthRuleEvaluationResult.success(
                            "Auth " + purpose.name().toLowerCase(Locale.ROOT) + " success rule matched: " + rule.name(),
                            warnings);
                }
            }
            return AuthRuleEvaluationResult.failure(
                    "No enabled auth " + purpose.name().toLowerCase(Locale.ROOT) + " success rule matched",
                    warnings);
        }

        if (runResult.success()) {
            return AuthRuleEvaluationResult.success(runResult.reason(), warnings);
        }
        return AuthRuleEvaluationResult.failure(runResult.reason(), warnings);
    }

    boolean matches(
            AuthRuleType type,
            String expression,
            String expectedValue,
            AuthFlowRunResult runResult,
            List<String> warnings) {
        AuthRuleType ruleType = Objects.requireNonNullElse(type, AuthRuleType.CUSTOM);
        return switch (ruleType) {
            case STATUS_CODE -> matchesStatus(expression, expectedValue, runResult.finalStatusCode());
            case HEADER_EXISTS -> headerExists(runResult.finalResponseRaw(), expression);
            case BODY_CONTAINS -> body(runResult.finalResponseRaw()).contains(ruleText(expression, expectedValue));
            case BODY_NOT_CONTAINS -> !body(runResult.finalResponseRaw()).contains(ruleText(expression, expectedValue));
            case JSON_PATH, CUSTOM -> {
                warnings.add("Auth rule type is not supported: " + ruleType
                        + "; use STATUS_CODE, HEADER_EXISTS, BODY_CONTAINS, or BODY_NOT_CONTAINS");
                yield false;
            }
        };
    }

    private boolean matchesStatus(String expression, String expectedValue, int actual) {
        String candidate = ruleText(expression, expectedValue);
        try {
            return Integer.parseInt(candidate.strip()) == actual;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean headerExists(String rawResponse, String headerName) {
        if (isBlank(rawResponse) || isBlank(headerName)) {
            return false;
        }
        String expected = headerName.strip().toLowerCase(Locale.ROOT);
        String normalized = rawResponse.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty()) {
                return false;
            }
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0 && line.substring(0, colonIndex).strip().toLowerCase(Locale.ROOT).equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private String body(String rawResponse) {
        if (rawResponse == null || rawResponse.isEmpty()) {
            return "";
        }
        String normalized = rawResponse.replace("\r\n", "\n");
        int separator = normalized.indexOf("\n\n");
        return separator < 0 ? "" : normalized.substring(separator + 2);
    }

    private String ruleText(String expression, String expectedValue) {
        return !isBlank(expectedValue) ? expectedValue.strip() : Objects.requireNonNullElse(expression, "").strip();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

package dev.nquangit.corestra.access.runner;

import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRuleType;

import java.util.Locale;
import java.util.Objects;

final class TargetAuthFailureDetector {
    boolean isAuthFailure(AuthProvider provider, int statusCode, String rawResponse) {
        Objects.requireNonNull(provider, "provider");
        for (AuthFailureRule rule : provider.failureRules()) {
            if (rule.enabled() && matches(rule, statusCode, rawResponse)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(AuthFailureRule rule, int statusCode, String rawResponse) {
        AuthRuleType ruleType = Objects.requireNonNullElse(rule.type(), AuthRuleType.CUSTOM);
        return switch (ruleType) {
            case STATUS_CODE -> matchesStatus(ruleText(rule.expression(), rule.expectedValue()), statusCode);
            case HEADER_EXISTS -> headerExists(rawResponse, rule.expression());
            case BODY_CONTAINS -> body(rawResponse).contains(ruleText(rule.expression(), rule.expectedValue()));
            case BODY_NOT_CONTAINS -> !body(rawResponse).contains(ruleText(rule.expression(), rule.expectedValue()));
            case JSON_PATH, CUSTOM -> false;
        };
    }

    private boolean matchesStatus(String expected, int actual) {
        try {
            return Integer.parseInt(expected.strip()) == actual;
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

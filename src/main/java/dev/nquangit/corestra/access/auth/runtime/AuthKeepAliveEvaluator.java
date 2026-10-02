package dev.nquangit.corestra.access.auth.runtime;

import dev.nquangit.corestra.access.auth.AuthKeepAliveConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Distinguishes a live session, explicit expiry, and inconclusive keep-alive failures. */
public final class AuthKeepAliveEvaluator {
    private final AuthRuleEvaluator rules;

    /** Creates an evaluator sharing the authentication rule matcher. */
    public AuthKeepAliveEvaluator(AuthRuleEvaluator rules) {
        this.rules = Objects.requireNonNull(rules, "rules");
    }

    /** Evaluates a response without treating a transport failure as session expiry. */
    public Result evaluate(AuthKeepAliveConfig config, AuthFlowRunResult run) {
        ArrayList<String> warnings = new ArrayList<>(run.warnings());
        if (!run.executed() || run.finalStatusCode() <= 0) {
            return new Result(Outcome.INCONCLUSIVE, run.reason(), warnings);
        }
        for (var rule : config.expiredRules()) {
            if (rule.enabled() && hasExpression(rule.expression(), rule.expectedValue())
                    && rules.matches(rule.type(), rule.expression(), rule.expectedValue(), run, warnings)) {
                return new Result(Outcome.EXPIRED, "Keep-alive session-expired rule matched: " + rule.name(), warnings);
            }
        }
        if (run.success() && run.finalStatusCode() >= 200 && run.finalStatusCode() < 400) {
            for (var rule : config.successRules()) {
                if (rule.enabled() && hasExpression(rule.expression(), rule.expectedValue())
                        && rules.matches(rule.type(), rule.expression(), rule.expectedValue(), run, warnings)) {
                    return new Result(Outcome.ALIVE, "Keep-alive success rule matched: " + rule.name(), warnings);
                }
            }
        }
        return new Result(Outcome.INCONCLUSIVE,
                "Keep-alive did not confirm success or session expiry: " + run.reason(), warnings);
    }

    private static boolean hasExpression(String expression, String expectedValue) {
        return !expression.isBlank() || !expectedValue.isBlank();
    }

    /** Meaning of a keep-alive response. */
    public enum Outcome { ALIVE, EXPIRED, INCONCLUSIVE }

    /** Immutable evaluation details suitable for UI and scheduler diagnostics. */
    public record Result(Outcome outcome, String reason, List<String> warnings) {
        /** Creates immutable evaluation details. */
        public Result {
            warnings = List.copyOf(warnings);
        }
    }
}

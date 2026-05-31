package dev.nquangit.corestra.http.inject;

import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Replaces all occurrences of a regex pattern in a request body with a given replacement value.
 *
 * <p>When the pattern compiles but produces no matches, the original body is returned unchanged.
 * Pattern compilation errors are surfaced via the returned {@link ReplaceOutcome}.
 */
public final class RegexBodyInjector {

    /**
     * Replaces all matches of {@code pattern} in {@code body} with {@code replacement}.
     *
     * @param body        the request body to mutate; must not be {@code null}
     * @param pattern     the regex pattern to search for; must not be {@code null}
     * @param replacement the replacement string; must not be {@code null}
     * @return a {@link ReplaceOutcome} carrying the resulting body or a failure message
     */
    public ReplaceOutcome inject(String body, String pattern, String replacement) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(replacement, "replacement");

        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return ReplaceOutcome.failure("Invalid regex pattern '" + pattern + "': " + e.getMessage());
        }

        String result = compiled.matcher(body).replaceAll(replacement);
        return ReplaceOutcome.success(result);
    }

    /**
     * Represents the outcome of a single regex-replace injection attempt.
     */
    public static final class ReplaceOutcome {

        private final String body;
        private final String failureReason;
        private final boolean succeeded;

        private ReplaceOutcome(String body, String failureReason, boolean succeeded) {
            this.body = body;
            this.failureReason = failureReason;
            this.succeeded = succeeded;
        }

        /**
         * Creates a successful outcome carrying the (possibly unchanged) body.
         *
         * @param body the body after replacement
         * @return a successful {@code ReplaceOutcome}
         */
        public static ReplaceOutcome success(String body) {
            return new ReplaceOutcome(body, null, true);
        }

        /**
         * Creates a failed outcome carrying an error message.
         *
         * @param reason a human-readable description of the failure
         * @return a failed {@code ReplaceOutcome}
         */
        public static ReplaceOutcome failure(String reason) {
            return new ReplaceOutcome(null, reason, false);
        }

        /**
         * Returns {@code true} when the injection completed without error.
         *
         * @return {@code true} on success
         */
        public boolean succeeded() {
            return succeeded;
        }

        /**
         * Returns the body after replacement, or {@code null} on failure.
         *
         * @return the mutated body, or {@code null}
         */
        public String body() {
            return body;
        }

        /**
         * Returns the failure reason, or {@code null} on success.
         *
         * @return the failure reason, or {@code null}
         */
        public String failureReason() {
            return failureReason;
        }
    }
}

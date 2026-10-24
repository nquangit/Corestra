package dev.nquangit.corestra.session;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Project-level conditions for explicitly selecting an existing session before live Burp requests. */
public record SessionSelectionConfig(boolean enabled, SessionScope scope, List<String> toolTypes,
                                     int timeoutSeconds, String stripHeader) {
    /** Tools exposed by the interactive live-request integration. Internal extension traffic is excluded. */
    public static final List<String> SUPPORTED_TOOLS = List.of("REPEATER", "PROXY", "INTRUDER", "SCANNER");

    /** Creates validated selection settings; empty tool lists match no requests. */
    public SessionSelectionConfig {
        scope = Objects.requireNonNullElseGet(scope, () -> SessionScope.custom(List.of(
                new SessionScopeCriterion(SessionCriterionType.HEADER, "X-User-Selection", ""))));
        toolTypes = Objects.requireNonNullElse(toolTypes, List.of("REPEATER")).stream()
                .map(value -> value.strip().toUpperCase(Locale.ROOT)).distinct().toList();
        if (!Set.copyOf(SUPPORTED_TOOLS).containsAll(toolTypes)) throw new IllegalArgumentException("Unsupported session selection tool");
        if (enabled && toolTypes.isEmpty()) throw new IllegalArgumentException("Select at least one tool for Send with session");
        timeoutSeconds = timeoutSeconds == 0 ? 300 : timeoutSeconds;
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) throw new IllegalArgumentException("Selection timeout must be between 1 and 3600 seconds");
        stripHeader = stripHeader == null ? "X-User-Selection" : stripHeader.strip();
        if (!stripHeader.isEmpty() && !stripHeader.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("Header to strip must be a valid HTTP header name");
        }
        if (enabled) {
            scope.criteria().forEach(SessionSelectionConfig::validateCriterion);
            scope.rules().forEach(rule -> rule.criteria().forEach(SessionSelectionConfig::validateCriterion));
        }
    }

    /** Disabled by default; preset asks on Repeater requests carrying X-User-Selection. */
    public static SessionSelectionConfig disabled() {
        return new SessionSelectionConfig(false, null, List.of("REPEATER"), 300, "X-User-Selection");
    }

    /** Whether this live request should ask the operator for a session. */
    public boolean matches(SessionTrafficContext context) {
        return enabled && toolTypes.contains(context.toolTypeName().toUpperCase(Locale.ROOT)) && scope.matches(context);
    }

    private static void validateCriterion(SessionScopeCriterion criterion) {
        criterion.validationError().ifPresent(message -> { throw new IllegalArgumentException(message); });
    }
}

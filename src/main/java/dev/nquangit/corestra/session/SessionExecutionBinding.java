package dev.nquangit.corestra.session;

import dev.nquangit.corestra.flow.execution.FlowInputValidator;
import dev.nquangit.corestra.flow.execution.ExecutionContext;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Immutable execution metadata for a pinned session; carries no credentials or token values. */
public record SessionExecutionBinding(String profileId, long generation, String fingerprint,
                                      String tool, List<String> origins) {
    private static final String KEY = "corestra.boundSession.";
    /** Creates a binding with exact destination origins. */
    public SessionExecutionBinding {
        if (profileId == null || profileId.isBlank() || fingerprint == null || fingerprint.isBlank()
                || tool == null || tool.isBlank() || generation < 0 || origins == null || origins.isEmpty()) {
            throw new IllegalArgumentException("Incomplete session execution binding");
        }
        origins = origins.stream().map(SessionExecutionBinding::normalizeOrigin).distinct().toList();
    }
    /** Whether this execution carries a session binding. */
    public static boolean isBound(ExecutionContext context) { return context.metadata().containsKey(KEY + "profile"); }
    /** Reads a binding, rejecting incomplete or malformed metadata. */
    public static SessionExecutionBinding read(ExecutionContext context) {
        try {
            Map<String, String> m = context.metadata();
            return new SessionExecutionBinding(m.get(KEY + "profile"), Long.parseLong(m.get(KEY + "generation")),
                    m.get(KEY + "fingerprint"), m.get(KEY + "tool"), List.of(m.get(KEY + "origins").split(",")));
        } catch (RuntimeException exception) { throw new SessionBindingException("Invalid execution binding"); }
    }
    /** Returns metadata to carry across prerequisite, live, downstream, retry and child-flow execution. */
    public Map<String, String> metadata() {
        return Map.of(KEY + "profile", profileId, KEY + "generation", Long.toString(generation),
                KEY + "fingerprint", fingerprint, KEY + "tool", tool, KEY + "origins", String.join(",", origins),
                FlowInputValidator.REQUIRE_INPUTS, "true");
    }
    /** Produces a canonical exact HTTP origin; paths, credentials and wildcards are rejected. */
    public static String normalizeOrigin(String value) {
        try {
            URI uri = URI.create(value.strip());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost();
            int port = uri.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
            if ((!scheme.equals("http") && !scheme.equals("https")) || host == null || host.isBlank()
                    || (uri.getRawAuthority() != null && uri.getRawAuthority().endsWith(":"))
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (!uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/")) || port < 1 || port > 65535) {
                throw new IllegalArgumentException();
            }
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + ":" + port;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Session origin must be an exact HTTP(S) origin, e.g. https://api.example.test:443");
        }
    }
    /** Checks the actual service destination, independently of selector headers or stored templates. */
    public void requireDestination(SessionTrafficContext context) {
        String host = context.host();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        try {
            if (origins.contains(normalizeOrigin(context.scheme() + "://" + host + ":" + context.port()))) return;
        } catch (IllegalArgumentException ignored) { }
        throw new SessionBindingException("Request destination is outside the allowed session origins");
    }
}

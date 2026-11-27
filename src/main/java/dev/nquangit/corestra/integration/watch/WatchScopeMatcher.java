package dev.nquangit.corestra.integration.watch;

import dev.nquangit.corestra.watch.WatchScope;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Decides whether a request is in a {@link WatchScope}. Montoya-free. The Repeater-only rule is
 * enforced by the caller ({@link RepeaterWatchHttpHandler}).
 */
public final class WatchScopeMatcher {
    private WatchScopeMatcher() { }

    /**
     * Returns whether the request matches the scope: method in {@code methods} (empty = any),
     * {@code hostPattern} found in the host and {@code pathPattern} found in path+query (blank = any).
     *
     * @param scope        scope
     * @param method       request method
     * @param host         request host
     * @param pathAndQuery request path including query
     * @return true when in scope
     * @throws java.util.regex.PatternSyntaxException when a pattern is invalid
     */
    public static boolean matches(WatchScope scope, String method, String host, String pathAndQuery) {
        Objects.requireNonNull(scope, "scope");
        String m = method == null ? "" : method.strip().toUpperCase(Locale.ROOT);
        if (!scope.methods().isEmpty() && !scope.methods().contains(m)) return false;
        if (!scope.hostPattern().isBlank()
                && !Pattern.compile(scope.hostPattern(), Pattern.CASE_INSENSITIVE).matcher(host == null ? "" : host).find()) {
            return false;
        }
        return scope.pathPattern().isBlank()
                || Pattern.compile(scope.pathPattern()).matcher(pathAndQuery == null ? "" : pathAndQuery).find();
    }
}

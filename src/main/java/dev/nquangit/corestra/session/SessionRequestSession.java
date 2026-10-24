package dev.nquangit.corestra.session;

import java.util.stream.Collectors;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
/** Short-lived live-request handoff; retains original selector headers but omits the request body. */
public record SessionRequestSession(String profileId, String fingerprint, SessionTrafficContext originalTraffic) {
    /** Captures a fingerprint and original selector headers, omitting the potentially large request body. */
    public SessionRequestSession(SessionInjectionPlan plan, SessionTrafficContext traffic) {
        this(plan.profileId(), SessionInjectionFingerprint.of(plan), new SessionTrafficContext(traffic.toolTypeName(),
                traffic.burpInScope(), traffic.method(), traffic.host(), traffic.path(), traffic.headers(), "", traffic.scheme(), traffic.port()));
    }
    /** Matches actual headers, retaining original selector headers only when absent after preparation. */
    public static List<String> matchHeaders(List<String> original, List<String> prepared) {
        var names = prepared.stream().map(SessionRequestSession::headerName).collect(Collectors.toSet());
        var headers = new ArrayList<>(prepared);
        original.stream().filter(line -> !names.contains(headerName(line))).forEach(headers::add);
        return List.copyOf(headers);
    }

    private static String headerName(String line) {
        int colon = line.indexOf(':');
        return (colon < 0 ? line : line.substring(0, colon)).strip().toLowerCase(Locale.ROOT);
    }
}


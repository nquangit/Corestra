package dev.nquangit.corestra.watch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;

/**
 * Which Repeater requests a Watch applies to. Watch only ever observes Repeater traffic.
 *
 * @param methods     upper-case HTTP methods; empty matches any method
 * @param hostPattern Java regex matched (find) against the request host; blank matches any host
 * @param pathPattern Java regex matched (find) against path and query; blank matches any path
 */
public record WatchScope(List<String> methods, String hostPattern, String pathPattern) {
    /**
     * Normalizes null fields so documents written by older versions keep loading.
     *
     * @param methods     HTTP methods
     * @param hostPattern host regex
     * @param pathPattern path regex
     */
    @JsonCreator
    public WatchScope(@JsonProperty("methods") List<String> methods,
                      @JsonProperty("hostPattern") String hostPattern,
                      @JsonProperty("pathPattern") String pathPattern) {
        this.methods = methods == null ? List.of()
                : methods.stream().filter(m -> m != null && !m.isBlank())
                        .map(m -> m.strip().toUpperCase(Locale.ROOT)).toList();
        this.hostPattern = hostPattern == null ? "" : hostPattern.strip();
        this.pathPattern = pathPattern == null ? "" : pathPattern.strip();
    }

    /**
     * Returns a scope matching POST requests to any host and path.
     *
     * @return default scope
     */
    public static WatchScope defaults() {
        return new WatchScope(List.of("POST"), "", "");
    }
}

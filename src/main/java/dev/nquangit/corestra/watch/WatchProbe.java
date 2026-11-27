package dev.nquangit.corestra.watch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.List;

/**
 * The read-only request a Watch sends to capture state (for example {@code GET /accounts}).
 *
 * <p>{@code {{name}}} placeholders anywhere in the template are rendered from the Watch's
 * request variables. Header values named in {@code inheritHeaders} are copied from the live
 * Repeater request so the probe observes the same actor/session the tester is using.</p>
 *
 * @param request        probe request template; null means not configured yet
 * @param inheritHeaders header names copied from the live request (case-insensitive)
 */
public record WatchProbe(StoredRequestTemplate request, List<String> inheritHeaders) {
    /** Default inherited headers. */
    public static final List<String> DEFAULT_INHERIT_HEADERS = List.of("Cookie", "Authorization");

    /**
     * Normalizes null fields.
     *
     * @param request        probe template
     * @param inheritHeaders inherited header names
     */
    @JsonCreator
    public WatchProbe(@JsonProperty("request") StoredRequestTemplate request,
                      @JsonProperty("inheritHeaders") List<String> inheritHeaders) {
        this.request = request;
        this.inheritHeaders = inheritHeaders == null ? DEFAULT_INHERIT_HEADERS
                : inheritHeaders.stream().filter(h -> h != null && !h.isBlank()).map(String::strip).toList();
    }

    /**
     * Returns whether a probe request has been configured.
     *
     * @return true when a template is present
     */
    @JsonIgnore
    public boolean configured() {
        return request != null;
    }
}

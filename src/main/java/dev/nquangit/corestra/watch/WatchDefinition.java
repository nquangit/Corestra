package dev.nquangit.corestra.watch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.flow.rule.ExtractRule;

import java.util.List;

/**
 * A Watch: before/after state observation around one matching Repeater request (or the whole
 * auto-trigger run that request starts), with optional expectations and response display.
 *
 * <p>Immutable; rebuild with the {@code with...} methods and preserve every field.</p>
 *
 * @param id               stable id
 * @param name             display name
 * @param enabled          whether live Repeater traffic is watched
 * @param scope            which Repeater requests start a watch
 * @param probe            state-reading request
 * @param requestVariables extract rules (REQUEST side) applied to the live request; their target
 *                         variable names are usable in expressions and probe placeholders
 * @param observations     values read from the probe response
 * @param expectations     checks over before/after values
 * @param display          response display options
 * @param timing           waiting behaviour
 */
public record WatchDefinition(String id, String name, boolean enabled, WatchScope scope, WatchProbe probe,
                              List<ExtractRule> requestVariables, List<WatchObservation> observations,
                              List<WatchExpectation> expectations, WatchDisplayOptions display,
                              WatchTiming timing) {
    /**
     * Normalizes null fields so older documents keep loading.
     *
     * @param id               id
     * @param name             name
     * @param enabled          enabled
     * @param scope            scope
     * @param probe            probe
     * @param requestVariables request variable rules
     * @param observations     observations
     * @param expectations     expectations
     * @param display          display options
     * @param timing           timing
     */
    @JsonCreator
    public WatchDefinition(@JsonProperty("id") String id,
                           @JsonProperty("name") String name,
                           @JsonProperty("enabled") boolean enabled,
                           @JsonProperty("scope") WatchScope scope,
                           @JsonProperty("probe") WatchProbe probe,
                           @JsonProperty("requestVariables") List<ExtractRule> requestVariables,
                           @JsonProperty("observations") List<WatchObservation> observations,
                           @JsonProperty("expectations") List<WatchExpectation> expectations,
                           @JsonProperty("display") WatchDisplayOptions display,
                           @JsonProperty("timing") WatchTiming timing) {
        this.id = id == null ? "" : id;
        this.name = name == null || name.isBlank() ? "Watch" : name.strip();
        this.enabled = enabled;
        this.scope = scope == null ? WatchScope.defaults() : scope;
        this.probe = probe == null ? new WatchProbe(null, null) : probe;
        this.requestVariables = requestVariables == null ? List.of() : List.copyOf(requestVariables);
        this.observations = observations == null ? List.of() : List.copyOf(observations);
        this.expectations = expectations == null ? List.of() : List.copyOf(expectations);
        this.display = display == null ? WatchDisplayOptions.defaults() : display;
        this.timing = timing == null ? WatchTiming.defaults() : timing;
    }

    /**
     * Creates a new, disabled, empty Watch.
     *
     * @param id   id
     * @param name name
     * @return empty definition
     */
    public static WatchDefinition empty(String id, String name) {
        return new WatchDefinition(id, name, false, null, null, null, null, null, null, null);
    }

    /** @param value new value @return copy */
    public WatchDefinition withName(String value) { return new WatchDefinition(id, value, enabled, scope, probe, requestVariables, observations, expectations, display, timing); }
    /** @param value new value @return copy */
    public WatchDefinition withEnabled(boolean value) { return new WatchDefinition(id, name, value, scope, probe, requestVariables, observations, expectations, display, timing); }
    /** @param value new value @return copy */
    public WatchDefinition withScope(WatchScope value) { return new WatchDefinition(id, name, enabled, value, probe, requestVariables, observations, expectations, display, timing); }
    /** @param value new value @return copy */
    public WatchDefinition withProbe(WatchProbe value) { return new WatchDefinition(id, name, enabled, scope, value, requestVariables, observations, expectations, display, timing); }
    /** @param value new value @return copy */
    public WatchDefinition withRequestVariables(List<ExtractRule> value) { return new WatchDefinition(id, name, enabled, scope, probe, value, observations, expectations, display, timing); }
    /** @param value new value @return copy */
    public WatchDefinition withObservations(List<WatchObservation> value) { return new WatchDefinition(id, name, enabled, scope, probe, requestVariables, value, expectations, display, timing); }
    /** @param value new value @return copy */
    public WatchDefinition withExpectations(List<WatchExpectation> value) { return new WatchDefinition(id, name, enabled, scope, probe, requestVariables, observations, value, display, timing); }
    /** @param value new value @return copy */
    public WatchDefinition withDisplay(WatchDisplayOptions value) { return new WatchDefinition(id, name, enabled, scope, probe, requestVariables, observations, expectations, value, timing); }
    /** @param value new value @return copy */
    public WatchDefinition withTiming(WatchTiming value) { return new WatchDefinition(id, name, enabled, scope, probe, requestVariables, observations, expectations, display, value); }
}

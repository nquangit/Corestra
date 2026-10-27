package dev.nquangit.corestra.flow;

import dev.nquangit.corestra.session.SessionExecutionBinding;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Per-step auto-trigger rule.
 *
 * <p>When {@link #enabled()} and an incoming HTTP request matches ALL {@link #criteria()}, the
 * Corestra HTTP handler:
 * <ol>
 *   <li>Runs all enabled steps before this one (prerequisites) using their stored templates,
 *       building up the variable context.</li>
 *   <li>Applies this step's extract rules to the <em>live incoming request</em>.</li>
 *   <li>Applies this step's inject rules to the <em>live incoming request</em>.</li>
 *   <li>Forwards the modified request.</li>
 * </ol>
 *
 * <p>An empty {@link #criteria()} list means "match all requests". An empty
 * {@link #allowedToolTypes()} list means "all ordinary tools"; Extensions requires
 * an explicit EXTENSIONS/EXTENDER entry. Each criterion is AND-ed.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
@JsonIgnoreProperties(ignoreUnknown = true)
public final class StepTriggerRule {
    /** Explicit empty selection; an absent/empty legacy list still selects all ordinary tools. */
    public static final String NO_TOOLS = "NONE";

    public enum ExecutionScope {
        MATCHED_STEP,
        STOP_AT_STEP,
        FULL_FLOW
    }

    private final boolean enabled;
    private final List<String> allowedToolTypes;
    private final List<MatchCriterion> criteria;
    private final ExecutionScope scope;
    private final String stopAtStepId;
    private final boolean inheritSession;
    private final List<String> sessionOrigins;
    private final TriggerInputBindings inputBindings;

    /** Creates a trigger with optional execution-wide session inheritance and exact extra origins. */
    @JsonCreator
    public StepTriggerRule(
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("allowedToolTypes") List<String> allowedToolTypes,
            @JsonProperty("criteria") List<MatchCriterion> criteria,
            @JsonProperty("scope") ExecutionScope scope,
            @JsonProperty("stopAtStepId") String stopAtStepId,
            @JsonProperty("inheritSession") boolean inheritSession,
            @JsonProperty("sessionOrigins") List<String> sessionOrigins,
            @JsonProperty("inputBindings") TriggerInputBindings inputBindings) {
        this.inputBindings = Objects.requireNonNullElseGet(inputBindings, TriggerInputBindings::none);
        this.inheritSession = inheritSession;
        this.sessionOrigins = (sessionOrigins == null ? List.<String>of() : sessionOrigins).stream()
                .map(SessionExecutionBinding::normalizeOrigin).distinct().toList();
        this.enabled = enabled;
        this.allowedToolTypes = normalizeList(allowedToolTypes);
        this.criteria = criteria == null ? List.of()
                : List.copyOf(new ArrayList<>(criteria));
        this.scope = scope != null ? scope : ExecutionScope.MATCHED_STEP;
        String rawStopId = stopAtStepId == null ? "" : stopAtStepId.strip();
        this.stopAtStepId = (this.scope == ExecutionScope.STOP_AT_STEP) ? rawStopId : "";
    }

    /** Creates a trigger without profile inputs, compatible with existing callers. */
    public StepTriggerRule(boolean enabled, List<String> tools, List<MatchCriterion> criteria,
                           ExecutionScope scope, String stopAtStepId, boolean inheritSession, List<String> origins) {
        this(enabled, tools, criteria, scope, stopAtStepId, inheritSession, origins, TriggerInputBindings.none());
    }

    /** Returns inputs supplied only to automatic executions of this trigger. */
    public TriggerInputBindings inputBindings() { return inputBindings; }

    /** Creates a legacy trigger without session inheritance. */
    public StepTriggerRule(boolean enabled, List<String> tools, List<MatchCriterion> criteria,
                           ExecutionScope scope, String stopAtStepId) {
        this(enabled, tools, criteria, scope, stopAtStepId, false, List.of());
    }

    /** Whether all executed steps must use the initiating request's session. */
    public boolean inheritSession() { return inheritSession; }

    /** Additional exact origins explicitly allowed to receive the inherited authentication. */
    public List<String> sessionOrigins() { return sessionOrigins; }

    /** Backward-compatible 3-arg constructor — scope defaults to MATCHED_STEP. */
    public StepTriggerRule(boolean enabled, List<String> allowedToolTypes, List<MatchCriterion> criteria) {
        this(enabled, allowedToolTypes, criteria, null, null);
    }

    /** Returns a disabled rule with no criteria. */
    public static StepTriggerRule disabled() {
        return new StepTriggerRule(false, List.of(), List.of());
    }

    /**
     * Returns a disabled rule pre-populated with HOST and PATH criteria derived from a captured
     * request template. The patterns are literal (via {@link Pattern#quote}) so they match the
     * exact host and path of the original request.
     *
     * @param template the captured request template
     * @return a pre-populated, disabled rule
     */
    public static StepTriggerRule fromTemplate(StoredRequestTemplate template) {
        if (template == null) return disabled();

        List<MatchCriterion> criteria = new ArrayList<>();

        String host = template.host();
        if (host != null && !host.isEmpty()) {
            criteria.add(new MatchCriterion(MatchCriterion.MatchType.HOST, "", Pattern.quote(host)));
        }

        String pathAndQuery = template.pathAndQuery() != null ? template.pathAndQuery() : "";
        int qIdx = pathAndQuery.indexOf('?');
        String pathOnly = qIdx >= 0 ? pathAndQuery.substring(0, qIdx) : pathAndQuery;
        if (!pathOnly.isEmpty()) {
            criteria.add(new MatchCriterion(MatchCriterion.MatchType.PATH, "", Pattern.quote(pathOnly)));
        }

        return new StepTriggerRule(false, List.of(), List.copyOf(criteria));
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public boolean enabled() { return enabled; }
    public List<String> allowedToolTypes() { return allowedToolTypes; }
    public List<MatchCriterion> criteria() { return criteria; }
    public ExecutionScope scope() { return scope; }
    public String stopAtStepId() { return stopAtStepId; }

    // ── Matching ──────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} when the rule is satisfied by the given request attributes.
     * ALL criteria must match; an empty criteria list matches everything.
     *
     * @param toolTypeName Burp ToolType.name() (e.g. "PROXY")
     * @param host         request host
     * @param path         request path without query string
     * @param headers      all headers as "Name: Value" strings
     * @param body         request body text
     */
    public boolean matches(String toolTypeName, String host, String path,
                           List<String> headers, String body) {
        if (!isToolAllowed(toolTypeName)) return false;
        for (MatchCriterion c : criteria) {
            if (!c.matches(host, path, headers, body)) return false;
        }
        return true;
    }

    private boolean isToolAllowed(String typeName) {
        if ("EXTENSIONS".equals(typeName)) {
            return allowedToolTypes.contains("EXTENSIONS") || allowedToolTypes.contains("EXTENDER");
        }
        return allowedToolTypes.isEmpty() || allowedToolTypes.contains(typeName);
    }

    // ── equals / hashCode / toString ──────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StepTriggerRule that)) return false;
        return enabled == that.enabled
                && inheritSession == that.inheritSession
                && sessionOrigins.equals(that.sessionOrigins)
                && inputBindings.equals(that.inputBindings)
                && Objects.equals(allowedToolTypes, that.allowedToolTypes)
                && Objects.equals(criteria, that.criteria)
                && scope == that.scope
                && Objects.equals(stopAtStepId, that.stopAtStepId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, allowedToolTypes, criteria, scope, stopAtStepId, inheritSession, sessionOrigins, inputBindings);
    }

    @Override
    public String toString() {
        return "StepTriggerRule{enabled=" + enabled
                + ", allowedToolTypes=" + allowedToolTypes
                + ", criteria=" + criteria
                + ", scope=" + scope
                + ", stopAtStepId='" + stopAtStepId + "'}";
    }

    // ── Inner class: MatchCriterion ───────────────────────────────────────────

    /**
     * A single match criterion — one aspect of the request that must match a regex pattern.
     * Multiple criteria are AND-ed together in a {@link StepTriggerRule}.
     */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class MatchCriterion {

        /** The part of the request this criterion inspects. */
        public enum MatchType {
            /** Matches against the request host. */
            HOST,
            /** Matches against the request path (no query string). */
            PATH,
            /** Matches against a specific request header's value. */
            HEADER,
            /** Matches against the full request body text. */
            BODY
        }

        private final MatchType type;
        private final String selector;   // header name for HEADER; empty for others
        private final String pattern;    // regex

        @JsonCreator
        public MatchCriterion(
                @JsonProperty("type") MatchType type,
                @JsonProperty("selector") String selector,
                @JsonProperty("pattern") String pattern) {
            this.type = Objects.requireNonNullElse(type, MatchType.HOST);
            this.selector = selector == null ? "" : selector.strip();
            this.pattern = pattern == null ? "" : pattern.strip();
        }

        public MatchType type()     { return type; }
        public String selector()    { return selector; }
        public String pattern()     { return pattern; }

        /**
         * Returns {@code true} when this criterion is satisfied by the given request attributes.
         * An empty pattern always matches.
         */
        public boolean matches(String host, String path, List<String> headers, String body) {
            String target = switch (type) {
                case HOST   -> host;
                case PATH   -> path;
                case HEADER -> findHeaderValue(headers, selector);
                case BODY   -> body;
            };
            return matchesPattern(pattern, target);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MatchCriterion that)) return false;
            return type == that.type
                    && Objects.equals(selector, that.selector)
                    && Objects.equals(pattern, that.pattern);
        }

        @Override
        public int hashCode() { return Objects.hash(type, selector, pattern); }

        @Override
        public String toString() {
            return "MatchCriterion{type=" + type
                    + ", selector='" + selector + '\''
                    + ", pattern='" + pattern + "'}";
        }

        // Displayed in the JTable cell
        public String displayType() {
            return switch (type) {
                case HOST   -> "Host";
                case PATH   -> "Path";
                case HEADER -> "Header: " + selector;
                case BODY   -> "Body";
            };
        }
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    static boolean matchesPattern(String pattern, String value) {
        if (pattern == null || pattern.isEmpty()) return true;
        if (value == null) return false;
        try {
            return Pattern.compile(pattern).matcher(value).find();
        } catch (PatternSyntaxException ignored) {
            return false;
        }
    }

    static String findHeaderValue(List<String> headers, String name) {
        if (name == null || name.isEmpty()) return "";
        String prefix = name.toLowerCase() + ":";
        for (String h : headers) {
            if (h.toLowerCase().startsWith(prefix)) {
                return h.substring(h.indexOf(':') + 1).strip();
            }
        }
        return "";
    }

    private static List<String> normalizeList(List<String> values) {
        if (values == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) out.add(v.strip());
        }
        return List.copyOf(out);
    }
}

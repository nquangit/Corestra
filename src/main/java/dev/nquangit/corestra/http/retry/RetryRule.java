package dev.nquangit.corestra.http.retry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Named decision for a response: when its conditions match, either retry ({@link Action#RETRY}) or
 * stop and deliver the response as-is ({@link Action#STOP}), e.g. a permanent application 403.
 * Rules in a profile are evaluated in order; the first match wins.
 *
 * @param id          stable identifier
 * @param name        display name, used in response notes
 * @param enabled     disabled rules are skipped
 * @param action      what to do on a match
 * @param conditions  conditions; same group = AND, different groups = OR
 * @param delayMillis fixed wait before each retry, overriding Retry-After/backoff; {@code null} = profile
 * @param maxRetries  resend limit while this rule matches; {@code null} = profile
 * @param highlight   Burp highlight for a delivered response that matches this rule
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetryRule(String id, String name, boolean enabled, Action action, List<RetryCondition> conditions,
                        Long delayMillis, Integer maxRetries, Highlight highlight) {

    /** What a matching rule does. */
    public enum Action {
        /** Resend the request (within profile and rule limits). */
        RETRY,
        /** Deliver the response unchanged and stop evaluating further rules. */
        STOP
    }

    /** Burp highlight colors (names match Montoya's {@code HighlightColor}). */
    public enum Highlight { NONE, RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PINK, MAGENTA, GRAY }

    /** Validates and normalizes the rule. */
    public RetryRule {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.strip();
        name = name == null || name.isBlank() ? "Rule" : name.strip();
        action = Objects.requireNonNullElse(action, Action.RETRY);
        conditions = List.copyOf(Objects.requireNonNullElse(conditions, List.<RetryCondition>of()).stream()
                .filter(Objects::nonNull).toList());
        if (delayMillis != null && (delayMillis < 0 || delayMillis > RetryProfile.MAX_DELAY_MILLIS)) {
            throw new IllegalArgumentException("Rule '" + name + "': delay must be 0-" + RetryProfile.MAX_DELAY_MILLIS + " ms");
        }
        if (maxRetries != null && (maxRetries < 1 || maxRetries > RetryProfile.MAX_RETRIES)) {
            throw new IllegalArgumentException("Rule '" + name + "': max retries must be 1-" + RetryProfile.MAX_RETRIES);
        }
        highlight = Objects.requireNonNullElse(highlight, Highlight.NONE);
    }

    /**
     * Creates an enabled rule with a fresh id and profile defaults.
     *
     * @param name       name
     * @param action     action
     * @param conditions conditions
     * @return rule
     */
    public static RetryRule of(String name, Action action, List<RetryCondition> conditions) {
        return new RetryRule(null, name, true, action, conditions, null, null, Highlight.NONE);
    }

    /** Returns a copy with a highlight. */
    public RetryRule withHighlight(Highlight color) {
        return new RetryRule(id, name, enabled, action, conditions, delayMillis, maxRetries, color);
    }

    /** Returns a copy with a fixed retry delay. */
    public RetryRule withDelayMillis(Long delay) {
        return new RetryRule(id, name, enabled, action, conditions, delay, maxRetries, highlight);
    }

    /** Returns a copy with a fresh id (for duplicating). */
    public RetryRule withNewId() {
        return new RetryRule(null, name, enabled, action, conditions, delayMillis, maxRetries, highlight);
    }

    /** Returns enabled conditions grouped by OR-group number, in group order. */
    public List<List<RetryCondition>> enabledGroups() {
        Map<Integer, List<RetryCondition>> groups = new TreeMap<>();
        for (RetryCondition condition : conditions) {
            if (condition.enabled()) groups.computeIfAbsent(condition.group(), key -> new ArrayList<>()).add(condition);
        }
        return groups.values().stream().map(List::copyOf).toList();
    }

    /** Returns a readable boolean expression, e.g. {@code (status = "403" AND ...) OR (...)}. */
    public String expression() {
        List<List<RetryCondition>> groups = enabledGroups();
        if (groups.isEmpty()) return "(no enabled conditions — never matches)";
        List<String> parts = new ArrayList<>();
        for (List<RetryCondition> group : groups) {
            String joined = String.join(" AND ", group.stream().map(RetryCondition::describe).toList());
            parts.add(groups.size() > 1 && group.size() > 1 ? "(" + joined + ")" : joined);
        }
        return String.join("\nOR ", parts);
    }
}

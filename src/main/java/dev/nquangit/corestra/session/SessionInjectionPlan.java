package dev.nquangit.corestra.session;

import dev.nquangit.corestra.flow.rule.InjectRule;

import java.util.List;

/**
 * Immutable request-injection snapshot prepared while holding one Session Profile's auth lock.
 *
 * @param profileId selected Session Profile id
 * @param authenticationGeneration in-memory generation of the authentication state
 * @param rules fully rendered request injection rules
 * @param authenticationRevision linked actor/provider identity and authentication attempt epoch
 */
public record SessionInjectionPlan(
        String profileId,
        long authenticationGeneration,
        List<InjectRule> rules,
        String authenticationRevision) {

    /** Creates an injection plan without an explicit linked-actor revision. */
    public SessionInjectionPlan(String profileId, long generation, List<InjectRule> rules) {
        this(profileId, generation, rules, "");
    }

    /** Creates a validated immutable injection plan. */
    public SessionInjectionPlan {
        authenticationRevision = authenticationRevision == null ? "" : authenticationRevision;
        profileId = profileId == null ? "" : profileId;
        authenticationGeneration = Math.max(0L, authenticationGeneration);
        rules = List.copyOf(rules == null ? List.of() : rules);
    }
}

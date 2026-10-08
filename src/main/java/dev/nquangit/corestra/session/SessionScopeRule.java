package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;

/** A named alternative scope rule whose criteria must all match the same request. */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public record SessionScopeRule(String name, List<SessionScopeCriterion> criteria) {
    /** Creates an immutable rule. An empty rule never matches traffic. */
    @JsonCreator
    public SessionScopeRule(@JsonProperty("name") String name,
                            @JsonProperty("criteria") List<SessionScopeCriterion> criteria) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Scope rule name must not be blank");
        if (name.strip().equalsIgnoreCase("Common")) throw new IllegalArgumentException("Common is reserved for shared scope conditions");
        this.name = name.strip();
        this.criteria = List.copyOf(Objects.requireNonNullElse(criteria, List.of()));
    }

    /** Returns true only when the nonempty rule matches every criterion. */
    public boolean matches(SessionTrafficContext context) {
        return !criteria.isEmpty() && criteria.stream().allMatch(criterion -> criterion.matches(context));
    }
}

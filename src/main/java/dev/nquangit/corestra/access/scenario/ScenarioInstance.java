package dev.nquangit.corestra.access.scenario;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.access.matrix.ScenarioPolicy;

import java.time.Clock;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Captures scenario state produced by setup flow execution for later target request rendering.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ScenarioInstance {
    private final String id;
    private final String name;
    private final String ownerDataProfileId;
    private final Map<String, ScenarioVariable> variables;
    private final ScenarioPolicy policy;
    private final long createdAtEpochMillis;
    private final boolean consumed;
    private final String producedByFlowId;
    private final String producedByUntilStepId;

    /**
     * Creates a scenario instance.
     *
     * @param id stable scenario identifier
     * @param name display name
     * @param ownerDataProfileId owner data profile id
     * @param variables scenario variables keyed without {@code scenario.} prefix
     * @param policy scenario reuse policy
     * @param createdAtEpochMillis creation timestamp in epoch milliseconds
     * @param consumed whether the scenario has been consumed
     * @param producedByFlowId producing flow id
     * @param producedByUntilStepId prefix stop step id, or empty for whole setup flow
     */
    @JsonCreator
    public ScenarioInstance(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("ownerDataProfileId") String ownerDataProfileId,
            @JsonProperty("variables") Map<String, ScenarioVariable> variables,
            @JsonProperty("policy") ScenarioPolicy policy,
            @JsonProperty("createdAtEpochMillis") long createdAtEpochMillis,
            @JsonProperty("consumed") boolean consumed,
            @JsonProperty("producedByFlowId") String producedByFlowId,
            @JsonProperty("producedByUntilStepId") String producedByUntilStepId) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.ownerDataProfileId = normalizeOptionalText(ownerDataProfileId);
        this.variables = copyVariables(variables);
        this.policy = Objects.requireNonNullElse(policy, ScenarioPolicy.REUSE_EXISTING);
        this.createdAtEpochMillis = validateEpochMillis(createdAtEpochMillis, "createdAtEpochMillis");
        this.consumed = consumed;
        this.producedByFlowId = normalizeOptionalText(producedByFlowId);
        this.producedByUntilStepId = normalizeOptionalText(producedByUntilStepId);
    }

    /**
     * Returns the stable scenario identifier.
     *
     * @return scenario identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the display name.
     *
     * @return display name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the owner data profile id.
     *
     * @return owner data profile id, or an empty string
     */
    public String ownerDataProfileId() {
        return ownerDataProfileId;
    }

    /**
     * Returns scenario variables keyed without the {@code scenario.} prefix.
     *
     * @return immutable scenario variable map
     */
    public Map<String, ScenarioVariable> variables() {
        return variables;
    }

    /**
     * Returns the scenario policy.
     *
     * @return scenario policy
     */
    public ScenarioPolicy policy() {
        return policy;
    }

    /**
     * Returns the creation timestamp.
     *
     * @return creation timestamp in epoch milliseconds
     */
    public long createdAtEpochMillis() {
        return createdAtEpochMillis;
    }

    /**
     * Returns whether this scenario has been consumed.
     *
     * @return true when consumed
     */
    public boolean consumed() {
        return consumed;
    }

    /**
     * Returns the producing flow id.
     *
     * @return producing flow id, or an empty string
     */
    public String producedByFlowId() {
        return producedByFlowId;
    }

    /**
     * Returns the prefix stop step id.
     *
     * @return until-before step id, or an empty string
     */
    public String producedByUntilStepId() {
        return producedByUntilStepId;
    }

    /**
     * Returns scenario variables as a simple key-value map suitable for scoped rendering.
     *
     * @return immutable map of plain scenario keys to values
     */
    public Map<String, String> toVariableMap() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        variables.forEach((key, variable) -> values.put(key, variable.value()));
        return Map.copyOf(values);
    }

    /**
     * Returns whether any variable in this scenario has expired according to the supplied clock.
     *
     * @param clock clock used for comparison
     * @return true when at least one variable is expired
     */
    public boolean isExpired(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return variables.values().stream().anyMatch(variable -> variable.isExpired(clock));
    }

    /**
     * Returns a copy of this scenario marked consumed.
     *
     * @return consumed scenario copy
     */
    public ScenarioInstance markConsumed() {
        return new ScenarioInstance(
                id,
                name,
                ownerDataProfileId,
                variables,
                policy,
                createdAtEpochMillis,
                true,
                producedByFlowId,
                producedByUntilStepId);
    }

    /**
     * Compares this scenario to another object.
     *
     * @param other the candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScenarioInstance that)) {
            return false;
        }
        return createdAtEpochMillis == that.createdAtEpochMillis
                && consumed == that.consumed
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(ownerDataProfileId, that.ownerDataProfileId)
                && Objects.equals(variables, that.variables)
                && policy == that.policy
                && Objects.equals(producedByFlowId, that.producedByFlowId)
                && Objects.equals(producedByUntilStepId, that.producedByUntilStepId);
    }

    /**
     * Returns the hash code for this scenario.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, ownerDataProfileId, variables, policy,
                createdAtEpochMillis, consumed, producedByFlowId, producedByUntilStepId);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return diagnostic string
     */
    @Override
    public String toString() {
        return "ScenarioInstance{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", ownerDataProfileId='" + ownerDataProfileId + '\'' +
                ", variables=" + variables.keySet() +
                ", policy=" + policy +
                ", createdAtEpochMillis=" + createdAtEpochMillis +
                ", consumed=" + consumed +
                ", producedByFlowId='" + producedByFlowId + '\'' +
                ", producedByUntilStepId='" + producedByUntilStepId + '\'' +
                '}';
    }

    private static Map<String, ScenarioVariable> copyVariables(Map<String, ScenarioVariable> variables) {
        if (variables == null || variables.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, ScenarioVariable> copied = new LinkedHashMap<>();
        variables.values().forEach(variable -> {
            ScenarioVariable checked = Objects.requireNonNull(variable, "scenario variable");
            copied.put(checked.key(), checked);
        });
        return Collections.unmodifiableMap(copied);
    }

    private static long validateEpochMillis(long value, String fieldName) {
        if (value < 0L) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }

    private static String normalizeRequiredText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}

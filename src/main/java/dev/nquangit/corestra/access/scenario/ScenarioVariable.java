package dev.nquangit.corestra.access.scenario;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Represents one variable produced by setup flow execution for an access-test scenario.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ScenarioVariable {
    private static final String SCENARIO_PREFIX = "scenario.";
    private static final Pattern KEY_SEGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    private final String key;
    private final String value;
    private final long createdAtEpochMillis;
    private final long expiresAtEpochMillis;
    private final boolean consumable;
    private final boolean consumed;
    private final String producedByFlowId;
    private final String producedByStepId;
    private final String ownerDataProfileId;

    /**
     * Creates a scenario variable.
     *
     * <p>Keys are stored without the {@code scenario.} prefix internally. For compatibility with extracted
     * flow variables, constructor input with a {@code scenario.} prefix is accepted and normalized.</p>
     *
     * @param key scenario variable key
     * @param value variable value
     * @param createdAtEpochMillis creation timestamp in epoch milliseconds
     * @param expiresAtEpochMillis expiry timestamp in epoch milliseconds, or 0 when no expiry is known
     * @param consumable whether this variable should be consumed once used
     * @param consumed whether this variable has already been consumed
     * @param producedByFlowId producing flow id
     * @param producedByStepId producing step id
     * @param ownerDataProfileId owner data profile id
     */
    @JsonCreator
    public ScenarioVariable(
            @JsonProperty("key") String key,
            @JsonProperty("value") String value,
            @JsonProperty("createdAtEpochMillis") long createdAtEpochMillis,
            @JsonProperty("expiresAtEpochMillis") long expiresAtEpochMillis,
            @JsonProperty("consumable") boolean consumable,
            @JsonProperty("consumed") boolean consumed,
            @JsonProperty("producedByFlowId") String producedByFlowId,
            @JsonProperty("producedByStepId") String producedByStepId,
            @JsonProperty("ownerDataProfileId") String ownerDataProfileId) {
        this.key = normalizeKey(key);
        this.value = Objects.requireNonNullElse(value, "");
        this.createdAtEpochMillis = validateEpochMillis(createdAtEpochMillis, "createdAtEpochMillis");
        this.expiresAtEpochMillis = validateEpochMillis(expiresAtEpochMillis, "expiresAtEpochMillis");
        this.consumable = consumable;
        this.consumed = consumed;
        this.producedByFlowId = normalizeOptionalText(producedByFlowId);
        this.producedByStepId = normalizeOptionalText(producedByStepId);
        this.ownerDataProfileId = normalizeOptionalText(ownerDataProfileId);
    }

    /**
     * Returns the scenario variable key without a {@code scenario.} prefix.
     *
     * @return scenario variable key
     */
    public String key() {
        return key;
    }

    /**
     * Returns the scenario variable value.
     *
     * @return variable value
     */
    public String value() {
        return value;
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
     * Returns the expiry timestamp.
     *
     * @return expiry timestamp in epoch milliseconds, or 0
     */
    public long expiresAtEpochMillis() {
        return expiresAtEpochMillis;
    }

    /**
     * Returns whether this variable is consumable.
     *
     * @return true when consumable
     */
    public boolean consumable() {
        return consumable;
    }

    /**
     * Returns whether this variable has been consumed.
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
     * Returns the producing step id.
     *
     * @return producing step id, or an empty string
     */
    public String producedByStepId() {
        return producedByStepId;
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
     * Returns whether this variable has expired according to the supplied clock.
     *
     * @param clock clock used for comparison
     * @return true when the variable has a positive expiry timestamp at or before the clock time
     */
    public boolean isExpired(Clock clock) {
        Objects.requireNonNull(clock, "clock");
        return expiresAtEpochMillis > 0L && expiresAtEpochMillis <= clock.millis();
    }

    /**
     * Returns a copy of this variable marked consumed.
     *
     * @return consumed variable copy
     */
    public ScenarioVariable markConsumed() {
        return new ScenarioVariable(
                key,
                value,
                createdAtEpochMillis,
                expiresAtEpochMillis,
                consumable,
                true,
                producedByFlowId,
                producedByStepId,
                ownerDataProfileId);
    }

    /**
     * Compares this variable to another object.
     *
     * @param other the candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScenarioVariable that)) {
            return false;
        }
        return createdAtEpochMillis == that.createdAtEpochMillis
                && expiresAtEpochMillis == that.expiresAtEpochMillis
                && consumable == that.consumable
                && consumed == that.consumed
                && Objects.equals(key, that.key)
                && Objects.equals(value, that.value)
                && Objects.equals(producedByFlowId, that.producedByFlowId)
                && Objects.equals(producedByStepId, that.producedByStepId)
                && Objects.equals(ownerDataProfileId, that.ownerDataProfileId);
    }

    /**
     * Returns the hash code for this variable.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(key, value, createdAtEpochMillis, expiresAtEpochMillis,
                consumable, consumed, producedByFlowId, producedByStepId, ownerDataProfileId);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return diagnostic string
     */
    @Override
    public String toString() {
        return "ScenarioVariable{" +
                "key='" + key + '\'' +
                ", valueLength=" + value.length() +
                ", createdAtEpochMillis=" + createdAtEpochMillis +
                ", expiresAtEpochMillis=" + expiresAtEpochMillis +
                ", consumable=" + consumable +
                ", consumed=" + consumed +
                ", producedByFlowId='" + producedByFlowId + '\'' +
                ", producedByStepId='" + producedByStepId + '\'' +
                ", ownerDataProfileId='" + ownerDataProfileId + '\'' +
                '}';
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        String normalized = value.strip();
        if (normalized.startsWith(SCENARIO_PREFIX)) {
            normalized = normalized.substring(SCENARIO_PREFIX.length());
        }
        if (!isValidKey(normalized)) {
            throw new IllegalArgumentException("key must match segment(.segment)* with non-blank segments");
        }
        return normalized;
    }

    static boolean isValidKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return Arrays.stream(key.split("\\.", -1)).allMatch(KEY_SEGMENT.asMatchPredicate());
    }

    private static long validateEpochMillis(long value, String fieldName) {
        if (value < 0L) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}

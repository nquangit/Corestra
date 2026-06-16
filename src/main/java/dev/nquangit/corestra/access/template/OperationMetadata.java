package dev.nquangit.corestra.access.template;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Describes behavioral metadata for an access request template.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class OperationMetadata {
    private final OperationType operationType;
    private final boolean stateChanging;
    private final boolean consumable;
    private final String description;

    /**
     * Creates operation metadata.
     *
     * @param operationType broad operation classification
     * @param stateChanging whether the operation changes server-side state
     * @param consumable whether the operation consumes a one-time resource or action
     * @param description optional human-readable description
     */
    @JsonCreator
    public OperationMetadata(
            @JsonProperty("operationType") OperationType operationType,
            @JsonProperty("stateChanging") boolean stateChanging,
            @JsonProperty("consumable") boolean consumable,
            @JsonProperty("description") String description) {
        this.operationType = Objects.requireNonNullElse(operationType, OperationType.UNKNOWN);
        this.stateChanging = stateChanging;
        this.consumable = consumable;
        this.description = description == null ? "" : description.strip();
    }

    /**
     * Returns default unknown operation metadata.
     *
     * @return unknown operation metadata
     */
    public static OperationMetadata unknown() {
        return new OperationMetadata(OperationType.UNKNOWN, false, false, "");
    }

    /**
     * Returns the broad operation classification.
     *
     * @return operation classification
     */
    public OperationType operationType() {
        return operationType;
    }

    /**
     * Returns whether the operation changes server-side state.
     *
     * @return whether the operation changes state
     */
    public boolean stateChanging() {
        return stateChanging;
    }

    /**
     * Returns whether the operation consumes a one-time resource or action.
     *
     * @return whether the operation is consumable
     */
    public boolean consumable() {
        return consumable;
    }

    /**
     * Returns the optional description.
     *
     * @return description, or an empty string
     */
    public String description() {
        return description;
    }

    /**
     * Compares this metadata to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OperationMetadata that)) {
            return false;
        }
        return stateChanging == that.stateChanging
                && consumable == that.consumable
                && operationType == that.operationType
                && Objects.equals(description, that.description);
    }

    /**
     * Returns the hash code for this metadata.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(operationType, stateChanging, consumable, description);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "OperationMetadata{" +
                "operationType=" + operationType +
                ", stateChanging=" + stateChanging +
                ", consumable=" + consumable +
                ", description='" + description + '\'' +
                '}';
    }
}

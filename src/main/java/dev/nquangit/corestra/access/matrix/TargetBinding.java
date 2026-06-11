package dev.nquangit.corestra.access.matrix;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Binds an access test to the target actor, data, environment, and scenario instance.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class TargetBinding {
    private final String targetAuthProfileId;
    private final String dataProfileId;
    private final String environmentId;
    private final String scenarioInstanceId;

    /**
     * Creates a target binding.
     *
     * @param targetAuthProfileId target actor auth profile identifier
     * @param dataProfileId data profile identifier
     * @param environmentId environment identifier
     * @param scenarioInstanceId scenario instance identifier
     */
    @JsonCreator
    public TargetBinding(
            @JsonProperty("targetAuthProfileId") String targetAuthProfileId,
            @JsonProperty("dataProfileId") String dataProfileId,
            @JsonProperty("environmentId") String environmentId,
            @JsonProperty("scenarioInstanceId") String scenarioInstanceId) {
        this.targetAuthProfileId = normalizeOptionalText(targetAuthProfileId);
        this.dataProfileId = normalizeOptionalText(dataProfileId);
        this.environmentId = normalizeOptionalText(environmentId);
        this.scenarioInstanceId = normalizeOptionalText(scenarioInstanceId);
    }

    /**
     * Returns an empty target binding.
     *
     * @return empty target binding
     */
    public static TargetBinding empty() {
        return new TargetBinding("", "", "", "");
    }

    /**
     * Returns the target actor auth profile identifier.
     *
     * @return target auth profile identifier, or an empty string
     */
    public String targetAuthProfileId() {
        return targetAuthProfileId;
    }

    /**
     * Returns the data profile identifier.
     *
     * @return data profile identifier, or an empty string
     */
    public String dataProfileId() {
        return dataProfileId;
    }

    /**
     * Returns the environment identifier.
     *
     * @return environment identifier, or an empty string
     */
    public String environmentId() {
        return environmentId;
    }

    /**
     * Returns the scenario instance identifier.
     *
     * @return scenario instance identifier, or an empty string
     */
    public String scenarioInstanceId() {
        return scenarioInstanceId;
    }

    /**
     * Compares this binding to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TargetBinding that)) {
            return false;
        }
        return Objects.equals(targetAuthProfileId, that.targetAuthProfileId)
                && Objects.equals(dataProfileId, that.dataProfileId)
                && Objects.equals(environmentId, that.environmentId)
                && Objects.equals(scenarioInstanceId, that.scenarioInstanceId);
    }

    /**
     * Returns the hash code for this binding.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(targetAuthProfileId, dataProfileId, environmentId, scenarioInstanceId);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "TargetBinding{" +
                "targetAuthProfileId='" + targetAuthProfileId + '\'' +
                ", dataProfileId='" + dataProfileId + '\'' +
                ", environmentId='" + environmentId + '\'' +
                ", scenarioInstanceId='" + scenarioInstanceId + '\'' +
                '}';
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}

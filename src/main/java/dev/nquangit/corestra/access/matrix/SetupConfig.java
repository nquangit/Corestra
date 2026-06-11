package dev.nquangit.corestra.access.matrix;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Describes setup behavior for an access test case.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SetupConfig {
    private final SetupMode mode;
    private final String setupFlowId;
    private final String untilBeforeStepId;
    private final String setupAuthProfileId;
    private final SetupAuthBinding setupAuthBinding;
    private final ScenarioPolicy scenarioPolicy;

    /**
     * Creates setup configuration.
     *
     * @param mode setup mode
     * @param setupFlowId setup flow identifier
     * @param untilBeforeStepId step identifier before which setup should stop
     * @param setupAuthProfileId fixed setup auth profile identifier
     * @param setupAuthBinding setup auth binding strategy
     * @param scenarioPolicy scenario reuse policy
     */
    @JsonCreator
    public SetupConfig(
            @JsonProperty("mode") SetupMode mode,
            @JsonProperty("setupFlowId") String setupFlowId,
            @JsonProperty("untilBeforeStepId") String untilBeforeStepId,
            @JsonProperty("setupAuthProfileId") String setupAuthProfileId,
            @JsonProperty("setupAuthBinding") SetupAuthBinding setupAuthBinding,
            @JsonProperty("scenarioPolicy") ScenarioPolicy scenarioPolicy) {
        this.mode = Objects.requireNonNullElse(mode, SetupMode.NONE);
        this.setupFlowId = normalizeOptionalText(setupFlowId);
        this.untilBeforeStepId = normalizeOptionalText(untilBeforeStepId);
        this.setupAuthProfileId = normalizeOptionalText(setupAuthProfileId);
        this.setupAuthBinding = Objects.requireNonNullElse(setupAuthBinding, SetupAuthBinding.DATA_OWNER);
        this.scenarioPolicy = Objects.requireNonNullElse(scenarioPolicy, ScenarioPolicy.REUSE_EXISTING);
    }

    /**
     * Returns setup configuration that performs no setup.
     *
     * @return no-op setup configuration
     */
    public static SetupConfig none() {
        return new SetupConfig(SetupMode.NONE, "", "", "", SetupAuthBinding.DATA_OWNER, ScenarioPolicy.REUSE_EXISTING);
    }

    /**
     * Returns the setup mode.
     *
     * @return setup mode
     */
    public SetupMode mode() {
        return mode;
    }

    /**
     * Returns the setup flow identifier.
     *
     * @return setup flow identifier, or an empty string
     */
    public String setupFlowId() {
        return setupFlowId;
    }

    /**
     * Returns the step identifier before which setup should stop.
     *
     * @return step identifier, or an empty string
     */
    public String untilBeforeStepId() {
        return untilBeforeStepId;
    }

    /**
     * Returns the fixed setup auth profile identifier.
     *
     * @return setup auth profile identifier, or an empty string
     */
    public String setupAuthProfileId() {
        return setupAuthProfileId;
    }

    /**
     * Returns the setup auth binding strategy.
     *
     * @return setup auth binding strategy
     */
    public SetupAuthBinding setupAuthBinding() {
        return setupAuthBinding;
    }

    /**
     * Returns the scenario reuse policy.
     *
     * @return scenario reuse policy
     */
    public ScenarioPolicy scenarioPolicy() {
        return scenarioPolicy;
    }

    /**
     * Compares this setup config to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SetupConfig that)) {
            return false;
        }
        return mode == that.mode
                && Objects.equals(setupFlowId, that.setupFlowId)
                && Objects.equals(untilBeforeStepId, that.untilBeforeStepId)
                && Objects.equals(setupAuthProfileId, that.setupAuthProfileId)
                && setupAuthBinding == that.setupAuthBinding
                && scenarioPolicy == that.scenarioPolicy;
    }

    /**
     * Returns the hash code for this setup config.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(mode, setupFlowId, untilBeforeStepId, setupAuthProfileId, setupAuthBinding, scenarioPolicy);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "SetupConfig{" +
                "mode=" + mode +
                ", setupFlowId='" + setupFlowId + '\'' +
                ", untilBeforeStepId='" + untilBeforeStepId + '\'' +
                ", setupAuthProfileId='" + setupAuthProfileId + '\'' +
                ", setupAuthBinding=" + setupAuthBinding +
                ", scenarioPolicy=" + scenarioPolicy +
                '}';
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}

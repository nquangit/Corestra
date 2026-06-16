package dev.nquangit.corestra.access.teardown;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * Configures an optional teardown flow for one access test case.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class TeardownConfig {
    private final TeardownMode mode;
    private final String teardownFlowId;
    private final TeardownAuthBinding authBinding;
    private final String teardownAuthProfileId;
    private final boolean runOnSuccess;
    private final boolean runOnFailure;
    private final boolean runOnSkipped;

    /**
     * Creates teardown configuration.
     *
     * @param mode teardown mode
     * @param teardownFlowId flow id to run when mode is RUN_FLOW
     * @param authBinding auth profile binding strategy
     * @param teardownAuthProfileId fixed auth profile id when auth binding is FIXED_PROFILE
     * @param runOnSuccess whether teardown should run after OK/REVIEW target outcomes
     * @param runOnFailure whether teardown should run after failed or suspicious outcomes
     * @param runOnSkipped whether teardown should run after skipped/not-run outcomes
     */
    @JsonCreator
    public TeardownConfig(
            @JsonProperty("mode") TeardownMode mode,
            @JsonProperty("teardownFlowId") String teardownFlowId,
            @JsonProperty("authBinding") TeardownAuthBinding authBinding,
            @JsonProperty("teardownAuthProfileId") String teardownAuthProfileId,
            @JsonProperty("runOnSuccess") boolean runOnSuccess,
            @JsonProperty("runOnFailure") boolean runOnFailure,
            @JsonProperty("runOnSkipped") boolean runOnSkipped) {
        this.mode = Objects.requireNonNullElse(mode, TeardownMode.NONE);
        this.teardownFlowId = normalizeOptionalText(teardownFlowId);
        this.authBinding = Objects.requireNonNullElse(authBinding, TeardownAuthBinding.DATA_OWNER);
        this.teardownAuthProfileId = normalizeOptionalText(teardownAuthProfileId);
        this.runOnSuccess = runOnSuccess;
        this.runOnFailure = runOnFailure;
        this.runOnSkipped = runOnSkipped;
    }

    /**
     * Returns no-op teardown configuration.
     *
     * @return no-op teardown configuration
     */
    public static TeardownConfig none() {
        return new TeardownConfig(TeardownMode.NONE, "", TeardownAuthBinding.DATA_OWNER, "", false, false, false);
    }

    /**
     * Returns default RUN_FLOW configuration for a flow.
     *
     * @param flowId teardown flow id
     * @return RUN_FLOW teardown configuration
     */
    public static TeardownConfig runFlow(String flowId) {
        return new TeardownConfig(TeardownMode.RUN_FLOW, flowId, TeardownAuthBinding.DATA_OWNER, "",
                true, false, false);
    }

    /** @return teardown mode */
    public TeardownMode mode() {
        return mode;
    }

    /** @return flow id to run when enabled */
    public String teardownFlowId() {
        return teardownFlowId;
    }

    /** @return auth binding strategy */
    public TeardownAuthBinding authBinding() {
        return authBinding;
    }

    /** @return fixed auth profile id, or empty */
    public String teardownAuthProfileId() {
        return teardownAuthProfileId;
    }

    /** @return whether teardown runs after OK/REVIEW target outcomes */
    public boolean runOnSuccess() {
        return runOnSuccess;
    }

    /** @return whether teardown runs after failed or suspicious outcomes */
    public boolean runOnFailure() {
        return runOnFailure;
    }

    /** @return whether teardown runs after skipped/not-run outcomes */
    public boolean runOnSkipped() {
        return runOnSkipped;
    }

    /**
     * Compares this config to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TeardownConfig that)) {
            return false;
        }
        return runOnSuccess == that.runOnSuccess
                && runOnFailure == that.runOnFailure
                && runOnSkipped == that.runOnSkipped
                && mode == that.mode
                && Objects.equals(teardownFlowId, that.teardownFlowId)
                && authBinding == that.authBinding
                && Objects.equals(teardownAuthProfileId, that.teardownAuthProfileId);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(mode, teardownFlowId, authBinding, teardownAuthProfileId,
                runOnSuccess, runOnFailure, runOnSkipped);
    }

    /**
     * Returns a diagnostic string.
     *
     * @return diagnostic string
     */
    @Override
    public String toString() {
        return "TeardownConfig{" +
                "mode=" + mode +
                ", teardownFlowId='" + teardownFlowId + '\'' +
                ", authBinding=" + authBinding +
                ", teardownAuthProfileId='" + teardownAuthProfileId + '\'' +
                ", runOnSuccess=" + runOnSuccess +
                ", runOnFailure=" + runOnFailure +
                ", runOnSkipped=" + runOnSkipped +
                '}';
    }

    private static String normalizeOptionalText(String value) {
        return value == null ? "" : value.strip();
    }
}

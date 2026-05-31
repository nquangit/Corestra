package dev.nquangit.corestra.flow;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Defines prerequisite and follow-up flow integration hooks for a flow definition.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class FlowTriggerConfig {
    private final List<String> prerequisiteStepIds;
    private final List<String> prerequisiteFlowIds;
    private final String recoveryFlowId;
    private final boolean sessionHandlingIntegrationEnabled;
    private final List<FollowUpFlowHook> followUpHooks;

    /**
     * Creates flow trigger and follow-up configuration.
     *
     * @param prerequisiteStepIds prerequisite step identifiers inside the current flow
     * @param prerequisiteFlowIds prerequisite flow identifiers that should run before this flow
     * @param recoveryFlowId an optional recovery flow identifier
     * @param sessionHandlingIntegrationEnabled whether session handling integration is enabled
     * @param followUpHooks conditional follow-up flow hooks
     */
    @JsonCreator
    public FlowTriggerConfig(
            @JsonProperty("prerequisiteStepIds") List<String> prerequisiteStepIds,
            @JsonProperty("prerequisiteFlowIds") List<String> prerequisiteFlowIds,
            @JsonProperty("recoveryFlowId") String recoveryFlowId,
            @JsonProperty("sessionHandlingIntegrationEnabled") boolean sessionHandlingIntegrationEnabled,
            @JsonProperty("followUpHooks") List<FollowUpFlowHook> followUpHooks) {
        this.prerequisiteStepIds = normalizeStringList(prerequisiteStepIds);
        this.prerequisiteFlowIds = normalizeStringList(prerequisiteFlowIds);
        this.recoveryFlowId = normalizeOptionalText(recoveryFlowId);
        this.sessionHandlingIntegrationEnabled = sessionHandlingIntegrationEnabled;
        this.followUpHooks = List.copyOf(followUpHooks == null ? List.of() : new ArrayList<>(followUpHooks));
    }

    /**
     * Returns an empty trigger configuration.
     *
     * @return an empty trigger configuration
     */
    public static FlowTriggerConfig none() {
        return new FlowTriggerConfig(List.of(), List.of(), null, false, List.of());
    }

    /**
     * Returns the prerequisite step identifiers.
     *
     * @return the prerequisite step identifiers
     */
    public List<String> prerequisiteStepIds() {
        return prerequisiteStepIds;
    }

    /**
     * Returns the prerequisite flow identifiers.
     *
     * @return the prerequisite flow identifiers
     */
    public List<String> prerequisiteFlowIds() {
        return prerequisiteFlowIds;
    }

    /**
     * Returns the recovery flow identifier.
     *
     * @return the recovery flow identifier, or an empty string when none is configured
     */
    public String recoveryFlowId() {
        return recoveryFlowId;
    }

    /**
     * Returns whether session handling integration is enabled.
     *
     * @return whether session handling integration is enabled
     */
    public boolean sessionHandlingIntegrationEnabled() {
        return sessionHandlingIntegrationEnabled;
    }

    /**
     * Returns the conditional follow-up flow hooks.
     *
     * @return the follow-up flow hooks
     */
    public List<FollowUpFlowHook> followUpHooks() {
        return followUpHooks;
    }

    /**
     * Compares this trigger configuration to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FlowTriggerConfig that)) {
            return false;
        }
        return sessionHandlingIntegrationEnabled == that.sessionHandlingIntegrationEnabled
                && Objects.equals(prerequisiteStepIds, that.prerequisiteStepIds)
                && Objects.equals(prerequisiteFlowIds, that.prerequisiteFlowIds)
                && Objects.equals(recoveryFlowId, that.recoveryFlowId)
                && Objects.equals(followUpHooks, that.followUpHooks);
    }

    /**
     * Returns the hash code for this trigger configuration.
     *
     * @return the hash code for this trigger configuration
     */
    @Override
    public int hashCode() {
        return Objects.hash(prerequisiteStepIds, prerequisiteFlowIds, recoveryFlowId, sessionHandlingIntegrationEnabled, followUpHooks);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "FlowTriggerConfig{" +
                "prerequisiteStepIds=" + prerequisiteStepIds +
                ", prerequisiteFlowIds=" + prerequisiteFlowIds +
                ", recoveryFlowId='" + recoveryFlowId + '\'' +
                ", sessionHandlingIntegrationEnabled=" + sessionHandlingIntegrationEnabled +
                ", followUpHooks=" + followUpHooks +
                '}';
    }

    /**
     * Describes when a follow-up flow hook should run.
     */
    public enum FollowUpCondition {
        /**
         * Runs regardless of the flow result.
         */
        ALWAYS,

        /**
         * Runs only after a successful flow execution.
         */
        ON_SUCCESS,

        /**
         * Runs only after a failed flow execution.
         */
        ON_FAILURE
    }

    /**
     * Points to another flow that should run when a specific condition is met.
     */
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    public static final class FollowUpFlowHook {
        private final FollowUpCondition condition;
        private final String targetFlowId;

        /**
         * Creates a conditional follow-up flow hook.
         *
         * @param condition the condition that activates the hook
         * @param targetFlowId the referenced follow-up flow identifier
         */
        @JsonCreator
        public FollowUpFlowHook(
                @JsonProperty("condition") FollowUpCondition condition,
                @JsonProperty("targetFlowId") String targetFlowId) {
            this.condition = Objects.requireNonNull(condition, "condition");
            this.targetFlowId = normalizeRequiredText(targetFlowId, "targetFlowId");
        }

        /**
         * Returns the activation condition.
         *
         * @return the activation condition
         */
        public FollowUpCondition condition() {
            return condition;
        }

        /**
         * Returns the follow-up flow identifier.
         *
         * @return the follow-up flow identifier
         */
        public String targetFlowId() {
            return targetFlowId;
        }

        /**
         * Compares this follow-up hook to another object.
         *
         * @param other the candidate object
         * @return {@code true} when the objects are equal, otherwise {@code false}
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FollowUpFlowHook that)) {
                return false;
            }
            return condition == that.condition && Objects.equals(targetFlowId, that.targetFlowId);
        }

        /**
         * Returns the hash code for this follow-up hook.
         *
         * @return the hash code for this follow-up hook
         */
        @Override
        public int hashCode() {
            return Objects.hash(condition, targetFlowId);
        }

        /**
         * Returns a diagnostic string representation.
         *
         * @return a diagnostic string representation
         */
        @Override
        public String toString() {
            return "FollowUpFlowHook{" +
                    "condition=" + condition +
                    ", targetFlowId='" + targetFlowId + '\'' +
                    '}';
        }
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

    private static List<String> normalizeStringList(List<String> values) {
        if (values == null) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.strip());
            }
        }
        return List.copyOf(normalized);
    }
}

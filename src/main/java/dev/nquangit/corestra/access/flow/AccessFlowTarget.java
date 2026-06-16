package dev.nquangit.corestra.access.flow;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.flow.FlowDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reusable Access Testing target that executes a saved Flowise flow snapshot.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AccessFlowTarget {
    private final String id;
    private final String name;
    private final FlowDefinition flowSnapshot;
    private final String sourceFlowId;
    private final String sourceFlowName;
    private final String sourceFlowHash;
    private final String targetStepId;
    private final FlowTargetRunMode runMode;
    private final boolean enabled;
    private final List<FlowInjectionOverride> injectionOverrides;

    /**
     * Creates a flow target.
     *
     * @param id stable flow target id
     * @param name display name
     * @param flowSnapshot saved flow snapshot to execute
     * @param sourceFlowId source Flowise flow id
     * @param sourceFlowName source Flowise flow name at capture time
     * @param sourceFlowHash source Flowise flow hash at capture time
     * @param targetStepId selected target step id
     * @param runMode execution mode around the selected target step
     * @param enabled whether the target is enabled for selection
     * @param injectionOverrides per-run injection override configuration
     */
    @JsonCreator
    public AccessFlowTarget(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("flowSnapshot") FlowDefinition flowSnapshot,
            @JsonProperty("sourceFlowId") String sourceFlowId,
            @JsonProperty("sourceFlowName") String sourceFlowName,
            @JsonProperty("sourceFlowHash") String sourceFlowHash,
            @JsonProperty("targetStepId") String targetStepId,
            @JsonProperty("runMode") FlowTargetRunMode runMode,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("injectionOverrides") List<FlowInjectionOverride> injectionOverrides) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.flowSnapshot = Objects.requireNonNull(flowSnapshot, "flowSnapshot");
        this.sourceFlowId = normalizeRequiredText(sourceFlowId, "sourceFlowId");
        this.sourceFlowName = normalizeOptionalText(sourceFlowName);
        this.sourceFlowHash = normalizeOptionalText(sourceFlowHash);
        this.targetStepId = normalizeRequiredText(targetStepId, "targetStepId");
        if (!flowSnapshot.hasStep(this.targetStepId)) {
            throw new IllegalArgumentException("targetStepId must reference a step in flowSnapshot: " + targetStepId);
        }
        this.runMode = Objects.requireNonNullElse(runMode, FlowTargetRunMode.STOP_AT_STEP);
        this.enabled = enabled;
        this.injectionOverrides = copyOverrides(injectionOverrides);
    }

    /**
     * Creates a flow target using the default stop-at-step run mode.
     *
     * @param id stable flow target id
     * @param name display name
     * @param flowSnapshot saved flow snapshot to execute
     * @param sourceFlowId source Flowise flow id
     * @param sourceFlowName source Flowise flow name at capture time
     * @param sourceFlowHash source Flowise flow hash at capture time
     * @param targetStepId selected target step id
     * @param enabled whether the target is enabled for selection
     * @param injectionOverrides per-run injection override configuration
     */
    public AccessFlowTarget(
            String id,
            String name,
            FlowDefinition flowSnapshot,
            String sourceFlowId,
            String sourceFlowName,
            String sourceFlowHash,
            String targetStepId,
            boolean enabled,
            List<FlowInjectionOverride> injectionOverrides) {
        this(id, name, flowSnapshot, sourceFlowId, sourceFlowName, sourceFlowHash, targetStepId,
                FlowTargetRunMode.STOP_AT_STEP, enabled, injectionOverrides);
    }

    /**
     * Returns the stable flow target id.
     *
     * @return stable flow target id
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
     * Returns the immutable flow snapshot to execute.
     *
     * @return flow snapshot
     */
    public FlowDefinition flowSnapshot() {
        return flowSnapshot;
    }

    /**
     * Returns the original Flowise flow id.
     *
     * @return source flow id
     */
    public String sourceFlowId() {
        return sourceFlowId;
    }

    /**
     * Returns the original Flowise flow name at capture time.
     *
     * @return source flow name
     */
    public String sourceFlowName() {
        return sourceFlowName;
    }

    /**
     * Returns the source-flow hash at capture time.
     *
     * @return source flow hash
     */
    public String sourceFlowHash() {
        return sourceFlowHash;
    }

    /**
     * Returns the selected target step id.
     *
     * @return target step id
     */
    public String targetStepId() {
        return targetStepId;
    }

    /**
     * Returns the run mode.
     *
     * @return run mode
     */
    public FlowTargetRunMode runMode() {
        return runMode;
    }

    /**
     * Returns whether this flow target is enabled for selection.
     *
     * @return true when enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns immutable per-run injection overrides.
     *
     * @return injection overrides
     */
    public List<FlowInjectionOverride> injectionOverrides() {
        return injectionOverrides;
    }

    /**
     * Returns a copy with the enabled flag changed.
     *
     * @param enabled new enabled flag
     * @return updated flow target
     */
    public AccessFlowTarget withEnabled(boolean enabled) {
        return new AccessFlowTarget(id, name, flowSnapshot, sourceFlowId, sourceFlowName, sourceFlowHash,
                targetStepId, runMode, enabled, injectionOverrides);
    }

    /**
     * Returns a copy with the run mode changed.
     *
     * @param runMode new run mode
     * @return updated flow target
     */
    public AccessFlowTarget withRunMode(FlowTargetRunMode runMode) {
        return new AccessFlowTarget(id, name, flowSnapshot, sourceFlowId, sourceFlowName, sourceFlowHash,
                targetStepId, runMode, enabled, injectionOverrides);
    }

    /**
     * Compares this target to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccessFlowTarget that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(flowSnapshot, that.flowSnapshot)
                && Objects.equals(sourceFlowId, that.sourceFlowId)
                && Objects.equals(sourceFlowName, that.sourceFlowName)
                && Objects.equals(sourceFlowHash, that.sourceFlowHash)
                && Objects.equals(targetStepId, that.targetStepId)
                && runMode == that.runMode
                && Objects.equals(injectionOverrides, that.injectionOverrides);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, flowSnapshot, sourceFlowId, sourceFlowName, sourceFlowHash,
                targetStepId, runMode, enabled, injectionOverrides);
    }

    /**
     * Returns a diagnostic string.
     *
     * @return diagnostic string
     */
    @Override
    public String toString() {
        return "AccessFlowTarget{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", sourceFlowId='" + sourceFlowId + '\'' +
                ", targetStepId='" + targetStepId + '\'' +
                ", runMode=" + runMode +
                ", enabled=" + enabled +
                ", injectionOverrides=" + injectionOverrides.size() +
                '}';
    }

    private static List<FlowInjectionOverride> copyOverrides(List<FlowInjectionOverride> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            return List.of();
        }
        ArrayList<FlowInjectionOverride> copied = new ArrayList<>();
        overrides.forEach(override -> copied.add(Objects.requireNonNull(override, "injection override")));
        return List.copyOf(copied);
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

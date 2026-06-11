package dev.nquangit.corestra.access.template;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.access.variable.ScopedVariableRef;
import dev.nquangit.corestra.flow.StoredRequestTemplate;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Wraps a captured request template with access-testing metadata.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class AccessRequestTemplate {
    private final String id;
    private final String name;
    private final StoredRequestTemplate baseRequest;
    private final Set<ScopedVariableRef> placeholders;
    private final OperationMetadata operationMetadata;
    private final String sourceFlowId;
    private final String sourceStepId;
    private final String sourceRequestHash;
    private final boolean enabled;

    /**
     * Creates an access request template.
     *
     * <p>{@link StoredRequestTemplate} is already immutable, so this class stores the provided reference.
     *
     * @param id stable template identifier
     * @param name display name
     * @param baseRequest immutable base request template
     * @param placeholders scoped placeholders discovered in the template
     * @param operationMetadata operation metadata
     * @param sourceFlowId source flow identifier
     * @param sourceStepId source step identifier
     * @param sourceRequestHash source request hash
     * @param enabled whether this template is enabled
     */
    @JsonCreator
    public AccessRequestTemplate(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("baseRequest") StoredRequestTemplate baseRequest,
            @JsonProperty("placeholders") Set<ScopedVariableRef> placeholders,
            @JsonProperty("operationMetadata") OperationMetadata operationMetadata,
            @JsonProperty("sourceFlowId") String sourceFlowId,
            @JsonProperty("sourceStepId") String sourceStepId,
            @JsonProperty("sourceRequestHash") String sourceRequestHash,
            @JsonProperty("enabled") boolean enabled) {
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.baseRequest = Objects.requireNonNull(baseRequest, "baseRequest");
        this.placeholders = copyPlaceholders(placeholders);
        this.operationMetadata = operationMetadata == null ? OperationMetadata.unknown() : operationMetadata;
        this.sourceFlowId = normalizeOptionalText(sourceFlowId);
        this.sourceStepId = normalizeOptionalText(sourceStepId);
        this.sourceRequestHash = normalizeOptionalText(sourceRequestHash);
        this.enabled = enabled;
    }

    /**
     * Returns the stable template identifier.
     *
     * @return stable template identifier
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
     * Returns the immutable base request template reference.
     *
     * @return base request template
     */
    public StoredRequestTemplate baseRequest() {
        return baseRequest;
    }

    /**
     * Returns scoped placeholders discovered in the template.
     *
     * @return immutable placeholder set
     */
    public Set<ScopedVariableRef> placeholders() {
        return placeholders;
    }

    /**
     * Returns operation metadata.
     *
     * @return operation metadata
     */
    public OperationMetadata operationMetadata() {
        return operationMetadata;
    }

    /**
     * Returns the source flow identifier.
     *
     * @return source flow identifier, or an empty string
     */
    public String sourceFlowId() {
        return sourceFlowId;
    }

    /**
     * Returns the source step identifier.
     *
     * @return source step identifier, or an empty string
     */
    public String sourceStepId() {
        return sourceStepId;
    }

    /**
     * Returns the source request hash.
     *
     * @return source request hash, or an empty string
     */
    public String sourceRequestHash() {
        return sourceRequestHash;
    }

    /**
     * Returns whether this template is enabled.
     *
     * @return whether this template is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Compares this template to another object.
     *
     * @param other the candidate object
     * @return true when the objects are equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AccessRequestTemplate that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(baseRequest, that.baseRequest)
                && Objects.equals(placeholders, that.placeholders)
                && Objects.equals(operationMetadata, that.operationMetadata)
                && Objects.equals(sourceFlowId, that.sourceFlowId)
                && Objects.equals(sourceStepId, that.sourceStepId)
                && Objects.equals(sourceRequestHash, that.sourceRequestHash);
    }

    /**
     * Returns the hash code for this template.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, name, baseRequest, placeholders, operationMetadata,
                sourceFlowId, sourceStepId, sourceRequestHash, enabled);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "AccessRequestTemplate{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", baseRequest=" + baseRequest +
                ", placeholders=" + placeholders +
                ", operationMetadata=" + operationMetadata +
                ", sourceFlowId='" + sourceFlowId + '\'' +
                ", sourceStepId='" + sourceStepId + '\'' +
                ", sourceRequestHash='" + sourceRequestHash + '\'' +
                ", enabled=" + enabled +
                '}';
    }

    private static Set<ScopedVariableRef> copyPlaceholders(Set<ScopedVariableRef> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<ScopedVariableRef> copied = new LinkedHashSet<>();
        placeholders.forEach(ref -> copied.add(Objects.requireNonNull(ref, "placeholder")));
        return Set.copyOf(copied);
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

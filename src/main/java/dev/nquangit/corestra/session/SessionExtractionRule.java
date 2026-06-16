package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.flow.rule.ExtractRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configures one session-value extraction from a request or response.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SessionExtractionRule {
    private final String id;
    private final boolean enabled;
    private final ExtractRule.SourceSide sourceSide;
    private final ExtractRule.ExtractorType extractorType;
    private final String sourceSelector;
    private final String targetKey;
    private final List<String> allowedToolTypes;

    /**
     * Creates a session extraction rule.
     *
     * @param id stable rule id
     * @param enabled whether rule is enabled
     * @param sourceSide request or response source side
     * @param extractorType extraction mechanism
     * @param sourceSelector extractor selector
     * @param targetKey session key to update, with or without {@code session.} prefix
     * @param allowedToolTypes allowed Montoya tool type names; empty means all tools
     */
    @JsonCreator
    public SessionExtractionRule(
            @JsonProperty("id") String id,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("sourceSide") ExtractRule.SourceSide sourceSide,
            @JsonProperty("extractorType") ExtractRule.ExtractorType extractorType,
            @JsonProperty("sourceSelector") String sourceSelector,
            @JsonProperty("targetKey") String targetKey,
            @JsonProperty("allowedToolTypes") List<String> allowedToolTypes) {
        this.id = normalizeRequiredText(id, "id");
        this.enabled = enabled;
        this.sourceSide = Objects.requireNonNullElse(sourceSide, ExtractRule.SourceSide.RESPONSE);
        this.extractorType = Objects.requireNonNullElse(extractorType, ExtractRule.ExtractorType.HEADER);
        this.sourceSelector = normalizeOptionalText(sourceSelector);
        this.targetKey = SessionState.normalizeSessionKey(targetKey);
        this.allowedToolTypes = normalizeToolTypes(allowedToolTypes);
    }

    /**
     * Returns the stable rule id.
     *
     * @return rule id
     */
    public String id() {
        return id;
    }

    /**
     * Returns whether the rule is enabled.
     *
     * @return true when enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the source side.
     *
     * @return source side
     */
    public ExtractRule.SourceSide sourceSide() {
        return sourceSide;
    }

    /**
     * Returns the extractor type.
     *
     * @return extractor type
     */
    public ExtractRule.ExtractorType extractorType() {
        return extractorType;
    }

    /**
     * Returns the source selector.
     *
     * @return source selector
     */
    public String sourceSelector() {
        return sourceSelector;
    }

    /**
     * Returns the target session key without prefix.
     *
     * @return target key
     */
    public String targetKey() {
        return targetKey;
    }

    /**
     * Returns allowed Montoya tool type names.
     *
     * @return immutable tool names
     */
    public List<String> allowedToolTypes() {
        return allowedToolTypes;
    }

    /**
     * Returns whether this rule can run for the supplied tool.
     *
     * @param toolTypeName Montoya tool type name
     * @return true when allowed
     */
    public boolean allowsTool(String toolTypeName) {
        return allowedToolTypes.isEmpty() || allowedToolTypes.contains(toolTypeName);
    }

    /**
     * Converts this rule to a Flowise extraction rule.
     *
     * @return extraction rule
     */
    public ExtractRule toExtractRule() {
        return new ExtractRule(
                id,
                "Session extraction",
                true,
                sourceSide,
                extractorType,
                sourceSelector,
                targetKey,
                List.of());
    }

    /**
     * Compares this rule to another object.
     *
     * @param other candidate object
     * @return true when equal
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SessionExtractionRule that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && sourceSide == that.sourceSide
                && extractorType == that.extractorType
                && Objects.equals(sourceSelector, that.sourceSelector)
                && Objects.equals(targetKey, that.targetKey)
                && Objects.equals(allowedToolTypes, that.allowedToolTypes);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, enabled, sourceSide, extractorType, sourceSelector, targetKey, allowedToolTypes);
    }

    private static List<String> normalizeToolTypes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.strip());
            }
        }
        return List.copyOf(normalized);
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

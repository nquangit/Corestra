package dev.nquangit.corestra.session;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.nquangit.corestra.flow.rule.InjectRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configures one session-value injection into an outgoing request.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class SessionInjectionRule {
    private final String id;
    private final boolean enabled;
    private final InjectRule.Target target;
    private final String targetSelector;
    private final String valueTemplate;
    private final InjectRule.WriteStrategy writeStrategy;
    private final List<String> allowedToolTypes;

    /**
     * Creates a session injection rule.
     *
     * @param id stable rule id
     * @param enabled whether rule is enabled
     * @param target request target to mutate
     * @param targetSelector selector inside the target
     * @param valueTemplate template such as {@code Bearer {{session.accessToken}}}
     * @param writeStrategy write strategy
     * @param allowedToolTypes allowed Montoya tool type names; empty means all tools
     */
    @JsonCreator
    public SessionInjectionRule(
            @JsonProperty("id") String id,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("target") InjectRule.Target target,
            @JsonProperty("targetSelector") String targetSelector,
            @JsonProperty("valueTemplate") String valueTemplate,
            @JsonProperty("writeStrategy") InjectRule.WriteStrategy writeStrategy,
            @JsonProperty("allowedToolTypes") List<String> allowedToolTypes) {
        this.id = normalizeRequiredText(id, "id");
        this.enabled = enabled;
        this.target = Objects.requireNonNullElse(target, InjectRule.Target.REQUEST_HEADER);
        this.targetSelector = normalizeOptionalText(targetSelector);
        this.valueTemplate = normalizeOptionalText(valueTemplate);
        this.writeStrategy = Objects.requireNonNullElse(writeStrategy, InjectRule.WriteStrategy.OVERWRITE);
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
     * Returns the request mutation target.
     *
     * @return target
     */
    public InjectRule.Target target() {
        return target;
    }

    /**
     * Returns the target selector.
     *
     * @return selector, or empty
     */
    public String targetSelector() {
        return targetSelector;
    }

    /**
     * Returns the value template.
     *
     * @return value template
     */
    public String valueTemplate() {
        return valueTemplate;
    }

    /**
     * Returns the write strategy.
     *
     * @return write strategy
     */
    public InjectRule.WriteStrategy writeStrategy() {
        return writeStrategy;
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
     * Converts this rule to a Flowise injection rule after value rendering.
     *
     * @param renderedValue rendered value
     * @return injection rule
     */
    public InjectRule toInjectRule(String renderedValue) {
        return new InjectRule(
                id,
                "Session injection",
                true,
                target,
                targetSelector,
                InjectRule.ValueSourceType.STATIC,
                "",
                renderedValue,
                writeStrategy);
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
        if (!(other instanceof SessionInjectionRule that)) {
            return false;
        }
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && target == that.target
                && Objects.equals(targetSelector, that.targetSelector)
                && Objects.equals(valueTemplate, that.valueTemplate)
                && writeStrategy == that.writeStrategy
                && Objects.equals(allowedToolTypes, that.allowedToolTypes);
    }

    /**
     * Returns the hash code.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, enabled, target, targetSelector, valueTemplate, writeStrategy, allowedToolTypes);
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

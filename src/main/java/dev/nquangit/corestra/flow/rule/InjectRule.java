package dev.nquangit.corestra.flow.rule;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import dev.nquangit.corestra.interaction.InteractiveInputConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Describes how a flow step should write a value into a request before execution.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class InjectRule {
    private final RuleType ruleType;
    private final String id;
    private final String name;
    private final boolean enabled;
    private final Target target;
    private final String targetSelector;
    private final ValueSourceType valueSourceType;
    private final String valueSourceKey;
    private final String staticValue;
    private final WriteStrategy writeStrategy;
    private final List<String> transformChain;
    private final InteractiveInputConfig interactiveInput;

    /**
     * Creates an injection rule.
     *
     * @param id the stable rule identifier
     * @param name the display name
     * @param enabled whether the rule is enabled
     * @param target the request target that should be modified
     * @param targetSelector the selector inside the target
     * @param valueSourceType the source of the injected value
     * @param valueSourceKey the execution variable name when the source is variable-based
     * @param staticValue the inline value for STATIC or the placeholder expression for TEMPLATE
     * @param writeStrategy the write strategy
     */
    @JsonCreator
    public InjectRule(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("target") Target target,
            @JsonProperty("targetSelector") String targetSelector,
            @JsonProperty("valueSourceType") ValueSourceType valueSourceType,
            @JsonProperty("valueSourceKey") String valueSourceKey,
            @JsonProperty("staticValue") String staticValue,
            @JsonProperty("writeStrategy") WriteStrategy writeStrategy,
            @JsonProperty("transformChain") List<String> transformChain,
            @JsonProperty("ruleType") RuleType ruleType,
            @JsonProperty("interactiveInput") InteractiveInputConfig interactiveInput) {
        this.ruleType = validateRuleType(ruleType, RuleType.INJECT);
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.target = Objects.requireNonNull(target, "target");
        this.targetSelector = normalizeOptionalText(targetSelector);
        validateTargetSelector(this.target, this.targetSelector);
        this.valueSourceType = Objects.requireNonNull(valueSourceType, "valueSourceType");
        this.valueSourceKey = normalizeValueSourceKey(valueSourceType, valueSourceKey);
        this.staticValue = valueSourceType == ValueSourceType.VARIABLE || valueSourceType == ValueSourceType.INTERACTIVE
                ? "" : Objects.requireNonNullElse(staticValue, "");
        if (valueSourceType == ValueSourceType.TEMPLATE) {
            InjectValueTemplate.variableKeys(this.staticValue);
        }
        this.writeStrategy = Objects.requireNonNullElse(writeStrategy, WriteStrategy.OVERWRITE);
        this.transformChain = normalizeStringList(transformChain);
        this.interactiveInput = valueSourceType == ValueSourceType.INTERACTIVE
                ? Objects.requireNonNullElseGet(interactiveInput, InteractiveInputConfig::defaults) : null;
    }

    /** Creates a rule compatible with documents and callers without interactive configuration. */
    public InjectRule(String id, String name, boolean enabled, Target target, String targetSelector,
                      ValueSourceType valueSourceType, String valueSourceKey, String staticValue,
                      WriteStrategy writeStrategy, List<String> transformChain, RuleType ruleType) {
        this(id, name, enabled, target, targetSelector, valueSourceType, valueSourceKey, staticValue,
                writeStrategy, transformChain, ruleType, null);
    }

    /** Returns prompt configuration for INTERACTIVE rules, or null for other sources. */
    public InteractiveInputConfig interactiveInput() {
        return interactiveInput;
    }

    /**
     * Creates an injection rule without an explicit serialized rule type.
     *
     * @param id the stable rule identifier
     * @param name the display name
     * @param enabled whether the rule is enabled
     * @param target the request target that should be modified
     * @param targetSelector the selector inside the target
     * @param valueSourceType the source of the injected value
     * @param valueSourceKey the execution variable name when the source is variable-based
     * @param staticValue the inline value for STATIC or the placeholder expression for TEMPLATE
     * @param writeStrategy the write strategy
     */
    public InjectRule(
            String id,
            String name,
            boolean enabled,
            Target target,
            String targetSelector,
            ValueSourceType valueSourceType,
            String valueSourceKey,
            String staticValue,
            WriteStrategy writeStrategy) {
        this(id, name, enabled, target, targetSelector, valueSourceType, valueSourceKey,
                staticValue, writeStrategy, List.of(), RuleType.INJECT);
    }

    /**
     * Creates an injection rule with an ordered transform chain.
     *
     * @param id stable rule identifier
     * @param name display name
     * @param enabled whether the rule is enabled
     * @param target request target
     * @param targetSelector selector inside the target
     * @param valueSourceType source type
     * @param valueSourceKey context variable key
     * @param staticValue inline STATIC value or TEMPLATE expression
     * @param writeStrategy write strategy
     * @param transformChain transforms applied before injection
     */
    public InjectRule(
            String id,
            String name,
            boolean enabled,
            Target target,
            String targetSelector,
            ValueSourceType valueSourceType,
            String valueSourceKey,
            String staticValue,
            WriteStrategy writeStrategy,
            List<String> transformChain) {
        this(id, name, enabled, target, targetSelector, valueSourceType, valueSourceKey,
                staticValue, writeStrategy, transformChain, RuleType.INJECT);
    }

    /**
     * Returns the broad rule type.
     *
     * @return the broad rule type
     */
    public RuleType ruleType() {
        return ruleType;
    }

    /**
     * Returns the stable rule identifier.
     *
     * @return the stable rule identifier
     */
    public String id() {
        return id;
    }

    /**
     * Returns the display name.
     *
     * @return the display name
     */
    public String name() {
        return name;
    }

    /**
     * Returns whether the rule is enabled.
     *
     * @return whether the rule is enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Returns the request target that should be modified.
     *
     * @return the request target that should be modified
     */
    public Target target() {
        return target;
    }

    /**
     * Returns the selector inside the target.
     *
     * @return the selector inside the target
     */
    public String targetSelector() {
        return targetSelector;
    }

    /**
     * Returns the value source type.
     *
     * @return the value source type
     */
    public ValueSourceType valueSourceType() {
        return valueSourceType;
    }

    /**
     * Returns the execution variable name used by variable-based injection.
     *
     * @return the execution variable name, or an empty string for inline values/templates
     */
    public String valueSourceKey() {
        return valueSourceKey;
    }

    /**
     * Returns the inline static value or the unrendered TEMPLATE expression.
     *
     * @return the inline value/template, or an empty string for variable-based injection
     */
    public String staticValue() {
        return staticValue;
    }

    /** Returns all variable keys required by this rule, without resolving their values. */
    @JsonIgnore
    public List<String> referencedVariableKeys() {
        return switch (valueSourceType) {
            case VARIABLE -> List.of(valueSourceKey);
            case STATIC, INTERACTIVE -> List.of();
            case TEMPLATE -> InjectValueTemplate.variableKeys(staticValue);
        };
    }

    /**
     * Returns the write strategy.
     *
     * @return the write strategy
     */
    public WriteStrategy writeStrategy() {
        return writeStrategy;
    }

    /**
     * Returns transforms applied to the resolved value before injection.
     *
     * @return immutable transform chain
     */
    public List<String> transformChain() {
        return transformChain;
    }

    /**
     * Compares this injection rule to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InjectRule that)) {
            return false;
        }
        return enabled == that.enabled
                && ruleType == that.ruleType
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && target == that.target
                && Objects.equals(targetSelector, that.targetSelector)
                && valueSourceType == that.valueSourceType
                && Objects.equals(valueSourceKey, that.valueSourceKey)
                && Objects.equals(staticValue, that.staticValue)
                && writeStrategy == that.writeStrategy
                && Objects.equals(transformChain, that.transformChain)
                && Objects.equals(interactiveInput, that.interactiveInput);
    }

    /**
     * Returns the hash code for this injection rule.
     *
     * @return the hash code for this injection rule
     */
    @Override
    public int hashCode() {
        return Objects.hash(ruleType, id, name, enabled, target, targetSelector, valueSourceType,
                valueSourceKey, staticValue, writeStrategy, transformChain, interactiveInput);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "InjectRule{" +
                "ruleType=" + ruleType +
                ", id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", target=" + target +
                ", targetSelector='" + targetSelector + '\'' +
                ", valueSourceType=" + valueSourceType +
                ", valueSourceKey='" + valueSourceKey + '\'' +
                ", staticValue='" + staticValue + '\'' +
                ", writeStrategy=" + writeStrategy +
                ", transformChain=" + transformChain +
                '}';
    }

    /**
     * Enumerates supported injection targets.
     */
    public enum Target {
        /**
         * Writes into a request header.
         */
        REQUEST_HEADER,

        /**
         * Writes into a request cookie.
         */
        REQUEST_COOKIE,

        /**
         * Writes into a request query parameter.
         */
        REQUEST_QUERY_PARAMETER,

        /**
         * Writes into a form field in the request body.
         */
        REQUEST_FORM_FIELD,

        /**
         * Writes into a JSON field selected by dot-path.
         */
        REQUEST_JSON_PATH,

        /**
         * Performs regex replacement on the request body.
         */
        REQUEST_REGEX_REPLACEMENT,

        /**
         * Appends raw text to the request body.
         */
        REQUEST_RAW_BODY_APPEND,

        /**
         * Replaces the request body with raw text.
         */
        REQUEST_RAW_BODY_REPLACE
    }

    /**
     * Enumerates supported value source kinds.
     */
    public enum ValueSourceType {
        /**
         * Uses a value from the execution context.
         */
        VARIABLE,

        /**
         * Uses an inline static value.
         */
        STATIC,

        /** Combines literal text and {@code {{variableKey}}} placeholders from the execution context. */
        TEMPLATE,

        /** Asks the operator for a value immediately before applying this injection rule. */
        INTERACTIVE
    }

    /**
     * Enumerates supported write strategies.
     */
    public enum WriteStrategy {
        /**
         * Replaces any existing value.
         */
        OVERWRITE,

        /**
         * Appends to an existing value where supported.
         */
        APPEND,

        /**
         * Skips writing the value if the target already exists (header already present,
         * cookie already set, JSON path already has a value, query param already exists,
         * form field already exists).
         */
        SKIP_IF_PRESENT
    }

    private static RuleType validateRuleType(RuleType actual, RuleType expected) {
        if (actual == null) {
            return expected;
        }
        if (actual != expected) {
            throw new IllegalArgumentException("ruleType must be " + expected);
        }
        return actual;
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

    private static void validateTargetSelector(Target target, String targetSelector) {
        if (!requiresTargetSelector(target)) {
            return;
        }
        if (targetSelector.isEmpty()) {
            throw new IllegalArgumentException("targetSelector must not be blank for target " + target);
        }
    }

    private static boolean requiresTargetSelector(Target target) {
        return switch (target) {
            case REQUEST_HEADER,
                    REQUEST_COOKIE,
                    REQUEST_QUERY_PARAMETER,
                    REQUEST_FORM_FIELD,
                    REQUEST_JSON_PATH,
                    REQUEST_REGEX_REPLACEMENT -> true;
            case REQUEST_RAW_BODY_APPEND, REQUEST_RAW_BODY_REPLACE -> false;
        };
    }

    private static String normalizeValueSourceKey(ValueSourceType valueSourceType, String valueSourceKey) {
        if (valueSourceType != ValueSourceType.VARIABLE) {
            return "";
        }
        return normalizeRequiredText(valueSourceKey, "valueSourceKey");
    }

    private static List<String> normalizeStringList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        ArrayList<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.stripLeading());
            }
        }
        return List.copyOf(normalized);
    }
}

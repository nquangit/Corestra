package dev.nquangit.corestra.flow.rule;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Describes how a flow step should extract a value from a request or response.
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ExtractRule {
    /** Controls selection from JSONPath matches, independently of the value's JSON type. */
    public enum JsonResultMode {
        SINGLE("Require one match"), FIRST("First match"), ALL("All matches (JSON array)");
        private final String label;
        JsonResultMode(String label) { this.label = label; }
        /** Returns the readable editor label. */
        @Override public String toString() { return label; }
    }
    private final RuleType ruleType;
    private final String id;
    private final JsonResultMode jsonResultMode;
    private final String name;
    private final boolean enabled;
    private final SourceSide sourceSide;
    private final ExtractorType extractorType;
    private final String sourceSelector;
    private final String targetVariableName;
    private final List<String> transformChain;
    private final RegexSource regexSource;
    private final Integer regexGroupIndex;

    /**
     * Creates an extraction rule.
     *
     * @param id the stable rule identifier
     * @param name the display name
     * @param enabled whether the rule is enabled
     * @param sourceSide whether the rule reads from the request or response
     * @param extractorType the extractor type
     * @param sourceSelector the extractor-specific selector
     * @param targetVariableName the destination execution variable name
     * @param transformChain optional transform identifiers applied after extraction
     */
    @JsonCreator
    public ExtractRule(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("sourceSide") SourceSide sourceSide,
            @JsonProperty("extractorType") ExtractorType extractorType,
            @JsonProperty("sourceSelector") String sourceSelector,
            @JsonProperty("targetVariableName") String targetVariableName,
            @JsonProperty("transformChain") List<String> transformChain,
            @JsonProperty("regexSource") RegexSource regexSource,
            @JsonProperty("regexGroupIndex") Integer regexGroupIndex,
            @JsonProperty("ruleType") RuleType ruleType,
            @JsonProperty("jsonResultMode") JsonResultMode jsonResultMode) {
        this.jsonResultMode = Objects.requireNonNullElse(jsonResultMode, JsonResultMode.SINGLE);
        this.ruleType = validateRuleType(ruleType, RuleType.EXTRACT);
        this.id = normalizeRequiredText(id, "id");
        this.name = normalizeRequiredText(name, "name");
        this.enabled = enabled;
        this.sourceSide = Objects.requireNonNull(sourceSide, "sourceSide");
        ExtractorType storedType = Objects.requireNonNull(extractorType, "extractorType");
        this.extractorType = storedType.canonicalType();
        this.sourceSelector = normalizeOptionalText(sourceSelector);
        validateSourceSelector(this.extractorType, this.sourceSelector);
        this.targetVariableName = normalizeRequiredText(targetVariableName, "targetVariableName");
        this.transformChain = storedType.migrateTransforms(normalizeStringList(transformChain));
        this.regexSource = extractorType == ExtractorType.REGEX
                ? Objects.requireNonNullElse(regexSource, RegexSource.BODY)
                : RegexSource.BODY;
        this.regexGroupIndex = extractorType == ExtractorType.REGEX ? regexGroupIndex : null;
        if (this.regexGroupIndex != null && this.regexGroupIndex < 0) {
            throw new IllegalArgumentException("regexGroupIndex must not be negative");
        }
    }

    /** Creates a rule with strict single-match JSONPath behavior, compatible with older callers. */
    public ExtractRule(String id, String name, boolean enabled, SourceSide sourceSide, ExtractorType extractorType,
                       String sourceSelector, String targetVariableName, List<String> transformChain,
                       RegexSource regexSource, Integer regexGroupIndex, RuleType ruleType) {
        this(id, name, enabled, sourceSide, extractorType, sourceSelector, targetVariableName, transformChain,
                regexSource, regexGroupIndex, ruleType, JsonResultMode.SINGLE);
    }

    /** Returns JSONPath match handling; dot paths and JSON Pointers retain their legacy behavior. */
    public JsonResultMode jsonResultMode() { return jsonResultMode; }

    /** Returns a rule with explicit JSONPath match handling, preserving all other fields. */
    public ExtractRule withJsonResultMode(JsonResultMode mode) {
        return new ExtractRule(id, name, enabled, sourceSide, extractorType, sourceSelector, targetVariableName,
                transformChain, regexSource, regexGroupIndex, ruleType, mode);
    }

    /**
     * Creates an extraction rule without an explicit serialized rule type.
     *
     * @param id the stable rule identifier
     * @param name the display name
     * @param enabled whether the rule is enabled
     * @param sourceSide whether the rule reads from the request or response
     * @param extractorType the extractor type
     * @param sourceSelector the extractor-specific selector
     * @param targetVariableName the destination execution variable name
     * @param transformChain optional transform identifiers applied after extraction
     */
    public ExtractRule(
            String id,
            String name,
            boolean enabled,
            SourceSide sourceSide,
            ExtractorType extractorType,
            String sourceSelector,
            String targetVariableName,
            List<String> transformChain) {
        this(id, name, enabled, sourceSide, extractorType, sourceSelector, targetVariableName,
                transformChain, RegexSource.BODY, null, RuleType.EXTRACT);
    }

    /**
     * Creates an extraction rule with explicit regex input and capture group settings.
     *
     * @param id stable rule identifier
     * @param name display name
     * @param enabled whether the rule is enabled
     * @param sourceSide request or response side
     * @param extractorType extractor type
     * @param sourceSelector extractor selector or regex pattern
     * @param targetVariableName destination variable
     * @param transformChain ordered value transforms
     * @param regexSource regex input section
     * @param regexGroupIndex capture group, where zero is the full match; {@code null} preserves legacy suffix parsing
     */
    public ExtractRule(
            String id,
            String name,
            boolean enabled,
            SourceSide sourceSide,
            ExtractorType extractorType,
            String sourceSelector,
            String targetVariableName,
            List<String> transformChain,
            RegexSource regexSource,
            Integer regexGroupIndex) {
        this(id, name, enabled, sourceSide, extractorType, sourceSelector, targetVariableName,
                transformChain, regexSource, regexGroupIndex, RuleType.EXTRACT);
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
     * Returns the source side.
     *
     * @return the source side
     */
    public SourceSide sourceSide() {
        return sourceSide;
    }

    /**
     * Returns the extractor type.
     *
     * @return the extractor type
     */
    public ExtractorType extractorType() {
        return extractorType;
    }

    /**
     * Returns the extractor-specific selector.
     *
     * @return the extractor-specific selector
     */
    public String sourceSelector() {
        return sourceSelector;
    }

    /**
     * Returns the destination variable name.
     *
     * @return the destination variable name
     */
    public String targetVariableName() {
        return targetVariableName;
    }

    /**
     * Returns the configured transform chain.
     *
     * @return the configured transform chain
     */
    public List<String> transformChain() {
        return transformChain;
    }

    /**
     * Returns the HTTP message section searched by a regex rule.
     *
     * @return regex input section
     */
    public RegexSource regexSource() {
        return regexSource;
    }

    /**
     * Returns the configured regex capture group, or {@code null} for a legacy selector.
     *
     * @return explicit capture group or {@code null}
     */
    public Integer regexGroupIndex() {
        return regexGroupIndex;
    }

    /**
     * Compares this extraction rule to another object.
     *
     * @param other the candidate object
     * @return {@code true} when the objects are equal, otherwise {@code false}
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ExtractRule that)) {
            return false;
        }
        return enabled == that.enabled
                && jsonResultMode == that.jsonResultMode
                && ruleType == that.ruleType
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && sourceSide == that.sourceSide
                && extractorType == that.extractorType
                && Objects.equals(sourceSelector, that.sourceSelector)
                && Objects.equals(targetVariableName, that.targetVariableName)
                && Objects.equals(transformChain, that.transformChain)
                && regexSource == that.regexSource
                && Objects.equals(regexGroupIndex, that.regexGroupIndex);
    }

    /**
     * Returns the hash code for this extraction rule.
     *
     * @return the hash code for this extraction rule
     */
    @Override
    public int hashCode() {
        return Objects.hash(ruleType, id, name, enabled, sourceSide, extractorType, sourceSelector,
                targetVariableName, transformChain, regexSource, regexGroupIndex, jsonResultMode);
    }

    /**
     * Returns a diagnostic string representation.
     *
     * @return a diagnostic string representation
     */
    @Override
    public String toString() {
        return "ExtractRule{" +
                "ruleType=" + ruleType +
                ", id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", sourceSide=" + sourceSide +
                ", extractorType=" + extractorType +
                ", sourceSelector='" + sourceSelector + '\'' +
                ", targetVariableName='" + targetVariableName + '\'' +
                ", transformChain=" + transformChain +
                ", regexSource=" + regexSource +
                ", regexGroupIndex=" + regexGroupIndex +
                '}';
    }

    /**
     * Identifies whether the extractor reads from the request or response.
     */
    public enum SourceSide {
        /**
         * Reads from the request.
         */
        REQUEST,

        /**
         * Reads from the response.
         */
        RESPONSE
    }

    /**
     * Enumerates the supported extraction mechanisms.
     */
    public enum ExtractorType {
        /**
         * Extracts a header value.
         */
        HEADER,

        /**
         * Extracts a cookie value.
         */
        COOKIE,

        /**
         * Extracts JSON using a legacy dot path, pointer: selector, or rooted JSONPath query.
         */
        JSON_PATH,

        /**
         * Extracts a value via regular expression matching.
         */
        REGEX,

        /**
         * Extracts plain body text.
         */
        BODY_TEXT,

        /** Extracts the exact body bytes without encoding or charset conversion. */
        BODY_RAW,

        /**
         * Compatibility token for previously saved rules; normalized to BODY_RAW plus base64Encode.
         * @deprecated Use BODY_RAW and explicit transforms. Hidden from all rule editors.
         */
        @Deprecated
        BODY_BASE64,

        /**
         * Extracts the response status code.
         */
        STATUS_CODE;

        /** Returns types offered by rule editors, excluding legacy persistence tokens. */
        public static ExtractorType[] selectableValues() {
            return java.util.Arrays.stream(values()).filter(type -> type != BODY_BASE64)
                    .toArray(ExtractorType[]::new);
        }

        /** Returns the runtime type for a persisted extractor token. */
        public ExtractorType canonicalType() {
            return this == BODY_BASE64 ? BODY_RAW : this;
        }

        /** Migrates an old implicit encoder to the equivalent explicit transform chain. */
        public List<String> migrateTransforms(List<String> transforms) {
            if (this != BODY_BASE64) return transforms;
            List<String> migrated = new ArrayList<>();
            migrated.add("base64Encode");
            migrated.addAll(transforms);
            return List.copyOf(migrated);
        }
    }

    /**
     * Selects the HTTP message section searched by regex extraction.
     */
    public enum RegexSource {
        /** Searches only the message body. */
        BODY,
        /** Searches the start line and headers, excluding the body. */
        HEADERS,
        /** Searches the start line, headers, blank separator, and body. */
        FULL_MESSAGE
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

    private static void validateSourceSelector(ExtractorType extractorType, String sourceSelector) {
        if (!requiresSourceSelector(extractorType)) {
            return;
        }
        if (sourceSelector.isEmpty()) {
            throw new IllegalArgumentException("sourceSelector must not be blank for extractorType " + extractorType);
        }
    }

    private static boolean requiresSourceSelector(ExtractorType extractorType) {
        return switch (extractorType) {
            case HEADER, COOKIE, JSON_PATH, REGEX -> true;
            case BODY_TEXT, BODY_RAW, BODY_BASE64, STATUS_CODE -> false;
        };
    }

    private static List<String> normalizeStringList(List<String> values) {
        if (values == null) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.stripLeading());
            }
        }
        return List.copyOf(normalized);
    }
}

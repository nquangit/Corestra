package dev.nquangit.corestra.watch;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One named value (or keyed set of values) read from the probe response.
 *
 * <p>Keys produced in a {@link WatchSnapshot}:</p>
 * <ul>
 *   <li>{@link Source#JSON_SELECTOR} and {@link Source#REGEX}: one key equal to {@code name}.
 *       JSON selectors use the same syntax as Flowise extract rules (explicit JSONPath
 *       {@code $...}, {@code pointer:/...} or legacy dot path) and must select one value.
 *       REGEX uses the named group {@code value} if present, else group 1.</li>
 *   <li>{@link Source#JSON_COLLECTION}: one key per element as {@code name[elementKey]}, where
 *       {@code itemsPath} selects an array (JSONPath) and {@code keyPath}/{@code valuePath}
 *       are dot paths relative to each element (e.g. {@code accountNo}, {@code balance.available}).</li>
 *   <li>{@link Source#REGEX_COLLECTION}: every match of {@code selector}, which must have named
 *       groups {@code key} and {@code value}, becomes {@code name[key]}.</li>
 * </ul>
 * <p>Regex sources search the probe's response body only.</p>
 *
 * @param id           stable id
 * @param name         display name and key prefix (e.g. {@code balance})
 * @param source       how values are located
 * @param selector     JSON selector or regex (JSON_SELECTOR, REGEX, REGEX_COLLECTION)
 * @param itemsPath    JSONPath to the element array (JSON_COLLECTION)
 * @param keyPath      element-relative dot path for the key (JSON_COLLECTION)
 * @param valuePath    element-relative dot path for the value (JSON_COLLECTION)
 * @param valueType    NUMBER values are parsed with {@code numberFormat} and get deltas
 * @param numberFormat number normalization for NUMBER values
 */
public record WatchObservation(String id, String name, Source source, String selector,
                               String itemsPath, String keyPath, String valuePath,
                               ValueType valueType, WatchNumberFormat numberFormat) {
    /** Where observation values come from. Append new values only. */
    public enum Source {
        /** One JSON value. */
        JSON_SELECTOR,
        /** One value per JSON array element. */
        JSON_COLLECTION,
        /** One regex value. */
        REGEX,
        /** One value per regex match with key/value groups. */
        REGEX_COLLECTION
    }

    /** Value semantics. Append new values only. */
    public enum ValueType {
        /** Exact decimal with deltas. */
        NUMBER,
        /** Compared as text. */
        TEXT
    }

    /**
     * Normalizes null fields.
     *
     * @param id           id
     * @param name         name
     * @param source       source
     * @param selector     selector
     * @param itemsPath    items path
     * @param keyPath      key path
     * @param valuePath    value path
     * @param valueType    value type
     * @param numberFormat number format
     */
    @JsonCreator
    public WatchObservation(@JsonProperty("id") String id,
                            @JsonProperty("name") String name,
                            @JsonProperty("source") Source source,
                            @JsonProperty("selector") String selector,
                            @JsonProperty("itemsPath") String itemsPath,
                            @JsonProperty("keyPath") String keyPath,
                            @JsonProperty("valuePath") String valuePath,
                            @JsonProperty("valueType") ValueType valueType,
                            @JsonProperty("numberFormat") WatchNumberFormat numberFormat) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name.strip();
        this.source = source == null ? Source.JSON_SELECTOR : source;
        this.selector = selector == null ? "" : selector;
        this.itemsPath = itemsPath == null ? "" : itemsPath;
        this.keyPath = keyPath == null ? "" : keyPath;
        this.valuePath = valuePath == null ? "" : valuePath;
        this.valueType = valueType == null ? ValueType.NUMBER : valueType;
        this.numberFormat = numberFormat == null ? WatchNumberFormat.automatic() : numberFormat;
    }
}

package dev.nquangit.corestra.watch.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import dev.nquangit.corestra.flow.rule.ExtractRule.JsonResultMode;
import dev.nquangit.corestra.http.extract.ExtractionResult;
import dev.nquangit.corestra.http.extract.JsonBodyExtractor;
import dev.nquangit.corestra.watch.WatchObservation;
import dev.nquangit.corestra.watch.WatchProbeClient.ProbeResponse;
import dev.nquangit.corestra.watch.WatchSnapshot.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Reads {@link WatchObservation} values from a probe response body.
 *
 * <p>Keys are {@code name} (JSON_SELECTOR, REGEX) or {@code name[key]} (collections). Problems
 * never throw; they become error notes prefixed with the observation name. Duplicate collection
 * keys keep the first value. NUMBER values that do not parse keep their raw text with a null
 * number. JSON values (and JSON numbers in particular) are parsed with
 * {@link WatchNumberParser#parseMachineFirst} so {@code 1.234} in JSON stays 1.234; regex text uses
 * {@link WatchNumberParser#parse}.</p>
 */
public final class WatchObservationReader {
    private static final int MAX_COLLECTION_ITEMS = 10_000;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private static final Configuration JSON_PATH = Configuration.builder()
            .jsonProvider(new JacksonJsonNodeJsonProvider(MAPPER))
            .mappingProvider(new JacksonMappingProvider(MAPPER)).build();

    private final WatchNumberParser parser;
    private final JsonBodyExtractor jsonExtractor = new JsonBodyExtractor();

    /**
     * Reading of all observations.
     *
     * @param values key to value, in observation/document order
     * @param errors error notes
     */
    public record Reading(Map<String, Value> values, List<String> errors) {
        /**
         * Copies defensively, keeping order.
         *
         * @param values values
         * @param errors errors
         */
        public Reading {
            values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
            errors = List.copyOf(errors);
        }
    }

    /**
     * Creates a reader.
     *
     * @param parser number parser
     */
    public WatchObservationReader(WatchNumberParser parser) {
        this.parser = parser == null ? new WatchNumberParser() : parser;
    }

    /**
     * Reads every observation from {@code response}'s body.
     *
     * @param response     probe response
     * @param observations observations
     * @return values and error notes
     */
    public Reading read(ProbeResponse response, List<WatchObservation> observations) {
        Map<String, Value> values = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        String body = response == null ? "" : response.body();
        List<WatchObservation> list = observations == null ? List.of() : observations;
        for (int i = 0; i < list.size(); i++) {
            WatchObservation observation = list.get(i);
            if (observation == null) continue;
            if (observation.name().isEmpty()) {
                errors.add("Observation #" + (i + 1) + " has no name");
                continue;
            }
            Sink sink = new Sink(observation, values, errors);
            try {
                switch (observation.source()) {
                    case JSON_SELECTOR -> readJsonSelector(body, observation, sink);
                    case JSON_COLLECTION -> readJsonCollection(body, observation, sink);
                    case REGEX -> readRegex(body, observation, sink);
                    case REGEX_COLLECTION -> readRegexCollection(body, observation, sink);
                }
            } catch (RuntimeException unexpected) {
                sink.error("could not be read (" + unexpected.getClass().getSimpleName() + ")");
            }
        }
        return new Reading(values, errors);
    }

    private void readJsonSelector(String body, WatchObservation observation, Sink sink) {
        if (observation.selector().isBlank()) {
            sink.error("JSON selector is empty");
            return;
        }
        ExtractionResult result = jsonExtractor.extract(body, observation.selector().strip(), observation.name(),
                JsonResultMode.SINGLE);
        if (!result.isSuccess()) {
            sink.error(result.failureReason());
            return;
        }
        sink.put(observation.name(), result.value(), true);
    }

    private void readJsonCollection(String body, WatchObservation observation, Sink sink) {
        if (observation.keyPath().isBlank() || observation.valuePath().isBlank()) {
            sink.error("key path and value path are required");
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (Exception invalid) {
            sink.error("body is not valid JSON");
            return;
        }
        if (root == null || root.isMissingNode()) {
            sink.error("body is empty");
            return;
        }
        List<JsonNode> items = selectItems(root, observation.itemsPath().strip(), sink);
        if (items == null) return;
        if (items.isEmpty()) {
            sink.error("items path matched no elements");
            return;
        }
        int index = 0;
        for (JsonNode item : items) {
            if (++index > MAX_COLLECTION_ITEMS) {
                sink.error("more than " + MAX_COLLECTION_ITEMS + " elements; the rest were ignored");
                break;
            }
            JsonNode keyNode = relative(item, observation.keyPath());
            if (keyNode == null || keyNode.isNull() || keyNode.isContainerNode()) {
                sink.error("element #" + index + " has no scalar key at '" + observation.keyPath() + "'");
                continue;
            }
            String key = observation.name() + "[" + keyNode.asText() + "]";
            JsonNode valueNode = relative(item, observation.valuePath());
            if (valueNode == null || valueNode.isNull()) {
                sink.error(key + " has no value at '" + observation.valuePath() + "'");
                continue;
            }
            if (valueNode.isNumber() && observation.valueType() == WatchObservation.ValueType.NUMBER) {
                BigDecimal number = valueNode.decimalValue();
                int scale = observation.numberFormat().minorUnitScale();
                sink.putParsed(key, number.toPlainString(), scale > 0 ? number.movePointLeft(scale) : number);
            } else {
                sink.put(key, valueNode.isContainerNode() ? valueNode.toString() : valueNode.asText(), true);
            }
        }
    }

    private static List<JsonNode> selectItems(JsonNode root, String itemsPath, Sink sink) {
        String path = itemsPath.isEmpty() ? "$" : itemsPath.startsWith("$") ? itemsPath : "$." + itemsPath;
        JsonPath query;
        try {
            query = JsonPath.compile(path);
        } catch (RuntimeException invalid) {
            sink.error("invalid items path '" + itemsPath + "'");
            return null;
        }
        Object raw;
        try {
            raw = query.read(root, JSON_PATH);
        } catch (PathNotFoundException missing) {
            sink.error("items path '" + itemsPath + "' not found");
            return null;
        } catch (RuntimeException failed) {
            sink.error("items path '" + itemsPath + "' could not be evaluated");
            return null;
        }
        JsonNode selected = raw instanceof JsonNode node ? node : MAPPER.valueToTree(raw);
        if (selected == null || !selected.isArray()) {
            sink.error("items path '" + itemsPath + "' did not select an array");
            return null;
        }
        // A wildcard/filter returns a match list; a single matched array is unwrapped.
        if (!query.isDefinite() && selected.size() == 1 && selected.get(0).isArray()) selected = selected.get(0);
        List<JsonNode> items = new ArrayList<>();
        selected.forEach(items::add);
        return items;
    }

    private static JsonNode relative(JsonNode element, String dotPath) {
        JsonNode current = element;
        for (String segment : dotPath.strip().split("\\.", -1)) {
            if (current == null) return null;
            if (current.isObject()) {
                current = current.get(segment);
            } else if (current.isArray() && segment.chars().allMatch(Character::isDigit) && !segment.isEmpty()) {
                current = current.get(Integer.parseInt(segment));
            } else {
                return null;
            }
        }
        return current == null || current.isMissingNode() ? null : current;
    }

    private void readRegex(String body, WatchObservation observation, Sink sink) {
        Pattern pattern = compile(observation, sink);
        if (pattern == null) return;
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            sink.error("regex did not match");
            return;
        }
        String value;
        if (pattern.namedGroups().containsKey("value")) value = matcher.group("value");
        else value = matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
        if (value == null) {
            sink.error("regex value group did not participate in the match");
            return;
        }
        sink.put(observation.name(), value, false);
    }

    private void readRegexCollection(String body, WatchObservation observation, Sink sink) {
        Pattern pattern = compile(observation, sink);
        if (pattern == null) return;
        if (!pattern.namedGroups().containsKey("key") || !pattern.namedGroups().containsKey("value")) {
            sink.error("regex needs named groups (?<key>...) and (?<value>...)");
            return;
        }
        Matcher matcher = pattern.matcher(body);
        int matches = 0;
        while (matcher.find()) {
            if (++matches > MAX_COLLECTION_ITEMS) {
                sink.error("more than " + MAX_COLLECTION_ITEMS + " matches; the rest were ignored");
                break;
            }
            String key = matcher.group("key");
            String value = matcher.group("value");
            if (key == null || value == null) {
                sink.error("match #" + matches + " has no key or value");
            } else {
                sink.put(observation.name() + "[" + key.strip() + "]", value, false);
            }
        }
        if (matches == 0) sink.error("regex did not match");
    }

    private static Pattern compile(WatchObservation observation, Sink sink) {
        if (observation.selector().isEmpty()) {
            sink.error("regex is empty");
            return null;
        }
        try {
            return Pattern.compile(observation.selector());
        } catch (PatternSyntaxException invalid) {
            sink.error("invalid regex: " + invalid.getDescription());
            return null;
        }
    }

    /** Collects values/errors for one observation, applying value typing and duplicate checks. */
    private final class Sink {
        private final WatchObservation observation;
        private final Map<String, Value> values;
        private final List<String> errors;

        Sink(WatchObservation observation, Map<String, Value> values, List<String> errors) {
            this.observation = observation;
            this.values = values;
            this.errors = errors;
        }

        void error(String message) {
            errors.add(observation.name() + ": " + message);
        }

        void put(String key, String raw, boolean machineFirst) {
            if (observation.valueType() == WatchObservation.ValueType.TEXT || values.containsKey(key)) {
                putParsed(key, raw, null);
                return;
            }
            BigDecimal number = machineFirst ? parser.parseMachineFirst(raw, observation.numberFormat())
                    : parser.parse(raw, observation.numberFormat());
            if (number == null) errors.add(key + ": '" + abbreviate(raw) + "' is not a number");
            putParsed(key, raw, number);
        }

        void putParsed(String key, String raw, BigDecimal number) {
            if (values.containsKey(key)) {
                errors.add(key + ": duplicate key; kept the first value");
                return;
            }
            values.put(key, new Value(raw, number));
        }
    }

    private static String abbreviate(String raw) {
        return raw.length() <= 80 ? raw : raw.substring(0, 77) + "...";
    }
}

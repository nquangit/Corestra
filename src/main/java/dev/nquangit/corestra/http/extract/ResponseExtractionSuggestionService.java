package dev.nquangit.corestra.http.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import java.util.*;
import static dev.nquangit.corestra.flow.rule.ExtractRule.ExtractorType;

/** Bounded, response-only discovery of extraction rules. Analysis never sends traffic or installs rules. */
public final class ResponseExtractionSuggestionService {
    /** Maximum suggestions shown in one analysis. */
    public static final int MAX_CANDIDATES = 500;
    /** Maximum response body size analyzed as JSON, in Java characters. */
    public static final int MAX_JSON_CHARS = 1_048_576;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** An extraction that has been checked against the sample with the real runtime extractor. */
    public record Candidate(ExtractorType type, String selector, String variable, String preview) {
        /** Creates a normal response rule with a user-editable variable name. */
        public ExtractRule toRule(String key) {
            validateVariable(key);
            return new ExtractRule(UUID.randomUUID().toString(), "Extract " + type + " " + key.strip(), true,
                    ExtractRule.SourceSide.RESPONSE, type, selector, key.strip(), List.of());
        }
    }

    /** Suggestions and visible explanations for skipped/limited content. */
    public record Analysis(List<Candidate> candidates, List<String> notes) {
        /** Freezes an analysis result. */
        public Analysis { candidates = List.copyOf(candidates); notes = List.copyOf(notes); }
    }

    /** Inspects Set-Cookie, headers, status, text and JSON; existing sources are not suggested twice. */
    public Analysis analyze(HttpMessageSnapshot response, List<ExtractRule> existing) {
        Objects.requireNonNull(response, "response");
        Collector collector = new Collector(response, existing);
        for (String line : response.headers()) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).strip();
            if (name.equalsIgnoreCase("Set-Cookie")) {
                String pair = line.substring(colon + 1).strip().split(";", 2)[0];
                int equals = pair.indexOf('=');
                if (equals > 0) collector.add(ExtractorType.COOKIE, pair.substring(0, equals).strip(), pair.substring(0, equals).strip());
            } else collector.add(ExtractorType.HEADER, name, "header." + name);
        }
        collector.add(ExtractorType.STATUS_CODE, "", "statusCode");
        String body = response.body();
        String trimmed = body.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            if (body.length() > MAX_JSON_CHARS) collector.notes.add("JSON analysis skipped: body exceeds 1 Mi characters.");
            else {
                try { collector.jsonRoot = JSON.readTree(body); collector.visit(collector.jsonRoot, "", "body", 0); }
                catch (Exception invalid) { collector.notes.add("Body could not be analyzed as JSON; headers and cookies are still available."); }
            }
        }
        boolean text = response.headers().stream().anyMatch(h -> h.toLowerCase(Locale.ROOT).matches("content-type\\s*:.*(text/|json|xml|javascript|x-www-form-urlencoded).*"));
        if (!body.isEmpty() && (text || trimmed.startsWith("{") || trimmed.startsWith("[")))
            collector.add(ExtractorType.BODY_TEXT, "", "responseBody");
        collector.notes.add("Response only. Existing sources are omitted; repeated headers/cookie names use the first value, as at runtime. Null JSON values are not extractable.");
        return new Analysis(collector.values, collector.notes);
    }

    /** Validates a selection against the latest rules and appends it atomically; existing rules are preserved. */
    public List<ExtractRule> merge(List<ExtractRule> existing, List<ExtractRule> selected) {
        List<ExtractRule> merged = new ArrayList<>(existing);
        Set<String> sources = new HashSet<>(), keys = new HashSet<>();
        existing.forEach(r -> { sources.add(sourceKey(r)); keys.add(r.targetVariableName()); });
        for (ExtractRule rule : selected) {
            if (rule.sourceSide() != ExtractRule.SourceSide.RESPONSE) throw new IllegalArgumentException("Only response extraction is supported");
            validateVariable(rule.targetVariableName());
            if (!sources.add(sourceKey(rule))) continue;
            if (!keys.add(rule.targetVariableName())) throw new IllegalArgumentException("Variable already used by another extractor: " + rule.targetVariableName());
            merged.add(rule);
        }
        return List.copyOf(merged);
    }

    private static void validateVariable(String value) {
        if (value == null || value.isBlank() || value.strip().chars().anyMatch(c -> Character.isWhitespace(c) || c == '|' || c == '{' || c == '}'))
            throw new IllegalArgumentException("Enter a variable name without spaces, braces or |.");
    }

    private static String sourceKey(ExtractRule rule) {
        String selector = rule.sourceSelector();
        if (rule.extractorType() == ExtractorType.HEADER) selector = selector.toLowerCase(Locale.ROOT);
        if (rule.extractorType() == ExtractorType.JSON_PATH && !selector.startsWith("pointer:"))
            selector = "pointer:/" + String.join("/", Arrays.stream(selector.split("\\.", -1)).map(ResponseExtractionSuggestionService::escape).toList());
        return rule.sourceSide() + ":" + rule.extractorType() + ":" + selector;
    }
    private static String escape(String key) { return key.replace("~", "~0").replace("/", "~1"); }

    private static final class Collector {
        final HttpMessageSnapshot response;
        final List<Candidate> values = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final Set<String> sources = new HashSet<>(), keys = new HashSet<>();
        final ExtractionService extraction = new ExtractionService();
        JsonNode jsonRoot;
        int visited;
        boolean limited;
        Collector(HttpMessageSnapshot response, List<ExtractRule> existing) {
            this.response = response;
            existing.forEach(r -> { sources.add(sourceKey(r)); keys.add(r.targetVariableName()); });
        }
        void add(ExtractorType type, String selector, String suggested) {
            if (values.size() >= MAX_CANDIDATES) { limit(); return; }
            String base = suggested.replaceAll("[^\\p{L}\\p{N}_.-]", "_");
            if (base.isBlank()) base = "value";
            String key = base;
            for (int suffix = 2; keys.contains(key); suffix++) key = base + "_" + suffix;
            var rule = new Candidate(type, selector, key, "").toRule(key);
            if (!sources.add(sourceKey(rule))) return;
            var extracted = type == ExtractorType.JSON_PATH
                    ? new JsonBodyExtractor().extractTree(jsonRoot, selector, key) : extraction.extract(rule, null, response);
            if (!extracted.isSuccess()) return;
            String value = extracted.value();
            String preview = value.substring(0, Math.min(180, value.length())).replace("\r", "\\r").replace("\n", "\\n");
            if (value.length() > 180) preview += "…";
            keys.add(key); values.add(new Candidate(type, selector, key, preview));
        }
        void visit(JsonNode node, String pointer, String variable, int depth) {
            if (node == null || node.isNull()) return;
            if (++visited > 2_000 || depth > 32 || values.size() >= MAX_CANDIDATES) { limit(); return; }
            add(ExtractorType.JSON_PATH, "pointer:" + pointer, variable);
            if (node.isObject()) {
                var fields = node.fields();
                while (fields.hasNext() && values.size() < MAX_CANDIDATES && visited <= 2_000) {
                    var field = fields.next();
                    visit(field.getValue(), pointer + "/" + escape(field.getKey()), pointer.isEmpty() ? field.getKey() : variable + "." + field.getKey(), depth + 1);
                }
                if (fields.hasNext()) limit();
            } else if (node.isArray()) {
                for (int i = 0; i < node.size() && visited <= 2_000 && values.size() < MAX_CANDIDATES; i++)
                    visit(node.get(i), pointer + "/" + i, variable + "." + i, depth + 1);
                if (node.size() > MAX_CANDIDATES) limit();
            }
        }
        void limit() { if (!limited) notes.add("Analysis limited to 500 suggestions, 2,000 JSON nodes and 32 levels. Existing rules are unchanged."); limited = true; }
    }
}

package dev.nquangit.corestra.integration.watch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.nquangit.corestra.watch.WatchDisplayOptions;
import dev.nquangit.corestra.watch.WatchResult;
import dev.nquangit.corestra.watch.WatchSnapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns a {@link WatchResult} into response decorations (headers, body, notes, highlight)
 * according to {@link WatchDisplayOptions}. Montoya-free and deterministic; the Montoya side is
 * applied by {@link WatchResponseDecorator}.
 *
 * <ul>
 *   <li>headers: {@code X-Corestra-Watch: <STATUS> changed=<n> fail=<n> settled=<yes|no> <ms>ms},
 *       one {@code X-Corestra-Watch-<i>} per row (at most {@link #MAX_ROW_HEADERS}, then
 *       {@code X-Corestra-Watch-More}), and {@code X-Corestra-Watch-Note-<i>} per note. Values are
 *       ASCII only, with CR/LF and other control characters replaced.</li>
 *   <li>jsonEnvelope: {@code {"corestraWatch":{...},"response":<original JSON>}} when the body is
 *       JSON; otherwise the text block is used instead and a note says so.</li>
 *   <li>textBlock: a readable block, a blank line, then the original body.</li>
 *   <li>annotations: a one-line summary for Burp notes and a highlight (RED for FAIL/ERROR,
 *       GREEN for PASS, YELLOW for CHANGED, none for NO_CHANGE).</li>
 * </ul>
 */
public final class WatchResponseRenderer {
    /** Main summary header name. */
    public static final String HEADER = "X-Corestra-Watch";
    /** Maximum per-row headers before an overflow header is added. */
    public static final int MAX_ROW_HEADERS = 20;
    /** Maximum note headers. */
    public static final int MAX_NOTE_HEADERS = 10;
    /** Note added when a JSON envelope was requested for a non-JSON body. */
    public static final String JSON_FALLBACK_NOTE = "jsonEnvelope skipped: response body is not JSON; text block used";
    private static final int MAX_HEADER_VALUE = 200;
    private static final int MAX_TEXT_ROWS = 500;
    private static final int MAX_ANNOTATION_NOTES = 1_000;

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .nodeFactory(JsonNodeFactory.withExactBigDecimals(true))
            .build();

    private WatchResponseRenderer() { }

    /** Highlight to apply; names match Montoya {@code HighlightColor} constants. */
    public enum Highlight {
        /** FAIL or ERROR. */
        RED,
        /** PASS. */
        GREEN,
        /** CHANGED. */
        YELLOW
    }

    /**
     * One header to set on the response (replacing any existing header with the same name).
     *
     * @param name  header name
     * @param value ASCII header value
     */
    public record Header(String name, String value) { }

    /**
     * Decorations to apply.
     *
     * @param headers   headers to set, in order
     * @param body      replacement body, or null to keep the body
     * @param notes     Burp notes text to merge, or null to leave notes alone
     * @param highlight highlight to set, or null to leave it alone
     */
    public record Decoration(List<Header> headers, String body, String notes, Highlight highlight) {
        /**
         * Copies the header list.
         *
         * @param headers   headers
         * @param body      body
         * @param notes     notes
         * @param highlight highlight
         */
        public Decoration {
            headers = headers == null ? List.of() : List.copyOf(headers);
        }
    }

    /**
     * Renders decorations for one result.
     *
     * @param result       watch result
     * @param options      display options
     * @param originalBody body of the (possibly aggregated) response; null treated as ""
     * @return decorations
     */
    public static Decoration render(WatchResult result, WatchDisplayOptions options, String originalBody) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(options, "options");
        String body = originalBody == null ? "" : originalBody;
        List<String> notes = new ArrayList<>(result.notes());

        String newBody = null;
        boolean wantText = options.textBlock();
        if (options.jsonEnvelope()) {
            JsonNode parsed = parseJson(body);
            if (parsed != null) {
                newBody = envelope(result, parsed);
                wantText = false;
            } else {
                notes.add(JSON_FALLBACK_NOTE);
                wantText = true;
            }
        }
        if (wantText) {
            newBody = textBlock(result, notes) + "\n\n" + body;
        }

        List<Header> headers = options.headers() ? headers(result, notes) : List.of();
        String annotationNotes = options.annotations() ? annotationNotes(result, notes) : null;
        Highlight highlight = options.annotations() ? highlight(result.status()) : null;
        return new Decoration(headers, newBody, annotationNotes, highlight);
    }

    /**
     * Joins existing Burp notes with a new summary.
     *
     * @param existing existing notes (may be null/blank)
     * @param added    summary to add
     * @return merged notes
     */
    public static String mergeNotes(String existing, String added) {
        if (existing == null || existing.isBlank()) return added;
        return existing + " | " + added;
    }

    /**
     * Returns the one-line summary {@code <STATUS> changed=<n> fail=<n> settled=<yes|no> <ms>ms}.
     *
     * @param result result
     * @return summary
     */
    public static String summary(WatchResult result) {
        long changed = result.rows().stream().filter(WatchResult.Row::changed).count();
        long failed = result.rows().stream().filter(row -> row.verdict() == WatchResult.Verdict.FAIL).count();
        return result.status() + " changed=" + changed + " fail=" + failed + " settled=" + (result.settled() ? "yes" : "no")
                + " " + result.settleMillis() + "ms";
    }

    private static List<Header> headers(WatchResult result, List<String> notes) {
        List<Header> headers = new ArrayList<>();
        headers.add(new Header(HEADER, ascii(summary(result))));
        List<WatchResult.Row> rows = result.rows();
        for (int i = 0; i < rows.size() && i < MAX_ROW_HEADERS; i++) {
            headers.add(new Header(HEADER + "-" + (i + 1), ascii(rowText(rows.get(i), " "))));
        }
        if (rows.size() > MAX_ROW_HEADERS) {
            headers.add(new Header(HEADER + "-More", (rows.size() - MAX_ROW_HEADERS) + " more rows"));
        }
        for (int i = 0; i < notes.size() && i < MAX_NOTE_HEADERS; i++) {
            headers.add(new Header(HEADER + "-Note-" + (i + 1), ascii(notes.get(i))));
        }
        return headers;
    }

    private static String rowText(WatchResult.Row row, String keySeparator) {
        StringBuilder text = new StringBuilder();
        text.append(row.key()).append(keySeparator)
                .append(value(row.beforeRaw())).append(" -> ").append(value(row.afterRaw()));
        if (row.delta() != null) text.append(" d=").append(delta(row.delta()));
        if (row.explanation() != null && !row.explanation().isBlank()) {
            text.append(" [").append(row.explanation()).append(']');
        }
        text.append(' ').append(row.verdict());
        return text.toString();
    }

    private static String value(String raw) {
        return raw == null ? "(absent)" : raw;
    }

    private static String delta(BigDecimal delta) {
        String plain = delta.toPlainString();
        return delta.signum() > 0 ? "+" + plain : plain;
    }

    private static String textBlock(WatchResult result, List<String> notes) {
        StringBuilder block = new StringBuilder();
        block.append("[Corestra Watch] ").append(oneLine(result.watchName())).append(": ").append(summary(result));
        List<WatchResult.Row> rows = result.rows();
        for (int i = 0; i < rows.size() && i < MAX_TEXT_ROWS; i++) {
            block.append("\n  ").append(oneLine(rowText(rows.get(i), ": ")));
        }
        if (rows.size() > MAX_TEXT_ROWS) block.append("\n  ... ").append(rows.size() - MAX_TEXT_ROWS).append(" more rows");
        for (String error : snapshotErrors("before", result.before())) block.append("\n  error: ").append(oneLine(error));
        for (String error : snapshotErrors("after", result.after())) block.append("\n  error: ").append(oneLine(error));
        for (String note : notes) block.append("\n  note: ").append(oneLine(note));
        return block.toString();
    }

    private static List<String> snapshotErrors(String label, WatchSnapshot snapshot) {
        if (snapshot == null) return List.of();
        return snapshot.errors().stream().map(error -> label + ": " + error).toList();
    }

    private static String envelope(WatchResult result, JsonNode original) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode watch = root.putObject("corestraWatch");
        watch.put("watchId", result.watchId());
        watch.put("watchName", result.watchName());
        watch.put("status", String.valueOf(result.status()));
        watch.put("changed", result.rows().stream().filter(WatchResult.Row::changed).count());
        watch.put("failed", result.rows().stream().filter(row -> row.verdict() == WatchResult.Verdict.FAIL).count());
        watch.put("settled", result.settled());
        watch.put("settleMillis", result.settleMillis());
        ArrayNode rows = watch.putArray("rows");
        for (WatchResult.Row row : result.rows()) {
            ObjectNode node = rows.addObject();
            node.put("key", row.key());
            node.put("before", row.beforeRaw());
            node.put("after", row.afterRaw());
            node.put("delta", row.delta() == null ? null : delta(row.delta()));
            node.put("changed", row.changed());
            node.put("verdict", String.valueOf(row.verdict()));
            node.put("explanation", row.explanation());
        }
        ArrayNode errors = watch.putArray("errors");
        snapshotErrors("before", result.before()).forEach(errors::add);
        snapshotErrors("after", result.after()).forEach(errors::add);
        ArrayNode notes = watch.putArray("notes");
        result.notes().forEach(notes::add);
        root.set("response", original);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Could not write watch envelope", impossible);
        }
    }

    private static JsonNode parseJson(String body) {
        if (body.isBlank()) return null;
        try {
            JsonNode node = MAPPER.readTree(body);
            return node == null || node.isMissingNode() ? null : node;
        } catch (JsonProcessingException | RuntimeException notJson) {
            return null;
        }
    }

    private static String annotationNotes(WatchResult result, List<String> notes) {
        StringBuilder text = new StringBuilder("Watch ").append(oneLine(result.watchName())).append(": ");
        long changed = result.rows().stream().filter(WatchResult.Row::changed).count();
        long failed = result.rows().stream().filter(row -> row.verdict() == WatchResult.Verdict.FAIL).count();
        text.append(result.status()).append(" changed=").append(changed).append(" fail=").append(failed)
                .append(" settled=").append(result.settled() ? "yes" : "no");
        for (String note : notes) text.append("; ").append(oneLine(note));
        return text.length() > MAX_ANNOTATION_NOTES ? text.substring(0, MAX_ANNOTATION_NOTES - 3) + "..." : text.toString();
    }

    private static Highlight highlight(WatchResult.Status status) {
        if (status == null) return null;
        return switch (status) {
            case FAIL, ERROR -> Highlight.RED;
            case PASS -> Highlight.GREEN;
            case CHANGED -> Highlight.YELLOW;
            case NO_CHANGE -> null;
        };
    }

    private static String oneLine(String text) {
        if (text == null) return "";
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * Returns a header-safe value: printable ASCII only, CR/LF/control characters as spaces,
     * non-ASCII as {@code ?}, trimmed and truncated.
     *
     * @param text text
     * @return safe value
     */
    static String ascii(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_HEADER_VALUE + 3));
        text.codePoints().forEach(cp -> {
            if (cp >= 0x20 && cp < 0x7f) out.append((char) cp);
            else if (cp < 0x20 || cp == 0x7f) out.append(' ');
            else out.append('?');
        });
        String value = out.toString().strip();
        return value.length() > MAX_HEADER_VALUE ? value.substring(0, MAX_HEADER_VALUE) + "..." : value;
    }
}

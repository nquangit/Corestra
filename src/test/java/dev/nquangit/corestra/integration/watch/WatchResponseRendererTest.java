package dev.nquangit.corestra.integration.watch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.nquangit.corestra.watch.WatchDisplayOptions;
import dev.nquangit.corestra.watch.WatchResult;
import dev.nquangit.corestra.watch.WatchSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WatchResponseRendererTest {
    private static final WatchSnapshot SNAP = new WatchSnapshot(1L, 200, Map.of(), List.of());

    private static WatchResult result(WatchResult.Status status, List<WatchResult.Row> rows, List<String> notes) {
        return new WatchResult("w1", "Balances", status, rows, true, 1400L, SNAP, SNAP, notes);
    }

    private static WatchResult.Row debit() {
        return new WatchResult.Row("balance[0123]", "1,000,000", "494,500", new BigDecimal("-505500"), true,
                WatchResult.Verdict.FAIL, "expected -500000 - max(500000 * 0.011, 5000) = -505500");
    }

    private static WatchResult.Row unchanged() {
        return new WatchResult.Row("balance[0456]", "20", "20", BigDecimal.ZERO, false, WatchResult.Verdict.INFO, "");
    }

    @Test void headersSummarizeStatusAndRows() {
        var deco = WatchResponseRenderer.render(result(WatchResult.Status.FAIL, List.of(debit(), unchanged()), List.of("n1")),
                new WatchDisplayOptions(true, false, false, false), "{}");

        assertNull(deco.body());
        assertEquals("X-Corestra-Watch", deco.headers().getFirst().name());
        assertEquals("FAIL changed=1 fail=1 settled=yes 1400ms", deco.headers().getFirst().value());
        assertEquals("X-Corestra-Watch-1", deco.headers().get(1).name());
        assertEquals("balance[0123] 1,000,000 -> 494,500 d=-505500 [expected -500000 - max(500000 * 0.011, 5000) = -505500] FAIL",
                deco.headers().get(1).value());
        assertEquals("balance[0456] 20 -> 20 d=0 INFO", deco.headers().get(2).value());
        assertTrue(deco.headers().stream().anyMatch(h -> h.name().equals("X-Corestra-Watch-Note-1") && h.value().equals("n1")));
        assertNull(deco.notes());
        assertNull(deco.highlight());
    }

    @Test void headerValuesAreAsciiWithoutLineBreaksAndAbsentValuesAreMarked() {
        var row = new WatchResult.Row("name", null, "Nguyễn\r\nX-Evil: 1", null, true, WatchResult.Verdict.INFO, "");
        var deco = WatchResponseRenderer.render(result(WatchResult.Status.CHANGED, List.of(row), List.of("bad\nnote")),
                WatchDisplayOptions.defaults(), "");

        for (var header : deco.headers()) {
            assertTrue(header.value().chars().allMatch(c -> c >= 0x20 && c < 0x7f), header.value());
        }
        assertEquals("name (absent) -> Nguy?n  X-Evil: 1 INFO", deco.headers().get(1).value());
        assertEquals(WatchResponseRenderer.Highlight.YELLOW, deco.highlight());
    }

    @Test void rowHeadersAreCappedWithOverflowHeader() {
        List<WatchResult.Row> rows = new ArrayList<>();
        for (int i = 0; i < 25; i++) rows.add(new WatchResult.Row("k" + i, "1", "1", BigDecimal.ZERO, false, WatchResult.Verdict.INFO, ""));
        var deco = WatchResponseRenderer.render(result(WatchResult.Status.NO_CHANGE, rows, List.of()),
                new WatchDisplayOptions(true, false, false, false), "");

        assertEquals(1 + WatchResponseRenderer.MAX_ROW_HEADERS + 1, deco.headers().size());
        assertEquals("X-Corestra-Watch-More", deco.headers().getLast().name());
        assertEquals("5 more rows", deco.headers().getLast().value());
    }

    @Test void jsonEnvelopeWrapsOriginalJsonExactly() throws Exception {
        String original = "{\"ok\":true,\"amount\":12345678901234567890.123456789,\"list\":[1,2]}";
        var deco = WatchResponseRenderer.render(result(WatchResult.Status.FAIL, List.of(debit()), List.of("note")),
                new WatchDisplayOptions(false, true, false, false), original);

        JsonNode root = new ObjectMapper().readTree(deco.body());
        assertEquals(List.of("corestraWatch", "response"), iterable(root.fieldNames()));
        JsonNode watch = root.get("corestraWatch");
        assertEquals("FAIL", watch.get("status").asText());
        assertEquals("Balances", watch.get("watchName").asText());
        assertEquals(1, watch.get("changed").asInt());
        assertEquals(1, watch.get("failed").asInt());
        assertEquals("-505500", watch.get("rows").get(0).get("delta").asText());
        assertEquals("FAIL", watch.get("rows").get(0).get("verdict").asText());
        assertEquals("note", watch.get("notes").get(0).asText());
        assertTrue(deco.body().endsWith("\"response\":" + original + "}"), deco.body());
        assertTrue(deco.headers().isEmpty());
    }

    @Test void jsonEnvelopeFallsBackToTextBlockForNonJsonBodies() {
        var deco = WatchResponseRenderer.render(result(WatchResult.Status.PASS, List.of(), List.of()),
                new WatchDisplayOptions(true, true, false, false), "[Step: s1] Status: 200\n{\"a\":1}\n{\"b\":2}\n");

        assertTrue(deco.body().startsWith("[Corestra Watch] Balances: PASS"), deco.body());
        assertTrue(deco.body().contains("jsonEnvelope skipped: response body is not JSON"), deco.body());
        assertTrue(deco.body().endsWith("\n\n[Step: s1] Status: 200\n{\"a\":1}\n{\"b\":2}\n"), deco.body());
        assertTrue(deco.headers().stream().anyMatch(h -> h.value().contains("jsonEnvelope skipped")));
    }

    @Test void textBlockPrependsReadableBlock() {
        var deco = WatchResponseRenderer.render(result(WatchResult.Status.FAIL, List.of(debit()), List.of("downstream flow: timeout")),
                new WatchDisplayOptions(false, false, true, false), "original");

        String[] parts = deco.body().split("\n\n", 2);
        assertEquals("original", parts[1]);
        assertTrue(parts[0].startsWith("[Corestra Watch] Balances: FAIL changed=1 fail=1 settled=yes 1400ms"), parts[0]);
        assertTrue(parts[0].contains("balance[0123]: 1,000,000 -> 494,500 d=-505500 [expected"), parts[0]);
        assertTrue(parts[0].contains("note: downstream flow: timeout"), parts[0]);
    }

    @Test void jsonEnvelopeWinsOverTextBlockForJsonBodies() {
        var deco = WatchResponseRenderer.render(result(WatchResult.Status.PASS, List.of(), List.of()),
                new WatchDisplayOptions(false, true, true, false), "[1]");
        assertTrue(deco.body().startsWith("{\"corestraWatch\":"), deco.body());
    }

    @Test void annotationsCarrySummaryAndHighlight() {
        var options = new WatchDisplayOptions(false, false, false, true);
        assertEquals(WatchResponseRenderer.Highlight.RED, WatchResponseRenderer.render(result(WatchResult.Status.FAIL, List.of(), List.of()), options, "").highlight());
        assertEquals(WatchResponseRenderer.Highlight.RED, WatchResponseRenderer.render(result(WatchResult.Status.ERROR, List.of(), List.of()), options, "").highlight());
        assertEquals(WatchResponseRenderer.Highlight.GREEN, WatchResponseRenderer.render(result(WatchResult.Status.PASS, List.of(), List.of()), options, "").highlight());
        assertEquals(WatchResponseRenderer.Highlight.YELLOW, WatchResponseRenderer.render(result(WatchResult.Status.CHANGED, List.of(), List.of()), options, "").highlight());
        var noChange = WatchResponseRenderer.render(result(WatchResult.Status.NO_CHANGE, List.of(), List.of("x")), options, "");
        assertNull(noChange.highlight());
        assertEquals("Watch Balances: NO_CHANGE changed=0 fail=0 settled=yes; x", noChange.notes());
    }

    @Test void mergeNotesKeepsExistingText() {
        assertEquals("mine | Watch", WatchResponseRenderer.mergeNotes("mine", "Watch"));
        assertEquals("Watch", WatchResponseRenderer.mergeNotes("  ", "Watch"));
        assertEquals("Watch", WatchResponseRenderer.mergeNotes(null, "Watch"));
    }

    private static List<String> iterable(java.util.Iterator<String> it) {
        List<String> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}

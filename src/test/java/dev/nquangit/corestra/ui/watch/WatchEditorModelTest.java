package dev.nquangit.corestra.ui.watch;

import dev.nquangit.corestra.flow.StoredRequestTemplate;
import dev.nquangit.corestra.flow.rule.ExtractRule;
import dev.nquangit.corestra.flow.rule.RuleType;
import dev.nquangit.corestra.watch.WatchConfigFixtures;
import dev.nquangit.corestra.watch.WatchDefinition;
import dev.nquangit.corestra.watch.WatchExpectation;
import dev.nquangit.corestra.watch.WatchObservation;
import dev.nquangit.corestra.watch.WatchResult;
import dev.nquangit.corestra.watch.WatchSnapshot;
import dev.nquangit.corestra.watch.WatchTiming;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WatchEditorModelTest {

    private static WatchEditorModel.EditorState copy(WatchEditorModel.EditorState s, String host, String port,
                                                    boolean https, String raw) {
        return new WatchEditorModel.EditorState(s.name(), s.enabled(), s.methods(), s.hostPattern(), s.pathPattern(),
                host, port, https, raw, s.inheritHeaders(), s.variables(), s.observations(), s.expectations(),
                s.display(), s.timing());
    }

    @Test
    void stateRoundTripPreservesEveryFieldIncludingHiddenRuleFields() {
        ExtractRule withTransforms = new ExtractRule("r9", "amount", true, ExtractRule.SourceSide.REQUEST,
                ExtractRule.ExtractorType.JSON_PATH, "$.amount", "amount", List.of("trim"),
                ExtractRule.RegexSource.BODY, null, RuleType.EXTRACT, ExtractRule.JsonResultMode.FIRST);
        WatchDefinition watch = WatchConfigFixtures.fullWatch("w").withRequestVariables(List.of(withTransforms));

        WatchDefinition rebuilt = WatchEditorModel.apply(watch, WatchEditorModel.toState(watch));

        assertEquals(watch, rebuilt);
        assertSame(watch.probe().request(), rebuilt.probe().request(), "unchanged probe text keeps the stored template");
        assertEquals(List.of("trim"), rebuilt.requestVariables().getFirst().transformChain());
        assertEquals(ExtractRule.JsonResultMode.FIRST, rebuilt.requestVariables().getFirst().jsonResultMode());
    }

    @Test
    void emptyWatchRoundTripsWithoutProbe() {
        WatchDefinition empty = WatchDefinition.empty("e", "Empty");
        WatchDefinition rebuilt = WatchEditorModel.apply(empty, WatchEditorModel.toState(empty));
        assertEquals(empty, rebuilt);
        assertNull(rebuilt.probe().request());
    }

    @Test
    void editedProbeIsParsedWithTargetFields() {
        WatchDefinition watch = WatchConfigFixtures.fullWatch("w");
        WatchEditorModel.EditorState state = copy(WatchEditorModel.toState(watch), "api.bank.test", "8080", false,
                "GET /balances?x={{from}} HTTP/1.1\r\nHost: api.bank.test:8080\r\nAccept: */*\r\n\r\n");

        StoredRequestTemplate probe = WatchEditorModel.apply(watch, state).probe().request();

        assertEquals("http", probe.scheme());
        assertEquals("api.bank.test", probe.host());
        assertEquals(8080, probe.port());
        assertEquals("/balances?x={{from}}", probe.pathAndQuery());
        assertTrue(probe.headerLines().contains("Accept: */*"));
    }

    @Test
    void invalidProbeInputsGiveUserFacingMessages() {
        WatchEditorModel.EditorState base = WatchEditorModel.toState(WatchConfigFixtures.fullWatch("w"));
        WatchDefinition watch = WatchConfigFixtures.fullWatch("w");
        String raw = "GET / HTTP/1.1\r\nHost: a\r\n\r\n";
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> WatchEditorModel.apply(watch, copy(base, "", "443", true, raw))).getMessage().contains("host"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> WatchEditorModel.apply(watch, copy(base, "a", "99999", true, raw))).getMessage().contains("port"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> WatchEditorModel.apply(watch, copy(base, "a", "443", true, "nonsense"))).getMessage().startsWith("Probe:"));
    }

    @Test
    void variableDraftRequiresSelectorAndBuildsRequestSideRules() {
        WatchEditorModel.VariableDraft draft = WatchEditorModel.newVariable("v1");
        ExtractRule rule = draft.toRule(1);
        assertEquals(ExtractRule.SourceSide.REQUEST, rule.sourceSide());
        assertEquals("amount", rule.targetVariableName());

        WatchEditorModel.VariableDraft blank = (WatchEditorModel.VariableDraft) WatchEditorModel.VARIABLE_COLUMNS.get(3)
                .setter().apply(draft, "");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> blank.toRule(2)).getMessage().contains("#2"));
        WatchEditorModel.VariableDraft body = new WatchEditorModel.VariableDraft("b", true, "raw",
                ExtractRule.ExtractorType.BODY_TEXT, "", null, null, null);
        assertEquals(ExtractRule.ExtractorType.BODY_TEXT, body.toRule(1).extractorType());
    }

    @Test
    void columnSettersRebuildRecordsAndKeepOtherFields() {
        WatchObservation observation = WatchConfigFixtures.fullWatch("w").observations().getFirst();
        for (int c = 0; c < WatchEditorModel.OBSERVATION_COLUMNS.size(); c++) {
            var column = WatchEditorModel.OBSERVATION_COLUMNS.get(c);
            WatchObservation same = column.setter().apply(observation, column.getter().apply(observation));
            assertEquals(observation, same, "column " + column.name());
        }
        var scale = WatchEditorModel.OBSERVATION_COLUMNS.stream().filter(c -> c.name().equals("Minor-unit scale")).findFirst().orElseThrow();
        WatchObservation scaled = scale.setter().apply(observation, 4);
        assertEquals(4, scaled.numberFormat().minorUnitScale());
        assertEquals(observation.numberFormat().groupSeparator(), scaled.numberFormat().groupSeparator());
        assertEquals(observation.itemsPath(), scaled.itemsPath());

        WatchExpectation expectation = WatchConfigFixtures.fullWatch("w").expectations().getFirst();
        for (var column : WatchEditorModel.EXPECTATION_COLUMNS) {
            assertEquals(expectation, column.setter().apply(expectation, column.getter().apply(expectation)), column.name());
        }
        WatchExpectation typed = WatchEditorModel.EXPECTATION_COLUMNS.get(2).setter().apply(expectation, WatchExpectation.Type.UNCHANGED);
        assertEquals(WatchExpectation.Type.UNCHANGED, typed.type());
        assertEquals(expectation.expression(), typed.expression());
    }

    @Test
    void listHelpersAndDuplicate() {
        assertEquals(List.of("POST", "put"), WatchEditorModel.parseList(" POST , ,put\n"));
        assertEquals(List.of(), WatchEditorModel.parseList("  "));
        assertEquals("a, b", WatchEditorModel.joinList(List.of("a", "b")));
        WatchDefinition copy = WatchEditorModel.duplicate(WatchConfigFixtures.fullWatch("w"), "w2");
        assertEquals("w2", copy.id());
        assertFalse(copy.enabled());
        assertTrue(copy.name().endsWith("(copy)"));
        assertEquals(WatchConfigFixtures.fullWatch("w").observations(), copy.observations());
        assertTrue(WatchEditorModel.EXPRESSION_HELP.contains("-{{amount}} - max({{amount}} * 0.011, 5000)"));
    }

    @Test
    void resultHistoryIsBoundedNewestFirstAndFormatsRows() {
        WatchSnapshot before = new WatchSnapshot(1, 200, Map.of("balance[1]", new WatchSnapshot.Value("1,000", new BigDecimal("1000"))), List.of());
        WatchSnapshot after = new WatchSnapshot(2, 200, Map.of(), List.of("timeout"));
        WatchResult result = new WatchResult("w", "Transfer", WatchResult.Status.FAIL,
                List.of(new WatchResult.Row("balance[1]", "1,000", null, new BigDecimal("5"), true,
                        WatchResult.Verdict.FAIL, "expected -5")), false, 1200, before, after, List.of("flow wait done"));
        WatchResultHistory history = new WatchResultHistory(3);
        for (int i = 0; i < 5; i++) history.add(i, result);
        assertEquals(3, history.size());
        assertEquals(4, history.entries().getFirst().receivedAtMillis());

        String summary = WatchResultHistory.summary(result);
        assertTrue(summary.contains("1 failed") && summary.contains("NOT settled") && summary.contains("flow wait done"), summary);
        Object[] row = WatchResultHistory.detailRows(result).getFirst();
        assertEquals("(absent)", row[2]);
        assertEquals("+5", row[3]);
        assertTrue(WatchResultHistory.notesText(result).contains("AFTER error: timeout"));
        List<Object[]> snapshotRows = WatchResultHistory.snapshotRows(before);
        assertEquals("1000", snapshotRows.getFirst()[2]);
        assertEquals("error", WatchResultHistory.snapshotRows(after).getFirst()[0]);
        assertEquals(WatchTiming.defaults(), WatchDefinition.empty("x", "y").timing());
    }
}

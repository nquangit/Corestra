package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.control.*;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.persistence.FlowSerializer;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionActionEditorTest {
    @Test void savedRetryBudgetRemainsEditableAndSurvivesAnUnrelatedEditAndSerialization() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (var type : List.of(StepActionType.RETRY_CURRENT_STEP, StepActionType.RETRY_FLOW_FROM_START)) {
                var original = new StepAction("retry", "My retry", true, type, Map.of("maxAttempts", "5", "futureOption", "preserve"));
                var editor = new ConditionActionPanel.ActionTable(List.of(original), List.of());
                var table = findTable(editor);
                assertEquals("5", table.getValueAt(0, 2));
                assertTrue(table.isCellEditable(0, 2));
                assertEquals(original, editor.actions().getFirst());
                table.setValueAt("7", 0, 2);
                var saved = editor.actions().getFirst();
                assertEquals("7", saved.parameters().get("maxAttempts"));
                assertEquals("preserve", saved.parameters().get("futureOption"));
                var rule = new StepConditionActionRule("rule", "Rule", true, ConditionMatchPolicy.ANY,
                        List.of(status(StepConditionOperator.IN, "403,429")), List.of(saved));
                var step = new dev.nquangit.corestra.flow.FlowStep("s", "S", true, "",
                        new dev.nquangit.corestra.flow.StoredRequestTemplate("GET", "https", "api.test", 443, "/", List.of(), ""),
                        List.of(), List.of(), List.of(), dev.nquangit.corestra.flow.FlowStep.StepErrorStrategy.FAIL_FLOW)
                        .withConditionActionRules(List.of(rule));
                var flow = new FlowDefinition("flow", "Flow", "", true, List.of(step), null, null, null);
                var serializer = new FlowSerializer();
                assertEquals(saved, serializer.deserialize(serializer.serialize(List.of(flow))).getFirst().steps().getFirst()
                        .conditionActionRules().getFirst().actions().getFirst());
            }
        });
    }

    @Test void legacyRetriesShowTheirActualDefaultAndTypeChangesDoNotReuseSleepSecondsAsRetryCount() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new ConditionActionPanel.ActionTable(List.of(new StepAction("a", "Sleep", true,
                    StepActionType.SLEEP, Map.of("seconds", "10"))), List.of());
            var table = findTable(editor);
            table.setValueAt(StepActionType.RETRY_FLOW_FROM_START, 0, 1);
            assertEquals(Integer.toString(FlowEngine.DEFAULT_RETRY_LIMIT), table.getValueAt(0, 2));
            assertEquals(Map.of("maxAttempts", "1"), editor.actions().getFirst().parameters());
            table.setValueAt(StepActionType.BREAK_FLOW, 0, 1);
            assertFalse(table.isCellEditable(0, 2));
            assertTrue(editor.actions().getFirst().parameters().isEmpty());
            var legacy = new ConditionActionPanel.ActionTable(List.of(new StepAction("b", "Retry", true,
                    StepActionType.RETRY_CURRENT_STEP, Map.of())), List.of());
            assertEquals("1", findTable(legacy).getValueAt(0, 2));
        });
    }

    @Test void numericParametersRejectInvalidCountsAndKeepEngineBounds() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new ConditionActionPanel.ActionTable(List.of(new StepAction("a", "Retry", true,
                    StepActionType.RETRY_FLOW_FROM_START, Map.of())), List.of());
            var table = findTable(editor);
            for (String value : List.of("", "-1", "11", "1.5", "forever", "999999999999999999999")) {
                table.setValueAt(value, 0, 2);
                assertThrows(IllegalArgumentException.class, editor::actions, value);
            }
            for (String value : List.of("0", "10")) {
                table.setValueAt(value, 0, 2);
                assertEquals(value, editor.actions().getFirst().parameters().get("maxAttempts"));
            }
            table.setValueAt(StepActionType.SLEEP, 0, 1);
            table.setValueAt("10000", 0, 2);
            assertThrows(IllegalArgumentException.class, editor::actions);
            table.setValueAt("10", 0, 2);
            assertEquals("10", editor.actions().getFirst().parameters().get("seconds"));
        });
    }

    @Test void statusValidationExplainsImpossibleAndMisformatted403Or429Conditions() {
        var two = List.of(status(StepConditionOperator.EQUALS, "403"), status(StepConditionOperator.EQUALS, "429"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ConditionActionPanel.validateConditions(ConditionMatchPolicy.ALL, two))
                .getMessage().contains("ANY"));
        assertDoesNotThrow(() -> ConditionActionPanel.validateConditions(ConditionMatchPolicy.ANY, two));
        assertDoesNotThrow(() -> ConditionActionPanel.validateConditions(ConditionMatchPolicy.ALL, List.of(status(StepConditionOperator.IN, "403, 429"))));
        for (var operator : List.of(StepConditionOperator.EQUALS, StepConditionOperator.IN)) {
            assertThrows(IllegalArgumentException.class, () -> ConditionActionPanel.validateConditions(ConditionMatchPolicy.ALL,
                    List.of(status(operator, "403|429"))));
        }
        assertDoesNotThrow(() -> ConditionActionPanel.validateConditions(ConditionMatchPolicy.ALL,
                List.of(status(StepConditionOperator.MATCHES_REGEX, "^(403|429)$"))));
        var negated = new StepCondition("not429", "Not 429", true, StepConditionSource.RESPONSE_STATUS, "",
                StepConditionOperator.EQUALS, ConditionValueSource.STATIC, "429", false, true);
        assertDoesNotThrow(() -> ConditionActionPanel.validateConditions(ConditionMatchPolicy.ALL, List.of(two.getFirst(), negated)));
    }

    private static StepCondition status(StepConditionOperator operator, String expected) {
        return new StepCondition("status-" + expected, "Status", true, StepConditionSource.RESPONSE_STATUS, "", operator,
                ConditionValueSource.STATIC, expected, false, false);
    }

    private static JTable findTable(Component component) {
        if (component instanceof JTable table) return table;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            JTable found = findTable(child); if (found != null) return found;
        }
        return null;
    }
}

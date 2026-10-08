package dev.nquangit.corestra.ui.session;

import dev.nquangit.corestra.session.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionScopeTableModelTest {
    @Test
    void deletingFinalRuleLeavesOnlySharedConditionsAndPreservesTheirMatchingAfterReload() throws Exception {
        for (boolean withShared : List.of(false, true)) {
            var model = new SessionScopeTableModel();
            if (withShared) model.addRow(new Object[]{SessionCriterionType.HEADER, "X-User", "^Alice$", "Common"});
            model.addRule();
            model.removeRule("Rule 1");
            assertEquals(List.of("Common"), model.ruleNames());
            assertEquals(withShared ? 1 : 0, model.getRowCount());
            var scope = model.scope(SessionScopeMode.CUSTOM);
            assertTrue(scope.rules().isEmpty());
            assertTrue(scope.matches(false, "GET", "api", "/", List.of("X-User: Alice"), ""));
            assertEquals(!withShared, scope.matches(false, "GET", "api", "/", List.of("X-User: Bob"), ""));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            String json = mapper.writeValueAsString(scope);
            assertFalse(json.contains("Empty custom scope"));
            assertFalse(json.contains("(?!)"));
            var restored = new SessionScopeTableModel();
            restored.load(mapper.readValue(json, SessionScope.class));
            assertEquals(model.ruleNames(), restored.ruleNames());
            assertEquals(model.getRowCount(), restored.getRowCount());
            assertEquals(scope, restored.scope(SessionScopeMode.CUSTOM));
            int row = restored.addRule();
            restored.setValueAt("^api$", row, 2);
            assertTrue(restored.scope(SessionScopeMode.CUSTOM).matches(false, "GET", "api", "/", List.of("X-User: Alice"), ""));
        }
    }

    @Test
    void oldEmptyScopePlaceholdersDisappearWithoutDroppingUserConditions() {
        for (List<SessionScopeCriterion> placeholder : List.of(List.<SessionScopeCriterion>of(),
                List.of(new SessionScopeCriterion(SessionCriterionType.HOST, "", "(?!)")))) {
            var model = new SessionScopeTableModel();
            var shared = new SessionScopeCriterion(SessionCriterionType.METHOD, "", "^GET$");
            model.load(new SessionScope(SessionScopeMode.CUSTOM, List.of(shared),
                    List.of(new SessionScopeRule("Empty custom scope", placeholder))));
            assertEquals(List.of("Common"), model.ruleNames());
            assertEquals(1, model.getRowCount());
            var scope = model.scope(SessionScopeMode.CUSTOM);
            assertEquals(List.of(shared), scope.criteria());
            assertTrue(scope.rules().isEmpty());
            assertTrue(scope.matches(false, "GET", "api", "/", List.of(), ""));
            assertFalse(scope.matches(false, "POST", "api", "/", List.of(), ""));
            assertTrue(model.description().startsWith("No scope rules: apply this profile whenever all shared conditions match."));
        }
        var model = new SessionScopeTableModel();
        var userRule = new SessionScopeRule("Empty custom scope",
                List.of(new SessionScopeCriterion(SessionCriterionType.HOST, "", "^api$")));
        model.load(new SessionScope(SessionScopeMode.CUSTOM, List.of(), List.of(userRule)));
        assertEquals(List.of(userRule), model.scope(SessionScopeMode.CUSTOM).rules());
    }

    @Test
    void renamingPreservesEveryConditionAndDoesNotMergeRules() {
        var model = new SessionScopeTableModel();
        model.addRow(new Object[]{SessionCriterionType.HOST, "", "^api$", "API"});
        model.addRow(new Object[]{SessionCriterionType.HEADER, "X-User", "^Alice$", "API"});
        model.addRow(new Object[]{SessionCriterionType.HEADER, "X-Tenant", "^Team$", "API"});
        model.addRow(new Object[]{SessionCriterionType.HOST, "", "^admin$", "Admin"});
        var before = model.scope(SessionScopeMode.CUSTOM);
        assertThrows(IllegalArgumentException.class, () -> model.renameRule("API", "Admin"));
        assertThrows(IllegalArgumentException.class, () -> model.renameRule("API", " common "));
        assertThrows(IllegalArgumentException.class, () -> model.renameRule("API", " "));
        assertEquals(before, model.scope(SessionScopeMode.CUSTOM));
        model.renameRule("API", " Payments ");
        var after = model.scope(SessionScopeMode.CUSTOM);
        assertEquals("Payments", after.rules().getFirst().name());
        assertEquals(before.rules().getFirst().criteria(), after.rules().getFirst().criteria());
        assertEquals(before.rules().get(1), after.rules().get(1));
    }

    @Test
    void emptyRuleNeverMatchesAndRemovingItLeavesOnlySharedConditions() {
        var model = new SessionScopeTableModel();
        model.addRow(new Object[]{SessionCriterionType.METHOD, "", "^GET$", "Common"});
        model.addRow(new Object[]{SessionCriterionType.HOST, "", "^api$", "API"});
        model.removeCondition(1);
        assertFalse(model.scope(SessionScopeMode.CUSTOM).matches(false, "GET", "other", "/", List.of(), ""));
        assertEquals("API", model.ruleAt(1));
        model.setValueAt("^api$", 1, 2);
        model.moveCondition(1, "Common");
        assertFalse(model.scope(SessionScopeMode.CUSTOM).matches(false, "GET", "api", "/", List.of(), ""));
        model.removeRule("API");
        assertTrue(model.scope(SessionScopeMode.CUSTOM).matches(false, "GET", "api", "/", List.of(), ""));
        assertFalse(model.scope(SessionScopeMode.CUSTOM).matches(false, "GET", "other", "/", List.of(), ""));
    }

    @Test
    void summaryShowsSharedRequirementsSeparateAlternativesAndValidationErrors() {
        var model = new SessionScopeTableModel();
        model.addRow(new Object[]{SessionCriterionType.HEADER, "X-User", "^Alice$", "Common"});
        model.addRow(new Object[]{SessionCriterionType.HOST, "", "^api$", "API"});
        model.addRow(new Object[]{SessionCriterionType.HOST, "", "[", "Admin"});
        String summary = model.description();
        assertTrue(summary.contains("AND at least one rule"));
        assertTrue(summary.contains("Shared: Header X-User must exist; value matches /^Alice$/"));
        assertTrue(summary.contains("Rule API: HOST matches /^api$/"));
        assertTrue(summary.contains("Rule Admin: HOST matches /[/ AND INVALID:"));
    }

    @Test
    void rowsBecomeAndWithinEachRuleAndOrAcrossRules() {
        var model = new SessionScopeTableModel();
        model.addRow(new Object[]{SessionCriterionType.HOST, "", "^a$", "Rule 1"});
        model.addRow(new Object[]{SessionCriterionType.HOST, "", "^b$", "Rule 2"});
        model.addRow(new Object[]{SessionCriterionType.HEADER, "X-User", "^Alice$", "Common"});
        SessionScope scope = model.scope(SessionScopeMode.CUSTOM);
        assertEquals(2, scope.rules().size());
        for (String host : List.of("a", "b")) {
            assertTrue(scope.matches(false, "GET", host, "/", List.of("X-User: Alice"), ""));
            assertFalse(scope.matches(false, "GET", host, "/", List.of("X-User: Bob"), ""));
        }
        var reloaded = new SessionScopeTableModel();
        reloaded.load(scope);
        assertEquals(scope, reloaded.scope(SessionScopeMode.CUSTOM));
    }

    @Test
    void emptyCustomScopeMatchesAllButAnUnconfiguredRuleMatchesNothing() {
        var model = new SessionScopeTableModel();
        assertTrue(model.scope(SessionScopeMode.CUSTOM).matches(false, "GET", "a", "/", List.of(), ""));
        assertTrue(model.description().contains("ALL requests"));
        model.addRule();
        assertFalse(model.scope(SessionScopeMode.CUSTOM).matches(false, "GET", "a", "/", List.of(), ""));
        model.setValueAt("^a$", 0, 2);
        assertTrue(model.scope(SessionScopeMode.CUSTOM).matches(false, "GET", "a", "/", List.of(), ""));
        model.removeRow(0);
        assertTrue(model.scope(SessionScopeMode.CUSTOM).matches(false, "GET", "a", "/", List.of(), ""));
    }

    @Test
    void malformedRegexAndMissingHeaderSelectorAreRejectedBeforeSaving() {
        var model = new SessionScopeTableModel();
        model.addRow(new Object[]{SessionCriterionType.HOST, "", "[", "Rule 1"});
        assertThrows(IllegalArgumentException.class, () -> model.scope(SessionScopeMode.CUSTOM));
        model.setValueAt(SessionCriterionType.HEADER, 0, 0);
        model.setValueAt(".*", 0, 2);
        assertThrows(IllegalArgumentException.class, () -> model.scope(SessionScopeMode.CUSTOM));
        model.setValueAt("X-User", 0, 1);
        assertDoesNotThrow(() -> model.scope(SessionScopeMode.CUSTOM));
    }

    @Test
    void legacyEmptyCustomScopeStaysExplicitlyAllAndEmptyAlternativeNeverBroadensOnReload() {
        var model = new SessionScopeTableModel();
        model.load(SessionScope.custom(List.of()));
        assertEquals(0, model.getRowCount());
        assertTrue(model.scope(SessionScopeMode.CUSTOM).matches(false, "GET", "a", "/", List.of(), ""));
        model.load(new SessionScope(SessionScopeMode.CUSTOM,
                List.of(new SessionScopeCriterion(SessionCriterionType.METHOD, "", "^GET$")),
                List.of(new SessionScopeRule("Empty", List.of()))));
        assertFalse(model.scope(SessionScopeMode.CUSTOM).matches(false, "GET", "a", "/", List.of(), ""));
    }
}

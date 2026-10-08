package dev.nquangit.corestra.ui.session;

import dev.nquangit.corestra.session.SessionCriterionType;
import dev.nquangit.corestra.session.SessionScope;
import dev.nquangit.corestra.session.SessionScopeCriterion;
import dev.nquangit.corestra.session.SessionScopeMode;
import dev.nquangit.corestra.session.SessionScopeRule;

import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Maps scope-table rows to shared conditions and optional named alternative rules. */
final class SessionScopeTableModel extends DefaultTableModel {
    static final String COMMON = "Common";
    static final int RULE_COLUMN = 3;

    SessionScopeTableModel() {
        super(new String[]{"Type", "Selector (header name)", "Regex pattern", "Scope rule"}, 0);
    }

    void load(SessionScope scope) {
        setRowCount(0);
        scope.criteria().forEach(criterion -> add(criterion, COMMON));
        scope.rules().forEach(rule -> {
            if (rule.criteria().isEmpty()) add(new SessionScopeCriterion(SessionCriterionType.HOST, "", "(?!)"), rule.name());
            else rule.criteria().forEach(criterion -> add(criterion, rule.name()));
        });
    }

    SessionScope scope(SessionScopeMode mode) {
        List<SessionScopeCriterion> common = new ArrayList<>();
        LinkedHashMap<String, List<SessionScopeCriterion>> rules = new LinkedHashMap<>();
        for (int row = 0; row < getRowCount(); row++) {
            Object value = getValueAt(row, 0);
            SessionCriterionType type = value instanceof SessionCriterionType actual ? actual
                    : SessionCriterionType.valueOf(text(row, 0));
            var criterion = new SessionScopeCriterion(type, text(row, 1), text(row, 2));
            if (mode == SessionScopeMode.CUSTOM && criterion.validationError().isPresent()) {
                throw new IllegalArgumentException("Scope row " + (row + 1) + ": " + criterion.validationError().orElseThrow());
            }
            String name = ruleAt(row);
            if (COMMON.equals(name)) common.add(criterion);
            else rules.computeIfAbsent(name, key -> new ArrayList<>()).add(criterion);
        }
        List<SessionScopeRule> alternatives = new ArrayList<>();
        rules.forEach((name, criteria) -> alternatives.add(new SessionScopeRule(name, criteria)));
        return new SessionScope(mode, common, alternatives);
    }

    int addRule() {
        int index = 1;
        List<String> names = ruleNames();
        while (names.contains("Rule " + index)) index++;
        addRow(new Object[]{SessionCriterionType.HOST, "", "(?!)", "Rule " + index});
        return getRowCount() - 1;
    }

    void addCriterion(String rule) {
        addRow(new Object[]{SessionCriterionType.HOST, "", "(?!)", rule});
    }

    String ruleAt(int row) {
        if (row < 0 || row >= getRowCount()) return COMMON;
        String name = text(row, RULE_COLUMN).strip();
        return name.isBlank() || name.equalsIgnoreCase(COMMON) ? COMMON : name;
    }

    List<String> ruleNames() {
        List<String> names = new ArrayList<>();
        names.add(COMMON);
        for (int row = 0; row < getRowCount(); row++) {
            String name = ruleAt(row);
            if (!names.contains(name)) names.add(name);
        }
        return List.copyOf(names);
    }

    boolean hasMultipleCommonHosts() {
        int hosts = 0;
        for (int row = 0; row < getRowCount(); row++) {
            if (COMMON.equals(ruleAt(row)) && SessionCriterionType.HOST.toString().equals(text(row, 0))) hosts++;
        }
        return hosts > 1;
    }

    void validateRuleName(String original, String replacement) {
        if (COMMON.equals(original)) throw new IllegalArgumentException("Shared conditions cannot be renamed.");
        if (replacement == null || replacement.isBlank()) throw new IllegalArgumentException("Enter a rule name.");
        String name = replacement.strip();
        if (name.equalsIgnoreCase(COMMON)) throw new IllegalArgumentException("Common is reserved for shared conditions.");
        if (!name.equals(original) && ruleNames().contains(name)) {
            throw new IllegalArgumentException("A rule with that name already exists.");
        }
    }

    void renameRule(String original, String replacement) {
        validateRuleName(original, replacement);
        for (int row = 0; row < getRowCount(); row++) {
            if (original.equals(ruleAt(row))) setValueAt(replacement.strip(), row, RULE_COLUMN);
        }
    }

    void removeRule(String name) {
        if (COMMON.equals(name)) throw new IllegalArgumentException("Shared conditions cannot be removed as a rule.");
        for (int row = getRowCount() - 1; row >= 0; row--) {
            if (name.equals(ruleAt(row))) removeRow(row);
        }
    }

    void removeCondition(int row) {
        String group = ruleAt(row);
        removeRow(row);
        preserveEmptyRule(group);
    }

    void moveCondition(int row, String target) {
        if (!ruleNames().contains(target)) throw new IllegalArgumentException("Select an existing scope rule.");
        String original = ruleAt(row);
        setValueAt(target, row, RULE_COLUMN);
        preserveEmptyRule(original);
    }

    String description() {
        if (getRowCount() == 0) {
            return "No shared conditions or scope rules: Custom scope applies to ALL requests (all hosts, paths and methods).";
        }
        boolean alternatives = ruleNames().size() > 1;
        StringBuilder text = new StringBuilder(alternatives
                ? "Apply this profile when all shared conditions match AND at least one rule below matches.\n"
                : "No scope rules: apply this profile whenever all shared conditions match.\n");
        if (hasMultipleCommonHosts()) {
            text.append("Warning: multiple shared HOST conditions must ALL match. Move alternative hosts into separate rules.\n");
        }
        for (String group : ruleNames()) {
            List<String> conditions = new ArrayList<>();
            for (int row = 0; row < getRowCount(); row++) {
                if (!group.equals(ruleAt(row))) continue;
                String type = text(row, 0);
                String pattern = text(row, 2);
                String subject = SessionCriterionType.HEADER.toString().equals(type)
                        ? "Header " + text(row, 1) + " must exist; value" : type;
                conditions.add(subject + ("(?!)".equals(pattern) ? " is not configured (matches no traffic)"
                        : pattern.isBlank() ? " (any value)" : " matches /" + pattern + "/"));
                try {
                    var criterion = new SessionScopeCriterion(SessionCriterionType.valueOf(type), text(row, 1), pattern);
                    criterion.validationError().ifPresent(error -> conditions.add("INVALID: " + error));
                } catch (IllegalArgumentException exception) {
                    conditions.add("INVALID: " + exception.getMessage());
                }
            }
            text.append(COMMON.equals(group) ? "Shared" : "Rule " + group).append(": ");
            text.append(conditions.isEmpty() ? "no extra restrictions" : String.join(" AND ", conditions)).append('\n');
        }
        return text.toString().stripTrailing();
    }

    private void preserveEmptyRule(String group) {
        if (!COMMON.equals(group) && !ruleNames().contains(group)) addCriterion(group);
    }

    private void add(SessionScopeCriterion criterion, String rule) {
        addRow(new Object[]{criterion.type(), criterion.selector(), criterion.pattern(), rule});
    }

    private String text(int row, int column) {
        Object value = getValueAt(row, column);
        return value == null ? "" : value.toString();
    }
}

package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Editable table of Auth success or failure rules. Shared by the Auth Provider editor and the
 * Session Handler (standalone live re-login) so both edit the same rule model the same way.
 */
public final class AuthOutcomeRulesTable extends JPanel {
    private static final int ID_COLUMN = 4;

    private final String title;
    private final boolean success;
    private final DefaultTableModel model;
    private final JTable table;
    private boolean loading;

    /**
     * Creates the table.
     *
     * @param title label shown next to the buttons
     * @param rules initial {@link AuthSuccessRule}s or {@link AuthFailureRule}s
     * @param success true for success rules (new rows default to 200), false for failure rules
     */
    public AuthOutcomeRulesTable(String title, List<?> rules, boolean success) {
        super(new BorderLayout(6, 6));
        this.title = title;
        this.success = success;
        setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        model = new DefaultTableModel(new Object[]{"Enabled", "Type", "Expression", "Expected Value", "Id"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }

            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : Object.class;
            }
        };
        table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(1)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(new AuthRuleType[]{
                        AuthRuleType.STATUS_CODE,
                        AuthRuleType.HEADER_EXISTS,
                        AuthRuleType.BODY_CONTAINS,
                        AuthRuleType.BODY_NOT_CONTAINS})));
        table.getColumnModel().removeColumn(table.getColumnModel().getColumn(ID_COLUMN));

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);
        setRules(rules);
    }

    /**
     * Calls {@code listener} after the user edits, adds or removes a row (not after {@link #setRules}).
     *
     * @param listener change callback
     */
    public void addChangeListener(Runnable listener) {
        model.addTableModelListener(event -> {
            if (!loading) {
                listener.run();
            }
        });
    }

    /**
     * Returns the rows as success rules; blank rows are skipped.
     *
     * @return immutable rules
     */
    public List<AuthSuccessRule> successRules() {
        stopEditing();
        ArrayList<AuthSuccessRule> rules = new ArrayList<>();
        for (int row = 0; row < model.getRowCount(); row++) {
            String expression = stringAt(row, 2);
            String expectedValue = stringAt(row, 3);
            if (expression.isBlank() && expectedValue.isBlank()) {
                continue;
            }
            rules.add(new AuthSuccessRule(
                    idAt(row, "success"),
                    nameFor("Success", expression, expectedValue),
                    booleanAt(row, 0),
                    enumAt(row, 1, AuthRuleType.class, AuthRuleType.CUSTOM),
                    expression,
                    expectedValue));
        }
        return List.copyOf(rules);
    }

    /**
     * Returns the rows as failure rules; blank rows are skipped.
     *
     * @return immutable rules
     */
    public List<AuthFailureRule> failureRules() {
        stopEditing();
        ArrayList<AuthFailureRule> rules = new ArrayList<>();
        for (int row = 0; row < model.getRowCount(); row++) {
            String expression = stringAt(row, 2);
            String expectedValue = stringAt(row, 3);
            if (expression.isBlank() && expectedValue.isBlank()) {
                continue;
            }
            rules.add(new AuthFailureRule(
                    idAt(row, "failure"),
                    nameFor("Failure", expression, expectedValue),
                    booleanAt(row, 0),
                    enumAt(row, 1, AuthRuleType.class, AuthRuleType.CUSTOM),
                    expression,
                    expectedValue));
        }
        return List.copyOf(rules);
    }

    /**
     * Replaces all rows without notifying change listeners.
     *
     * @param rules {@link AuthSuccessRule}s or {@link AuthFailureRule}s
     */
    public void setRules(List<?> rules) {
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        loading = true;
        try {
            model.setRowCount(0);
            if (rules == null) {
                return;
            }
            for (Object rule : rules) {
                if (rule instanceof AuthSuccessRule successRule) {
                    model.addRow(new Object[]{
                            successRule.enabled(),
                            successRule.type(),
                            successRule.expression(),
                            successRule.expectedValue(),
                            successRule.id()});
                } else if (rule instanceof AuthFailureRule failureRule) {
                    model.addRow(new Object[]{
                            failureRule.enabled(),
                            failureRule.type(),
                            failureRule.expression(),
                            failureRule.expectedValue(),
                            failureRule.id()});
                }
            }
        } finally {
            loading = false;
        }
    }

    /** Commits an in-progress cell edit so the model holds the latest value. */
    public void stopEditing() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        table.setEnabled(enabled);
    }

    private JPanel buttons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton add = StyledButton.neutral("Add Row");
        JButton remove = StyledButton.danger("Remove Row");
        add.addActionListener(event -> model.addRow(new Object[]{
                true,
                AuthRuleType.STATUS_CODE,
                success ? "200" : "401",
                "",
                ""}));
        remove.addActionListener(event -> removeSelectedRow());
        panel.add(add);
        panel.add(remove);
        panel.add(Box.createHorizontalStrut(6));
        panel.add(new JLabel(title));
        return panel;
    }

    private void removeSelectedRow() {
        int row = table.getSelectedRow();
        if (row >= 0) {
            stopEditing();
            model.removeRow(table.convertRowIndexToModel(row));
        }
    }

    private String stringAt(int row, int column) {
        Object value = model.getValueAt(row, column);
        return value == null ? "" : value.toString().strip();
    }

    private boolean booleanAt(int row, int column) {
        Object value = model.getValueAt(row, column);
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String idAt(int row, String prefix) {
        String id = stringAt(row, ID_COLUMN);
        if (id.isBlank()) {
            // Persist the generated id in the row so repeated reads return stable ids.
            id = prefix + "-" + UUID.randomUUID();
            boolean wasLoading = loading;
            loading = true;
            try {
                model.setValueAt(id, row, ID_COLUMN);
            } finally {
                loading = wasLoading;
            }
        }
        return id;
    }

    private <T extends Enum<T>> T enumAt(int row, int column, Class<T> enumType, T defaultValue) {
        Object value = model.getValueAt(row, column);
        if (enumType.isInstance(value)) {
            return enumType.cast(value);
        }
        if (value != null) {
            try {
                return Enum.valueOf(enumType, value.toString());
            } catch (IllegalArgumentException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String nameFor(String prefix, String first, String second) {
        String value = first == null || first.isBlank() ? second : first;
        return value == null || value.isBlank() ? prefix : prefix + " " + value.strip();
    }
}

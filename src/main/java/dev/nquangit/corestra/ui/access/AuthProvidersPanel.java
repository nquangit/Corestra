package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthFailureRule;
import dev.nquangit.corestra.access.auth.AuthFallbackPolicy;
import dev.nquangit.corestra.access.auth.AuthInjectionLocation;
import dev.nquangit.corestra.access.auth.AuthInjectionMode;
import dev.nquangit.corestra.access.auth.AuthInjectionRule;
import dev.nquangit.corestra.access.auth.AuthProvider;
import dev.nquangit.corestra.access.auth.AuthRetryPolicy;
import dev.nquangit.corestra.access.auth.AuthRuleType;
import dev.nquangit.corestra.access.auth.AuthSuccessRule;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class AuthProvidersPanel extends JPanel implements AccessTabPanel {
    private final AccessUiController controller;
    private final DefaultTableModel tableModel;
    private final JTable table;

    AuthProvidersPanel(AccessUiController controller) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        tableModel = new DefaultTableModel(new Object[]{
                "Name", "Enabled", "Login Flow", "Refresh Flow", "Validate Flow", "Fallback", "Retry", "Id"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().removeColumn(table.getColumnModel().getColumn(7));

        add(toolbar(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        refresh();
    }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        javax.swing.JButton add = StyledButton.primary("Add");
        javax.swing.JButton edit = StyledButton.neutral("Edit");
        javax.swing.JButton delete = StyledButton.danger("Delete");
        add.addActionListener(event -> showEditor(null));
        edit.addActionListener(event -> selectedProvider().ifPresent(this::showEditor));
        delete.addActionListener(event -> selectedProvider().ifPresent(this::deleteProvider));
        panel.add(add);
        panel.add(edit);
        panel.add(delete);
        return panel;
    }

    private void showEditor(AuthProvider existing) {
        AuthProviderFormPanel form = new AuthProviderFormPanel(existing == null
                ? null
                : AuthProviderFormModel.from(existing),
                controller.flows());
        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                existing == null ? "Add Auth Provider" : "Edit Auth Provider",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            controller.saveAuthProvider(existing == null ? "" : existing.id(), form.toModel());
            refresh();
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Invalid Auth Provider", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteProvider(AuthProvider provider) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Delete auth provider \"" + provider.name() + "\"?",
                "Delete Auth Provider",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            controller.deleteAuthProvider(provider.id());
            refresh();
        }
    }

    private java.util.Optional<AuthProvider> selectedProvider() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return java.util.Optional.empty();
        }
        int modelRow = table.convertRowIndexToModel(row);
        String id = (String) tableModel.getValueAt(modelRow, 7);
        return controller.authProvider(id);
    }

    @Override
    public void refreshUi() {
        refresh();
    }

    private void refresh() {
        tableModel.setRowCount(0);
        for (AuthProvider provider : controller.authProviders()) {
            tableModel.addRow(new Object[]{
                    provider.name(),
                    provider.enabled(),
                    flowLabel(provider.loginFlowId()),
                    flowLabel(provider.refreshFlowId()),
                    flowLabel(provider.validateFlowId()),
                    provider.fallbackPolicy(),
                    provider.targetRetryPolicy(),
                    provider.id()});
        }
    }

    private String flowLabel(String flowId) {
        String normalized = flowId == null ? "" : flowId.strip();
        if (normalized.isBlank()) {
            return "";
        }
        return controller.flows().stream()
                .filter(flow -> flow.id().equals(normalized))
                .map(flow -> flow.name() + " (" + flow.id() + ")")
                .findFirst()
                .orElse(normalized);
    }

    private static final class AuthProviderFormPanel extends JPanel {
        private final JTextField name = new JTextField();
        private final JTextField description = new JTextField();
        private final JCheckBox enabled = new JCheckBox("Enabled", true);
        private final JComboBox<FlowOption> loginFlow = new JComboBox<>();
        private final JComboBox<FlowOption> refreshFlow = new JComboBox<>();
        private final JComboBox<FlowOption> validateFlow = new JComboBox<>();
        private final JComboBox<AuthFallbackPolicy> fallbackPolicy = new JComboBox<>(AuthFallbackPolicy.values());
        private final JComboBox<AuthRetryPolicy> targetRetryPolicy = new JComboBox<>(AuthRetryPolicy.values());
        private final InjectionRulesTablePanel injectionRules;
        private final OutcomeRulesTablePanel successRules;
        private final OutcomeRulesTablePanel failureRules;

        AuthProviderFormPanel(AuthProviderFormModel model, List<FlowDefinition> flows) {
            super(new BorderLayout(8, 8));
            AuthProviderFormModel initial = model == null
                    ? new AuthProviderFormModel(
                    "New Auth Provider",
                    "",
                    true,
                    "",
                    "",
                    "",
                    AuthFallbackPolicy.MANUAL_ONLY,
                    AuthRetryPolicy.NO_RETRY,
                    List.of(),
                    List.of(),
                    List.of())
                    : model;
            populateFlowChoices(flows, initial.loginFlowId(), initial.refreshFlowId(), initial.validateFlowId());
            name.setText(initial.name());
            description.setText(initial.description());
            enabled.setSelected(initial.enabled());
            selectFlow(loginFlow, initial.loginFlowId());
            selectFlow(refreshFlow, initial.refreshFlowId());
            selectFlow(validateFlow, initial.validateFlowId());
            fallbackPolicy.setSelectedItem(initial.fallbackPolicy());
            targetRetryPolicy.setSelectedItem(initial.targetRetryPolicy());
            injectionRules = new InjectionRulesTablePanel(initial.injectionRules());
            successRules = new OutcomeRulesTablePanel("Success Rules", initial.successRules(), true);
            failureRules = new OutcomeRulesTablePanel("Failure Rules", initial.failureRules(), false);

            add(buildFields(), BorderLayout.NORTH);
            add(buildRuleTabs(), BorderLayout.CENTER);
            setPreferredSize(new Dimension(820, 620));
        }

        AuthProviderFormModel toModel() {
            return new AuthProviderFormModel(
                    name.getText(),
                    description.getText(),
                    enabled.isSelected(),
                    selectedFlowId(loginFlow),
                    selectedFlowId(refreshFlow),
                    selectedFlowId(validateFlow),
                    selectedFallbackPolicy(),
                    selectedRetryPolicy(),
                    injectionRules.rules(),
                    successRules.successRules(),
                    failureRules.failureRules());
        }

        private JPanel buildFields() {
            JPanel panel = new JPanel(new BorderLayout(8, 8));
            JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
            fields.add(new JLabel("Name"));
            fields.add(name);
            fields.add(new JLabel("Description"));
            fields.add(description);
            fields.add(new JLabel(""));
            fields.add(enabled);
            fields.add(new JLabel("Login Flow"));
            fields.add(loginFlow);
            fields.add(new JLabel("Refresh Flow"));
            fields.add(refreshFlow);
            fields.add(new JLabel("Validate Flow"));
            fields.add(validateFlow);
            fields.add(new JLabel("Fallback Policy"));
            fields.add(fallbackPolicy);
            fields.add(new JLabel("Target Retry Policy"));
            fields.add(targetRetryPolicy);

            JTextArea note = new JTextArea(
                    "Select flows that already exist in Flowise. Leave a flow as None when the selected policy does not use it.");
            note.setEditable(false);
            note.setOpaque(false);
            note.setLineWrap(true);
            note.setWrapStyleWord(true);

            panel.add(fields, BorderLayout.CENTER);
            panel.add(note, BorderLayout.SOUTH);
            return panel;
        }

        private void populateFlowChoices(
                List<FlowDefinition> flows,
                String loginFlowId,
                String refreshFlowId,
                String validateFlowId) {
            addFlowChoices(loginFlow, flows, loginFlowId);
            addFlowChoices(refreshFlow, flows, refreshFlowId);
            addFlowChoices(validateFlow, flows, validateFlowId);
        }

        private void addFlowChoices(JComboBox<FlowOption> combo, List<FlowDefinition> flows, String selectedId) {
            combo.removeAllItems();
            combo.addItem(FlowOption.none());
            boolean selectedFound = selectedId == null || selectedId.isBlank();
            if (flows != null) {
                for (FlowDefinition flow : flows) {
                    combo.addItem(FlowOption.from(flow));
                    if (flow.id().equals(selectedId)) {
                        selectedFound = true;
                    }
                }
            }
            if (!selectedFound) {
                combo.addItem(FlowOption.missing(selectedId));
            }
        }

        private void selectFlow(JComboBox<FlowOption> combo, String id) {
            String normalized = id == null ? "" : id.strip();
            for (int index = 0; index < combo.getItemCount(); index++) {
                if (combo.getItemAt(index).id().equals(normalized)) {
                    combo.setSelectedIndex(index);
                    return;
                }
            }
            combo.setSelectedIndex(0);
        }

        private String selectedFlowId(JComboBox<FlowOption> combo) {
            Object selected = combo.getSelectedItem();
            return selected instanceof FlowOption option ? option.id() : "";
        }

        private JTabbedPane buildRuleTabs() {
            JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
            tabs.addTab("Injection", injectionRules);
            tabs.addTab("Success", successRules);
            tabs.addTab("Failure", failureRules);
            return tabs;
        }

        private AuthFallbackPolicy selectedFallbackPolicy() {
            Object selected = fallbackPolicy.getSelectedItem();
            return selected instanceof AuthFallbackPolicy policy ? policy : AuthFallbackPolicy.MANUAL_ONLY;
        }

        private AuthRetryPolicy selectedRetryPolicy() {
            Object selected = targetRetryPolicy.getSelectedItem();
            return selected instanceof AuthRetryPolicy policy ? policy : AuthRetryPolicy.NO_RETRY;
        }
    }

    private record FlowOption(String id, String label) {
        private static FlowOption none() {
            return new FlowOption("", "None");
        }

        private static FlowOption from(FlowDefinition flow) {
            return new FlowOption(flow.id(), flow.name() + " (" + flow.id() + ")");
        }

        private static FlowOption missing(String id) {
            String normalized = id == null ? "" : id.strip();
            return new FlowOption(normalized, "Missing flow: " + normalized);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class InjectionRulesTablePanel extends JPanel {
        private static final int ID_COLUMN = 5;

        private final DefaultTableModel model;
        private final JTable table;

        InjectionRulesTablePanel(List<AuthInjectionRule> rules) {
            super(new BorderLayout(6, 6));
            setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            model = new DefaultTableModel(new Object[]{"Enabled", "Location", "Target", "Value Template", "Mode", "Id"}, 0) {
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
                    .setCellEditor(new DefaultCellEditor(new JComboBox<>(AuthInjectionLocation.values())));
            table.getColumnModel().getColumn(4)
                    .setCellEditor(new DefaultCellEditor(new JComboBox<>(AuthInjectionMode.values())));
            table.getColumnModel().removeColumn(table.getColumnModel().getColumn(ID_COLUMN));

            add(new JScrollPane(table), BorderLayout.CENTER);
            add(buttons(), BorderLayout.SOUTH);
            setRules(rules);
        }

        List<AuthInjectionRule> rules() {
            stopEditing();
            ArrayList<AuthInjectionRule> rules = new ArrayList<>();
            for (int row = 0; row < model.getRowCount(); row++) {
                String target = stringAt(row, 2);
                String valueTemplate = stringAt(row, 3);
                if (target.isBlank() && valueTemplate.isBlank()) {
                    continue;
                }
                rules.add(new AuthInjectionRule(
                        idAt(row, "injection"),
                        nameFor("Injection", target, valueTemplate),
                        booleanAt(row, 0),
                        enumAt(row, 1, AuthInjectionLocation.class, AuthInjectionLocation.HEADER),
                        target,
                        valueTemplate,
                        enumAt(row, 4, AuthInjectionMode.class, AuthInjectionMode.OVERRIDE)));
            }
            return List.copyOf(rules);
        }

        private void setRules(List<AuthInjectionRule> rules) {
            model.setRowCount(0);
            if (rules != null) {
                rules.forEach(rule -> model.addRow(new Object[]{
                        rule.enabled(),
                        rule.location(),
                        rule.target(),
                        rule.valueTemplate(),
                        rule.mode(),
                        rule.id()}));
            }
        }

        private JPanel buttons() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            javax.swing.JButton add = StyledButton.neutral("Add Row");
            javax.swing.JButton remove = StyledButton.danger("Remove Row");
            add.addActionListener(event -> model.addRow(new Object[]{
                    true,
                    AuthInjectionLocation.HEADER,
                    "Authorization",
                    "Bearer {{auth.accessToken}}",
                    AuthInjectionMode.OVERRIDE,
                    ""}));
            remove.addActionListener(event -> removeSelectedRow());
            panel.add(add);
            panel.add(remove);
            return panel;
        }

        private void removeSelectedRow() {
            int row = table.getSelectedRow();
            if (row >= 0) {
                model.removeRow(table.convertRowIndexToModel(row));
            }
        }

        private void stopEditing() {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
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
            return id.isBlank() ? prefix + "-" + UUID.randomUUID() : id;
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
    }

    private static final class OutcomeRulesTablePanel extends JPanel {
        private static final int ID_COLUMN = 4;

        private final String title;
        private final boolean success;
        private final DefaultTableModel model;
        private final JTable table;

        OutcomeRulesTablePanel(String title, List<?> rules, boolean success) {
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
                    .setCellEditor(new DefaultCellEditor(new JComboBox<>(AuthRuleType.values())));
            table.getColumnModel().removeColumn(table.getColumnModel().getColumn(ID_COLUMN));

            add(new JScrollPane(table), BorderLayout.CENTER);
            add(buttons(), BorderLayout.SOUTH);
            setRules(rules);
        }

        List<AuthSuccessRule> successRules() {
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

        List<AuthFailureRule> failureRules() {
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

        private void setRules(List<?> rules) {
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
        }

        private JPanel buttons() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            javax.swing.JButton add = StyledButton.neutral("Add Row");
            javax.swing.JButton remove = StyledButton.danger("Remove Row");
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
                model.removeRow(table.convertRowIndexToModel(row));
            }
        }

        private void stopEditing() {
            if (table.isEditing()) {
                table.getCellEditor().stopCellEditing();
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
            return id.isBlank() ? prefix + "-" + UUID.randomUUID() : id;
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
    }

    private static String nameFor(String prefix, String first, String second) {
        String value = first == null || first.isBlank() ? second : first;
        return value == null || value.isBlank() ? prefix : prefix + " " + value.strip();
    }
}

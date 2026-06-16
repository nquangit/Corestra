package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.control.ConditionMatchPolicy;
import dev.nquangit.corestra.flow.control.ConditionValueSource;
import dev.nquangit.corestra.flow.control.StepAction;
import dev.nquangit.corestra.flow.control.StepActionType;
import dev.nquangit.corestra.flow.control.StepCondition;
import dev.nquangit.corestra.flow.control.StepConditionActionRule;
import dev.nquangit.corestra.flow.control.StepConditionOperator;
import dev.nquangit.corestra.flow.control.StepConditionSource;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Displays and edits post-step condition/action rules for the selected flow step.
 */
public final class ConditionActionPanel extends JPanel {
    private static final String[] COLUMNS = {"Enabled", "Name", "Match", "Conditions", "Actions", "Id"};

    private final FlowWorkspaceController controller;
    private final DefaultTableModel model;
    private final JTable table;

    /**
     * Creates a condition/action editor panel.
     *
     * @param controller workspace controller
     */
    public ConditionActionPanel(FlowWorkspaceController controller) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.model = readOnlyModel();
        this.table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(5).setMinWidth(0);
        table.getColumnModel().getColumn(5).setMaxWidth(0);
        table.getColumnModel().getColumn(5).setPreferredWidth(0);

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buttonBar(), BorderLayout.SOUTH);
        controller.addFlowChangeListener(this::refresh);
        refresh();
    }

    private JPanel buttonBar() {
        StyledButton add = StyledButton.primary("+");
        add.setToolTipText("Add condition/action rule");
        add.addActionListener(event -> addRule());

        StyledButton edit = StyledButton.neutral("Edit");
        edit.setToolTipText("Edit selected condition/action rule");
        edit.addActionListener(event -> editRule());

        StyledButton remove = StyledButton.danger("-");
        remove.setToolTipText("Remove selected condition/action rule");
        remove.addActionListener(event -> removeRule());

        JPanel bar = new JPanel();
        bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
        bar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        bar.add(add);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(edit);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(remove);
        bar.add(Box.createHorizontalGlue());
        return bar;
    }

    private static DefaultTableModel readOnlyModel() {
        return new DefaultTableModel(COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    }

    private void refresh() {
        model.setRowCount(0);
        FlowStep step = controller.selectedStep();
        if (step == null) {
            return;
        }
        for (StepConditionActionRule rule : step.conditionActionRules()) {
            model.addRow(new Object[]{
                    rule.enabled(),
                    rule.name(),
                    rule.matchPolicy(),
                    rule.conditions().size(),
                    rule.actions().size(),
                    rule.id()});
        }
    }

    private void addRule() {
        FlowStep step = controller.selectedStep();
        if (step == null) {
            return;
        }
        RuleDialog.show(this, null, controller.flows()).ifPresent(saved ->
                saveStep(step.withConditionActionRules(append(step.conditionActionRules(), saved))));
    }

    private void editRule() {
        FlowStep step = controller.selectedStep();
        int row = table.getSelectedRow();
        if (step == null || row < 0 || row >= step.conditionActionRules().size()) {
            return;
        }
        StepConditionActionRule existing = step.conditionActionRules().get(row);
        RuleDialog.show(this, existing, controller.flows()).ifPresent(saved ->
                saveStep(step.withConditionActionRules(replace(step.conditionActionRules(), row, saved))));
    }

    private void removeRule() {
        FlowStep step = controller.selectedStep();
        int row = table.getSelectedRow();
        if (step == null || row < 0 || row >= step.conditionActionRules().size()) {
            return;
        }
        saveStep(step.withConditionActionRules(remove(step.conditionActionRules(), row)));
    }

    private void saveStep(FlowStep updatedStep) {
        FlowDefinition active = controller.activeFlow();
        if (active == null) {
            return;
        }
        List<FlowStep> newSteps = new ArrayList<>(active.steps());
        for (int i = 0; i < newSteps.size(); i++) {
            if (newSteps.get(i).id().equals(updatedStep.id())) {
                newSteps.set(i, updatedStep);
                break;
            }
        }
        FlowDefinition updated = new FlowDefinition(
                active.id(), active.name(), active.description(), active.enabled(),
                newSteps, active.rules(), active.aggregation(), active.triggerConfig());
        controller.updateFlow(updated);
        controller.setSelectedStep(updatedStep);
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> updated = new ArrayList<>(values);
        updated.add(value);
        return updated;
    }

    private static <T> List<T> replace(List<T> values, int index, T value) {
        List<T> updated = new ArrayList<>(values);
        updated.set(index, value);
        return updated;
    }

    private static <T> List<T> remove(List<T> values, int index) {
        List<T> updated = new ArrayList<>(values);
        updated.remove(index);
        return updated;
    }

    private static final class RuleDialog {
        private final JTextField name = new JTextField(24);
        private final JCheckBox enabled = new JCheckBox("Enabled");
        private final JComboBox<ConditionMatchPolicy> matchPolicy = new JComboBox<>(ConditionMatchPolicy.values());
        private final ConditionTable conditions;
        private final ActionTable actions;
        private final String id;

        private RuleDialog(StepConditionActionRule initial, List<FlowDefinition> flows) {
            this.id = initial == null ? UUID.randomUUID().toString() : initial.id();
            name.setText(initial == null ? "New Condition Action Rule" : initial.name());
            enabled.setSelected(initial == null || initial.enabled());
            matchPolicy.setSelectedItem(initial == null ? ConditionMatchPolicy.ALL : initial.matchPolicy());
            conditions = new ConditionTable(initial == null ? List.of() : initial.conditions());
            actions = new ActionTable(initial == null ? List.of() : initial.actions(), flows);
        }

        private static Optional<StepConditionActionRule> show(
                Component parent,
                StepConditionActionRule initial,
                List<FlowDefinition> flows) {
            RuleDialog editor = new RuleDialog(initial, flows);
            JPanel panel = editor.panel();
            int choice = JOptionPane.showConfirmDialog(
                    parent,
                    panel,
                    initial == null ? "Add Condition & Actions" : "Edit Condition & Actions",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return Optional.empty();
            }
            try {
                return Optional.of(editor.toRule());
            } catch (IllegalArgumentException exception) {
                JOptionPane.showMessageDialog(parent, exception.getMessage(), "Invalid Rule", JOptionPane.ERROR_MESSAGE);
                return Optional.empty();
            }
        }

        private JPanel panel() {
            JPanel top = new JPanel(new GridBagLayout());
            top.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
            GridBagConstraints label = constraints(0, 0, 0, GridBagConstraints.NONE);
            GridBagConstraints field = constraints(1, 0, 1, GridBagConstraints.HORIZONTAL);
            top.add(new JLabel("Name"), label);
            top.add(name, field);
            label.gridy++;
            field.gridy++;
            top.add(new JLabel("Match"), label);
            top.add(matchPolicy, field);
            label.gridy++;
            field.gridy++;
            top.add(new JLabel("State"), label);
            top.add(enabled, field);

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Conditions", conditions);
            tabs.addTab("Actions", actions);

            JPanel panel = new JPanel(new BorderLayout());
            panel.setPreferredSize(new Dimension(860, 560));
            panel.add(top, BorderLayout.NORTH);
            panel.add(tabs, BorderLayout.CENTER);
            return panel;
        }

        private StepConditionActionRule toRule() {
            String trimmedName = name.getText().strip();
            if (trimmedName.isBlank()) {
                throw new IllegalArgumentException("Rule name must not be blank");
            }
            return new StepConditionActionRule(
                    id,
                    trimmedName,
                    enabled.isSelected(),
                    (ConditionMatchPolicy) matchPolicy.getSelectedItem(),
                    conditions.conditions(),
                    actions.actions());
        }

        private static GridBagConstraints constraints(int x, int y, double weightX, int fill) {
            GridBagConstraints constraints = new GridBagConstraints();
            constraints.gridx = x;
            constraints.gridy = y;
            constraints.weightx = weightX;
            constraints.insets = new Insets(2, 2, 2, 6);
            constraints.anchor = GridBagConstraints.WEST;
            constraints.fill = fill;
            return constraints;
        }
    }

    private static final class ConditionTable extends JPanel {
        private static final String[] COLUMNS = {
                "Enabled", "Source", "Selector", "Operator", "Value Source", "Expected", "Case", "Negate", "Id"};

        private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return switch (columnIndex) {
                    case 0, 6, 7 -> Boolean.class;
                    default -> Object.class;
                };
            }
        };
        private final JTable table = new JTable(model);

        private ConditionTable(List<StepCondition> conditions) {
            super(new BorderLayout());
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setFillsViewportHeight(true);
            table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(StepConditionSource.values())));
            table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(new JComboBox<>(StepConditionOperator.values())));
            table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(new JComboBox<>(ConditionValueSource.values())));
            hideIdColumn(table, 8);
            setConditions(conditions);
            add(new JScrollPane(table), BorderLayout.CENTER);
            add(buttonBar(), BorderLayout.SOUTH);
        }

        private JPanel buttonBar() {
            JButton add = StyledButton.primary("+");
            add.setToolTipText("Add condition");
            add.addActionListener(event -> model.addRow(new Object[]{
                    true,
                    StepConditionSource.RESPONSE_STATUS,
                    "",
                    StepConditionOperator.EQUALS,
                    ConditionValueSource.STATIC,
                    "200",
                    false,
                    false,
                    UUID.randomUUID().toString()}));

            JButton remove = StyledButton.danger("-");
            remove.setToolTipText("Remove selected condition");
            remove.addActionListener(event -> removeSelected(table, model));

            JPanel bar = new JPanel();
            bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
            bar.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
            bar.add(add);
            bar.add(Box.createHorizontalStrut(4));
            bar.add(remove);
            bar.add(Box.createHorizontalGlue());
            return bar;
        }

        private void setConditions(List<StepCondition> conditions) {
            model.setRowCount(0);
            for (StepCondition condition : conditions) {
                model.addRow(new Object[]{
                        condition.enabled(),
                        condition.source(),
                        condition.selector(),
                        condition.operator(),
                        condition.expectedValueSource(),
                        condition.expectedValue(),
                        condition.caseSensitive(),
                        condition.negate(),
                        condition.id()});
            }
        }

        private List<StepCondition> conditions() {
            stopEditing(table);
            List<StepCondition> conditions = new ArrayList<>();
            for (int row = 0; row < model.getRowCount(); row++) {
                StepConditionSource source = enumValue(row, 1, StepConditionSource.class, StepConditionSource.RESPONSE_STATUS);
                String selector = text(row, 2, "");
                StepConditionOperator operator = enumValue(row, 3, StepConditionOperator.class, StepConditionOperator.EQUALS);
                conditions.add(new StepCondition(
                        text(row, 8, UUID.randomUUID().toString()),
                        conditionName(source, selector, operator),
                        bool(row, 0),
                        source,
                        selector,
                        operator,
                        enumValue(row, 4, ConditionValueSource.class, ConditionValueSource.STATIC),
                        text(row, 5, ""),
                        bool(row, 6),
                        bool(row, 7)));
            }
            return conditions;
        }

        private String text(int row, int column, String fallback) {
            Object value = model.getValueAt(row, column);
            return value == null ? fallback : value.toString().strip();
        }

        private boolean bool(int row, int column) {
            Object value = model.getValueAt(row, column);
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(Objects.toString(value, "false"));
        }

        private <T extends Enum<T>> T enumValue(int row, int column, Class<T> type, T fallback) {
            Object value = model.getValueAt(row, column);
            if (type.isInstance(value)) {
                return type.cast(value);
            }
            try {
                return Enum.valueOf(type, Objects.toString(value, fallback.name()));
            } catch (IllegalArgumentException exception) {
                return fallback;
            }
        }
    }

    private static final class ActionTable extends JPanel {
        private static final String[] COLUMNS = {"Enabled", "Type", "Parameter", "Id"};

        private final List<FlowOption> flowOptions;
        private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : Object.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column != 2 || parametersEditable(row);
            }

            @Override
            public void setValueAt(Object aValue, int row, int column) {
                super.setValueAt(aValue, row, column);
                if (column == 1) {
                    StepActionType type = enumValue(row, 1, StepActionType.class, StepActionType.BREAK_FLOW);
                    if (!parametersEditable(row)) {
                        super.setValueAt("", row, 2);
                    } else if (type == StepActionType.TRIGGER_FLOW
                            && Objects.toString(getValueAt(row, 2), "").isBlank()
                            && !flowOptions.isEmpty()) {
                        super.setValueAt(flowOptions.getFirst(), row, 2);
                    } else if (type == StepActionType.SLEEP
                            && Objects.toString(getValueAt(row, 2), "").isBlank()) {
                        super.setValueAt("1", row, 2);
                    }
                }
            }
        };
        private final JTable table;

        private ActionTable(List<StepAction> actions, List<FlowDefinition> flows) {
            super(new BorderLayout());
            this.flowOptions = flowOptions(flows);
            this.table = new ActionJTable(model, flowOptions);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setFillsViewportHeight(true);
            table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(new JComboBox<>(StepActionType.values())));
            hideIdColumn(table, 3);
            setActions(actions);
            add(new JScrollPane(table), BorderLayout.CENTER);
            add(buttonBar(), BorderLayout.SOUTH);
        }

        private JPanel buttonBar() {
            JButton add = StyledButton.primary("+");
            add.setToolTipText("Add action");
            add.addActionListener(event -> model.addRow(new Object[]{
                    true,
                    StepActionType.BREAK_FLOW,
                    "",
                    UUID.randomUUID().toString()}));

            JButton remove = StyledButton.danger("-");
            remove.setToolTipText("Remove selected action");
            remove.addActionListener(event -> removeSelected(table, model));

            JButton up = StyledButton.neutral("Up");
            up.setToolTipText("Move selected action up");
            up.addActionListener(event -> moveSelected(-1));

            JButton down = StyledButton.neutral("Down");
            down.setToolTipText("Move selected action down");
            down.addActionListener(event -> moveSelected(1));

            JPanel bar = new JPanel();
            bar.setLayout(new BoxLayout(bar, BoxLayout.X_AXIS));
            bar.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
            bar.add(add);
            bar.add(Box.createHorizontalStrut(4));
            bar.add(remove);
            bar.add(Box.createHorizontalStrut(8));
            bar.add(up);
            bar.add(Box.createHorizontalStrut(4));
            bar.add(down);
            bar.add(Box.createHorizontalGlue());
            return bar;
        }

        private void setActions(List<StepAction> actions) {
            model.setRowCount(0);
            for (StepAction action : actions) {
                model.addRow(new Object[]{
                        action.enabled(),
                        action.type(),
                        parameterValue(action),
                        action.id()});
            }
        }

        private List<StepAction> actions() {
            stopEditing(table);
            List<StepAction> actions = new ArrayList<>();
            for (int row = 0; row < model.getRowCount(); row++) {
                StepActionType type = enumValue(row, 1, StepActionType.class, StepActionType.BREAK_FLOW);
                actions.add(new StepAction(
                        text(row, 3, UUID.randomUUID().toString()),
                        actionName(type),
                        bool(row, 0),
                        type,
                        parametersFor(type, model.getValueAt(row, 2))));
            }
            return actions;
        }

        private boolean parametersEditable(int row) {
            StepActionType type = enumValue(row, 1, StepActionType.class, StepActionType.BREAK_FLOW);
            return type == StepActionType.SLEEP || type == StepActionType.TRIGGER_FLOW;
        }

        private void moveSelected(int direction) {
            stopEditing(table);
            int row = table.getSelectedRow();
            int target = row + direction;
            if (row < 0 || target < 0 || target >= model.getRowCount()) {
                return;
            }
            model.moveRow(row, row, target);
            table.setRowSelectionInterval(target, target);
        }

        private String text(int row, int column, String fallback) {
            Object value = model.getValueAt(row, column);
            return value == null ? fallback : value.toString().strip();
        }

        private boolean bool(int row, int column) {
            Object value = model.getValueAt(row, column);
            return value instanceof Boolean bool ? bool : Boolean.parseBoolean(Objects.toString(value, "false"));
        }

        private <T extends Enum<T>> T enumValue(int row, int column, Class<T> type, T fallback) {
            Object value = model.getValueAt(row, column);
            if (type.isInstance(value)) {
                return type.cast(value);
            }
            try {
                return Enum.valueOf(type, Objects.toString(value, fallback.name()));
            } catch (IllegalArgumentException exception) {
                return fallback;
            }
        }
    }

    private static final class ActionJTable extends JTable {
        private final List<FlowOption> flowOptions;

        private ActionJTable(DefaultTableModel model, List<FlowOption> flowOptions) {
            super(model);
            this.flowOptions = flowOptions;
        }

        @Override
        public TableCellEditor getCellEditor(int row, int column) {
            if (column == 2 && actionTypeAt(row) == StepActionType.TRIGGER_FLOW) {
                JComboBox<FlowOption> combo = new JComboBox<>(flowOptions.toArray(FlowOption[]::new));
                combo.setSelectedItem(flowOptionFor(Objects.toString(getValueAt(row, column), ""), flowOptions));
                return new DefaultCellEditor(combo);
            }
            return super.getCellEditor(row, column);
        }

        private StepActionType actionTypeAt(int row) {
            Object value = getValueAt(row, 1);
            if (value instanceof StepActionType type) {
                return type;
            }
            try {
                return StepActionType.valueOf(Objects.toString(value, StepActionType.BREAK_FLOW.name()));
            } catch (IllegalArgumentException exception) {
                return StepActionType.BREAK_FLOW;
            }
        }
    }

    private static void removeSelected(JTable table, DefaultTableModel model) {
        stopEditing(table);
        int row = table.getSelectedRow();
        if (row >= 0 && row < model.getRowCount()) {
            model.removeRow(row);
        }
    }

    private static void stopEditing(JTable table) {
        TableCellEditor editor = table.getCellEditor();
        if (editor != null) {
            editor.stopCellEditing();
        }
    }

    private static void hideIdColumn(JTable table, int column) {
        table.getColumnModel().getColumn(column).setMinWidth(0);
        table.getColumnModel().getColumn(column).setMaxWidth(0);
        table.getColumnModel().getColumn(column).setPreferredWidth(0);
    }

    private static String conditionName(
            StepConditionSource source,
            String selector,
            StepConditionOperator operator) {
        String suffix = selector == null || selector.isBlank() ? "" : " " + selector.strip();
        return source + suffix + " " + operator;
    }

    private static String actionName(StepActionType type) {
        return switch (type) {
            case BREAK_FLOW -> "Break flow";
            case SLEEP -> "Sleep";
            case RETRY_CURRENT_STEP -> "Retry current step";
            case RETRY_FLOW_FROM_START -> "Retry flow from start";
            case TRIGGER_FLOW -> "Trigger flow";
        };
    }

    private static Object parameterValue(StepAction action) {
        return switch (action.type()) {
            case SLEEP -> action.parameters().getOrDefault("seconds", "1");
            case TRIGGER_FLOW -> action.parameters().getOrDefault("flowId", "");
            case BREAK_FLOW, RETRY_CURRENT_STEP, RETRY_FLOW_FROM_START -> "";
        };
    }

    private static Map<String, String> parametersFor(StepActionType type, Object value) {
        String text = value instanceof FlowOption option
                ? option.id()
                : Objects.toString(value, "").strip();
        if (text.isBlank()) {
            return Map.of();
        }
        return switch (type) {
            case SLEEP -> Map.of("seconds", text);
            case TRIGGER_FLOW -> Map.of("flowId", text);
            case BREAK_FLOW, RETRY_CURRENT_STEP, RETRY_FLOW_FROM_START -> Map.of();
        };
    }

    private static List<FlowOption> flowOptions(List<FlowDefinition> flows) {
        List<FlowOption> options = new ArrayList<>();
        for (FlowDefinition flow : Objects.requireNonNullElse(flows, List.<FlowDefinition>of())) {
            options.add(new FlowOption(flow.id(), flow.name()));
        }
        return List.copyOf(options);
    }

    private static FlowOption flowOptionFor(String value, List<FlowOption> options) {
        for (FlowOption option : options) {
            if (option.id().equals(value) || option.toString().equals(value)) {
                return option;
            }
        }
        return options.isEmpty() ? new FlowOption(value, value) : options.getFirst();
    }

    private record FlowOption(String id, String name) {
        @Override
        public String toString() {
            return name == null || name.isBlank() ? id : name + " (" + id + ")";
        }
    }
}

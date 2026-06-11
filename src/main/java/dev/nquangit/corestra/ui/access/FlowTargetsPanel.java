package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.flow.FlowTargetHasher;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages saved Access Testing flow targets.
 */
final class FlowTargetsPanel extends JPanel implements AccessTabPanel {
    private final AccessUiController controller;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextArea preview = new JTextArea();
    private final FlowTargetHasher hasher = new FlowTargetHasher();

    FlowTargetsPanel(AccessUiController controller) {
        super(new BorderLayout(8, 8));
        this.controller = Objects.requireNonNull(controller, "controller");
        this.tableModel = new DefaultTableModel(
                new Object[]{"Name", "Source Flow", "Target Step", "Steps", "Stale", "Enabled", "Id"},
                0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        this.table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().removeColumn(table.getColumnModel().getColumn(6));
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showPreview();
            }
        });
        preview.setEditable(false);
        preview.setLineWrap(false);
        add(toolbar(), BorderLayout.NORTH);
        add(content(), BorderLayout.CENTER);
        refresh();
    }

    @Override
    public void refreshUi() {
        refresh();
    }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JButton add = StyledButton.primary("Add Flow Target");
        JButton matrix = StyledButton.neutral("Add as Matrix Target");
        JButton delete = StyledButton.danger("Delete");
        add.setToolTipText("Snapshot a Flowise flow for Access Testing. No HTTP request is sent.");
        matrix.setToolTipText("Create a disabled Matrix row from the selected flow target.");
        delete.setToolTipText("Delete the selected flow target.");
        add.addActionListener(event -> showAddDialog());
        matrix.addActionListener(event -> selectedTarget().ifPresent(this::showMatrixDialog));
        delete.addActionListener(event -> selectedTarget().ifPresent(this::deleteTarget));
        panel.add(add);
        panel.add(matrix);
        panel.add(delete);
        panel.add(new JLabel("Flow targets are saved snapshots; source-flow edits do not auto-refresh them."));
        return panel;
    }

    private JPanel content() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel previewPanel = new JPanel(new BorderLayout());
        previewPanel.setBorder(BorderFactory.createTitledBorder("Executed Steps Preview"));
        previewPanel.add(new JScrollPane(preview), BorderLayout.CENTER);
        root.add(previewPanel, BorderLayout.SOUTH);
        return root;
    }

    private void refresh() {
        tableModel.setRowCount(0);
        for (AccessFlowTarget target : controller.flowTargets()) {
            tableModel.addRow(new Object[]{
                    target.name(),
                    target.sourceFlowName().isBlank() ? target.sourceFlowId() : target.sourceFlowName(),
                    stepName(target),
                    enabledStepsThroughTarget(target),
                    stale(target) ? "Yes" : "No",
                    target.enabled(),
                    target.id()
            });
        }
        showPreview();
    }

    private Optional<AccessFlowTarget> selectedTarget() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return Optional.empty();
        }
        int modelRow = table.convertRowIndexToModel(row);
        String id = (String) tableModel.getValueAt(modelRow, 6);
        return controller.flowTargets().stream().filter(target -> target.id().equals(id)).findFirst();
    }

    private void showPreview() {
        preview.setText(selectedTarget().map(this::previewText).orElse(""));
        preview.setCaretPosition(0);
    }

    private String previewText(AccessFlowTarget target) {
        StringBuilder text = new StringBuilder();
        if (stale(target)) {
            text.append("Warning: source flow has changed since this snapshot was created.").append('\n');
        }
        for (FlowStep step : target.flowSnapshot().steps()) {
            if (!step.enabled()) {
                continue;
            }
            text.append(step.id().equals(target.targetStepId()) ? "* " : "  ");
            text.append(step.name()).append("  ")
                    .append(step.requestTemplate().method()).append(' ')
                    .append(step.requestTemplate().pathAndQuery()).append('\n');
            if (step.id().equals(target.targetStepId())) {
                break;
            }
        }
        if (!target.injectionOverrides().isEmpty()) {
            text.append('\n').append("Overrides: ").append(target.injectionOverrides().size()).append('\n');
        }
        return text.toString();
    }

    private void showAddDialog() {
        FlowTargetFormPanel form = new FlowTargetFormPanel(controller.flows());
        if (form.empty()) {
            JOptionPane.showMessageDialog(this,
                    "Create at least one Flowise flow before adding a flow target.",
                    "Add Flow Target",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                "Add Flow Target",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            controller.createFlowTargetFromFlow(form.flow(), form.targetStepId(), List.of());
            refresh();
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Add Flow Target", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showMatrixDialog(AccessFlowTarget target) {
        MatrixTargetPanel form = new MatrixTargetPanel(controller);
        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                "Add Flow Target as Matrix Row",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            controller.addFlowTargetAsMatrixTarget(
                    target,
                    form.authProfileId(),
                    form.dataProfileId(),
                    form.expected());
            JOptionPane.showMessageDialog(this,
                    "Created disabled Matrix row for flow target:\n" + target.name(),
                    "Matrix Target Created",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Matrix Target", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteTarget(AccessFlowTarget target) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Delete flow target \"" + target.name() + "\"?",
                "Delete Flow Target",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.YES_OPTION) {
            controller.deleteFlowTarget(target.id());
            refresh();
        }
    }

    private String stepName(AccessFlowTarget target) {
        return target.flowSnapshot().steps().stream()
                .filter(step -> step.id().equals(target.targetStepId()))
                .map(FlowStep::name)
                .findFirst()
                .orElse(target.targetStepId());
    }

    private int enabledStepsThroughTarget(AccessFlowTarget target) {
        int count = 0;
        for (FlowStep step : target.flowSnapshot().steps()) {
            if (step.enabled()) {
                count++;
            }
            if (step.id().equals(target.targetStepId())) {
                return count;
            }
        }
        return count;
    }

    private boolean stale(AccessFlowTarget target) {
        return controller.flows().stream()
                .filter(flow -> flow.id().equals(target.sourceFlowId()))
                .findFirst()
                .map(flow -> !hasher.hash(flow).equals(target.sourceFlowHash()))
                .orElse(false);
    }

    private static final class FlowTargetFormPanel extends JPanel {
        private final JComboBox<FlowOption> flow = new JComboBox<>();
        private final JComboBox<StepOption> step = new JComboBox<>();

        private FlowTargetFormPanel(List<FlowDefinition> flows) {
            super(new GridLayout(0, 2, 6, 6));
            for (FlowDefinition item : flows) {
                flow.addItem(new FlowOption(item));
            }
            flow.addActionListener(event -> refreshSteps());
            add(new JLabel("Flow"));
            add(flow);
            add(new JLabel("Target Step"));
            add(step);
            refreshSteps();
        }

        private boolean empty() {
            return flow.getItemCount() == 0;
        }

        private FlowDefinition flow() {
            FlowOption option = (FlowOption) flow.getSelectedItem();
            if (option == null) {
                throw new IllegalArgumentException("Flow is required");
            }
            return option.flow();
        }

        private String targetStepId() {
            StepOption option = (StepOption) step.getSelectedItem();
            return option == null ? "" : option.step().id();
        }

        private void refreshSteps() {
            step.removeAllItems();
            if (flow.getSelectedItem() instanceof FlowOption option) {
                for (FlowStep item : option.flow().steps()) {
                    if (item.enabled()) {
                        step.addItem(new StepOption(item));
                    }
                }
                if (step.getItemCount() > 0) {
                    step.setSelectedIndex(step.getItemCount() - 1);
                }
            }
        }
    }

    private static final class MatrixTargetPanel extends JPanel {
        private final JComboBox<ComboItem> auth = new JComboBox<>();
        private final JComboBox<ComboItem> data = new JComboBox<>();
        private final JComboBox<ExpectedResult> expected = new JComboBox<>(ExpectedResult.values());

        private MatrixTargetPanel(AccessUiController controller) {
            super(new GridLayout(0, 2, 6, 6));
            for (AuthProfile profile : controller.authProfiles()) {
                auth.addItem(new ComboItem(profile.id(), profile.name()));
            }
            data.addItem(new ComboItem("", ""));
            for (DataProfile profile : controller.dataProfiles()) {
                data.addItem(new ComboItem(profile.id(), profile.name()));
            }
            expected.setSelectedItem(ExpectedResult.REVIEW);
            add(new JLabel("Target Auth"));
            add(auth);
            add(new JLabel("Data Profile"));
            add(data);
            add(new JLabel("Expected"));
            add(expected);
        }

        private String authProfileId() {
            ComboItem item = (ComboItem) auth.getSelectedItem();
            return item == null ? "" : item.id();
        }

        private String dataProfileId() {
            ComboItem item = (ComboItem) data.getSelectedItem();
            return item == null ? "" : item.id();
        }

        private ExpectedResult expected() {
            return (ExpectedResult) expected.getSelectedItem();
        }
    }

    private record FlowOption(FlowDefinition flow) {
        @Override
        public String toString() {
            return flow.name();
        }
    }

    private record StepOption(FlowStep step) {
        @Override
        public String toString() {
            return step.name();
        }
    }

    private record ComboItem(String id, String label) {
        @Override
        public String toString() {
            return label == null || label.isBlank() ? id : label;
        }
    }
}

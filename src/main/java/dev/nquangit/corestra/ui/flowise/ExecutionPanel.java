package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.actions.RunFlowAction;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.execution.StepExecutionRecord;

import dev.nquangit.corestra.ui.component.StyledButton;
import dev.nquangit.corestra.ui.component.UiTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Displays the most recent execution result for the active flow.
 *
 * <p>Shows a per-step summary table with columns: Step, Status, Code, Duration (ms), Variables.
 * When no execution has been run the panel shows a placeholder message.</p>
 *
 * <p>The panel registers a change listener on the supplied {@link FlowWorkspaceController} and
 * refreshes automatically whenever state changes.</p>
 */
public final class ExecutionPanel extends JPanel {

    private static final String[] COLUMNS = {"Step", "Status", "Code", "Duration (ms)", "Variables"};

    private final FlowWorkspaceController controller;
    private final JLabel summaryLabel;
    private final DefaultTableModel tableModel;
    private final JButton runButton;
    private final JButton runStepButton;

    /**
     * Creates an execution panel wired to the supplied controller.
     *
     * @param controller the workspace controller that owns the last execution result
     * @throws NullPointerException if {@code controller} is {@code null}
     */
    public ExecutionPanel(FlowWorkspaceController controller) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");

        summaryLabel = buildSummaryLabel();
        tableModel = new DefaultTableModel(COLUMNS, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        JTable table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        // Column widths: Step is widest, Code/Variables are narrow
        table.getColumnModel().getColumn(0).setPreferredWidth(160); // Step
        table.getColumnModel().getColumn(1).setPreferredWidth(55);  // Status
        table.getColumnModel().getColumn(2).setPreferredWidth(45);  // Code
        table.getColumnModel().getColumn(3).setPreferredWidth(95);  // Duration (ms)
        table.getColumnModel().getColumn(4).setPreferredWidth(70);  // Variables

        runButton = StyledButton.success("► Run Flow");
        runButton.setToolTipText("Execute the active flow from first to last enabled step");
        runButton.addActionListener(e -> runFlow());

        runStepButton = StyledButton.primary("Run Step");
        runStepButton.setToolTipText("Execute only the selected step (for debugging)");
        runStepButton.addActionListener(e -> runStep());

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(runStepButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(runButton);

        JPanel header = new JPanel(new BorderLayout());
        header.add(summaryLabel, BorderLayout.CENTER);
        header.add(buttons, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);

        controller.addFlowChangeListener(this::refresh);
        refresh();
    }

    private void runFlow() {
        FlowEngine engine = controller.flowEngine();
        FlowDefinition active = controller.activeFlow();
        if (engine == null || active == null) {
            return;
        }
        RunFlowAction action = new RunFlowAction(engine);
        execute(() -> action.run(active), "Running flow…");
    }

    private void runStep() {
        FlowEngine engine = controller.flowEngine();
        FlowDefinition active = controller.activeFlow();
        FlowStep step = controller.selectedStep();
        if (engine == null || active == null || step == null) {
            return;
        }
        RunFlowAction action = new RunFlowAction(engine);
        execute(() -> action.runSingleStep(active, step), "Running step…");
    }

    /**
     * Runs the supplied execution off the event dispatch thread, then stores the result back on it.
     * The result is rendered via the controller's change notification.
     */
    private void execute(Callable<ExecutionResult> task, String runningStatus) {
        setRunning(true, runningStatus);

        new SwingWorker<ExecutionResult, Void>() {
            @Override protected ExecutionResult doInBackground() throws Exception {
                return task.call();
            }

            @Override protected void done() {
                try {
                    ExecutionResult result = get();
                    controller.setLastExecutionResult(result);
                } catch (Exception ex) {
                    summaryLabel.setText("Execution failed: " + ex.getMessage());
                    summaryLabel.setForeground(UiTheme.pick(new Color(0xDC2626), new Color(0xF87171)));
                } finally {
                    setRunning(false, null);
                }
            }
        }.execute();
    }

    private void setRunning(boolean running, String runningStatus) {
        if (running) {
            runButton.setEnabled(false);
            runStepButton.setEnabled(false);
            summaryLabel.setText(runningStatus);
            summaryLabel.setForeground(UIManager.getColor("Label.foreground"));
        } else {
            updateButtonState();
        }
    }

    private void updateButtonState() {
        boolean canRun = controller.flowEngine() != null && controller.activeFlow() != null;
        runButton.setEnabled(canRun);
        runStepButton.setEnabled(canRun && controller.selectedStep() != null);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private JLabel buildSummaryLabel() {
        JLabel label = new JLabel("No execution results yet.", SwingConstants.LEFT);
        label.setBorder(BorderFactory.createEmptyBorder(6, 6, 4, 6));
        Font base = UIManager.getFont("Label.font");
        if (base != null) {
            label.setFont(base.deriveFont(Font.ITALIC));
        }
        return label;
    }

    private void refresh() {
        updateButtonState();
        tableModel.setRowCount(0);
        ExecutionResult result = controller.lastExecutionResult();
        if (result == null) {
            summaryLabel.setText("No execution results yet.");
            summaryLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            return;
        }

        if (result.success()) {
            summaryLabel.setText("Last run: SUCCESS — " + result.flowName());
            summaryLabel.setForeground(UiTheme.pick(new Color(0x059669), new Color(0x34D399)));
        } else {
            summaryLabel.setText("Last run: FAILED — " + result.failureMessage());
            summaryLabel.setForeground(UiTheme.pick(new Color(0xDC2626), new Color(0xF87171)));
        }

        FlowDefinition active = controller.activeFlow();
        List<StepExecutionRecord> records = result.stepRecords();
        for (StepExecutionRecord record : records) {
            String stepName = resolveStepName(active, record.stepId());
            String status = record.success() ? "OK" : "FAIL";
            long duration = record.completedAtEpochMillis() - record.startedAtEpochMillis();
            tableModel.addRow(new Object[]{
                    stepName,
                    status,
                    record.responseStatusCode() > 0 ? record.responseStatusCode() : "-",
                    duration,
                    record.extractedVariables().size()
            });
        }
    }

    private static String resolveStepName(FlowDefinition flow, String stepId) {
        if (flow == null) {
            return stepId;
        }
        for (FlowStep step : flow.steps()) {
            if (step.id().equals(stepId)) {
                return step.name();
            }
        }
        return stepId;
    }
}

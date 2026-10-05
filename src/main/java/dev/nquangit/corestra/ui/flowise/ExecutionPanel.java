package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.actions.RunFlowAction;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.execution.ExecutionResult;
import dev.nquangit.corestra.flow.execution.FlowEngine;
import dev.nquangit.corestra.flow.execution.StepExecutionRecord;
import dev.nquangit.corestra.flow.rule.InjectRule;
import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.ui.access.AccessUiController;

import dev.nquangit.corestra.ui.component.StyledButton;
import dev.nquangit.corestra.ui.component.UiTheme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JComboBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final AccessUiController accessController;
    private final JLabel contextLabel;
    private String selectedAuthProfileId = "";
    private String selectedDataProfileId = "";
    private String customVariablesText = "";

    /**
     * Creates an execution panel wired to the supplied controller.
     *
     * @param controller the workspace controller that owns the last execution result
     * @throws NullPointerException if {@code controller} is {@code null}
     */
    public ExecutionPanel(FlowWorkspaceController controller) {
        this(controller, null);
    }

    /** Creates an execution panel with optional Access Testing run contexts. */
    public ExecutionPanel(
            FlowWorkspaceController controller,
            AccessUiController accessController) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.accessController = accessController;

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

        JButton contextButton = StyledButton.neutral("Run Context…");
        contextButton.setToolTipText("Choose Auth/Data Profiles and custom variables for this manual run");
        contextButton.addActionListener(event -> configureRunContext());
        contextButton.setVisible(accessController != null);
        contextLabel = new JLabel(accessController == null ? "" : "Context: none");

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(runStepButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(contextButton);
        buttons.add(Box.createHorizontalStrut(6));
        buttons.add(runButton);

        JPanel header = new JPanel(new BorderLayout());
        header.add(summaryLabel, BorderLayout.CENTER);
        header.add(buttons, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        if (accessController != null) {
            contextLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));
            contextLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            top.add(contextLabel, BorderLayout.SOUTH);
        }

        add(top, BorderLayout.NORTH);
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
        Map<String, String> variables = initialVariables(active, null);
        if (variables == null) {
            return;
        }
        execute(() -> action.run(active, variables), "Running flow…");
    }

    private void runStep() {
        FlowEngine engine = controller.flowEngine();
        FlowDefinition active = controller.activeFlow();
        FlowStep step = controller.selectedStep();
        if (engine == null || active == null || step == null) {
            return;
        }
        RunFlowAction action = new RunFlowAction(engine);
        Map<String, String> variables = initialVariables(active, step);
        if (variables == null) {
            return;
        }
        execute(() -> action.runSingleStep(active, step, variables), "Running step…");
    }

    private void configureRunContext() {
        if (accessController == null) {
            return;
        }
        JComboBox<ContextOption<AuthProfile>> authCombo = new JComboBox<>();
        authCombo.addItem(ContextOption.none("No Auth Profile"));
        accessController.authProfiles().stream().filter(AuthProfile::enabled)
                .forEach(profile -> authCombo.addItem(new ContextOption<>(
                        profile.id(), profile.name() + roleSuffix(profile), profile)));
        selectOption(authCombo, selectedAuthProfileId);

        JComboBox<ContextOption<DataProfile>> dataCombo = new JComboBox<>();
        dataCombo.addItem(ContextOption.none("No Data Profile"));
        accessController.dataProfiles().stream().filter(DataProfile::enabled)
                .forEach(profile -> dataCombo.addItem(new ContextOption<>(profile.id(), profile.name(), profile)));
        selectOption(dataCombo, selectedDataProfileId);

        JTextArea customArea = new JTextArea(customVariablesText, 7, 38);
        customArea.setToolTipText("One key=value per line. Custom values override selected profiles.");
        JPanel form = new JPanel(new BorderLayout(0, 8));
        JPanel selections = new JPanel(new GridLayout(0, 2, 6, 6));
        selections.add(new JLabel("Auth Profile:"));
        selections.add(authCombo);
        selections.add(new JLabel("Data Profile:"));
        selections.add(dataCombo);
        form.add(selections, BorderLayout.NORTH);
        JPanel custom = new JPanel(new BorderLayout());
        custom.setBorder(BorderFactory.createTitledBorder("Custom variables (key=value)"));
        custom.add(new JScrollPane(customArea), BorderLayout.CENTER);
        form.add(custom, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(
                this, form, "Configure Flowise Run Context",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            Map<String, String> parsed = FlowRunContext.parseCustomVariables(customArea.getText());
            ContextOption<AuthProfile> auth = selected(authCombo);
            ContextOption<DataProfile> data = selected(dataCombo);
            validateOwnership(auth.value(), data.value());
            selectedAuthProfileId = auth.id();
            selectedDataProfileId = data.id();
            customVariablesText = customArea.getText();
            updateContextLabel(auth, data, parsed.size());
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "Invalid Run Context", JOptionPane.ERROR_MESSAGE);
        }
    }

    private Map<String, String> initialVariables(FlowDefinition flow, FlowStep onlyStep) {
        try {
            AuthProfile auth = selectedAuthProfile();
            DataProfile data = selectedDataProfile();
            validateOwnership(auth, data);
            Map<String, String> variables = FlowRunContext.variables(
                    auth, data, FlowRunContext.parseCustomVariables(customVariablesText));
            List<String> missing = missingScopedVariables(flow, onlyStep, variables);
            if (!missing.isEmpty()) {
                JOptionPane.showMessageDialog(
                        this,
                        "Run Context cannot resolve: " + String.join(", ", missing)
                                + "\nSelect the required profile or provide the key under Custom variables.",
                        "Incomplete Run Context",
                        JOptionPane.ERROR_MESSAGE);
                return null;
            }
            return variables;
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "Invalid Run Context", JOptionPane.ERROR_MESSAGE);
            return null;
        }
    }

    static List<String> missingScopedVariables(
            FlowDefinition flow,
            FlowStep onlyStep,
            Map<String, String> initialVariables) {
        LinkedHashSet<String> available = new LinkedHashSet<>(initialVariables.keySet());
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        List<FlowStep> steps = onlyStep == null ? flow.steps() : List.of(onlyStep);
        for (FlowStep step : steps) {
            if (onlyStep == null && !step.enabled()) {
                continue;
            }
            for (InjectRule rule : step.injectRules()) {
                if (!rule.enabled()) {
                    continue;
                }
                for (String key : rule.referencedVariableKeys()) {
                    if (isProfileScoped(key) && !available.contains(key)) {
                        missing.add(key);
                    }
                }
            }
            step.extractRules().stream().filter(rule -> rule.enabled())
                    .forEach(rule -> available.add(rule.targetVariableName()));
        }
        return List.copyOf(missing);
    }

    private static boolean isProfileScoped(String key) {
        return key.startsWith("credentials.") || key.startsWith("auth.") || key.startsWith("data.");
    }

    private AuthProfile selectedAuthProfile() {
        if (accessController == null || selectedAuthProfileId.isBlank()) {
            return null;
        }
        return accessController.authProfile(selectedAuthProfileId)
                .filter(AuthProfile::enabled)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Selected Auth Profile is missing or disabled. Reconfigure Run Context."));
    }

    private DataProfile selectedDataProfile() {
        if (accessController == null || selectedDataProfileId.isBlank()) {
            return null;
        }
        return accessController.dataProfile(selectedDataProfileId)
                .filter(DataProfile::enabled)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Selected Data Profile is missing or disabled. Reconfigure Run Context."));
    }

    private static void validateOwnership(AuthProfile auth, DataProfile data) {
        if (auth != null && data != null && !data.ownerAuthProfileId().isBlank()
                && !data.ownerAuthProfileId().equals(auth.id())) {
            throw new IllegalArgumentException(
                    "The selected Data Profile belongs to a different Auth Profile.");
        }
    }

    private void updateContextLabel(
            ContextOption<AuthProfile> auth,
            ContextOption<DataProfile> data,
            int customCount) {
        List<String> parts = new ArrayList<>();
        if (auth.value() != null) parts.add(auth.label());
        if (data.value() != null) parts.add(data.label());
        if (customCount > 0) parts.add(customCount + " custom");
        contextLabel.setText("Context: " + (parts.isEmpty() ? "none" : String.join(" · ", parts)));
    }

    private static String roleSuffix(AuthProfile profile) {
        return profile.role().isBlank() ? "" : " (" + profile.role() + ")";
    }

    private static <T> void selectOption(JComboBox<ContextOption<T>> combo, String id) {
        for (int index = 0; index < combo.getItemCount(); index++) {
            if (combo.getItemAt(index).id().equals(id)) {
                combo.setSelectedIndex(index);
                return;
            }
        }
        combo.setSelectedIndex(0);
    }

    @SuppressWarnings("unchecked")
    private static <T> ContextOption<T> selected(JComboBox<ContextOption<T>> combo) {
        return (ContextOption<T>) combo.getSelectedItem();
    }

    private record ContextOption<T>(String id, String label, T value) {
        static <T> ContextOption<T> none(String label) {
            return new ContextOption<>("", label, null);
        }

        @Override public String toString() { return label; }
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
                    String message = "Execution failed: " + ex.getMessage();
                    summaryLabel.setText(message);
                    summaryLabel.setToolTipText(message);
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
            summaryLabel.setToolTipText(null);
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
            summaryLabel.setToolTipText(null);
            summaryLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            return;
        }

        if (result.success()) {
            summaryLabel.setText("Last run: SUCCESS — " + result.flowName());
            summaryLabel.setToolTipText(null);
            summaryLabel.setForeground(UiTheme.pick(new Color(0x059669), new Color(0x34D399)));
        } else {
            String message = "Last run: FAILED — " + result.failureMessage();
            summaryLabel.setText(message);
            summaryLabel.setToolTipText(message);
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

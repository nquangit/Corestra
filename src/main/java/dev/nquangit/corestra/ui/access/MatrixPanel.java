package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.access.dependency.SetupSuggestionResult;
import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.access.flow.AccessFlowTarget;
import dev.nquangit.corestra.access.flow.AccessTargetType;
import dev.nquangit.corestra.access.matrix.AccessTestCase;
import dev.nquangit.corestra.access.matrix.ExpectedResult;
import dev.nquangit.corestra.access.matrix.GeneratedMatrixRow;
import dev.nquangit.corestra.access.matrix.MatrixGenerationRequest;
import dev.nquangit.corestra.access.matrix.MatrixGenerationResult;
import dev.nquangit.corestra.access.matrix.MatrixGenerationStrategy;
import dev.nquangit.corestra.access.matrix.ScenarioPolicy;
import dev.nquangit.corestra.access.matrix.SetupAuthBinding;
import dev.nquangit.corestra.access.matrix.SetupMode;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertion;
import dev.nquangit.corestra.access.postcheck.PostCheckAssertionType;
import dev.nquangit.corestra.access.postcheck.PostCheckAuthBinding;
import dev.nquangit.corestra.access.postcheck.PostCheckRequest;
import dev.nquangit.corestra.access.race.RaceAuthMode;
import dev.nquangit.corestra.access.race.RaceRunConfig;
import dev.nquangit.corestra.access.race.RaceRunResult;
import dev.nquangit.corestra.access.race.RaceScenarioMode;
import dev.nquangit.corestra.access.race.RaceWorkerResult;
import dev.nquangit.corestra.access.teardown.TeardownAuthBinding;
import dev.nquangit.corestra.access.teardown.TeardownConfig;
import dev.nquangit.corestra.access.teardown.TeardownMode;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.access.runner.AccessPlanListener;
import dev.nquangit.corestra.access.runner.AccessPlanPreview;
import dev.nquangit.corestra.access.runner.AccessPlanRunConfig;
import dev.nquangit.corestra.access.runner.AccessPlanSummary;
import dev.nquangit.corestra.access.runner.AccessRunResult;
import dev.nquangit.corestra.access.runner.PlanSafetySummary;
import dev.nquangit.corestra.access.runner.PlanValidationIssue;
import dev.nquangit.corestra.access.variable.RenderMode;
import dev.nquangit.corestra.access.template.AccessRequestTemplate;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.JButton;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.DefaultCellEditor;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class MatrixPanel extends JPanel implements AccessTabPanel {
    private final AccessUiController controller;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JButton addButton;
    private final JButton generateButton;
    private final JButton suggestSetupButton;
    private final JButton editButton;
    private final JButton deleteButton;
    private final JButton duplicateButton;
    private final JButton runSelectedButton;
    private final JButton runRaceButton;
    private final JButton startPlanButton;
    private final JButton stopButton;
    private final JButton copySummaryButton;
    private final JCheckBox dryRunCheckBox;
    private final JLabel progressLabel = new JLabel("Idle");
    private String lastSummaryText = "";
    private SwingWorker<AccessPlanSummary, Void> planWorker;
    private SwingWorker<AccessTestResult, Void> selectedRunWorker;

    MatrixPanel(AccessUiController controller) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        addButton = StyledButton.primary("Add Row");
        generateButton = StyledButton.neutral("Generate Matrix");
        suggestSetupButton = StyledButton.neutral("Suggest Setup from Flow");
        editButton = StyledButton.neutral("Edit Row");
        deleteButton = StyledButton.danger("Delete Row");
        duplicateButton = StyledButton.neutral("Duplicate Row");
        runSelectedButton = StyledButton.success("Run Selected");
        runRaceButton = StyledButton.danger("Run Race");
        startPlanButton = StyledButton.success("Start Plan");
        stopButton = StyledButton.danger("Stop");
        copySummaryButton = StyledButton.neutral("Copy Summary");
        dryRunCheckBox = new JCheckBox("Dry Run / Render Only");

        tableModel = new DefaultTableModel(
                new Object[]{"Name", "Target", "Target Auth", "Data Profile", "Expected", "Enabled", "Status", "Id"},
                0) {
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
        controller.latestPlanSummary().ifPresent(summary -> {
            lastSummaryText = summaryText(summary);
            copySummaryButton.setEnabled(true);
            progressLabel.setText(summary.stopped()
                    ? "Last plan stopped " + summary.attempted() + " / " + summary.total()
                    : "Last plan finished " + summary.attempted() + " / " + summary.total());
        });
        refresh();
    }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        runSelectedButton.setToolTipText("Run the selected access test row.");
        runRaceButton.setToolTipText("Run an explicitly confirmed race-condition batch for the selected row.");
        startPlanButton.setToolTipText("Run all enabled rows sequentially.");
        stopButton.setToolTipText("Stop before the next row starts.");
        dryRunCheckBox.setToolTipText("Render target, setup, and post-check requests without sending HTTP.");
        copySummaryButton.setToolTipText("Copy the most recent plan summary.");
        stopButton.setEnabled(false);
        copySummaryButton.setEnabled(false);
        addButton.addActionListener(event -> showEditor(null));
        generateButton.addActionListener(event -> showGenerateDialog());
        suggestSetupButton.addActionListener(event -> selectedTestCase().ifPresent(this::showSuggestSetupDialog));
        editButton.addActionListener(event -> selectedTestCase().ifPresent(this::showEditor));
        deleteButton.addActionListener(event -> selectedTestCase().ifPresent(this::deleteTestCase));
        duplicateButton.addActionListener(event -> selectedTestCase().ifPresent(testCase -> {
            controller.duplicateTestCase(testCase.id());
            refresh();
        }));
        runSelectedButton.addActionListener(event -> selectedTestCase().ifPresent(this::runSelected));
        runRaceButton.addActionListener(event -> selectedTestCase().ifPresentOrElse(
                this::showRaceDialog,
                () -> JOptionPane.showMessageDialog(
                        this,
                        "Select one matrix row before running a race test.",
                        "Run Race",
                        JOptionPane.INFORMATION_MESSAGE)));
        startPlanButton.addActionListener(event -> startPlan());
        copySummaryButton.addActionListener(event -> copySummary());
        stopButton.addActionListener(event -> {
            controller.requestStopPlan();
            progressLabel.setText("Stopping after current row...");
            stopButton.setEnabled(false);
        });
        panel.add(addButton);
        panel.add(generateButton);
        panel.add(suggestSetupButton);
        panel.add(editButton);
        panel.add(deleteButton);
        panel.add(duplicateButton);
        panel.add(runSelectedButton);
        panel.add(runRaceButton);
        panel.add(dryRunCheckBox);
        panel.add(startPlanButton);
        panel.add(stopButton);
        panel.add(copySummaryButton);
        panel.add(progressLabel);
        return panel;
    }

    private void runSelected(AccessTestCase testCase) {
        if (selectedRunWorker != null && !selectedRunWorker.isDone()) {
            return;
        }
        String testCaseId = testCase.id();
        boolean dryRun = dryRunCheckBox.isSelected();
        setRunningState(true);
        progressLabel.setText(dryRun ? "Rendering selected row..." : "Running selected row...");
        selectedRunWorker = new SwingWorker<>() {
            @Override
            protected AccessTestResult doInBackground() {
                return controller.runTestCase(
                        testCaseId,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        RenderMode.STRICT,
                        dryRun);
            }

            @Override
            protected void done() {
                try {
                    AccessTestResult result = get();
                    progressLabel.setText(dryRun ? "Selected row rendered" : "Selected row finished");
                    refresh();
                    JOptionPane.showMessageDialog(
                            MatrixPanel.this,
                            result.runStatus() + " / " + result.verdict() + "\n" + result.reason(),
                            dryRun ? "Access Test Dry Run" : "Access Test Result",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception exception) {
                    progressLabel.setText(dryRun ? "Selected dry run failed" : "Selected row failed");
                    JOptionPane.showMessageDialog(
                            MatrixPanel.this,
                            rootMessage(exception),
                            "Run Failed",
                            JOptionPane.ERROR_MESSAGE);
                    refresh();
                } finally {
                    selectedRunWorker = null;
                    setRunningState(false);
                    refresh();
                }
            }
        };
        selectedRunWorker.execute();
    }

    private static String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    private void startPlan() {
        if (planWorker != null && !planWorker.isDone()) {
            return;
        }
        AccessPlanRunConfig config = new AccessPlanRunConfig(
                RenderMode.STRICT,
                Map.of(),
                Map.of(),
                Map.of(),
                false,
                false,
                dryRunCheckBox.isSelected());
        AccessPlanPreview preview;
        try {
            preview = controller.previewPlan(config);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Plan Preview Failed", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!confirmPlanStart(config, preview)) {
            return;
        }
        setRunningState(true);
        progressLabel.setText("Starting...");
        planWorker = new SwingWorker<>() {
            @Override
            protected AccessPlanSummary doInBackground() {
                return controller.runPlan(config, listener());
            }

            @Override
            protected void done() {
                try {
                    AccessPlanSummary summary = get();
                    lastSummaryText = summaryText(summary);
                    copySummaryButton.setEnabled(true);
                    progressLabel.setText(summary.stopped()
                            ? "Stopped " + summary.attempted() + " / " + summary.total()
                            : "Finished " + summary.attempted() + " / " + summary.total());
                    JOptionPane.showMessageDialog(
                            MatrixPanel.this,
                            lastSummaryText,
                            "Plan Summary",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception exception) {
                    progressLabel.setText("Plan failed");
                    JOptionPane.showMessageDialog(MatrixPanel.this,
                            exception.getMessage(),
                            "Plan Failed",
                            JOptionPane.ERROR_MESSAGE);
                } finally {
                    setRunningState(false);
                    refresh();
                }
            }
        };
        planWorker.execute();
    }

    private boolean confirmPlanStart(AccessPlanRunConfig config, AccessPlanPreview preview) {
        PlanConfirmationPanel panel = new PlanConfirmationPanel(preview, config.dryRun());
        int result = JOptionPane.showConfirmDialog(
                this,
                panel,
                config.dryRun() ? "Confirm Dry Run" : "Confirm Access Plan",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return result == JOptionPane.OK_OPTION;
    }

    private void copySummary() {
        if (lastSummaryText.isBlank()) {
            return;
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(lastSummaryText), null);
    }

    private static String summaryText(AccessPlanSummary summary) {
        return "Total: " + summary.total()
                + "\nOK: " + summary.ok()
                + "\nSuspicious: " + summary.suspicious()
                + "\nReview: " + summary.review()
                + "\nError: " + summary.error()
                + "\nInvalid: " + summary.invalid()
                + "\nSkipped: " + summary.skipped()
                + "\nFailed: " + summary.failed()
                + "\nStopped: " + (summary.stopped() ? "yes" : "no");
    }

    private AccessPlanListener listener() {
        return new AccessPlanListener() {
            @Override
            public void onPlanStarted(int totalRows) {
                SwingUtilities.invokeLater(() -> progressLabel.setText("Running 0 / " + totalRows));
            }

            @Override
            public void onRowStarted(String testCaseId, int index, int total) {
                SwingUtilities.invokeLater(() -> {
                    progressLabel.setText("Running " + index + " / " + total);
                    refresh();
                });
            }

            @Override
            public void onRowFinished(String testCaseId, AccessRunResult result, int index, int total) {
                SwingUtilities.invokeLater(() -> {
                    progressLabel.setText("Running " + index + " / " + total);
                    refresh();
                });
            }

            @Override
            public void onPlanStopped(AccessPlanSummary summary) {
                SwingUtilities.invokeLater(() ->
                        progressLabel.setText("Stopped " + summary.attempted() + " / " + summary.total()));
            }

            @Override
            public void onPlanFinished(AccessPlanSummary summary) {
                SwingUtilities.invokeLater(() ->
                        progressLabel.setText("Finished " + summary.attempted() + " / " + summary.total()));
            }
        };
    }

    private void setRunningState(boolean running) {
        addButton.setEnabled(!running);
        generateButton.setEnabled(!running);
        suggestSetupButton.setEnabled(!running);
        editButton.setEnabled(!running);
        deleteButton.setEnabled(!running);
        duplicateButton.setEnabled(!running);
        runSelectedButton.setEnabled(!running);
        runRaceButton.setEnabled(!running);
        startPlanButton.setEnabled(!running);
        dryRunCheckBox.setEnabled(!running);
        copySummaryButton.setEnabled(!running && !lastSummaryText.isBlank());
        stopButton.setEnabled(running);
    }

    private void showEditor(AccessTestCase existing) {
        MatrixRowFormPanel form = new MatrixRowFormPanel(controller, existing == null
                ? null
                : MatrixRowFormModel.from(existing));
        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                existing == null ? "Add Matrix Row" : "Edit Matrix Row",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            controller.saveTestCase(existing == null ? "" : existing.id(), form.toModel());
            refresh();
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Invalid Matrix Row", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showGenerateDialog() {
        MatrixGenerationOptionsPanel options = new MatrixGenerationOptionsPanel(controller);
        int optionResult = JOptionPane.showConfirmDialog(
                this,
                options,
                "Generate Matrix",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (optionResult != JOptionPane.OK_OPTION) {
            return;
        }

        MatrixGenerationResult generated = controller.generateMatrix(options.toRequest());
        if (generated.empty()) {
            JOptionPane.showMessageDialog(
                    this,
                    generationSummary(generated),
                    "Generate Matrix",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        MatrixGenerationPreviewPanel preview = new MatrixGenerationPreviewPanel(controller, generated.generatedRows());
        int previewResult = JOptionPane.showConfirmDialog(
                this,
                preview,
                "Preview Generated Rows",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (previewResult != JOptionPane.OK_OPTION) {
            return;
        }

        MatrixGenerationResult saved = controller.saveGeneratedMatrixRows(preview.selectedRows());
        refresh();
        JOptionPane.showMessageDialog(
                this,
                generationSummary(saved),
                "Generated Matrix Rows",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void showRaceDialog(AccessTestCase testCase) {
        if (testCase.targetType() == AccessTargetType.FLOW_TARGET) {
            JOptionPane.showMessageDialog(
                    this,
                    "Race runs currently support request-template targets only.",
                    "Run Race",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        RaceOptionsPanel options = new RaceOptionsPanel(testCase);
        int optionResult = JOptionPane.showConfirmDialog(
                this,
                options,
                "Configure Race Run",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (optionResult != JOptionPane.OK_OPTION) {
            return;
        }

        RaceRunConfig config;
        try {
            config = options.toConfig(testCase.id());
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Invalid Race Config", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!config.dryRun() && !confirmRaceRun(testCase, config)) {
            return;
        }
        try {
            RaceRunResult result = controller.runRace(config);
            refresh();
            JOptionPane.showMessageDialog(
                    this,
                    new RaceResultPanel(result),
                    "Race Result",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Race Run Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean confirmRaceRun(AccessTestCase testCase, RaceRunConfig config) {
        JTextArea warning = new JTextArea(raceConfirmationText(testCase, config), 10, 72);
        warning.setEditable(false);
        warning.setLineWrap(true);
        warning.setWrapStyleWord(true);
        int result = JOptionPane.showConfirmDialog(
                this,
                new JScrollPane(warning),
                "Confirm Race Run",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        return result == JOptionPane.OK_OPTION;
    }

    private String raceConfirmationText(AccessTestCase testCase, RaceRunConfig config) {
        StringBuilder text = new StringBuilder();
        text.append("You are about to send ").append(config.totalTargetRequests())
                .append(" target requests using ").append(config.concurrency()).append(" concurrent workers.").append('\n');
        text.append("Use only in authorized testing environments. This may modify server-side state.").append('\n');
        templateFor(testCase).ifPresent(template -> {
            if (template.operationMetadata().stateChanging()
                    || template.operationMetadata().operationType()
                    == dev.nquangit.corestra.access.template.OperationType.STATE_CHANGING) {
                text.append("Warning: target template is marked state-changing.").append('\n');
            }
            if (template.operationMetadata().consumable()
                    || template.operationMetadata().operationType()
                    == dev.nquangit.corestra.access.template.OperationType.CONSUMABLE) {
                text.append("Warning: target template is marked consumable or one-time-use.").append('\n');
            }
        });
        if (config.runPostChecksAfterBatch() && testCase.postChecks().stream().anyMatch(PostCheckRequest::enabled)) {
            text.append("Enabled post-checks will send additional requests after the target batch.").append('\n');
        }
        if (testCase.teardown().mode() == TeardownMode.NONE) {
            text.append("Warning: selected row has no teardown configured.").append('\n');
        }
        if (config.scenarioMode() == RaceScenarioMode.REUSE_SINGLE_SCENARIO) {
            text.append("Scenario mode reuses one scenario across workers.").append('\n');
        }
        return text.toString();
    }

    private void showSuggestSetupDialog(AccessTestCase testCase) {
        if (controller.flows().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No flows are available.", "Setup Suggestion", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SetupSuggestionOptionsPanel options = new SetupSuggestionOptionsPanel(controller, testCase);
        int optionResult = JOptionPane.showConfirmDialog(
                this,
                options,
                "Suggest Setup from Flow",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (optionResult != JOptionPane.OK_OPTION) {
            return;
        }

        SetupSuggestionResult suggestion = controller.suggestSetupFromFlow(
                options.flowId(),
                options.stepId(),
                RenderMode.STRICT);
        SetupSuggestionDetailsPanel details = new SetupSuggestionDetailsPanel(suggestion);
        int detailsResult = JOptionPane.showConfirmDialog(
                this,
                details,
                suggestion.success() ? "Apply Setup Suggestion" : "Setup Suggestion",
                suggestion.success() ? JOptionPane.OK_CANCEL_OPTION : JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (suggestion.success() && detailsResult == JOptionPane.OK_OPTION) {
            controller.applySetupSuggestion(testCase.id(), suggestion);
            refresh();
        }
    }

    private String generationSummary(MatrixGenerationResult result) {
        StringBuilder message = new StringBuilder();
        message.append("Rows: ").append(result.rows().size());
        if (result.duplicateCount() > 0) {
            message.append("\nDuplicates skipped: ").append(result.duplicateCount());
        }
        if (result.skippedCount() > 0) {
            message.append("\nCandidates skipped: ").append(result.skippedCount());
        }
        for (String warning : result.warnings()) {
            message.append('\n').append(warning);
        }
        result.skipReasons().stream().limit(8).forEach(skip ->
                message.append('\n')
                        .append(skip.objectType().isBlank() ? "Skipped" : skip.objectType())
                        .append(skip.objectId().isBlank() ? "" : " " + skip.objectId())
                        .append(": ")
                        .append(skip.reason()));
        if (result.skipReasons().size() > 8) {
            message.append('\n').append("Additional skipped candidates omitted from this summary.");
        }
        return message.toString();
    }

    private static final class PlanConfirmationPanel extends JPanel {
        PlanConfirmationPanel(AccessPlanPreview preview, boolean dryRun) {
            super(new BorderLayout(8, 8));
            JTextArea summary = new JTextArea(summary(preview.summary(), preview.hasErrors(), dryRun), 7, 72);
            summary.setEditable(false);
            summary.setLineWrap(true);
            summary.setWrapStyleWord(true);

            DefaultTableModel model = new DefaultTableModel(
                    new Object[]{"Row", "Severity", "Category", "Message"},
                    0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            for (PlanValidationIssue issue : preview.issues()) {
                model.addRow(new Object[]{
                        issue.testCaseName().isBlank() ? issue.testCaseId() : issue.testCaseName(),
                        issue.severity(),
                        issue.category(),
                        issue.message()});
            }
            JTable diagnostics = new JTable(model);
            diagnostics.setFillsViewportHeight(true);
            add(summary, BorderLayout.NORTH);
            add(new JScrollPane(diagnostics), BorderLayout.CENTER);
        }

        private static String summary(PlanSafetySummary summary, boolean hasErrors, boolean dryRun) {
            StringBuilder text = new StringBuilder();
            text.append("Mode: ").append(dryRun ? "Dry Run / Render Only" : "Live HTTP Run").append('\n');
            text.append("Enabled rows: ").append(summary.enabledRows()).append('\n');
            text.append("Rows with setup: ").append(summary.setupRows()).append('\n');
            text.append("Rows with post-checks: ").append(summary.postCheckRows()).append('\n');
            text.append("State-changing or consumable rows: ")
                    .append(summary.stateChangingOrConsumableRows()).append('\n');
            if (summary.stateChangingOrConsumableRows() > 0) {
                text.append("Warning: state-changing or consumable target templates are included.").append('\n');
            }
            if (summary.postCheckRows() > 0 && !dryRun) {
                text.append("Warning: post-checks will send additional HTTP requests.").append('\n');
            }
            if (dryRun) {
                text.append("Dry run will render requests and send zero HTTP traffic.").append('\n');
            }
            if (hasErrors) {
                text.append("Diagnostics include errors. Confirm only if this is intentional.");
            }
            return text.toString();
        }
    }

    private void deleteTestCase(AccessTestCase testCase) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Delete matrix row \"" + testCase.name() + "\"?",
                "Delete Matrix Row",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            controller.deleteTestCase(testCase.id());
            refresh();
        }
    }

    private java.util.Optional<AccessTestCase> selectedTestCase() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return java.util.Optional.empty();
        }
        int modelRow = table.convertRowIndexToModel(row);
        String id = (String) tableModel.getValueAt(modelRow, 7);
        return controller.testCases().stream().filter(testCase -> testCase.id().equals(id)).findFirst();
    }

    @Override
    public void refreshUi() {
        refresh();
    }

    private void refresh() {
        tableModel.setRowCount(0);
        for (AccessTestCase testCase : controller.testCases()) {
            tableModel.addRow(new Object[]{
                    testCase.name(),
                    targetName(testCase),
                    authName(testCase.target().targetAuthProfileId()),
                    dataName(testCase.target().dataProfileId()),
                    testCase.expected(),
                    testCase.enabled(),
                    testCase.status(),
                    testCase.id()});
        }
    }

    private String templateName(String id) {
        return controller.templates().stream()
                .filter(template -> template.id().equals(id))
                .map(AccessRequestTemplate::name)
                .findFirst()
                .orElse(id);
    }

    private String flowTargetName(String id) {
        return controller.flowTargets().stream()
                .filter(target -> target.id().equals(id))
                .map(AccessFlowTarget::name)
                .findFirst()
                .orElse(id);
    }

    private String targetName(AccessTestCase testCase) {
        if (testCase.targetType() == AccessTargetType.FLOW_TARGET) {
            return "Flow: " + flowTargetName(testCase.flowTargetId());
        }
        return templateName(testCase.templateId());
    }

    private String authName(String id) {
        return controller.authProfile(id).map(AuthProfile::name).orElse(id);
    }

    private String dataName(String id) {
        return controller.dataProfile(id).map(DataProfile::name).orElse(id);
    }

    private java.util.Optional<AccessRequestTemplate> templateFor(AccessTestCase testCase) {
        return controller.templates().stream()
                .filter(template -> template.id().equals(testCase.templateId()))
                .findFirst();
    }

    static final class RaceOptionsPanel extends JPanel {
        private final JSpinner concurrency = new JSpinner(
                new SpinnerNumberModel(2, 1, RaceRunConfig.MAX_CONCURRENCY, 1));
        private final JSpinner iterations = new JSpinner(
                new SpinnerNumberModel(1, 1, RaceRunConfig.MAX_ITERATIONS, 1));
        private final JCheckBox synchronize = new JCheckBox("Synchronize start", true);
        private final JCheckBox dryRun = new JCheckBox("Dry-run / render only", false);
        private final JCheckBox postChecks = new JCheckBox("Run post-checks after batch", true);
        private final JCheckBox teardown = new JCheckBox("Run teardown after batch", true);
        private final JComboBox<RaceScenarioMode> scenarioMode = new JComboBox<>(RaceScenarioMode.values());
        private final JComboBox<RaceAuthMode> authMode = new JComboBox<>(RaceAuthMode.values());
        private final JLabel total = new JLabel();

        RaceOptionsPanel(AccessTestCase testCase) {
            super(new BorderLayout(8, 8));
            scenarioMode.setSelectedItem(RaceScenarioMode.REUSE_SINGLE_SCENARIO);
            authMode.setSelectedItem(RaceAuthMode.ENSURE_AUTH_BEFORE_GATE);
            concurrency.addChangeListener(event -> refreshTotal());
            iterations.addChangeListener(event -> refreshTotal());

            JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
            fields.add(new JLabel("Matrix Row"));
            fields.add(new JLabel(testCase.name()));
            fields.add(new JLabel("Concurrency"));
            fields.add(concurrency);
            fields.add(new JLabel("Iterations"));
            fields.add(iterations);
            fields.add(new JLabel("Scenario Mode"));
            fields.add(scenarioMode);
            fields.add(new JLabel("Auth Mode"));
            fields.add(authMode);
            fields.add(new JLabel("Total target requests"));
            fields.add(total);

            JPanel checks = new JPanel(new GridLayout(0, 1, 4, 4));
            checks.add(synchronize);
            checks.add(dryRun);
            checks.add(postChecks);
            checks.add(teardown);

            JTextArea note = new JTextArea(
                    "Race runs are never automatic. Live runs require a second confirmation and use conservative caps.",
                    3,
                    60);
            note.setEditable(false);
            note.setLineWrap(true);
            note.setWrapStyleWord(true);
            add(fields, BorderLayout.NORTH);
            add(checks, BorderLayout.CENTER);
            add(note, BorderLayout.SOUTH);
            refreshTotal();
        }

        RaceRunConfig toConfig(String testCaseId) {
            return new RaceRunConfig(
                    testCaseId,
                    (Integer) concurrency.getValue(),
                    (Integer) iterations.getValue(),
                    synchronize.isSelected(),
                    0L,
                    0L,
                    RaceRunConfig.DEFAULT_PREPARATION_TIMEOUT_MILLIS,
                    (RaceScenarioMode) scenarioMode.getSelectedItem(),
                    (RaceAuthMode) authMode.getSelectedItem(),
                    dryRun.isSelected(),
                    postChecks.isSelected(),
                    teardown.isSelected(),
                    false);
        }

        private void refreshTotal() {
            int count = ((Integer) concurrency.getValue()) * ((Integer) iterations.getValue());
            total.setText(String.valueOf(count));
        }
    }

    private static final class RaceResultPanel extends JPanel {
        RaceResultPanel(RaceRunResult result) {
            super(new BorderLayout(8, 8));
            JTextArea summary = new JTextArea(summary(result), 8, 80);
            summary.setEditable(false);
            summary.setLineWrap(true);
            summary.setWrapStyleWord(true);

            DefaultTableModel model = new DefaultTableModel(
                    new Object[]{"Worker", "Iteration", "Status Code", "Verdict", "Duration ms", "Reason"},
                    0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
            for (RaceWorkerResult worker : result.workerResults()) {
                model.addRow(new Object[]{
                        worker.workerIndex(),
                        worker.iteration(),
                        worker.statusCode(),
                        worker.verdict(),
                        Math.max(0L, worker.finishedAtEpochMillis() - worker.releasedAtEpochMillis()),
                        worker.reason()});
            }
            JTable table = new JTable(model);
            table.setFillsViewportHeight(true);
            add(summary, BorderLayout.NORTH);
            add(new JScrollPane(table), BorderLayout.CENTER);
        }

        private static String summary(RaceRunResult result) {
            StringBuilder text = new StringBuilder();
            text.append("Status: ").append(result.status()).append('\n');
            text.append("Concurrency: ").append(result.concurrency()).append('\n');
            text.append("Iterations: ").append(result.iterations()).append('\n');
            text.append("2xx: ").append(result.twoXxCount())
                    .append("   4xx: ").append(result.fourXxCount())
                    .append("   5xx: ").append(result.fiveXxCount()).append('\n');
            text.append("Workers: ").append(result.workerResults().size()).append('\n');
            if (!result.warnings().isEmpty()) {
                text.append('\n').append("Warnings").append('\n');
                result.warnings().forEach(warning -> text.append("- ").append(warning).append('\n'));
            }
            return text.toString();
        }
    }

    private static final class SetupSuggestionOptionsPanel extends JPanel {
        private final AccessUiController controller;
        private final JComboBox<ComboItem> flow = new JComboBox<>();
        private final JComboBox<ComboItem> step = new JComboBox<>();

        SetupSuggestionOptionsPanel(AccessUiController controller, AccessTestCase testCase) {
            super(new GridLayout(0, 2, 6, 6));
            this.controller = controller;
            for (FlowDefinition item : controller.flows()) {
                flow.addItem(new ComboItem(item.id(), item.name()));
            }
            flow.addActionListener(event -> refreshSteps(""));
            select(flow, testCase.flowId());
            refreshSteps(testCase.targetStepId());
            add(new JLabel("Flow"));
            add(flow);
            add(new JLabel("Target Step"));
            add(step);
        }

        String flowId() {
            ComboItem item = (ComboItem) flow.getSelectedItem();
            return item == null ? "" : item.id();
        }

        String stepId() {
            ComboItem item = (ComboItem) step.getSelectedItem();
            return item == null ? "" : item.id();
        }

        private void refreshSteps(String preferredStepId) {
            step.removeAllItems();
            selectedFlow().ifPresent(selectedFlow ->
                    selectedFlow.steps().forEach(flowStep ->
                            step.addItem(new ComboItem(flowStep.id(), flowStep.name()))));
            select(step, preferredStepId);
        }

        private java.util.Optional<FlowDefinition> selectedFlow() {
            String selectedFlowId = flowId();
            return controller.flows().stream()
                    .filter(candidate -> candidate.id().equals(selectedFlowId))
                    .findFirst();
        }

        private static void select(JComboBox<ComboItem> combo, String id) {
            if (id == null || id.isBlank()) {
                return;
            }
            for (int index = 0; index < combo.getItemCount(); index++) {
                if (combo.getItemAt(index).id().equals(id)) {
                    combo.setSelectedIndex(index);
                    return;
                }
            }
        }
    }

    private static final class SetupSuggestionDetailsPanel extends JPanel {
        SetupSuggestionDetailsPanel(SetupSuggestionResult suggestion) {
            super(new BorderLayout(8, 8));
            JTextArea text = new JTextArea(details(suggestion), 14, 64);
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            add(new JScrollPane(text), BorderLayout.CENTER);
        }

        private static String details(SetupSuggestionResult suggestion) {
            StringBuilder text = new StringBuilder();
            text.append("Result: ").append(suggestion.success() ? "Ready" : "Manual setup required").append('\n');
            text.append("Reason: ").append(suggestion.reason()).append('\n');
            text.append("Setup Mode: ").append(suggestion.suggestedSetup().mode()).append('\n');
            text.append("Setup Flow ID: ").append(suggestion.suggestedSetup().setupFlowId()).append('\n');
            text.append("Until Before Step ID: ").append(suggestion.suggestedSetup().untilBeforeStepId()).append('\n');
            text.append("Scenario Policy: ").append(suggestion.suggestedSetup().scenarioPolicy()).append('\n');
            text.append('\n').append("Required Scenario Variables").append('\n');
            if (suggestion.requiredScenarioVariables().isEmpty()) {
                text.append("- none").append('\n');
            } else {
                suggestion.requiredScenarioVariables().forEach(ref ->
                        text.append("- ").append(ref.scopedKey()).append('\n'));
            }
            text.append('\n').append("Producer Steps").append('\n');
            if (suggestion.producerStepIds().isEmpty()) {
                text.append("- none").append('\n');
            } else {
                suggestion.producerStepIds().forEach((ref, stepId) ->
                        text.append("- ").append(ref.scopedKey()).append(" -> ").append(stepId).append('\n'));
            }
            if (!suggestion.warnings().isEmpty()) {
                text.append('\n').append("Warnings").append('\n');
                suggestion.warnings().forEach(warning -> text.append("- ").append(warning).append('\n'));
            }
            return text.toString();
        }
    }

    private static final class MatrixGenerationOptionsPanel extends JPanel {
        private final JComboBox<MatrixGenerationStrategy> strategy =
                new JComboBox<>(MatrixGenerationStrategy.values());
        private final JComboBox<ExpectedResult> adminExpected =
                new JComboBox<>(new ExpectedResult[]{ExpectedResult.REVIEW, ExpectedResult.ALLOW});
        private final JComboBox<ExpectedResult> roleEscalationExpected =
                new JComboBox<>(new ExpectedResult[]{ExpectedResult.REVIEW, ExpectedResult.DENY});
        private final JComboBox<ExpectedResult> crossTenantExpected =
                new JComboBox<>(new ExpectedResult[]{ExpectedResult.DENY, ExpectedResult.REVIEW});
        private final JComboBox<ExpectedResult> crossBranchExpected =
                new JComboBox<>(new ExpectedResult[]{ExpectedResult.DENY, ExpectedResult.REVIEW});
        private final JComboBox<ExpectedResult> makerSelfExpected =
                new JComboBox<>(new ExpectedResult[]{ExpectedResult.DENY, ExpectedResult.REVIEW});
        private final JComboBox<ExpectedResult> checkerApprovalExpected =
                new JComboBox<>(new ExpectedResult[]{ExpectedResult.REVIEW, ExpectedResult.ALLOW, ExpectedResult.DENY});
        private final JComboBox<ExpectedResult> defaultReviewExpected =
                new JComboBox<>(new ExpectedResult[]{ExpectedResult.REVIEW, ExpectedResult.DENY, ExpectedResult.ALLOW});
        private final JTextField tenantKey = new JTextField("tenantId");
        private final JTextField branchKey = new JTextField("branchId");
        private final JTextField makerRole = new JTextField("MAKER");
        private final JTextField checkerRole = new JTextField("CHECKER");
        private final JTextField adminRole = new JTextField("ADMIN");
        private final JCheckBox sameRoleOnly = new JCheckBox("Same role only", true);
        private final JCheckBox sameTenantOnly = new JCheckBox("Same tenant only");
        private final JCheckBox sameBranchOnly = new JCheckBox("Same branch only");
        private final JCheckBox includeCrossTenant = new JCheckBox("Include cross-tenant pairs");
        private final JCheckBox includeCrossBranch = new JCheckBox("Include cross-branch pairs");
        private final JList<ComboItem> templates;
        private final JList<ComboItem> dataProfiles;
        private final JList<ComboItem> authProfiles;

        MatrixGenerationOptionsPanel(AccessUiController controller) {
            super(new BorderLayout(8, 8));
            templates = new JList<>(controller.templates().stream()
                    .map(template -> new ComboItem(template.id(), template.name()))
                    .toArray(ComboItem[]::new));
            dataProfiles = new JList<>(controller.dataProfiles().stream()
                    .map(dataProfile -> new ComboItem(dataProfile.id(), dataProfile.name()))
                    .toArray(ComboItem[]::new));
            authProfiles = new JList<>(controller.authProfiles().stream()
                    .map(authProfile -> new ComboItem(authProfile.id(), authProfile.name()))
                    .toArray(ComboItem[]::new));
            templates.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            dataProfiles.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            authProfiles.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
            selectAll(templates);
            selectAll(dataProfiles);
            selectAll(authProfiles);

            JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
            fields.add(new JLabel("Strategy"));
            fields.add(strategy);
            fields.add(new JLabel("Admin Expected"));
            fields.add(adminExpected);
            fields.add(new JLabel("Role Escalation Expected"));
            fields.add(roleEscalationExpected);
            fields.add(new JLabel("Cross Tenant Expected"));
            fields.add(crossTenantExpected);
            fields.add(new JLabel("Cross Branch Expected"));
            fields.add(crossBranchExpected);
            fields.add(new JLabel("Maker Self Expected"));
            fields.add(makerSelfExpected);
            fields.add(new JLabel("Checker Approval Expected"));
            fields.add(checkerApprovalExpected);
            fields.add(new JLabel("Default Review Expected"));
            fields.add(defaultReviewExpected);
            fields.add(new JLabel("Tenant Key"));
            fields.add(tenantKey);
            fields.add(new JLabel("Branch Key"));
            fields.add(branchKey);
            fields.add(new JLabel("Maker Role"));
            fields.add(makerRole);
            fields.add(new JLabel("Checker Role"));
            fields.add(checkerRole);
            fields.add(new JLabel("Admin Role"));
            fields.add(adminRole);

            JPanel filters = new JPanel(new GridLayout(0, 2, 6, 2));
            filters.add(sameRoleOnly);
            filters.add(sameTenantOnly);
            filters.add(sameBranchOnly);
            filters.add(includeCrossTenant);
            filters.add(includeCrossBranch);

            JPanel lists = new JPanel(new GridLayout(1, 3, 6, 6));
            lists.add(labeledList("Templates", templates));
            lists.add(labeledList("Data Profiles", dataProfiles));
            lists.add(labeledList("Auth Profiles", authProfiles));

            strategy.addActionListener(event -> updateStrategyDefaults());
            updateStrategyDefaults();

            add(fields, BorderLayout.NORTH);
            add(lists, BorderLayout.CENTER);
            add(filters, BorderLayout.SOUTH);
        }

        MatrixGenerationRequest toRequest() {
            MatrixGenerationStrategy selectedStrategy = (MatrixGenerationStrategy) strategy.getSelectedItem();
            return new MatrixGenerationRequest(
                    selectedStrategy,
                    selectedIds(templates),
                    selectedIds(dataProfiles),
                    selectedIds(authProfiles),
                    tenantKey.getText(),
                    branchKey.getText(),
                    makerRole.getText(),
                    checkerRole.getText(),
                    adminRole.getText(),
                    (ExpectedResult) adminExpected.getSelectedItem(),
                    (ExpectedResult) roleEscalationExpected.getSelectedItem(),
                    (ExpectedResult) crossTenantExpected.getSelectedItem(),
                    (ExpectedResult) crossBranchExpected.getSelectedItem(),
                    (ExpectedResult) makerSelfExpected.getSelectedItem(),
                    (ExpectedResult) checkerApprovalExpected.getSelectedItem(),
                    (ExpectedResult) defaultReviewExpected.getSelectedItem(),
                    sameRoleOnly.isSelected(),
                    sameTenantOnly.isSelected(),
                    sameBranchOnly.isSelected(),
                    includeCrossTenant.isSelected() || selectedStrategy == MatrixGenerationStrategy.CROSS_TENANT,
                    includeCrossBranch.isSelected() || selectedStrategy == MatrixGenerationStrategy.CROSS_BRANCH,
                    false);
        }

        private void updateStrategyDefaults() {
            MatrixGenerationStrategy selectedStrategy = (MatrixGenerationStrategy) strategy.getSelectedItem();
            includeCrossTenant.setSelected(selectedStrategy == MatrixGenerationStrategy.CROSS_TENANT);
            includeCrossBranch.setSelected(selectedStrategy == MatrixGenerationStrategy.CROSS_BRANCH);
        }

        private static JPanel labeledList(String label, JList<ComboItem> list) {
            JPanel panel = new JPanel(new BorderLayout(4, 4));
            panel.add(new JLabel(label), BorderLayout.NORTH);
            panel.add(new JScrollPane(list), BorderLayout.CENTER);
            return panel;
        }

        private static void selectAll(JList<ComboItem> list) {
            if (list.getModel().getSize() > 0) {
                list.setSelectionInterval(0, list.getModel().getSize() - 1);
            }
        }

        private static Set<String> selectedIds(JList<ComboItem> list) {
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (ComboItem item : list.getSelectedValuesList()) {
                ids.add(item.id());
            }
            return Set.copyOf(ids);
        }
    }

    private static final class MatrixGenerationPreviewPanel extends JPanel {
        private final AccessUiController controller;
        private final List<GeneratedMatrixRow> rows;
        private final DefaultTableModel model;

        MatrixGenerationPreviewPanel(AccessUiController controller, List<GeneratedMatrixRow> rows) {
            super(new BorderLayout(8, 8));
            this.controller = controller;
            this.rows = List.copyOf(rows);
            model = new DefaultTableModel(
                    new Object[]{
                            "Add",
                            "Name",
                            "Template",
                            "Target Auth",
                            "Data Profile",
                            "Expected",
                            "Strategy",
                            "Reason",
                            "Confidence",
                            "Enabled"},
                    0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return column == 0 || column == 5;
                }

                @Override
                public Class<?> getColumnClass(int columnIndex) {
                    if (columnIndex == 0 || columnIndex == 9) {
                        return Boolean.class;
                    }
                    if (columnIndex == 5) {
                        return ExpectedResult.class;
                    }
                    return String.class;
                }
            };
            JTable previewTable = new JTable(model);
            previewTable.setFillsViewportHeight(true);
            previewTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            previewTable.getColumnModel().getColumn(5)
                    .setCellEditor(new DefaultCellEditor(new JComboBox<>(ExpectedResult.values())));
            for (GeneratedMatrixRow generatedRow : this.rows) {
                AccessTestCase row = generatedRow.row();
                model.addRow(new Object[]{
                        Boolean.TRUE,
                        row.name(),
                        templateName(row.templateId()),
                        authName(row.target().targetAuthProfileId()),
                        dataName(row.target().dataProfileId()),
                        row.expected(),
                        generatedRow.generationStrategy(),
                        generatedRow.generationReason(),
                        generatedRow.confidence(),
                        row.enabled()});
            }
            add(new JScrollPane(previewTable), BorderLayout.CENTER);
        }

        List<AccessTestCase> selectedRows() {
            ArrayList<AccessTestCase> selected = new ArrayList<>();
            for (int rowIndex = 0; rowIndex < model.getRowCount(); rowIndex++) {
                if (!Boolean.TRUE.equals(model.getValueAt(rowIndex, 0))) {
                    continue;
                }
                AccessTestCase original = rows.get(rowIndex).row();
                ExpectedResult expected = (ExpectedResult) model.getValueAt(rowIndex, 5);
                selected.add(copyWithExpected(original, expected));
            }
            return List.copyOf(selected);
        }

        private AccessTestCase copyWithExpected(AccessTestCase original, ExpectedResult expected) {
            return new AccessTestCase(
                    original.id(),
                    original.name(),
                    original.targetType(),
                    original.templateId(),
                    original.flowTargetId(),
                    original.flowId(),
                    original.targetStepId(),
                    original.setup(),
                    original.target(),
                    expected,
                    original.evaluationProfileId(),
                    original.assertions(),
                    original.postChecks(),
                    original.teardown(),
                    original.enabled(),
                    original.status(),
                    original.lastRunAtEpochMillis());
        }

        private String templateName(String id) {
            return controller.templates().stream()
                    .filter(template -> template.id().equals(id))
                    .map(AccessRequestTemplate::name)
                    .findFirst()
                    .orElse(id);
        }

        private String authName(String id) {
            return controller.authProfile(id).map(AuthProfile::name).orElse(id);
        }

        private String dataName(String id) {
            return controller.dataProfile(id).map(DataProfile::name).orElse(id);
        }
    }

    private static final class MatrixRowFormPanel extends JPanel {
        private final JTextField name = new JTextField();
        private final JComboBox<AccessTargetType> targetType = new JComboBox<>(AccessTargetType.values());
        private final JComboBox<ComboItem> template = new JComboBox<>();
        private final JComboBox<ComboItem> flowTarget = new JComboBox<>();
        private final JComboBox<ComboItem> auth = new JComboBox<>();
        private final JComboBox<ComboItem> data = new JComboBox<>();
        private final JTextField environment = new JTextField();
        private final JComboBox<ExpectedResult> expected = new JComboBox<>(ExpectedResult.values());
        private final JComboBox<ComboItem> evaluationProfile = new JComboBox<>();
        private final JCheckBox enabled = new JCheckBox("Enabled", true);
        private final JComboBox<SetupMode> setupMode = new JComboBox<>(SetupMode.values());
        private final JTextField setupFlowId = new JTextField();
        private final JTextField untilBeforeStepId = new JTextField();
        private final JComboBox<SetupAuthBinding> setupAuthBinding = new JComboBox<>(SetupAuthBinding.values());
        private final JTextField setupAuthProfileId = new JTextField();
        private final JComboBox<ScenarioPolicy> scenarioPolicy = new JComboBox<>(ScenarioPolicy.values());
        private final JTextField scenarioInstanceId = new JTextField();
        private final JComboBox<TeardownMode> teardownMode = new JComboBox<>(TeardownMode.values());
        private final JTextField teardownFlowId = new JTextField();
        private final JComboBox<TeardownAuthBinding> teardownAuthBinding = new JComboBox<>(TeardownAuthBinding.values());
        private final JTextField teardownAuthProfileId = new JTextField();
        private final JCheckBox teardownRunOnSuccess = new JCheckBox("Run on success", true);
        private final JCheckBox teardownRunOnFailure = new JCheckBox("Run on failure");
        private final JCheckBox teardownRunOnSkipped = new JCheckBox("Run on skipped");
        private final PostChecksTablePanel postChecks;

        MatrixRowFormPanel(AccessUiController controller, MatrixRowFormModel model) {
            super(new BorderLayout(8, 8));
            MatrixRowFormModel initial = model == null
                    ? new MatrixRowFormModel("New Access Test", firstTemplateId(controller), firstAuthId(controller),
                    "", "", ExpectedResult.REVIEW, true)
                    : model;
            name.setText(initial.name());
            targetType.setSelectedItem(initial.targetType());
            environment.setText(initial.environmentId());
            expected.setSelectedItem(initial.expected());
            enabled.setSelected(initial.enabled());
            evaluationProfile.addItem(new ComboItem("", ""));
            for (EvaluationProfile profile : controller.evaluationProfiles()) {
                evaluationProfile.addItem(new ComboItem(profile.id(), profile.name()));
            }
            select(evaluationProfile, initial.evaluationProfileId());
            setupMode.setSelectedItem(initial.setupMode());
            setupFlowId.setText(initial.setupFlowId());
            untilBeforeStepId.setText(initial.untilBeforeStepId());
            setupAuthBinding.setSelectedItem(initial.setupAuthBinding());
            setupAuthProfileId.setText(initial.setupAuthProfileId());
            scenarioPolicy.setSelectedItem(initial.scenarioPolicy());
            scenarioInstanceId.setText(initial.scenarioInstanceId());
            teardownMode.setSelectedItem(initial.teardownMode());
            teardownFlowId.setText(initial.teardownFlowId());
            teardownAuthBinding.setSelectedItem(initial.teardownAuthBinding());
            teardownAuthProfileId.setText(initial.teardownAuthProfileId());
            teardownRunOnSuccess.setSelected(initial.teardownRunOnSuccess());
            teardownRunOnFailure.setSelected(initial.teardownRunOnFailure());
            teardownRunOnSkipped.setSelected(initial.teardownRunOnSkipped());
            postChecks = new PostChecksTablePanel(initial.postChecks());

            for (AccessRequestTemplate item : controller.templates()) {
                template.addItem(new ComboItem(item.id(), item.name()));
            }
            for (AccessFlowTarget item : controller.flowTargets()) {
                flowTarget.addItem(new ComboItem(item.id(), item.name()));
            }
            for (AuthProfile item : controller.authProfiles()) {
                auth.addItem(new ComboItem(item.id(), item.name()));
            }
            data.addItem(new ComboItem("", ""));
            for (DataProfile item : controller.dataProfiles()) {
                data.addItem(new ComboItem(item.id(), item.name()));
            }
            select(template, initial.templateId());
            select(flowTarget, initial.flowTargetId());
            select(auth, initial.targetAuthProfileId());
            select(data, initial.dataProfileId());

            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            setPreferredSize(new Dimension(900, 640));
            targetType.addActionListener(event -> updateTargetControls());

            add(topFields(), BorderLayout.NORTH);
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Target", scroll(targetFields()));
            tabs.addTab("Setup", scroll(setupFields()));
            tabs.addTab("Post-checks", postChecksTab());
            tabs.addTab("Teardown", scroll(teardownFields()));
            add(tabs, BorderLayout.CENTER);
            updateTargetControls();
        }

        MatrixRowFormModel toModel() {
            postChecks.stopEditing();
            ComboItem templateItem = (ComboItem) template.getSelectedItem();
            ComboItem flowTargetItem = (ComboItem) flowTarget.getSelectedItem();
            ComboItem authItem = (ComboItem) auth.getSelectedItem();
            ComboItem dataItem = (ComboItem) data.getSelectedItem();
            ComboItem evaluationProfileItem = (ComboItem) evaluationProfile.getSelectedItem();
            return new MatrixRowFormModel(
                    (AccessTargetType) targetType.getSelectedItem(),
                    flowTargetItem == null ? "" : flowTargetItem.id(),
                    name.getText(),
                    templateItem == null ? "" : templateItem.id(),
                    authItem == null ? "" : authItem.id(),
                    dataItem == null ? "" : dataItem.id(),
                    environment.getText(),
                    (SetupMode) setupMode.getSelectedItem(),
                    setupFlowId.getText(),
                    untilBeforeStepId.getText(),
                    (SetupAuthBinding) setupAuthBinding.getSelectedItem(),
                    setupAuthProfileId.getText(),
                    (ScenarioPolicy) scenarioPolicy.getSelectedItem(),
                    scenarioInstanceId.getText(),
                    (ExpectedResult) expected.getSelectedItem(),
                    evaluationProfileItem == null ? "" : evaluationProfileItem.id(),
                    postChecks.postChecks(),
                    new TeardownConfig(
                            (TeardownMode) teardownMode.getSelectedItem(),
                            teardownFlowId.getText(),
                            (TeardownAuthBinding) teardownAuthBinding.getSelectedItem(),
                            teardownAuthProfileId.getText(),
                            teardownRunOnSuccess.isSelected(),
                            teardownRunOnFailure.isSelected(),
                            teardownRunOnSkipped.isSelected()),
                    enabled.isSelected());
        }

        private JPanel topFields() {
            JPanel fields = formGrid();
            fields.add(new JLabel("Name"));
            fields.add(name);
            fields.add(new JLabel("Target Type"));
            fields.add(targetType);
            fields.add(new JLabel(""));
            fields.add(enabled);
            return fields;
        }

        private JPanel targetFields() {
            JPanel fields = formGrid();
            fields.add(new JLabel("Template"));
            fields.add(template);
            fields.add(new JLabel("Flow Target"));
            fields.add(flowTarget);
            fields.add(new JLabel("Target Auth Profile"));
            fields.add(auth);
            fields.add(new JLabel("Data Profile"));
            fields.add(data);
            fields.add(new JLabel("Environment ID"));
            fields.add(environment);
            fields.add(new JLabel("Expected Result"));
            fields.add(expected);
            fields.add(new JLabel("Evaluation Profile"));
            fields.add(evaluationProfile);
            return fields;
        }

        private JPanel setupFields() {
            JPanel fields = formGrid();
            fields.add(new JLabel("Setup Mode"));
            fields.add(setupMode);
            fields.add(new JLabel("Setup Flow ID"));
            fields.add(setupFlowId);
            fields.add(new JLabel("Until Before Step ID"));
            fields.add(untilBeforeStepId);
            fields.add(new JLabel("Setup Auth Binding"));
            fields.add(setupAuthBinding);
            fields.add(new JLabel("Setup Auth Profile ID"));
            fields.add(setupAuthProfileId);
            fields.add(new JLabel("Scenario Policy"));
            fields.add(scenarioPolicy);
            fields.add(new JLabel("Scenario Instance ID"));
            fields.add(scenarioInstanceId);
            fields.add(new JLabel(""));
            fields.add(new JLabel("Setup/prefix supports simple linear flows only."));
            return fields;
        }

        private JPanel postChecksTab() {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            panel.add(postChecks, BorderLayout.CENTER);
            return panel;
        }

        private JPanel teardownFields() {
            JPanel fields = formGrid();
            fields.add(new JLabel("Teardown Mode"));
            fields.add(teardownMode);
            fields.add(new JLabel("Teardown Flow ID"));
            fields.add(teardownFlowId);
            fields.add(new JLabel("Teardown Auth Binding"));
            fields.add(teardownAuthBinding);
            fields.add(new JLabel("Teardown Auth Profile ID"));
            fields.add(teardownAuthProfileId);
            fields.add(new JLabel(""));
            fields.add(teardownRunOnSuccess);
            fields.add(new JLabel(""));
            fields.add(teardownRunOnFailure);
            fields.add(new JLabel(""));
            fields.add(teardownRunOnSkipped);
            return fields;
        }

        private void updateTargetControls() {
            AccessTargetType selected = (AccessTargetType) targetType.getSelectedItem();
            boolean flowRow = selected == AccessTargetType.FLOW_TARGET;
            template.setEnabled(!flowRow);
            flowTarget.setEnabled(flowRow);
        }

        private static JPanel formGrid() {
            JPanel panel = new JPanel(new GridLayout(0, 2, 6, 6));
            panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            return panel;
        }

        private static JScrollPane scroll(Component component) {
            JScrollPane scrollPane = new JScrollPane(component);
            scrollPane.setBorder(BorderFactory.createEmptyBorder());
            return scrollPane;
        }

        private static String firstTemplateId(AccessUiController controller) {
            List<AccessRequestTemplate> templates = controller.templates();
            return templates.isEmpty() ? "" : templates.getFirst().id();
        }

        private static String firstAuthId(AccessUiController controller) {
            List<AuthProfile> profiles = controller.authProfiles();
            return profiles.isEmpty() ? "" : profiles.getFirst().id();
        }

        private static void select(JComboBox<ComboItem> combo, String id) {
            for (int index = 0; index < combo.getItemCount(); index++) {
                if (combo.getItemAt(index).id().equals(id)) {
                    combo.setSelectedIndex(index);
                    return;
                }
            }
        }
    }

    private record ComboItem(String id, String label) {
        @Override
        public String toString() {
            return label == null || label.isBlank() ? id : label;
        }
    }

    private static final class PostChecksTablePanel extends JPanel {
        private final DefaultTableModel model;
        private final JTable table;

        PostChecksTablePanel(List<PostCheckRequest> initial) {
            super(new BorderLayout(6, 6));
            model = new DefaultTableModel(new Object[]{
                    "Name", "Enabled", "Template", "Auth Binding", "Auth Profile", "Data Profile", "Scenario", "Assertions"}, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return true;
                }
            };
            table = new JTable(model);
            table.setFillsViewportHeight(true);
            table.getColumnModel().getColumn(3)
                    .setCellEditor(new DefaultCellEditor(new JComboBox<>(PostCheckAuthBinding.values())));
            add(new JScrollPane(table), BorderLayout.CENTER);
            add(buttons(), BorderLayout.SOUTH);
            setPostChecks(initial);
        }

        List<PostCheckRequest> postChecks() {
            stopEditing();
            ArrayList<PostCheckRequest> postChecks = new ArrayList<>();
            for (int row = 0; row < model.getRowCount(); row++) {
                String name = valueAt(row, 0);
                String templateId = valueAt(row, 2);
                if (name.isBlank() && templateId.isBlank()) {
                    continue;
                }
                postChecks.add(new PostCheckRequest(
                        "post-check-" + (postChecks.size() + 1),
                        name.isBlank() ? "Post-check " + (postChecks.size() + 1) : name,
                        booleanAt(row, 1, true),
                        templateId,
                        valueAt(row, 4),
                        authBindingAt(row, 3),
                        valueAt(row, 5),
                        valueAt(row, 6),
                        parseAssertions(valueAt(row, 7), postChecks.size() + 1)));
            }
            return List.copyOf(postChecks);
        }

        void stopEditing() {
            if (table.isEditing() && table.getCellEditor() != null) {
                table.getCellEditor().stopCellEditing();
            }
        }

        private JPanel buttons() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            JButton add = StyledButton.neutral("Add Row");
            JButton remove = StyledButton.danger("Remove Row");
            add.addActionListener(event -> model.addRow(new Object[]{
                    "Post-check", true, "", PostCheckAuthBinding.TARGET_ACTOR, "", "", "", ""}));
            remove.addActionListener(event -> {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    model.removeRow(table.convertRowIndexToModel(row));
                }
            });
            panel.add(add);
            panel.add(remove);
            return panel;
        }

        private void setPostChecks(List<PostCheckRequest> postChecks) {
            model.setRowCount(0);
            if (postChecks == null) {
                return;
            }
            for (PostCheckRequest postCheck : postChecks) {
                model.addRow(new Object[]{
                        postCheck.name(),
                        postCheck.enabled(),
                        postCheck.templateId(),
                        postCheck.authBinding(),
                        postCheck.authProfileId(),
                        postCheck.dataProfileId(),
                        postCheck.scenarioInstanceId(),
                        assertionsText(postCheck.assertions())});
            }
        }

        private static List<PostCheckAssertion> parseAssertions(String text, int postCheckIndex) {
            if (text == null || text.isBlank()) {
                return List.of();
            }
            ArrayList<PostCheckAssertion> assertions = new ArrayList<>();
            for (String raw : text.split(";")) {
                String line = raw.strip();
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\\|", -1);
                PostCheckAssertionType type = PostCheckAssertionType.valueOf(parts[0].strip());
                String expression = part(parts, 1);
                String expected = part(parts, 2);
                boolean negate = Boolean.parseBoolean(part(parts, 3));
                assertions.add(new PostCheckAssertion(
                        "post-check-" + postCheckIndex + "-assert-" + (assertions.size() + 1),
                        type.name(),
                        true,
                        type,
                        expression,
                        expected,
                        negate));
            }
            return List.copyOf(assertions);
        }

        private static String assertionsText(List<PostCheckAssertion> assertions) {
            if (assertions == null || assertions.isEmpty()) {
                return "";
            }
            ArrayList<String> parts = new ArrayList<>();
            for (PostCheckAssertion assertion : assertions) {
                if (assertion.enabled()) {
                    parts.add(assertion.type().name() + "|" + assertion.expression()
                            + "|" + assertion.expectedValue() + "|" + assertion.negate());
                }
            }
            return String.join("; ", parts);
        }

        private static String part(String[] parts, int index) {
            return index < parts.length ? parts[index].strip() : "";
        }

        private String valueAt(int row, int column) {
            Object value = model.getValueAt(row, column);
            return value == null ? "" : value.toString().strip();
        }

        private boolean booleanAt(int row, int column, boolean defaultValue) {
            Object value = model.getValueAt(row, column);
            if (value instanceof Boolean bool) {
                return bool;
            }
            return value == null || value.toString().isBlank()
                    ? defaultValue
                    : Boolean.parseBoolean(value.toString());
        }

        private PostCheckAuthBinding authBindingAt(int row, int column) {
            Object value = model.getValueAt(row, column);
            if (value instanceof PostCheckAuthBinding binding) {
                return binding;
            }
            try {
                return PostCheckAuthBinding.valueOf(Objects.requireNonNullElse(value, "").toString().strip());
            } catch (RuntimeException ignored) {
                return PostCheckAuthBinding.TARGET_ACTOR;
            }
        }
    }
}

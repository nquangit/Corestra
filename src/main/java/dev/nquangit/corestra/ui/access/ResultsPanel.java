package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.result.AccessEvidence;
import dev.nquangit.corestra.access.result.AccessTestResult;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;

final class ResultsPanel extends JPanel implements AccessTabPanel {
    private final AccessUiController controller;
    private final DefaultTableModel tableModel;
    private final JTable table;
    private final JTextArea request = new JTextArea();
    private final JTextArea response = new JTextArea();
    private final JTextArea postChecks = new JTextArea();

    ResultsPanel(AccessUiController controller) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        tableModel = new DefaultTableModel(new Object[]{"Test Case", "Status", "Verdict", "Reason", "Finished", "Id"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().removeColumn(table.getColumnModel().getColumn(5));
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showEvidence();
            }
        });
        request.setEditable(false);
        response.setEditable(false);
        postChecks.setEditable(false);

        add(toolbar(), BorderLayout.NORTH);
        JSplitPane evidenceSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT,
                labelled("Redacted Request", new JScrollPane(request)),
                labelled("Redacted Response", new JScrollPane(response)));
        evidenceSplit.setResizeWeight(0.5);
        JSplitPane lowerSplit = new JSplitPane(
                JSplitPane.VERTICAL_SPLIT,
                evidenceSplit,
                labelled("Post-checks", new JScrollPane(postChecks)));
        lowerSplit.setResizeWeight(0.7);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), lowerSplit);
        split.setResizeWeight(0.4);
        add(split, BorderLayout.CENTER);
        refreshUi();
    }

    private JPanel toolbar() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        javax.swing.JButton draft = StyledButton.primary("Create Finding Draft");
        draft.addActionListener(event -> showFindingDraft());
        panel.add(draft);
        return panel;
    }

    private void showFindingDraft() {
        String resultId = selectedResultId();
        if (resultId.isBlank()) {
            JOptionPane.showMessageDialog(this, "Select a result first.",
                    "Create Finding Draft", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            String markdown = controller.savedFindingDraft(resultId)
                    .map(dev.nquangit.corestra.access.report.SavedFindingDraft::markdown)
                    .orElseGet(() -> new dev.nquangit.corestra.access.report.MarkdownFindingExporter()
                            .export(controller.createFindingDraft(resultId)));
            FindingDraftDialog dialog = new FindingDraftDialog(resultId, markdown);
            dialog.setLocationRelativeTo(this);
            dialog.setVisible(true);
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "Create Finding Draft", JOptionPane.ERROR_MESSAGE);
        }
    }

    @Override
    public void refreshUi() {
        String selected = selectedResultId();
        tableModel.setRowCount(0);
        for (AccessTestResult result : controller.results()) {
            tableModel.addRow(new Object[]{
                    result.testCaseId(),
                    result.runStatus(),
                    result.verdict(),
                    result.reason(),
                    result.finishedAtEpochMillis() == 0 ? "" : Instant.ofEpochMilli(result.finishedAtEpochMillis()),
                    result.id()});
        }
        select(selected);
        showEvidence();
    }

    private void showEvidence() {
        String resultId = selectedResultId();
        if (resultId.isBlank()) {
            request.setText("");
            response.setText("");
            postChecks.setText("");
            return;
        }
        controller.evidenceByResultId(resultId).ifPresentOrElse(this::showEvidence, () -> {
            request.setText("");
            response.setText("");
            postChecks.setText("");
        });
    }

    private void showEvidence(AccessEvidence evidence) {
        request.setText(evidence.redactedRequestRaw());
        response.setText(evidence.redactedResponseRaw());
        postChecks.setText(postCheckSummary(evidence) + teardownSummary(evidence));
    }

    private String postCheckSummary(AccessEvidence evidence) {
        if (evidence.postCheckEvidence().isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        for (dev.nquangit.corestra.access.postcheck.PostCheckEvidence entry : evidence.postCheckEvidence()) {
            summary.append(entry.name()).append(" - ").append(entry.verdict());
            if (!entry.reason().isBlank()) {
                summary.append(" - ").append(entry.reason());
            }
            summary.append('\n');
            for (dev.nquangit.corestra.access.postcheck.PostCheckAssertionResult assertion : entry.assertionResults()) {
                summary.append("  ")
                        .append(assertion.passed() ? "PASS" : "FAIL")
                        .append(" ")
                        .append(assertion.name())
                        .append(": ")
                        .append(assertion.reason())
                        .append('\n');
            }
            if (!entry.redactedRequestRaw().isBlank()) {
                summary.append("\nRequest:\n").append(entry.redactedRequestRaw()).append('\n');
            }
            if (!entry.redactedResponseRaw().isBlank()) {
                summary.append("\nResponse:\n").append(entry.redactedResponseRaw()).append('\n');
            }
            summary.append('\n');
        }
        return summary.toString();
    }

    private String teardownSummary(AccessEvidence evidence) {
        if (evidence.teardownEvidence().isEmpty()) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        if (!evidence.postCheckEvidence().isEmpty()) {
            summary.append('\n');
        }
        summary.append("Teardown").append('\n');
        for (dev.nquangit.corestra.access.teardown.TeardownEvidence entry : evidence.teardownEvidence()) {
            summary.append(entry.flowName().isBlank() ? entry.flowId() : entry.flowName())
                    .append(" - ")
                    .append(entry.success() ? "OK" : "FAILED");
            if (!entry.reason().isBlank()) {
                summary.append(" - ").append(entry.reason());
            }
            summary.append('\n');
            for (dev.nquangit.corestra.access.teardown.TeardownStepEvidence step : entry.steps()) {
                summary.append("  ")
                        .append(step.success() ? "PASS" : "FAIL")
                        .append(" ")
                        .append(step.stepId());
                if (step.responseStatusCode() > 0) {
                    summary.append(" HTTP ").append(step.responseStatusCode());
                }
                if (!step.reason().isBlank()) {
                    summary.append(": ").append(step.reason());
                }
                summary.append('\n');
                if (!step.redactedRequestRaw().isBlank()) {
                    summary.append("\nRequest:\n").append(step.redactedRequestRaw()).append('\n');
                }
                if (!step.redactedResponseRaw().isBlank()) {
                    summary.append("\nResponse:\n").append(step.redactedResponseRaw()).append('\n');
                }
            }
            summary.append('\n');
        }
        return summary.toString();
    }

    private String selectedResultId() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return "";
        }
        int modelRow = table.convertRowIndexToModel(row);
        return (String) tableModel.getValueAt(modelRow, 5);
    }

    private void select(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            if (id.equals(tableModel.getValueAt(row, 5))) {
                table.setRowSelectionInterval(row, row);
                return;
            }
        }
    }

    private static JPanel labelled(String title, JScrollPane child) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(child, BorderLayout.CENTER);
        return panel;
    }

    private final class FindingDraftDialog extends JDialog {
        private final String resultId;
        private final JTextArea markdown = new JTextArea(30, 100);

        FindingDraftDialog(String resultId, String initialMarkdown) {
            super(javax.swing.SwingUtilities.getWindowAncestor(ResultsPanel.this),
                    "Finding Draft",
                    java.awt.Dialog.ModalityType.APPLICATION_MODAL);
            this.resultId = resultId;
            markdown.setText(initialMarkdown);
            markdown.setCaretPosition(0);
            setLayout(new BorderLayout(8, 8));
            add(new JScrollPane(markdown), BorderLayout.CENTER);
            add(buttons(), BorderLayout.SOUTH);
            pack();
        }

        private JPanel buttons() {
            JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
            javax.swing.JButton copy = StyledButton.primary("Copy Markdown");
            javax.swing.JButton saveDraft = StyledButton.neutral("Save Draft");
            javax.swing.JButton save = StyledButton.neutral("Save Markdown to File");
            javax.swing.JButton close = StyledButton.neutral("Close");
            copy.addActionListener(event -> copyMarkdown());
            saveDraft.addActionListener(event -> saveDraft());
            save.addActionListener(event -> saveMarkdown());
            close.addActionListener(event -> dispose());
            panel.add(copy);
            panel.add(saveDraft);
            panel.add(save);
            panel.add(close);
            return panel;
        }

        private void copyMarkdown() {
            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new StringSelection(markdown.getText()), null);
        }

        private void saveDraft() {
            try {
                controller.saveFindingDraft(resultId, markdown.getText());
                JOptionPane.showMessageDialog(this, "Finding draft saved.",
                        "Save Draft", JOptionPane.INFORMATION_MESSAGE);
            } catch (IllegalArgumentException exception) {
                JOptionPane.showMessageDialog(this, exception.getMessage(),
                        "Save Draft", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void saveMarkdown() {
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("corestra-finding-draft.md"));
            int result = chooser.showSaveDialog(this);
            if (result != JFileChooser.APPROVE_OPTION) {
                return;
            }
            try {
                Files.writeString(chooser.getSelectedFile().toPath(), markdown.getText());
            } catch (IOException exception) {
                JOptionPane.showMessageDialog(this, exception.getMessage(),
                        "Save Markdown", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}

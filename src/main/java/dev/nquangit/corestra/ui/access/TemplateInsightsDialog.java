package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.intel.access.AccessInsight;
import dev.nquangit.corestra.intel.access.IdentifierOccurrence;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Lists access-control insights for every template (risk, identifiers, inferred operation,
 * recommended strategies) and applies the safe, reviewable fixes: set the inferred operation type
 * and parameterize literal identifiers into a Data Profile. Nothing here sends traffic.
 */
final class TemplateInsightsDialog extends JDialog {
    private final AccessUiController controller;
    private final Runnable onChange;
    private final DefaultTableModel model;
    private final JTable table;
    private final JTextArea details = new JTextArea();
    private List<AccessInsight> insights = List.of();

    private TemplateInsightsDialog(Component owner, AccessUiController controller, Runnable onChange) {
        super(SwingUtilities.getWindowAncestor(owner), "Template Insights", ModalityType.APPLICATION_MODAL);
        this.controller = controller;
        this.onChange = onChange;
        model = new DefaultTableModel(new Object[]{"Risk", "Level", "Template", "Operation (inferred)",
                "Identifiers", "Recommended strategies", "Ready"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setMaxWidth(70);
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showDetails();
            }
        });
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);

        JButton applyOperation = StyledButton.primary("Apply Inferred Operation");
        applyOperation.setToolTipText("Store the inferred READ_ONLY/STATE_CHANGING/CONSUMABLE type on the template");
        applyOperation.addActionListener(event -> selected().ifPresent(this::applyOperation));
        JButton parameterize = StyledButton.success("Parameterize Identifiers…");
        parameterize.setToolTipText("Replace literal object ids with {{data.*}} placeholders and save the originals"
                + " in a Data Profile owned by the actor you choose");
        parameterize.addActionListener(event -> selected().ifPresent(this::parameterize));
        JButton close = StyledButton.neutral("Close");
        close.addActionListener(event -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        buttons.add(applyOperation);
        buttons.add(parameterize);
        buttons.add(close);
        JLabel hint = new JLabel("Scores are explainable heuristics from the request, its captured response and"
                + " the workspace. Review before applying; nothing is sent.");
        hint.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 6));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(details));
        split.setResizeWeight(0.55);
        JPanel content = new JPanel(new BorderLayout(6, 6));
        content.add(hint, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        setPreferredSize(new Dimension(1100, 640));
        reload(null);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * Opens the dialog.
     *
     * @param owner parent component
     * @param controller access controller
     * @param onChange called after a template or data profile changes
     */
    static void open(Component owner, AccessUiController controller, Runnable onChange) {
        new TemplateInsightsDialog(owner, controller, onChange).setVisible(true);
    }

    private void reload(String selectTemplateId) {
        insights = controller.templateInsights();
        model.setRowCount(0);
        int selectRow = insights.isEmpty() ? -1 : 0;
        for (int index = 0; index < insights.size(); index++) {
            AccessInsight insight = insights.get(index);
            if (insight.templateId().equals(selectTemplateId)) {
                selectRow = index;
            }
            long ready = insight.strategies().stream().filter(AccessInsight.StrategyRecommendation::ready).count();
            model.addRow(new Object[]{
                    insight.score(),
                    insight.level(),
                    insight.templateName(),
                    insight.operation().metadata().operationType() + (insight.operationDiffers() ? " *" : ""),
                    insight.identifiers().size() + (insight.literalIdentifiers().isEmpty() ? "" :
                            " (" + insight.literalIdentifiers().size() + " literal)"),
                    insight.strategies().stream().map(r -> r.strategy().name()).collect(Collectors.joining(", ")),
                    ready + "/" + insight.strategies().size()});
        }
        if (selectRow >= 0) {
            table.setRowSelectionInterval(selectRow, selectRow);
        }
        showDetails();
    }

    private Optional<AccessInsight> selected() {
        int row = table.getSelectedRow();
        return row < 0 || row >= insights.size() ? Optional.empty() : Optional.of(insights.get(row));
    }

    private void showDetails() {
        details.setText(selected().map(TemplateInsightsDialog::describe).orElse("No templates."));
        details.setCaretPosition(0);
    }

    static String describe(AccessInsight insight) {
        StringBuilder text = new StringBuilder();
        text.append(insight.templateName()).append(" — risk ").append(insight.score()).append(" (")
                .append(insight.level()).append(")\n\nWhy:\n");
        insight.reasons().forEach(reason -> text.append("  ").append(reason).append('\n'));
        text.append("\nInferred operation: ").append(insight.operation().metadata().operationType())
                .append(insight.operationDiffers() ? " (differs from the template; Apply to store it)" : "")
                .append('\n');
        insight.operation().reasons().forEach(reason -> text.append("  - ").append(reason).append('\n'));
        text.append("\nObject identifiers:\n");
        if (insight.identifiers().isEmpty()) {
            text.append("  none found\n");
        }
        for (IdentifierOccurrence identifier : insight.identifiers()) {
            text.append("  - ").append(identifier.describe())
                    .append(identifier.parameterized() ? "  [parameterized]" : "  → {{data." + identifier.name() + "}}")
                    .append('\n');
        }
        text.append("\nRecommended strategies:\n");
        for (AccessInsight.StrategyRecommendation recommendation : insight.strategies()) {
            text.append("  - ").append(recommendation.strategy()).append(recommendation.ready() ? "  [ready]" : "")
                    .append(": ").append(recommendation.reason()).append('\n');
            recommendation.missingPrerequisites().forEach(missing -> text.append("      needs: ").append(missing).append('\n'));
        }
        if (!insight.sensitiveFieldPaths().isEmpty()) {
            text.append("\nSensitive fields in the captured response (use as Evaluation Profile sensitive fields):\n");
            insight.sensitiveFieldPaths().forEach(path -> text.append("  - ").append(path).append('\n'));
        }
        if (!insight.readinessIssues().isEmpty()) {
            text.append("\nBefore generating a matrix:\n");
            insight.readinessIssues().forEach(issue -> text.append("  - ").append(issue).append('\n'));
        }
        return text.toString();
    }

    private void applyOperation(AccessInsight insight) {
        try {
            controller.applyInferredOperation(insight.templateId());
            onChange.run();
            reload(insight.templateId());
        } catch (RuntimeException exception) {
            error(exception);
        }
    }

    private void parameterize(AccessInsight insight) {
        if (insight.literalIdentifiers().isEmpty()) {
            JOptionPane.showMessageDialog(this, "This template has no literal object identifiers.",
                    "Parameterize Identifiers", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<AuthProfile> profiles = controller.authProfiles();
        if (profiles.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Create the owner's Auth Profile first (Authentication tab).",
                    "Parameterize Identifiers", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JComboBox<Choice> owner = new JComboBox<>();
        profiles.forEach(profile -> owner.addItem(new Choice(profile.id(),
                profile.name() + (profile.role().isBlank() ? "" : " (" + profile.role() + ")"))));
        JComboBox<Choice> data = new JComboBox<>();
        data.addItem(new Choice("", "<New Data Profile>"));
        for (DataProfile profile : controller.dataProfiles()) {
            data.addItem(new Choice(profile.id(), profile.name()));
        }
        JPanel form = new JPanel(new GridLayout(0, 1, 4, 4));
        form.add(new JLabel("Values that will move into the Data Profile"
                + " (a key already used there for another value gets a numeric suffix):"));
        for (IdentifierOccurrence identifier : insight.literalIdentifiers()) {
            form.add(new JLabel("   {{data." + identifier.name() + "}} = " + identifier.value()));
        }
        form.add(new JLabel("Owner of these objects (the actor who captured them):"));
        form.add(owner);
        form.add(new JLabel("Store values in:"));
        form.add(data);
        int choice = JOptionPane.showConfirmDialog(this, form, "Parameterize Identifiers",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION || owner.getSelectedItem() == null) {
            return;
        }
        try {
            AccessUiController.TemplateParameterization result = controller.parameterizeTemplate(
                    insight.templateId(),
                    ((Choice) owner.getSelectedItem()).id(),
                    data.getSelectedItem() == null ? "" : ((Choice) data.getSelectedItem()).id());
            onChange.run();
            reload(insight.templateId());
            List<String> lines = new ArrayList<>();
            lines.add("Template now uses " + result.template().baseRequest().pathAndQuery());
            lines.add("Data Profile: " + result.dataProfile().name());
            if (!result.skipped().isEmpty()) {
                lines.add("Not replaced: " + String.join(", ", result.skipped()));
            }
            JOptionPane.showMessageDialog(this, String.join("\n", lines), "Parameterize Identifiers",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (RuntimeException exception) {
            error(exception);
        }
    }

    private void error(RuntimeException exception) {
        JOptionPane.showMessageDialog(this, exception.getMessage(), "Template Insights", JOptionPane.ERROR_MESSAGE);
    }

    private record Choice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }
}

package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.evaluate.EvaluationProfile;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;

final class EvaluationProfilesPanel extends JPanel implements AccessTabPanel {
    private final AccessUiController controller;
    private final DefaultTableModel tableModel;
    private final JTable table;

    EvaluationProfilesPanel(AccessUiController controller) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        this.tableModel = new DefaultTableModel(
                new Object[]{"Name", "Allow", "Deny", "Sensitive", "Masked", "Enabled", "Id"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        this.table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().removeColumn(table.getColumnModel().getColumn(6));

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
        edit.addActionListener(event -> selectedProfile().ifPresent(this::showEditor));
        delete.addActionListener(event -> selectedProfile().ifPresent(this::deleteProfile));
        panel.add(add);
        panel.add(edit);
        panel.add(delete);
        return panel;
    }

    private void showEditor(EvaluationProfile existing) {
        EvaluationProfileFormPanel form = new EvaluationProfileFormPanel(existing == null
                ? null
                : EvaluationProfileFormModel.from(existing));
        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                existing == null ? "Add Evaluation Profile" : "Edit Evaluation Profile",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            controller.saveEvaluationProfile(existing == null ? "" : existing.id(), form.toModel());
            refresh();
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(),
                    "Invalid Evaluation Profile", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteProfile(EvaluationProfile profile) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Delete evaluation profile \"" + profile.name() + "\"?",
                "Delete Evaluation Profile",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            controller.deleteEvaluationProfile(profile.id());
            refresh();
        }
    }

    private java.util.Optional<EvaluationProfile> selectedProfile() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return java.util.Optional.empty();
        }
        int modelRow = table.convertRowIndexToModel(row);
        String id = (String) tableModel.getValueAt(modelRow, 6);
        return controller.evaluationProfile(id);
    }

    @Override
    public void refreshUi() {
        refresh();
    }

    private void refresh() {
        tableModel.setRowCount(0);
        for (EvaluationProfile profile : controller.evaluationProfiles()) {
            tableModel.addRow(new Object[]{
                    profile.name(),
                    profile.allowIndicators().size(),
                    profile.denyIndicators().size(),
                    profile.sensitiveFieldRules().size(),
                    profile.maskedFieldRules().size(),
                    profile.enabled(),
                    profile.id()});
        }
    }

    private static final class EvaluationProfileFormPanel extends JPanel {
        private final JTextField name = new JTextField();
        private final JCheckBox enabled = new JCheckBox("Enabled", true);
        private final JTextArea allowIndicators = textArea();
        private final JTextArea denyIndicators = textArea();
        private final JTextArea sensitiveRules = textArea();
        private final JTextArea maskingRules = textArea();

        EvaluationProfileFormPanel(EvaluationProfileFormModel model) {
            super(new BorderLayout(8, 8));
            EvaluationProfileFormModel initial = model == null
                    ? new EvaluationProfileFormModel("New Evaluation Profile", true,
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), java.util.List.of())
                    : model;
            name.setText(initial.name());
            enabled.setSelected(initial.enabled());
            allowIndicators.setText(EvaluationProfileFormModel.indicatorsText(initial.allowIndicators()));
            denyIndicators.setText(EvaluationProfileFormModel.indicatorsText(initial.denyIndicators()));
            sensitiveRules.setText(EvaluationProfileFormModel.sensitiveRulesText(initial.sensitiveFieldRules()));
            maskingRules.setText(EvaluationProfileFormModel.maskingRulesText(initial.maskedFieldRules()));

            JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
            fields.add(new JLabel("Name"));
            fields.add(name);
            fields.add(new JLabel(""));
            fields.add(enabled);

            JPanel rules = new JPanel();
            rules.setLayout(new BoxLayout(rules, BoxLayout.Y_AXIS));
            rules.add(labelled("Allow Indicators", allowIndicators));
            rules.add(labelled("Deny Indicators", denyIndicators));
            rules.add(labelled("Sensitive Fields", sensitiveRules));
            rules.add(labelled("Masking Rules", maskingRules));

            add(fields, BorderLayout.NORTH);
            add(rules, BorderLayout.CENTER);
        }

        EvaluationProfileFormModel toModel() {
            return new EvaluationProfileFormModel(
                    name.getText(),
                    enabled.isSelected(),
                    EvaluationProfileFormModel.parseIndicators(allowIndicators.getText(), "allow"),
                    EvaluationProfileFormModel.parseIndicators(denyIndicators.getText(), "deny"),
                    EvaluationProfileFormModel.parseSensitiveRules(sensitiveRules.getText()),
                    EvaluationProfileFormModel.parseMaskingRules(maskingRules.getText()));
        }

        private static JTextArea textArea() {
            JTextArea area = new JTextArea(4, 56);
            area.setLineWrap(false);
            return area;
        }

        private static JPanel labelled(String title, JTextArea area) {
            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createTitledBorder(title));
            panel.add(new JScrollPane(area), BorderLayout.CENTER);
            return panel;
        }
    }
}

package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.access.data.DataProfile;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.List;

final class DataProfilesPanel extends JPanel implements AccessTabPanel {
    private final AccessUiController controller;
    private final DefaultTableModel tableModel;
    private final JTable table;

    DataProfilesPanel(AccessUiController controller) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        tableModel = new DefaultTableModel(new Object[]{"Name", "Owner Auth", "Enabled", "Id"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().removeColumn(table.getColumnModel().getColumn(3));

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

    private void showEditor(DataProfile existing) {
        DataProfileFormPanel form = new DataProfileFormPanel(controller, existing == null
                ? null
                : DataProfileFormModel.from(existing));
        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                existing == null ? "Add Data Profile" : "Edit Data Profile",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            controller.saveDataProfile(existing == null ? "" : existing.id(), form.toModel());
            refresh();
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Invalid Data Profile", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteProfile(DataProfile profile) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Delete data profile \"" + profile.name() + "\"?",
                "Delete Data Profile",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            controller.deleteDataProfile(profile.id());
            refresh();
        }
    }

    private java.util.Optional<DataProfile> selectedProfile() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return java.util.Optional.empty();
        }
        int modelRow = table.convertRowIndexToModel(row);
        String id = (String) tableModel.getValueAt(modelRow, 3);
        return controller.dataProfile(id);
    }

    @Override
    public void refreshUi() {
        refresh();
    }

    private void refresh() {
        tableModel.setRowCount(0);
        for (DataProfile profile : controller.dataProfiles()) {
            tableModel.addRow(new Object[]{
                    profile.name(),
                    ownerName(profile.ownerAuthProfileId()),
                    profile.enabled(),
                    profile.id()});
        }
    }

    private String ownerName(String id) {
        return controller.authProfile(id).map(AuthProfile::name).orElse(id);
    }

    private static final class DataProfileFormPanel extends JPanel {
        private final JTextField name = new JTextField();
        private final JComboBox<ComboItem> owner = new JComboBox<>();
        private final JCheckBox enabled = new JCheckBox("Enabled", true);
        private final KeyValueTablePanel values;

        DataProfileFormPanel(AccessUiController controller, DataProfileFormModel model) {
            super(new BorderLayout(8, 8));
            DataProfileFormModel initial = model == null
                    ? new DataProfileFormModel("New Data Profile", "", true, java.util.Map.of())
                    : model;
            name.setText(initial.name());
            enabled.setSelected(initial.enabled());
            owner.addItem(new ComboItem("", ""));
            for (AuthProfile profile : controller.authProfiles()) {
                owner.addItem(new ComboItem(profile.id(), profile.name()));
            }
            select(owner, initial.ownerAuthProfileId());
            values = new KeyValueTablePanel(initial.values());

            JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
            fields.add(new JLabel("Name"));
            fields.add(name);
            fields.add(new JLabel("Owner Auth Profile"));
            fields.add(owner);
            fields.add(new JLabel(""));
            fields.add(enabled);

            JPanel valuesPanel = new JPanel(new BorderLayout());
            valuesPanel.setBorder(BorderFactory.createTitledBorder("Values"));
            valuesPanel.add(values, BorderLayout.CENTER);

            add(fields, BorderLayout.NORTH);
            add(valuesPanel, BorderLayout.CENTER);
        }

        DataProfileFormModel toModel() {
            ComboItem selected = (ComboItem) owner.getSelectedItem();
            return new DataProfileFormModel(
                    name.getText(),
                    selected == null ? "" : selected.id(),
                    enabled.isSelected(),
                    values.values());
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
}

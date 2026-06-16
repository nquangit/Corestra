package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.access.auth.AuthProfile;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
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
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;

final class AuthProfilesPanel extends JPanel implements AccessTabPanel {
    private final AccessUiController controller;
    private final DefaultTableModel tableModel;
    private final JTable table;

    AuthProfilesPanel(AccessUiController controller) {
        super(new BorderLayout(8, 8));
        this.controller = controller;
        tableModel = new DefaultTableModel(new Object[]{"Name", "Role", "Provider", "Enabled", "Id"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        table = new JTable(tableModel);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().removeColumn(table.getColumnModel().getColumn(4));

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

    private void showEditor(AuthProfile existing) {
        AuthProfileFormPanel form = new AuthProfileFormPanel(controller.authProviderOptions(), existing == null
                ? null
                : AuthProfileFormModel.from(existing));
        int result = JOptionPane.showConfirmDialog(
                this,
                form,
                existing == null ? "Add Auth Profile" : "Edit Auth Profile",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            controller.saveAuthProfile(existing == null ? "" : existing.id(), form.toModel());
            refresh();
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Invalid Auth Profile", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteProfile(AuthProfile profile) {
        int result = JOptionPane.showConfirmDialog(
                this,
                "Delete auth profile \"" + profile.name() + "\"?",
                "Delete Auth Profile",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            controller.deleteAuthProfile(profile.id());
            refresh();
        }
    }

    private java.util.Optional<AuthProfile> selectedProfile() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return java.util.Optional.empty();
        }
        int modelRow = table.convertRowIndexToModel(row);
        String id = (String) tableModel.getValueAt(modelRow, 4);
        return controller.authProfile(id);
    }

    @Override
    public void refreshUi() {
        refresh();
    }

    private void refresh() {
        tableModel.setRowCount(0);
        List<AuthProfile> profiles = controller.authProfiles();
        for (AuthProfile profile : profiles) {
            tableModel.addRow(new Object[]{
                    profile.name(),
                    profile.role(),
                    profile.providerId(),
                    profile.enabled(),
                    profile.id()});
        }
    }

    private static final class AuthProfileFormPanel extends JPanel {
        private final JTextField name = new JTextField();
        private final JTextField role = new JTextField();
        private final JComboBox<AuthProviderOption> provider = new JComboBox<>();
        private final JCheckBox enabled = new JCheckBox("Enabled", true);
        private final KeyValueTablePanel variables;
        private final KeyValueTablePanel headers;
        private final KeyValueTablePanel cookies;

        AuthProfileFormPanel(List<AuthProviderOption> providerOptions, AuthProfileFormModel model) {
            super(new BorderLayout(8, 8));
            AuthProfileFormModel initial = model == null
                    ? new AuthProfileFormModel("New Auth Profile", "", true, java.util.Map.of(), java.util.Map.of(), java.util.Map.of())
                    : model;
            name.setText(initial.name());
            role.setText(initial.role());
            enabled.setSelected(initial.enabled());
            configureProviderOptions(providerOptions, initial.providerId());
            variables = new KeyValueTablePanel(initial.variables());
            headers = new KeyValueTablePanel(initial.headers());
            cookies = new KeyValueTablePanel(initial.cookies());

            JPanel fields = new JPanel(new GridLayout(0, 2, 6, 6));
            fields.add(new JLabel("Name"));
            fields.add(name);
            fields.add(new JLabel("Role"));
            fields.add(role);
            fields.add(new JLabel("Provider"));
            fields.add(provider);
            fields.add(new JLabel(""));
            fields.add(enabled);

            add(fields, BorderLayout.NORTH);
            add(buildValueTabs(), BorderLayout.CENTER);
            setPreferredSize(new Dimension(820, 620));
        }

        AuthProfileFormModel toModel() {
            return new AuthProfileFormModel(
                    name.getText(),
                    role.getText(),
                    selectedProviderId(),
                    enabled.isSelected(),
                    variables.values(),
                    headers.values(),
                    cookies.values());
        }

        private JTabbedPane buildValueTabs() {
            JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
            variables.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            headers.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            cookies.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            tabs.addTab("Variables", variables);
            tabs.addTab("Headers", headers);
            tabs.addTab("Cookies", cookies);
            return tabs;
        }

        private void configureProviderOptions(List<AuthProviderOption> providerOptions, String selectedProviderId) {
            ArrayList<AuthProviderOption> options = new ArrayList<>(
                    providerOptions == null || providerOptions.isEmpty()
                            ? List.of(AuthProviderOption.manual())
                            : providerOptions);
            boolean hasSelected = options.stream().anyMatch(option -> option.id().equals(selectedProviderId));
            if (!hasSelected && selectedProviderId != null && !selectedProviderId.isBlank()) {
                options.add(new AuthProviderOption(selectedProviderId, selectedProviderId + " (missing)"));
            }
            options.forEach(provider::addItem);
            for (int index = 0; index < provider.getItemCount(); index++) {
                if (provider.getItemAt(index).id().equals(selectedProviderId)) {
                    provider.setSelectedIndex(index);
                    return;
                }
            }
        }

        private String selectedProviderId() {
            Object selected = provider.getSelectedItem();
            return selected instanceof AuthProviderOption option
                    ? option.id()
                    : dev.nquangit.corestra.access.auth.AuthProfile.MANUAL_PROVIDER_ID;
        }
    }
}

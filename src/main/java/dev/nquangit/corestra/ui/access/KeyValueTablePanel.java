package dev.nquangit.corestra.ui.access;

import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;

final class KeyValueTablePanel extends JPanel {
    private final DefaultTableModel tableModel;
    private final JTable table;

    KeyValueTablePanel(Map<String, String> initialValues) {
        super(new BorderLayout(6, 6));
        tableModel = new DefaultTableModel(new Object[]{"Key", "Value"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }
        };
        table = new JTable(tableModel);
        table.setFillsViewportHeight(true);

        add(new JScrollPane(table), BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);
        setValues(initialValues);
    }

    Map<String, String> values() {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            String key = valueAt(row, 0);
            String value = valueAt(row, 1);
            if (!key.isBlank()) {
                values.put(key, value);
            }
        }
        return values;
    }

    private void setValues(Map<String, String> values) {
        tableModel.setRowCount(0);
        if (values != null) {
            values.forEach((key, value) -> tableModel.addRow(new Object[]{key, value}));
        }
    }

    private JPanel buttons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton add = StyledButton.neutral("Add Row");
        JButton remove = StyledButton.danger("Remove Row");
        add.addActionListener(event -> tableModel.addRow(new Object[]{"", ""}));
        remove.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                tableModel.removeRow(table.convertRowIndexToModel(row));
            }
        });
        panel.add(add);
        panel.add(remove);
        return panel;
    }

    private String valueAt(int row, int column) {
        Object value = tableModel.getValueAt(row, column);
        return value == null ? "" : value.toString().strip();
    }
}

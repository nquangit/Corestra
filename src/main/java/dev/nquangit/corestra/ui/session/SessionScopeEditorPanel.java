package dev.nquangit.corestra.ui.session;

import dev.nquangit.corestra.session.SessionCriterionType;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.List;
import java.util.function.Consumer;

/** Presents alternative scope rules separately while editing their original condition rows. */
final class SessionScopeEditorPanel extends JPanel {
    private final SessionScopeTableModel model;
    private final JTable table;
    private final Consumer<Runnable> mutate;
    private final DefaultListModel<String> groups = new DefaultListModel<>();
    private final JList<String> ruleList = new JList<>(groups);
    private final TableRowSorter<SessionScopeTableModel> sorter;
    private final JLabel heading = new JLabel();
    private final JLabel logic = new JLabel();
    private final JTextArea example = new JTextArea(2, 30);
    private final JTextArea summary = new JTextArea(5, 30);
    private final JButton rename = StyledButton.neutral("Rename rule");
    private final JButton delete = StyledButton.neutral("Remove rule");
    private final JButton move = StyledButton.neutral("Move condition to…");
    private boolean rebuilding;
    private boolean custom;

    SessionScopeEditorPanel(SessionScopeTableModel model, JTable table, Consumer<Runnable> mutate) {
        super(new BorderLayout(0, 8));
        this.model = model;
        this.table = table;
        this.mutate = mutate;
        sorter = new TableRowSorter<>(model);
        for (int column = 0; column < model.getColumnCount(); column++) sorter.setSortable(column, false);
        table.setRowSorter(sorter);
        table.removeColumn(table.getColumnModel().getColumn(SessionScopeTableModel.RULE_COLUMN));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(1).setHeaderValue("Header name (HEADER only)");
        table.getColumnModel().getColumn(0).setPreferredWidth(100);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(2).setPreferredWidth(400);
        ruleList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ruleList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                String group = value.toString();
                setText(SessionScopeTableModel.COMMON.equals(group) ? "Shared conditions (all rules)"
                        : "Rule: " + group);
                setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
                return this;
            }
        });
        ruleList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !rebuilding) {
                stopEditing();
                showSelectedGroup();
            }
        });
        table.getSelectionModel().addListSelectionListener(event -> updateExample());
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(new JLabel("Scope groups"), BorderLayout.NORTH);
        left.add(new JScrollPane(ruleList), BorderLayout.CENTER);
        JPanel actions = new JPanel(new GridLayout(1, 2, 4, 0));
        actions.add(rename);
        actions.add(delete);
        left.add(actions, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout(0, 6));
        JPanel title = new JPanel(new BorderLayout(0, 4));
        title.add(heading, BorderLayout.NORTH);
        title.add(logic, BorderLayout.SOUTH);
        right.add(title, BorderLayout.NORTH);
        right.add(new JScrollPane(table), BorderLayout.CENTER);
        configureReadOnly(example);
        JPanel help = new JPanel(new BorderLayout(0, 4));
        help.add(example, BorderLayout.CENTER);
        help.add(move, BorderLayout.SOUTH);
        right.add(help, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.28);
        split.setDividerLocation(260);
        left.setMinimumSize(new Dimension(250, 120));
        right.setMinimumSize(new Dimension(300, 120));
        add(split, BorderLayout.CENTER);
        configureReadOnly(summary);
        JScrollPane summaryScroll = new JScrollPane(summary);
        summaryScroll.setBorder(BorderFactory.createTitledBorder("Configuration preview — check Saved before enabling"));
        add(summaryScroll, BorderLayout.SOUTH);
        rename.addActionListener(event -> renameSelectedRule());
        delete.addActionListener(event -> mutate.accept(() -> model.removeRule(selectedGroup())));
        move.addActionListener(event -> moveSelectedCondition());
        refresh();
    }

    void refresh() {
        String selected = selectedGroup();
        List<String> names = model.ruleNames();
        rebuilding = true;
        try {
            groups.clear();
            names.forEach(groups::addElement);
            ruleList.setSelectedValue(names.contains(selected) ? selected : SessionScopeTableModel.COMMON, true);
        } finally {
            rebuilding = false;
        }
        showSelectedGroup();
        summary.setText(model.description());
        summary.setCaretPosition(0);
    }

    void selectGroup(String name) {
        ruleList.setSelectedValue(name, true);
    }

    String selectedGroup() {
        String value = ruleList.getSelectedValue();
        return value == null ? SessionScopeTableModel.COMMON : value;
    }

    void selectFirstRule() {
        selectGroup(groups.size() > 1 ? groups.get(1) : SessionScopeTableModel.COMMON);
    }

    void addRule() {
        int row = model.addRule();
        refresh();
        selectGroup(model.ruleAt(row));
        selectModelRow(row);
    }

    void addCondition() {
        model.addCriterion(selectedGroup());
        selectModelRow(model.getRowCount() - 1);
    }

    void removeCondition() {
        int row = table.getSelectedRow();
        if (row >= 0) model.removeCondition(table.convertRowIndexToModel(row));
    }

    void setCustomEnabled(boolean enabled) {
        custom = enabled;
        ruleList.setEnabled(enabled);
        updateActions();
        summary.setEnabled(enabled);
    }

    private void showSelectedGroup() {
        String group = selectedGroup();
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends SessionScopeTableModel, ? extends Integer> entry) {
                return model.ruleAt(entry.getIdentifier()).equals(group);
            }
        });
        boolean common = SessionScopeTableModel.COMMON.equals(group);
        heading.setText(common ? "Shared conditions" : "Conditions for rule: " + group);
        logic.setText(common ? "All shared conditions must match, even without scope rules."
                : "All conditions below must match the same request.");
        updateActions();
        updateExample();
    }

    private void updateActions() {
        boolean namedRule = !SessionScopeTableModel.COMMON.equals(selectedGroup());
        rename.setEnabled(custom && namedRule);
        delete.setEnabled(custom && namedRule);
        move.setEnabled(custom && table.getSelectedRow() >= 0);
    }

    private void updateExample() {
        updateActions();
        int selected = table.getSelectedRow();
        SessionCriterionType type = selected < 0 ? SessionCriterionType.HOST
                : (SessionCriterionType) table.getValueAt(selected, 0);
        String hint = switch (type) {
            case HOST -> "HOST: destination domain/IP, without https://. Example: ^api\\.example\\.test$";
            case PORT -> "PORT: destination port, not Burp's listener. Example: ^(443|8443)$";
            case PROTOCOL -> "PROTOCOL: http or https. Example: ^https$";
            case PATH -> "PATH: excludes the query string. Example: ^/api(?:/|$)";
            case METHOD -> "METHOD: HTTP method. Example: ^(GET|POST)$";
            case HEADER -> "HEADER: enter a header name, e.g. X-User, and a value regex, e.g. ^Alice$. The header must exist.";
            case BODY -> "BODY: request body as text. Example: operation=read";
        };
        example.setText(hint + "\nJava regex: ^…$ matches the whole value; otherwise matches anywhere. (?!) matches nothing.");
    }

    private void renameSelectedRule() {
        String original = selectedGroup();
        String replacement = JOptionPane.showInputDialog(this, "Rule name:", original);
        if (replacement == null) return;
        try {
            model.validateRuleName(original, replacement);
            mutate.accept(() -> {
                model.renameRule(original, replacement.strip());
                refresh();
                selectGroup(replacement.strip());
            });
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Invalid rule name", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void moveSelectedCondition() {
        stopEditing();
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        JComboBox<String> destination = new JComboBox<>(model.ruleNames().stream()
                .filter(name -> !name.equals(selectedGroup())).toArray(String[]::new));
        destination.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);
                setText(SessionScopeTableModel.COMMON.equals(value) ? "Shared conditions (all rules)"
                        : "Rule: " + value);
                return this;
            }
        });
        if (destination.getItemCount() == 0) {
            JOptionPane.showMessageDialog(this, "Add a scope rule first, then move the condition into it.");
            return;
        }
        if (JOptionPane.showConfirmDialog(this, destination, "Move selected condition to rule",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        String target = (String) destination.getSelectedItem();
        mutate.accept(() -> {
            model.moveCondition(modelRow, target);
            refresh();
            selectGroup(target);
        });
    }

    private void selectModelRow(int row) {
        int view = table.convertRowIndexToView(row);
        if (view >= 0) table.setRowSelectionInterval(view, view);
    }

    private void stopEditing() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
    }

    private static void configureReadOnly(JTextArea area) {
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setFont(javax.swing.UIManager.getFont("Label.font"));
    }
}

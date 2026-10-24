package dev.nquangit.corestra.ui.session;

import dev.nquangit.corestra.persistence.ProjectSettingsRepository;
import dev.nquangit.corestra.session.SessionCriterionType;
import dev.nquangit.corestra.session.SessionScopeMode;
import dev.nquangit.corestra.session.SessionSelectionConfig;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Edits project-wide live-request session selection independently of individual profiles. */
final class SessionSelectionSettingsDialog extends JDialog {
    private final SessionScopeTableModel model = new SessionScopeTableModel();
    private final JTable table = new JTable(model);
    private final SessionScopeEditorPanel scopeEditor = new SessionScopeEditorPanel(model, table, this::mutate);

    static void open(Component parent, ProjectSettingsRepository repository) {
        new SessionSelectionSettingsDialog(parent, repository).setVisible(true);
    }

    private SessionSelectionSettingsDialog(Component parent, ProjectSettingsRepository repository) {
        super(SwingUtilities.getWindowAncestor(parent), "Send with session — project settings", ModalityType.APPLICATION_MODAL);
        SessionSelectionConfig current = repository.load().sessionSelection();
        JCheckBox enabled = new JCheckBox("Enable session selection for matching live requests", current.enabled());
        JComboBox<SessionScopeMode> mode = new JComboBox<>(SessionScopeMode.values());
        mode.setSelectedItem(current.scope().mode());
        JTextField stripHeader = new JTextField(current.stripHeader(), 22);
        JSpinner timeout = new JSpinner(new SpinnerNumberModel(current.timeoutSeconds(), 1, 3600, 1));
        Map<String, JCheckBox> tools = new LinkedHashMap<>();
        JPanel toolOptions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (String name : SessionSelectionConfig.SUPPORTED_TOOLS) {
            JCheckBox box = new JCheckBox(name, current.toolTypes().contains(name));
            tools.put(name, box); toolOptions.add(box);
        }
        model.load(current.scope());
        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(new JComboBox<>(SessionCriterionType.values())));
        JPanel content = new JPanel(new BorderLayout(8, 10));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JPanel header = new JPanel(new GridLayout(0, 1, 0, 6));
        header.add(enabled);
        header.add(new JLabel("Choose an enabled, unexpired existing session. Cancel, timeout or no usable session stops the request."));
        header.add(new JLabel("Selection scope overrides the chosen profile's normal scope for this request. Its tool filters still apply."));
        header.add(toolOptions);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        options.add(new JLabel("Scope:")); options.add(mode);
        options.add(new JLabel("Timeout (s):")); options.add(timeout);
        options.add(new JLabel("Strip header (blank = keep):")); options.add(stripHeader);
        header.add(options);
        content.add(header, BorderLayout.NORTH);
        JPanel scope = new JPanel(new BorderLayout(0, 6));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton add = StyledButton.primary("Add condition");
        JButton remove = StyledButton.neutral("Remove condition");
        JButton rule = StyledButton.neutral("Add scope rule");
        JButton preset = StyledButton.neutral("Use X-User-Selection preset");
        add.addActionListener(event -> mutate(scopeEditor::addCondition));
        remove.addActionListener(event -> mutate(scopeEditor::removeCondition));
        rule.addActionListener(event -> mutate(scopeEditor::addRule));
        preset.addActionListener(event -> {
            stopEditing();
            model.load(SessionSelectionConfig.disabled().scope());
            mode.setSelectedItem(SessionScopeMode.CUSTOM);
            stripHeader.setText("X-User-Selection");
            scopeEditor.refresh();
        });
        actions.add(add); actions.add(remove); actions.add(rule); actions.add(preset);
        scope.add(actions, BorderLayout.NORTH); scope.add(scopeEditor, BorderLayout.CENTER);
        content.add(scope, BorderLayout.CENTER);
        Runnable updateMode = () -> {
            boolean custom = mode.getSelectedItem() == SessionScopeMode.CUSTOM;
            scopeEditor.setCustomEnabled(custom); table.setEnabled(custom);
            add.setEnabled(custom); remove.setEnabled(custom); rule.setEnabled(custom);
        };
        mode.addActionListener(event -> updateMode.run());
        scopeEditor.refresh(); updateMode.run();
        JLabel error = new JLabel(" ");
        JButton save = StyledButton.success("Save");
        JButton cancel = StyledButton.neutral("Cancel");
        save.addActionListener(event -> {
            try {
                stopEditing(); timeout.commitEdit();
                List<String> selectedTools = tools.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                        .map(Map.Entry::getKey).toList();
                SessionSelectionConfig config = new SessionSelectionConfig(enabled.isSelected(),
                        model.scope((SessionScopeMode) mode.getSelectedItem()), selectedTools,
                        (Integer) timeout.getValue(), stripHeader.getText());
                repository.save(repository.load().withSessionSelection(config));
                dispose();
            } catch (IllegalArgumentException | java.text.ParseException exception) {
                error.setText(exception.getMessage() == null ? "Check scope, tools and timeout." : exception.getMessage());
            }
        });
        cancel.addActionListener(event -> dispose());
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(error, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT)); buttons.add(cancel); buttons.add(save);
        footer.add(buttons, BorderLayout.EAST); content.add(footer, BorderLayout.SOUTH);
        setContentPane(content); getRootPane().setDefaultButton(save);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE); setSize(1100, 680); setLocationRelativeTo(parent);
    }

    private void mutate(Runnable change) {
        stopEditing(); change.run(); scopeEditor.refresh();
    }

    private void stopEditing() {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
            throw new IllegalArgumentException("Finish editing the scope condition first.");
        }
    }
}

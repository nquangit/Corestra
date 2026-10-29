package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StepTriggerRule;
import dev.nquangit.corestra.flow.StepTriggerRule.MatchCriterion;
import dev.nquangit.corestra.flow.StepTriggerRule.MatchCriterion.MatchType;
import dev.nquangit.corestra.ui.component.StyledButton;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.IntStream;

/**
 * Step-level auto-trigger configuration panel.
 *
 * <p>Shows the auto-trigger rule for the currently selected step. All controls auto-save
 * on change — there is no Apply button. A {@code refreshing} flag prevents listener loops.</p>
 *
 * <p>When no step is selected the panel is disabled and shows a placeholder label.
 * Match rules are displayed in a table; Add, Edit, and Remove buttons manage the list.</p>
 */
public final class TriggerPanel extends JPanel {

    private static final String[][] TOOL_ENTRIES = {
            {"Proxy",    "PROXY"},
            {"Repeater", "REPEATER"},
            {"Scanner",  "SCANNER"},
            {"Intruder", "INTRUDER"},
            {"Target",   "TARGET"},
            {"Logger",   "LOGGER"},
            {"Extensions / Extender", "EXTENSIONS"},
    };

    private final FlowWorkspaceController controller;
    private final TriggerInputsPanel inputPanel;

    private final JLabel stepNameLabel = new JLabel("No step selected", SwingConstants.LEFT);
    private final JCheckBox enabledBox = new JCheckBox("Enable auto-trigger for this step");
    private final Map<String, JCheckBox> toolBoxes = new LinkedHashMap<>();
    private final DefaultTableModel criteriaModel = new DefaultTableModel(
            new String[]{"Type", "Pattern"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable criteriaTable = new JTable(criteriaModel);

    private boolean refreshing = false;
    private String displayedFlowId;
    private String displayedStepId;

    // Scope controls
    private final JRadioButton scopeMatchedBtn  = new JRadioButton("Up to this step");
    private final JRadioButton scopeSpecificBtn = new JRadioButton("Up to step:");
    private final JRadioButton scopeFullBtn     = new JRadioButton("Full flow");
    private final JComboBox<ScopeStepEntry> scopeStepCombo = new JComboBox<>();
    private final JLabel scopeWarningLabel = new JLabel(" ");
    private final JCheckBox inheritSessionBox = new JCheckBox("Use session from triggering request for the entire execution");
    private final JTextField sessionOriginsField = new JTextField(32);

    /**
     * Creates a trigger panel wired to the supplied controller.
     *
     * @param controller the workspace controller that owns step selection state
     */
    public TriggerPanel(FlowWorkspaceController controller) {
        this(controller, null);
    }

    /** Creates a trigger editor with optional shared profile sources. */
    public TriggerPanel(FlowWorkspaceController controller, dev.nquangit.corestra.ui.access.AccessUiController access) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");
        this.inputPanel = new TriggerInputsPanel(access, this::saveTrigger);

        for (String[] e : TOOL_ENTRIES) {
            JCheckBox cb = new JCheckBox(e[0]);
            cb.setOpaque(false); cb.setFocusPainted(false);
            toolBoxes.put(e[1], cb);
        }
        toolBoxes.get("EXTENSIONS").setToolTipText("Requests reported by Burp as Extensions; Corestra's own sends are excluded. Enable explicitly and keep host/path criteria specific.");

        criteriaTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        criteriaTable.setFillsViewportHeight(true);
        criteriaTable.getTableHeader().setReorderingAllowed(false);
        criteriaTable.setPreferredScrollableViewportSize(new Dimension(0, 100));

        JScrollPane content = new JScrollPane(buildContent());
        content.setBorder(BorderFactory.createEmptyBorder());
        content.getVerticalScrollBar().setUnitIncrement(16);
        add(content, BorderLayout.CENTER);
        wireListeners();
        controller.addFlowChangeListener(this::refresh);
        refresh();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────────────────

    private JPanel buildContent() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        Font base = UIManager.getFont("Label.font");
        stepNameLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        if (base != null) stepNameLabel.setFont(base.deriveFont(Font.ITALIC, base.getSize2D() - 0.5f));
        stepNameLabel.setAlignmentX(LEFT_ALIGNMENT);
        p.add(stepNameLabel);
        p.add(Box.createVerticalStrut(6));

        enabledBox.setOpaque(false); enabledBox.setFocusPainted(false);
        enabledBox.setAlignmentX(LEFT_ALIGNMENT);
        p.add(enabledBox);
        p.add(Box.createVerticalStrut(10));

        p.add(dimLabel("Source tools (no selection = no trigger; Extensions requires opt-in):"));
        p.add(Box.createVerticalStrut(3));
        JPanel toolRow = new JPanel();
        toolRow.setLayout(new BoxLayout(toolRow, BoxLayout.X_AXIS));
        toolRow.setOpaque(false); toolRow.setAlignmentX(LEFT_ALIGNMENT);
        toolRow.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        for (JCheckBox cb : toolBoxes.values()) {
            toolRow.add(cb); toolRow.add(Box.createHorizontalStrut(6));
        }
        p.add(toolRow);
        p.add(Box.createVerticalStrut(10));

        p.add(dimLabel("When triggered, execute:"));
        p.add(Box.createVerticalStrut(3));
        p.add(buildScopeRow());
        p.add(Box.createVerticalStrut(2));
        scopeWarningLabel.setForeground(new Color(0xF59E0B));
        scopeWarningLabel.setAlignmentX(LEFT_ALIGNMENT);
        Font warnFont = UIManager.getFont("Label.font");
        if (warnFont != null) scopeWarningLabel.setFont(warnFont.deriveFont(warnFont.getSize2D() - 1f));
        p.add(scopeWarningLabel);
        p.add(Box.createVerticalStrut(10));

        p.add(inputPanel);
        p.add(Box.createVerticalStrut(10));

        inheritSessionBox.setOpaque(false);
        inheritSessionBox.setAlignmentX(LEFT_ALIGNMENT);
        inheritSessionBox.setToolTipText("Pins the initiating Session Profile and tool across all executed steps; session changes stop the run");
        p.add(inheritSessionBox);
        p.add(Box.createVerticalStrut(3));
        p.add(dimLabel("Additional session origins (comma-separated; empty = initiating origin only):"));
        sessionOriginsField.setMaximumSize(new Dimension(Integer.MAX_VALUE, sessionOriginsField.getPreferredSize().height));
        sessionOriginsField.setAlignmentX(LEFT_ALIGNMENT);
        sessionOriginsField.setToolTipText("Exact origins only, e.g. https://api.example.test:443, https://files.example.test:443. Press Enter to save.");
        p.add(sessionOriginsField);
        p.add(Box.createVerticalStrut(10));
        p.add(dimLabel("Match rules (ALL must match; empty list = all requests):"));
        p.add(Box.createVerticalStrut(3));

        JScrollPane scroll = new JScrollPane(criteriaTable);
        scroll.setAlignmentX(LEFT_ALIGNMENT);
        scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
        p.add(scroll);
        p.add(Box.createVerticalStrut(4));

        StyledButton addBtn    = StyledButton.primary("+ Add");
        StyledButton editBtn   = StyledButton.neutral("Edit");
        StyledButton removeBtn = StyledButton.danger("− Remove");
        addBtn.addActionListener(e -> addCriterion());
        editBtn.addActionListener(e -> editCriterion());
        removeBtn.addActionListener(e -> removeCriterion());

        JPanel btnRow = new JPanel();
        btnRow.setLayout(new BoxLayout(btnRow, BoxLayout.X_AXIS));
        btnRow.setOpaque(false); btnRow.setAlignmentX(LEFT_ALIGNMENT);
        btnRow.add(addBtn);
        btnRow.add(Box.createHorizontalStrut(4)); btnRow.add(editBtn);
        btnRow.add(Box.createHorizontalStrut(4)); btnRow.add(removeBtn);
        p.add(btnRow);

        return p;
    }

    private JLabel dimLabel(String text) {
        Font base = UIManager.getFont("Label.font");
        JLabel lbl = new JLabel(text);
        if (base != null) lbl.setFont(base.deriveFont(base.getSize2D() - 0.5f));
        lbl.setForeground(UIManager.getColor("Label.disabledForeground"));
        lbl.setAlignmentX(LEFT_ALIGNMENT);
        return lbl;
    }

    private JPanel buildScopeRow() {
        ButtonGroup bg = new ButtonGroup();
        bg.add(scopeMatchedBtn); bg.add(scopeSpecificBtn); bg.add(scopeFullBtn);
        scopeMatchedBtn.setOpaque(false);  scopeMatchedBtn.setFocusPainted(false);
        scopeSpecificBtn.setOpaque(false); scopeSpecificBtn.setFocusPainted(false);
        scopeFullBtn.setOpaque(false);     scopeFullBtn.setFocusPainted(false);

        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false); row.setAlignmentX(LEFT_ALIGNMENT);
        row.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
        row.add(scopeMatchedBtn);
        row.add(Box.createHorizontalStrut(8));
        row.add(scopeSpecificBtn);
        row.add(Box.createHorizontalStrut(4));
        row.add(scopeStepCombo);
        row.add(Box.createHorizontalStrut(8));
        row.add(scopeFullBtn);
        return row;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

    private void wireListeners() {
        // enabledBox and tool checkboxes save the trigger rule (enabled flag + tool filter).
        // Criteria are saved directly by add/edit/remove actions.
        inheritSessionBox.addActionListener(e -> { sessionOriginsField.setEnabled(inheritSessionBox.isSelected()); saveTrigger(); });
        sessionOriginsField.addActionListener(e -> saveTrigger());
        sessionOriginsField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent event) { saveTrigger(); }
        });
        enabledBox.addActionListener(e -> saveTrigger());
        for (JCheckBox cb : toolBoxes.values()) cb.addActionListener(e -> saveTrigger());
        scopeMatchedBtn.addActionListener(e -> { scopeStepCombo.setEnabled(false); saveTrigger(); });
        scopeSpecificBtn.addActionListener(e -> { scopeStepCombo.setEnabled(true); saveTrigger(); });
        scopeFullBtn.addActionListener(e -> { scopeStepCombo.setEnabled(false); saveTrigger(); });
        scopeStepCombo.addActionListener(e -> saveTrigger());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Criterion actions
    // ─────────────────────────────────────────────────────────────────────────

    private void addCriterion() {
        FlowStep step = controller.selectedStep();
        if (step == null) return;
        showCriterionDialog(null).ifPresent(c -> {
            List<MatchCriterion> updated = new ArrayList<>(step.triggerRule().criteria());
            updated.add(c);
            commitCriteria(step, updated);
        });
    }

    private void editCriterion() {
        FlowStep step = controller.selectedStep();
        int row = criteriaTable.getSelectedRow();
        if (step == null || row < 0 || row >= step.triggerRule().criteria().size()) return;
        showCriterionDialog(step.triggerRule().criteria().get(row)).ifPresent(c -> {
            List<MatchCriterion> updated = new ArrayList<>(step.triggerRule().criteria());
            updated.set(row, c);
            commitCriteria(step, updated);
        });
    }

    private void removeCriterion() {
        FlowStep step = controller.selectedStep();
        int row = criteriaTable.getSelectedRow();
        if (step == null || row < 0 || row >= step.triggerRule().criteria().size()) return;
        List<MatchCriterion> updated = new ArrayList<>(step.triggerRule().criteria());
        updated.remove(row);
        commitCriteria(step, updated);
    }

    private void commitCriteria(FlowStep step, List<MatchCriterion> newCriteria) {
        StepTriggerRule rule = step.triggerRule();
        StepTriggerRule updated = new StepTriggerRule(
                rule.enabled(), rule.allowedToolTypes(), List.copyOf(newCriteria),
                rule.scope(), rule.stopAtStepId(), rule.inheritSession(), rule.sessionOrigins(), rule.inputBindings());
        commitRule(step, updated);
    }

    private Optional<MatchCriterion> showCriterionDialog(MatchCriterion existing) {
        JLabel typeLabel     = new JLabel("Type:");
        JLabel selectorLabel = new JLabel("Header name:");
        JLabel patternLabel  = new JLabel("Pattern (regex):");

        MatchType[] types = {MatchType.HOST, MatchType.PATH, MatchType.METHOD, MatchType.HEADER, MatchType.BODY};

        JTextField selectorField = new JTextField(20);
        JTextField patternField  = new JTextField(20);

        int selectedIdx = 0;
        if (existing != null) {
            for (int i = 0; i < types.length; i++) {
                if (types[i] == existing.type()) { selectedIdx = i; break; }
            }
            selectorField.setText(existing.selector());
            patternField.setText(existing.pattern());
        }

        // Re-implement as JComboBox for simplicity
        javax.swing.JComboBox<String> typeCombo = new javax.swing.JComboBox<>(
                new String[]{"Host", "Path", "Method", "Header", "Body"});
        typeCombo.setSelectedIndex(selectedIdx);
        typeCombo.addActionListener(e -> {
            boolean isHeader = types[typeCombo.getSelectedIndex()] == MatchType.HEADER;
            selectorLabel.setEnabled(isHeader);
            selectorField.setEnabled(isHeader);
        });
        // Trigger initial state
        boolean isHeader = types[typeCombo.getSelectedIndex()] == MatchType.HEADER;
        selectorLabel.setEnabled(isHeader);
        selectorField.setEnabled(isHeader);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST; lc.insets = new Insets(3, 0, 3, 8); lc.gridx = 0;
        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1;
        fc.insets = new Insets(3, 0, 3, 0); fc.gridx = 1;

        lc.gridy = 0; fc.gridy = 0; form.add(typeLabel, lc);     form.add(typeCombo, fc);
        lc.gridy = 1; fc.gridy = 1; form.add(selectorLabel, lc); form.add(selectorField, fc);
        lc.gridy = 2; fc.gridy = 2; form.add(patternLabel, lc);  form.add(patternField, fc);

        int result = JOptionPane.showConfirmDialog(this, form,
                existing == null ? "Add match rule" : "Edit match rule",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return Optional.empty();

        MatchType type = types[typeCombo.getSelectedIndex()];
        String selector = type == MatchType.HEADER ? selectorField.getText().strip() : "";
        return Optional.of(new MatchCriterion(type, selector, patternField.getText().strip()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Refresh
    // ─────────────────────────────────────────────────────────────────────────

    private void refresh() {
        refreshing = true;
        try {
            FlowStep step = controller.selectedStep();
            FlowDefinition active = controller.activeFlow();
            boolean hasStep = step != null && active != null
                    && active.steps().stream().anyMatch(s -> s.id().equals(step.id()));

            displayedFlowId = hasStep ? active.id() : null;
            displayedStepId = hasStep ? step.id() : null;
            inputPanel.refresh(hasStep ? active : null, hasStep ? step : null);
            enabledBox.setEnabled(hasStep);
            inheritSessionBox.setEnabled(hasStep);
            sessionOriginsField.setEnabled(hasStep && inheritSessionBox.isSelected());
            toolBoxes.values().forEach(cb -> cb.setEnabled(hasStep));

            if (!hasStep) {
                stepNameLabel.setText(active != null
                        ? "Select a step to configure its trigger" : "No flow selected");
                enabledBox.setSelected(false);
                inheritSessionBox.setSelected(false); sessionOriginsField.setText("");
                toolBoxes.values().forEach(cb -> cb.setSelected(false));
                criteriaModel.setRowCount(0);
                scopeMatchedBtn.setSelected(true);
                scopeStepCombo.removeAllItems();
                scopeStepCombo.setEnabled(false);
                scopeWarningLabel.setText(" ");
                scopeMatchedBtn.setEnabled(false);
                scopeSpecificBtn.setEnabled(false);
                scopeFullBtn.setEnabled(false);
                return;
            }

            stepNameLabel.setText("Step: " + step.name());
            StepTriggerRule rule = step.triggerRule();
            enabledBox.setSelected(rule.enabled());
            inheritSessionBox.setSelected(rule.inheritSession());
            sessionOriginsField.setText(String.join(", ", rule.sessionOrigins()));
            sessionOriginsField.setEnabled(rule.inheritSession());

            List<String> allowed = rule.allowedToolTypes();
            toolBoxes.forEach((type, cb) ->
                    cb.setSelected("EXTENSIONS".equals(type)
                            ? allowed.contains(type) || allowed.contains("EXTENDER")
                            : allowed.isEmpty() || allowed.contains(type)));

            // Re-enable scope controls for a valid step.
            scopeMatchedBtn.setEnabled(true);
            scopeSpecificBtn.setEnabled(true);
            scopeFullBtn.setEnabled(true);

            // Populate scope combo with steps at or after the selected step.
            List<FlowStep> allSteps = active.steps();
            int matchedIdx = -1;
            for (int i = 0; i < allSteps.size(); i++) {
                if (allSteps.get(i).id().equals(step.id())) { matchedIdx = i; break; }
            }
            scopeStepCombo.removeAllItems();
            for (int i = matchedIdx; i >= 0 && i < allSteps.size(); i++) {
                scopeStepCombo.addItem(new ScopeStepEntry(allSteps.get(i).id(), allSteps.get(i).name()));
            }

            // Set scope radio and combo from saved rule.
            StepTriggerRule.ExecutionScope savedScope = rule.scope();
            String savedStopId = rule.stopAtStepId();
            switch (savedScope) {
                case MATCHED_STEP -> { scopeMatchedBtn.setSelected(true); scopeStepCombo.setEnabled(false); }
                case FULL_FLOW    -> { scopeFullBtn.setSelected(true);    scopeStepCombo.setEnabled(false); }
                case STOP_AT_STEP -> {
                    scopeSpecificBtn.setSelected(true); scopeStepCombo.setEnabled(true);
                    for (int i = 0; i < scopeStepCombo.getItemCount(); i++) {
                        if (scopeStepCombo.getItemAt(i).id.equals(savedStopId)) {
                            scopeStepCombo.setSelectedIndex(i); break;
                        }
                    }
                }
            }

            // Reorder warning: savedStopId present but not in combo (step moved before match).
            boolean reorderWarning = savedScope == StepTriggerRule.ExecutionScope.STOP_AT_STEP
                    && !savedStopId.isEmpty()
                    && IntStream.range(0, scopeStepCombo.getItemCount())
                            .noneMatch(i -> scopeStepCombo.getItemAt(i).id.equals(savedStopId));
            scopeWarningLabel.setText(reorderWarning
                    ? "⚠ Stop step is no longer reachable — will fall back to \"Up to this step\""
                    : " ");

            criteriaModel.setRowCount(0);
            for (MatchCriterion c : rule.criteria()) {
                criteriaModel.addRow(new Object[]{c.displayType(), c.pattern()});
            }
        } finally {
            refreshing = false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Save
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Saves the enabled flag and tool filter from the UI controls.
     * Criteria changes go through {@link #commitCriteria} directly.
     */
    private void saveTrigger() {
        if (refreshing) return;
        FlowStep step = controller.selectedStep();
        FlowDefinition active = controller.activeFlow();
        if (step == null || active == null) return;
        if (!Objects.equals(displayedFlowId, active.id()) || !Objects.equals(displayedStepId, step.id())) return;
        if (active.steps().stream().noneMatch(s -> s.id().equals(step.id()))) return;

        List<String> selectedTools = new ArrayList<>();
        toolBoxes.forEach((type, cb) -> { if (cb.isSelected()) selectedTools.add(type); });
        // Empty legacy filters retain "all ordinary tools"; Extensions requires an explicit opt-in.
        List<String> allowedTools = selectedTools.isEmpty() ? List.of(StepTriggerRule.NO_TOOLS)
                : selectedTools.size() == TOOL_ENTRIES.length - 1 && !selectedTools.contains("EXTENSIONS")
                ? List.of() : List.copyOf(selectedTools);

        StepTriggerRule.ExecutionScope scope = scopeMatchedBtn.isSelected()
                ? StepTriggerRule.ExecutionScope.MATCHED_STEP
                : scopeFullBtn.isSelected()
                        ? StepTriggerRule.ExecutionScope.FULL_FLOW
                        : StepTriggerRule.ExecutionScope.STOP_AT_STEP;
        ScopeStepEntry selectedStop = (ScopeStepEntry) scopeStepCombo.getSelectedItem();
        String stopAtStepId = (scope == StepTriggerRule.ExecutionScope.STOP_AT_STEP && selectedStop != null)
                ? selectedStop.id : "";

        StepTriggerRule current = step.triggerRule();
        StepTriggerRule updated;
        try {
            List<String> origins = java.util.Arrays.stream(sessionOriginsField.getText().split(","))
                    .map(String::strip).filter(value -> !value.isEmpty()).toList();
            updated = new StepTriggerRule(enabledBox.isSelected(), allowedTools, current.criteria(), scope,
                    stopAtStepId, inheritSessionBox.isSelected(), origins, inputPanel.bindings());
        } catch (IllegalArgumentException exception) {
            JOptionPane.showMessageDialog(this, exception.getMessage(), "Invalid session origin", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (updated.equals(current)) return;
        commitRule(step, updated);
    }

    private void commitRule(FlowStep step, StepTriggerRule newRule) {
        FlowDefinition active = controller.activeFlow();
        if (active == null) return;
        FlowStep updated = step.withTriggerRule(newRule);
        List<FlowStep> steps = new ArrayList<>(active.steps());
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(updated.id())) { steps.set(i, updated); break; }
        }
        controller.updateFlow(new FlowDefinition(
                active.id(), active.name(), active.description(), active.enabled(),
                steps, active.rules(), active.aggregation(), active.triggerConfig()));
        controller.setSelectedStep(updated);
    }

    private static final class ScopeStepEntry {
        final String id;
        final String name;
        ScopeStepEntry(String id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name; }
    }
}

package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.AggregationPlan;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.FlowTriggerConfig;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Flow-level automation settings panel: master enable switch, session-handling integration,
 * and aggregation configuration. All controls auto-save on change — no Apply button.
 *
 * <p>Aggregation is considered active when a final step target is configured (finalStepId or
 * useTriggeredStep). Per-step participation is controlled by
 * {@link FlowStep#includeInAggregation()} — there is no separate flow-level enable toggle.</p>
 *
 * <p>A {@code refreshing} flag prevents listener loops during programmatic refresh.</p>
 */
public final class AggregationPanel extends JPanel {

    private final FlowWorkspaceController controller;

    private final JCheckBox flowEnabledBox;
    private final JCheckBox sessionHandlingBox;
    private final JCheckBox includeInAggBox;
    private final JComboBox<AggregationPlan.Mode> modeCombo;
    private final JComboBox<StepItem> finalStepCombo;
    private final JTextField varsField;
    private final JLabel varsLabel;
    private final JLabel errorLabel;

    private boolean refreshing = false;

    /**
     * Creates an aggregation panel wired to the supplied controller.
     *
     * @param controller the workspace controller that owns the active flow state
     * @throws NullPointerException if {@code controller} is {@code null}
     */
    public AggregationPanel(FlowWorkspaceController controller) {
        super(new BorderLayout());
        this.controller = Objects.requireNonNull(controller, "controller");

        flowEnabledBox     = new JCheckBox("Enable this flow");
        sessionHandlingBox = new JCheckBox("Register as session-handling prerequisite");
        includeInAggBox    = new JCheckBox("Include this step's response in aggregation result");
        modeCombo          = new JComboBox<>(AggregationPlan.Mode.values());
        finalStepCombo     = new JComboBox<>();
        varsField          = new JTextField();
        varsLabel          = new JLabel("Variables (comma-separated):");
        errorLabel         = new JLabel(" ");
        errorLabel.setForeground(new java.awt.Color(0xEF4444));

        flowEnabledBox.setOpaque(false); flowEnabledBox.setFocusPainted(false);
        flowEnabledBox.setToolTipText(
                "Master switch — when disabled this flow is skipped by session handling and auto-trigger");
        sessionHandlingBox.setOpaque(false); sessionHandlingBox.setFocusPainted(false);
        includeInAggBox.setOpaque(false); includeInAggBox.setFocusPainted(false);
        includeInAggBox.setToolTipText(
                "When this step runs via auto-trigger, append its response body to the aggregation result");

        add(buildForm(), BorderLayout.NORTH);
        wireListeners();
        controller.addFlowChangeListener(this::refresh);
        refresh();
    }

    // -------------------------------------------------------------------------
    // Form
    // -------------------------------------------------------------------------

    private JPanel buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        GridBagConstraints spanc = new GridBagConstraints();
        spanc.gridx = 0; spanc.gridwidth = 2;
        spanc.fill = GridBagConstraints.HORIZONTAL;

        GridBagConstraints lc = new GridBagConstraints();
        lc.anchor = GridBagConstraints.WEST;
        lc.insets = new Insets(2, 0, 2, 8);
        lc.gridx = 0;

        GridBagConstraints fc = new GridBagConstraints();
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1;
        fc.insets = new Insets(2, 0, 2, 0);
        fc.gridx = 1;

        // Row 0: flow enabled
        spanc.gridy = 0; spanc.insets = new Insets(2, 0, 8, 0);
        form.add(flowEnabledBox, spanc);

        // Row 1: session handling
        spanc.gridy = 1; spanc.insets = new Insets(2, 0, 8, 0);
        form.add(sessionHandlingBox, spanc);

        // Row 2: separator label
        spanc.gridy = 2; spanc.insets = new Insets(0, 0, 4, 0);
        JLabel sepLabel = new JLabel("Response aggregation:");
        sepLabel.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        form.add(sepLabel, spanc);

        // Row 3: per-step include toggle
        spanc.gridy = 3; spanc.insets = new Insets(2, 0, 6, 0);
        form.add(includeInAggBox, spanc);

        // Row 4: mode
        lc.gridy = 4; fc.gridy = 4;
        form.add(new JLabel("Mode:"), lc); form.add(modeCombo, fc);

        // Row 5: final step
        lc.gridy = 5; fc.gridy = 5;
        form.add(new JLabel("Final step:"), lc); form.add(finalStepCombo, fc);

        // Row 6: variables (conditional)
        lc.gridy = 6; fc.gridy = 6;
        form.add(varsLabel, lc); form.add(varsField, fc);

        // Row 7: error label
        spanc.gridy = 7; spanc.insets = new Insets(2, 0, 0, 0);
        form.add(errorLabel, spanc);

        return form;
    }

    // -------------------------------------------------------------------------
    // Listeners & auto-save
    // -------------------------------------------------------------------------

    private void wireListeners() {
        flowEnabledBox.addActionListener(e -> saveFlow());
        sessionHandlingBox.addActionListener(e -> saveFlow());
        includeInAggBox.addActionListener(e -> saveStep());
        modeCombo.addActionListener(e -> { updateVarsVisibility(); saveAgg(); });
        finalStepCombo.addActionListener(e -> saveAgg());
        FocusAdapter fs = new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { saveAgg(); }
        };
        varsField.addFocusListener(fs);
        varsField.addActionListener(e -> saveAgg());
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------

    private void refresh() {
        refreshing = true;
        try {
            FlowDefinition active = controller.activeFlow();
            FlowStep step = controller.selectedStep();
            boolean hasFlow = active != null;
            boolean hasStep = hasFlow && step != null
                    && active.steps().stream().anyMatch(s -> s.id().equals(step.id()));

            flowEnabledBox.setEnabled(hasFlow);
            sessionHandlingBox.setEnabled(hasFlow);
            includeInAggBox.setEnabled(hasStep);
            modeCombo.setEnabled(hasFlow);
            finalStepCombo.setEnabled(hasFlow);
            varsField.setEnabled(hasFlow);
            errorLabel.setText(" ");

            includeInAggBox.setSelected(hasStep && step.includeInAggregation());

            if (!hasFlow) {
                flowEnabledBox.setSelected(false);
                sessionHandlingBox.setSelected(false);
                modeCombo.setSelectedIndex(0);
                finalStepCombo.setModel(new DefaultComboBoxModel<>());
                varsField.setText("");
                return;
            }

            flowEnabledBox.setSelected(active.enabled());
            sessionHandlingBox.setSelected(active.triggerConfig().sessionHandlingIntegrationEnabled());

            AggregationPlan plan = active.aggregation();
            modeCombo.setSelectedItem(plan.mode());

            DefaultComboBoxModel<StepItem> stepModel = new DefaultComboBoxModel<>();
            stepModel.addElement(new StepItem(null, "Triggered step (relative)", true));
            stepModel.addElement(new StepItem(null, "— none —", false));
            for (FlowStep s : active.steps()) stepModel.addElement(new StepItem(s.id(), s.name(), false));
            finalStepCombo.setModel(stepModel);

            if (plan.useTriggeredStep()) {
                finalStepCombo.setSelectedIndex(0);
            } else if (!plan.finalStepId().isEmpty()) {
                for (int i = 2; i < stepModel.getSize(); i++) {
                    if (plan.finalStepId().equals(stepModel.getElementAt(i).id)) {
                        finalStepCombo.setSelectedIndex(i); break;
                    }
                }
            } else {
                finalStepCombo.setSelectedIndex(1); // "— none —"
            }
            varsField.setText(String.join(", ", plan.selectedExtractVariables()));
        } finally {
            refreshing = false;
        }
        updateVarsVisibility();
    }

    private void updateVarsVisibility() {
        boolean show = modeCombo.getSelectedItem() == AggregationPlan.Mode.APPEND_SELECTED_EXTRACTS;
        varsLabel.setVisible(show);
        varsField.setVisible(show);
    }

    // -------------------------------------------------------------------------
    // Save
    // -------------------------------------------------------------------------

    private void saveStep() {
        if (refreshing) return;
        FlowStep step = controller.selectedStep();
        FlowDefinition active = controller.activeFlow();
        if (step == null || active == null) return;
        if (active.steps().stream().noneMatch(s -> s.id().equals(step.id()))) return;
        FlowStep updated = step.withIncludeInAggregation(includeInAggBox.isSelected());
        List<FlowStep> steps = new ArrayList<>(active.steps());
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(updated.id())) { steps.set(i, updated); break; }
        }
        controller.updateFlow(new FlowDefinition(
                active.id(), active.name(), active.description(), active.enabled(),
                steps, active.rules(), active.aggregation(), active.triggerConfig()));
        controller.setSelectedStep(updated);
    }

    private void saveFlow() {
        if (refreshing) return;
        FlowDefinition active = controller.activeFlow();
        if (active == null) return;
        FlowTriggerConfig old = active.triggerConfig();
        FlowTriggerConfig newCfg = new FlowTriggerConfig(
                old.prerequisiteStepIds(), old.prerequisiteFlowIds(), old.recoveryFlowId(),
                sessionHandlingBox.isSelected(), old.followUpHooks());
        controller.updateFlow(new FlowDefinition(
                active.id(), active.name(), active.description(),
                flowEnabledBox.isSelected(),
                active.steps(), active.rules(), active.aggregation(), newCfg));
    }

    private void saveAgg() {
        if (refreshing) return;
        FlowDefinition active = controller.activeFlow();
        if (active == null) return;

        AggregationPlan.Mode mode = (AggregationPlan.Mode) modeCombo.getSelectedItem();
        StepItem sel = (StepItem) finalStepCombo.getSelectedItem();
        boolean useTriggeredStep = sel != null && sel.useTriggeredStep;
        String finalStepId = (sel != null && !sel.useTriggeredStep && sel.id != null) ? sel.id : "";
        // Aggregation is considered enabled whenever a target is configured.
        boolean aggEnabled = useTriggeredStep || !finalStepId.isEmpty();
        errorLabel.setText(" ");

        List<String> vars = new ArrayList<>();
        for (String p : varsField.getText().split(",")) {
            String t = p.strip();
            if (!t.isEmpty()) vars.add(t);
        }

        controller.updateFlow(new FlowDefinition(
                active.id(), active.name(), active.description(), active.enabled(),
                active.steps(), active.rules(),
                new AggregationPlan(aggEnabled, mode, finalStepId, vars, useTriggeredStep),
                active.triggerConfig()));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static final class StepItem {
        final String id;
        final String name;
        final boolean useTriggeredStep;

        StepItem(String id, String name, boolean useTriggeredStep) {
            this.id = id; this.name = name; this.useTriggeredStep = useTriggeredStep;
        }

        @Override public String toString() { return name; }
    }
}

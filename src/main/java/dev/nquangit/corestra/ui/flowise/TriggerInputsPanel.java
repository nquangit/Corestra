package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.access.workspace.AccessWorkspace;
import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.flow.StepTriggerRule;
import dev.nquangit.corestra.flow.TriggerInputBindings;
import dev.nquangit.corestra.integration.TriggerInputService;
import dev.nquangit.corestra.ui.access.AccessUiController;
import dev.nquangit.corestra.ui.component.TableReviewPanel;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.time.Clock;
import java.util.List;

/** Compact profile selection with an on-demand, value-free input dependency preview. */
final class TriggerInputsPanel extends JPanel {
    private final AccessUiController access;
    private final Runnable changed;
    private final JComboBox<Choice> auth = new JComboBox<>();
    private final JComboBox<Choice> data = new JComboBox<>();
    private final JLabel status = new JLabel();
    private final JButton analyze = new JButton("Check inputs…");
    private final JButton targetRules = new JButton("Target inject rules…");
    private List<String> exclusions = List.of();
    private final TriggerInputService inputs;
    private FlowDefinition flow;
    private FlowStep step;
    private boolean refreshing;

    TriggerInputsPanel(AccessUiController access, Runnable changed) {
        super(new BorderLayout(6, 3));
        this.access = access;
        this.changed = changed;
        this.inputs = new TriggerInputService(this::profiles, Clock.systemUTC());
        setAlignmentX(LEFT_ALIGNMENT);
        auth.setName("triggerAuthProfile");
        data.setName("triggerDataProfile");
        status.setName("triggerInputStatus");
        analyze.setName("triggerCheckInputs");
        auth.setPreferredSize(new Dimension(180, auth.getPreferredSize().height));
        data.setPreferredSize(new Dimension(180, data.getPreferredSize().height));
        auth.setToolTipText("Optional source of credentials.* and unexpired auth.* values. Does not log in or inject a session automatically.");
        data.setToolTipText("Optional source of data.* values. Its owner does not select or change the actor/session.");
        JPanel selectors = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        selectors.add(new JLabel("Auth Profile:")); selectors.add(auth);
        selectors.add(new JLabel("Data Profile:")); selectors.add(data); selectors.add(analyze); selectors.add(targetRules);
        add(selectors, BorderLayout.NORTH);
        add(status, BorderLayout.CENTER);
        status.setToolTipText("None is valid when required inputs come from earlier extracts. Missing or blank values stop the request; stored request values are never a fallback.");
        auth.addActionListener(e -> save());
        data.addActionListener(e -> save());
        analyze.addActionListener(e -> showAnalysis());
        targetRules.addActionListener(e -> editTargetRules());
        targetRules.setToolTipText("Skip selected inject rules only on the live trigger target, e.g. to preserve Scanner payloads. The saved flow is unchanged.");
        PopupMenuListener refreshOptions = new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                if (!refreshing) reload(bindings());
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(PopupMenuEvent e) { }
        };
        auth.addPopupMenuListener(refreshOptions);
        data.addPopupMenuListener(refreshOptions);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                reload(bindings());
            }
        });
    }

    TriggerInputBindings bindings() { return new TriggerInputBindings(selected(auth), selected(data), exclusions); }

    void refresh(FlowDefinition flow, FlowStep step) {
        this.flow = flow;
        this.step = step;
        reload(step == null ? TriggerInputBindings.none() : step.triggerRule().inputBindings());
    }

    private void reload(TriggerInputBindings saved) {
        refreshing = true;
        try {
            exclusions = saved.excludedTargetRuleIds();
            auth.removeAllItems(); data.removeAllItems();
            auth.addItem(new Choice("", "None")); data.addItem(new Choice("", "None"));
            if (access != null) {
                access.authProfiles().forEach(p -> auth.addItem(new Choice(p.id(), p.name() + (p.enabled() ? "" : " [disabled]"))));
                access.dataProfiles().forEach(p -> data.addItem(new Choice(p.id(), p.name() + (p.enabled() ? "" : " [disabled]"))));
            }
            select(auth, saved.authProfileId()); select(data, saved.dataProfileId());
            auth.setEnabled(step != null && access != null);
            data.setEnabled(step != null && access != null);
            analyze.setEnabled(step != null);
            targetRules.setEnabled(step != null);
            updateStatus();
        } finally { refreshing = false; }
    }

    private void save() {
        if (refreshing || step == null) return;
        changed.run();
        updateStatus();
    }

    private List<TriggerInputService.Input> analysis() {
        if (flow == null || step == null) return List.of();
        int matched = flow.steps().indexOf(step);
        if (matched < 0) return List.of();
        StepTriggerRule rule = step.triggerRule();
        int stop = matched;
        if (rule.scope() == StepTriggerRule.ExecutionScope.FULL_FLOW) stop = flow.steps().size() - 1;
        if (rule.scope() == StepTriggerRule.ExecutionScope.STOP_AT_STEP) {
            for (int i = matched; i < flow.steps().size(); i++) {
                if (flow.steps().get(i).id().equals(rule.stopAtStepId())) { stop = i; break; }
            }
        }
        FlowDefinition effective = TriggerInputService.applyTargetExclusions(flow, matched, bindings());
        return TriggerInputService.analyze(effective, matched, stop, inputs.resolve(bindings()));
    }

    private void updateStatus() {
        if (step == null) { status.setText("Select a step to configure optional profile inputs."); return; }
        try {
            var rows = analysis();
            long missing = rows.stream().filter(TriggerInputService.Input::missing).count();
            status.setText(missing == 0
                    ? "Inputs ready / supplied at runtime. Profiles are optional; extraction is checked before sending."
                    : missing + " missing input(s) — request will stop. Use Check inputs for details.");
            if (!exclusions.isEmpty()) status.setText(status.getText() + " Target rules skipped: " + exclusions.size() + ".");
        } catch (IllegalArgumentException invalid) { status.setText(invalid.getMessage() + " — request will stop."); }
    }

    private void editTargetRules() {
        if (step == null) return;
        String flowId = flow.id();
        String stepId = step.id();
        var rules = step.injectRules();
        DefaultTableModel model = new DefaultTableModel(new String[]{"Apply on live target", "Rule", "Target", "Source"}, 0) {
            @Override public boolean isCellEditable(int row, int column) { return column == 0; }
            @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        };
        for (var rule : rules) model.addRow(new Object[]{!exclusions.contains(rule.id()),
                rule.name() + (rule.enabled() ? "" : " [disabled]"), rule.target() + " / " + rule.targetSelector(),
                rule.valueSourceType() == dev.nquangit.corestra.flow.rule.InjectRule.ValueSourceType.VARIABLE
                        ? rule.valueSourceKey() : rule.valueSourceType().name()});
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        TableReviewPanel content = new TableReviewPanel(this,
                "Uncheck rules that would overwrite the value being tested by Scanner/Intruder.\n"
                + "Only this live target changes. Prerequisites, downstream steps, Auth Provider and the saved rules are unchanged.\n"
                + "Disabled rules stay disabled. Saving also clears references to rules that have been deleted.", table, 820);
        if (JOptionPane.showConfirmDialog(this, content, "Auto-trigger target inject rules",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (flow == null || step == null || !flow.id().equals(flowId) || !step.id().equals(stepId)
                || !step.injectRules().equals(rules)) {
            JOptionPane.showMessageDialog(this, "The selected flow or its rules changed. Reopen Target inject rules to review the current configuration.");
            return;
        }
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
        java.util.ArrayList<String> skipped = new java.util.ArrayList<>();
        for (int row = 0; row < rules.size(); row++) if (!Boolean.TRUE.equals(model.getValueAt(row, 0))) skipped.add(rules.get(row).id());
        exclusions = List.copyOf(skipped);
        save();
    }

    private void showAnalysis() {
        reload(bindings());
        try {
            DefaultTableModel model = new DefaultTableModel(new String[]{"Step", "Variable", "Source / status"}, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
            for (var input : analysis()) model.addRow(new Object[]{input.stepName(), input.key(), input.source()});
            JTable table = new JTable(model);
            table.setFillsViewportHeight(true);
            TableReviewPanel content = new TableReviewPanel(this,
                    "Only inputs needed by the executed steps are listed. No values are displayed.\n"
                    + "Runtime extracts must succeed. None never falls back to credentials in the captured request.\n"
                    + "Expired Auth State is omitted; credentials can still be used for login.\n"
                    + "Profile selection supplies variables only; Session Handler / inherited session remains separate.", table, 780);
            JOptionPane.showMessageDialog(this, content, "Auto-trigger input check", JOptionPane.PLAIN_MESSAGE);
        } catch (IllegalArgumentException invalid) {
            JOptionPane.showMessageDialog(this, invalid.getMessage(), "Invalid trigger inputs", JOptionPane.ERROR_MESSAGE);
        }
    }

    private AccessWorkspace profiles() {
        return access == null ? AccessWorkspace.empty() : access.profileSnapshot();
    }

    private static String selected(JComboBox<Choice> combo) {
        Choice choice = (Choice) combo.getSelectedItem();
        return choice == null ? "" : choice.id();
    }

    private static void select(JComboBox<Choice> combo, String id) {
        for (int i = 0; i < combo.getItemCount(); i++) {
            if (combo.getItemAt(i).id().equals(id)) { combo.setSelectedIndex(i); return; }
        }
        Choice missing = new Choice(id, "[missing] " + id);
        combo.addItem(missing); combo.setSelectedItem(missing);
    }

    private record Choice(String id, String label) {
        @Override public String toString() { return label; }
    }
}

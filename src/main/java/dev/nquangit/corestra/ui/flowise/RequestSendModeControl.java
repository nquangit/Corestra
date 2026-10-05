package dev.nquangit.corestra.ui.flowise;

import dev.nquangit.corestra.flow.FlowDefinition;
import dev.nquangit.corestra.flow.FlowStep;
import dev.nquangit.corestra.persistence.HttpSendMode;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.Objects;

/** Compact per-request routing selector; does not add a flow-wide setting. */
final class RequestSendModeControl extends JPanel {
    private final FlowWorkspaceController controller;
    private final JComboBox<Choice> choices = new JComboBox<>(Choice.values());
    private boolean refreshing;

    RequestSendModeControl(FlowWorkspaceController controller) {
        super(new BorderLayout(4, 0));
        this.controller = Objects.requireNonNull(controller, "controller");
        choices.setName("requestSendMode");
        choices.setToolTipText("Overrides Project settings for this request only. Proxy Pipeline uses the Project proxy host/port.");
        JLabel label = new JLabel("Send via:");
        label.setLabelFor(choices);
        add(label, BorderLayout.WEST);
        add(choices, BorderLayout.CENTER);
        choices.addActionListener(event -> {
            if (!refreshing && choices.isEnabled()) {
                controller.setSelectedStepSendModeOverride(((Choice) choices.getSelectedItem()).mode);
            }
        });
        controller.addFlowChangeListener(this::refresh);
        refresh();
    }

    /** Keeps the header control sized to its current font and theme. */
    @Override public Dimension getMaximumSize() { return getPreferredSize(); }

    private void refresh() {
        refreshing = true;
        try {
            FlowStep selected = controller.selectedStep();
            FlowDefinition flow = controller.activeFlow();
            FlowStep current = selected == null || flow == null ? null : flow.steps().stream()
                    .filter(step -> step.id().equals(selected.id())).findFirst().orElse(null);
            choices.setEnabled(current != null);
            HttpSendMode mode = current == null ? null : current.sendModeOverride();
            for (Choice choice : Choice.values()) {
                if (choice.mode == mode) choices.setSelectedItem(choice);
            }
        } finally {
            refreshing = false;
        }
    }

    private enum Choice {
        PROJECT("Use Project settings", null),
        STANDARD("Standard", HttpSendMode.STANDARD),
        PROXY("Proxy Pipeline", HttpSendMode.PROXY_PIPELINE);

        private final String label;
        private final HttpSendMode mode;
        Choice(String label, HttpSendMode mode) { this.label = label; this.mode = mode; }
        @Override public String toString() { return label; }
    }
}
